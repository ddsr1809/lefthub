#!/usr/bin/env python3
"""El directorio (creadores, productoras y canales) guardado en archivos.

    datos.py guardar   AMBIENTE     base de datos -> datos/AMBIENTE/
    datos.py restaurar AMBIENTE     datos/AMBIENTE/ -> base de datos

AMBIENTE es development, testing o produccion. Cada uno tiene su carpeta y no
se mezclan: los ids de un ambiente no valen en otro.

La base de datos sigue siendo donde se trabaja (el panel). Esta carpeta es su
copia en archivos de texto, uno por creador y uno por productora, para que
viva en Git: ahi queda el historial de cada cambio y de ahi se recupera lo que
se borre o se pierda. Ver datos/README.md.

Habla con Postgres por `psql` dentro del contenedor `db`, asi que no necesita
nada instalado aparte de Docker y Python 3. Para probarlo contra otra base,
DATOS_PSQL es la orden que recibe el SQL por la entrada estandar.
"""

import argparse
import json
import os
import re
import secrets
import subprocess
import sys
import unicodedata
from collections import defaultdict
from pathlib import Path

# Version del formato de los archivos. Solo sube si un cambio deja de poder
# leerse con el codigo anterior; agregar columnas o tablas no la cambia.
FORMATO = 1

RAIZ = Path(__file__).resolve().parents[2]

AMBIENTES = {"development": None, "testing": "test", "produccion": "prod"}

# En el orden en que se pueden insertar sin romper una clave foranea.
TABLAS = [
    "productoras",
    "creadores",
    "canales",
    "conexiones",              # solo en una base anterior a los canales (V6)
    "creadores_productoras",
    "canales_creadores",
    "ajustes",
    "fotos",
]

EXTENSIONES = {"image/jpeg": "jpg", "image/png": "png", "image/webp": "webp", "image/gif": "gif"}

# Las claves que se leen primero en un archivo; el resto va por orden alfabetico.
PRIMERO = ["id", "nombre", "categoria", "plataforma", "url", "handle", "channel_id",
           "bio", "descripcion", "foto_url", "logo_url", "activo", "en_directorio",
           "orden", "creador_id", "productora_id", "origen_id", "clave", "valor",
           "origen", "tipo", "archivo"]


class Fallo(Exception):
    """Un error que se explica solo: se muestra sin traza."""


class Protegido(Fallo):
    """Guardar borraria demasiado. No se toca nada."""


# ---------------------------------------------------------------------------
# Postgres
# ---------------------------------------------------------------------------

def orden_psql(ambiente):
    """La orden que ejecuta psql en la base de ese ambiente, y desde donde."""
    propia = os.environ.get("DATOS_PSQL")
    if propia:
        return ["sh", "-c", propia], None

    app = Path(os.environ.get("APP_DIR") or RAIZ)
    dentro = 'exec psql -X -q -At -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
    corto = AMBIENTES[ambiente]

    if corto is None:
        env = app / "relay-server" / ".env.local"
        if not env.is_file():
            raise Fallo("Falta %s. Levanta primero el ambiente local (./local.sh)." % env)
        return ["docker", "compose", "--env-file", str(env), "-p", "vocesleft-local",
                "-f", str(app / "relay-server" / "docker-compose.yml"),
                "exec", "-T", "db", "sh", "-c", dentro], None

    runtime = app / "runtime" / corto
    env = app / "relay-server" / (".env." + corto)
    if not runtime.is_dir() or not env.is_file():
        raise Fallo("No encuentro %s ni %s: %s solo se guarda y se restaura en el VPS."
                    % (runtime, env, ambiente))
    return ["docker", "compose", "--env-file", str(env), "--env-file", ".env.imagen",
            "-p", "vocesleft-" + corto, "-f", "docker-compose.yml",
            "exec", "-T", "db", "sh", "-c", dentro], str(runtime)


def psql(ambiente, sql):
    """Ejecuta el SQL y devuelve, ya leido, el JSON de su ultima linea."""
    orden, desde = orden_psql(ambiente)
    try:
        r = subprocess.run(orden, input=sql.encode("utf-8"), cwd=desde,
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    except FileNotFoundError as e:
        raise Fallo("No pude ejecutar %s: %s" % (orden[0], e))
    if r.returncode != 0:
        raise Fallo("La base de datos de %s no respondio bien:\n%s"
                    % (ambiente, r.stderr.decode("utf-8", "replace").strip()))
    lineas = [l for l in r.stdout.decode("utf-8").splitlines() if l.strip()]
    if not lineas:
        raise Fallo("La base de datos de %s no devolvio nada." % ambiente)
    try:
        return json.loads(lineas[-1])
    except ValueError:
        raise Fallo("No entendi la respuesta de la base de datos de %s:\n%s"
                    % (ambiente, lineas[-1][:300]))


def tablas_y_columnas(ambiente):
    """Las tablas del directorio que existen en esa base, con sus columnas."""
    lista = ", ".join("'%s'" % t for t in TABLAS + ["suscripciones"])
    return psql(ambiente, """
        select coalesce(jsonb_object_agg(table_name, columnas), '{}'::jsonb)::text
          from (select table_name, jsonb_agg(column_name::text order by ordinal_position) as columnas
                  from information_schema.columns
                 where table_schema = 'public' and table_name in (%s)
                 group by table_name) t;
        """ % lista)


def leer_base(ambiente):
    """Todo el directorio en una sola consulta: una foto coherente."""
    hay = tablas_y_columnas(ambiente)
    if "creadores" not in hay:
        raise Fallo("La base de %s no tiene la tabla creadores: el servidor todavia "
                    "no ha arrancado contra ella." % ambiente)

    tablas = [t for t in TABLAS if t in hay]
    # `conexiones` se quedo en la base despues de V6, pero ya no se usa.
    if "canales" in tablas and "conexiones" in tablas:
        tablas.remove("conexiones")

    partes = ",\n".join(
        "  '%s', (select coalesce(jsonb_agg(to_jsonb(t)), '[]'::jsonb) from public.%s t)" % (t, t)
        for t in tablas)
    # En UTC para que las fechas se escriban igual en cualquier maquina.
    return psql(ambiente, "set timezone = 'UTC';\nselect jsonb_build_object(\n%s\n)::text;\n" % partes)


# ---------------------------------------------------------------------------
# De la base a los archivos
# ---------------------------------------------------------------------------

def slug(texto):
    """'Maria Jose  (Clips)' -> 'maria-jose-clips', para nombrar archivos."""
    plano = unicodedata.normalize("NFKD", texto or "")
    plano = "".join(c for c in plano if not unicodedata.combining(c))
    plano = re.sub(r"[^a-z0-9]+", "-", plano.lower()).strip("-")
    return plano[:40].strip("-")


def ordenar(fila):
    """Las mismas claves siempre en el mismo orden: los diffs salen limpios."""
    def peso(clave):
        return (PRIMERO.index(clave) if clave in PRIMERO else len(PRIMERO), clave)
    return {k: fila[k] for k in sorted(fila, key=peso)}


def como_texto(documento):
    return (json.dumps(documento, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def nombres_de_archivo(filas):
    """id -> nombre de archivo, legible y estable: 'juan-perez-1a2b3c4d.json'."""
    cortos = defaultdict(int)
    for f in filas:
        cortos[f["id"][:8]] += 1
    nombres = {}
    for f in filas:
        cola = f["id"][:8] if cortos[f["id"][:8]] == 1 else f["id"]
        base = slug(f.get("nombre"))
        nombres[f["id"]] = (base + "-" if base else "") + cola + ".json"
    return nombres


def armar(datos):
    """Las tablas -> {ruta dentro de la carpeta: contenido}.

    Cada creador lleva dentro sus canales y las productoras en las que figura;
    cada productora, sus canales propios (los que no tienen creador). Lo que no
    encaja en ninguno va a sueltos.json: aqui no se tira nada.
    """
    creadores = sorted(datos.get("creadores", []), key=lambda f: f["id"])
    productoras = sorted(datos.get("productoras", []), key=lambda f: f["id"])
    ids_creadores = {f["id"] for f in creadores}
    ids_productoras = {f["id"] for f in productoras}
    nombre = {f["id"]: f.get("nombre") for f in creadores + productoras}
    sueltos = defaultdict(list)

    def referencia(id_):
        return {"id": id_, "nombre": nombre.get(id_)}

    canales = datos.get("canales", [])
    ids_canales = {k["id"] for k in canales}

    con_quien = defaultdict(list)
    for liga in datos.get("canales_creadores", []):
        if liga["canal_id"] in ids_canales:
            con_quien[liga["canal_id"]].append(liga["creador_id"])
        else:
            sueltos["canales_creadores"].append(liga)

    del_creador, de_la_productora = defaultdict(list), defaultdict(list)
    for k in sorted(canales, key=lambda k: (k.get("orden") or 0, k.get("creado_en") or "", k["id"])):
        canal = ordenar(k)
        if con_quien[k["id"]]:
            canal["vinculados"] = [referencia(c) for c in sorted(con_quien[k["id"]])]
        if k.get("creador_id") in ids_creadores:
            del_creador[k["creador_id"]].append(canal)
        elif k.get("creador_id") is None and k.get("productora_id") in ids_productoras:
            de_la_productora[k["productora_id"]].append(canal)
        else:
            sueltos["canales"].append(canal)

    casas = defaultdict(list)
    for liga in datos.get("creadores_productoras", []):
        if liga["creador_id"] in ids_creadores:
            casas[liga["creador_id"]].append(liga["productora_id"])
        else:
            sueltos["creadores_productoras"].append(liga)

    conexiones = defaultdict(list)
    for cx in sorted(datos.get("conexiones", []), key=lambda c: c.get("plataforma") or ""):
        if cx.get("creador_id") in ids_creadores:
            conexiones[cx["creador_id"]].append(ordenar(cx))
        else:
            sueltos["conexiones"].append(cx)

    archivos = {}

    nombres = nombres_de_archivo(creadores)
    for c in creadores:
        doc = {"formato": FORMATO, "creador": ordenar(c)}
        doc["productoras"] = [referencia(p) for p in sorted(casas[c["id"]])]
        doc["canales"] = del_creador[c["id"]]
        if conexiones[c["id"]]:
            doc["conexiones"] = conexiones[c["id"]]
        archivos["creadores/" + nombres[c["id"]]] = como_texto(doc)

    nombres = nombres_de_archivo(productoras)
    for p in productoras:
        doc = {"formato": FORMATO, "productora": ordenar(p), "canales": de_la_productora[p["id"]]}
        archivos["productoras/" + nombres[p["id"]]] = como_texto(doc)

    ajustes = sorted(datos.get("ajustes", []), key=lambda a: a["clave"])
    if ajustes:
        archivos["ajustes.json"] = como_texto(
            {"formato": FORMATO, "ajustes": [ordenar(a) for a in ajustes]})

    for foto in datos.get("fotos", []):
        ficha = dict(foto)
        hexa = ficha.pop("datos", None) or ""
        if not hexa.startswith("\\x"):
            sueltos["fotos"].append(foto)
            continue
        imagen = foto["id"] + "." + EXTENSIONES.get(foto.get("tipo"), "bin")
        ficha["archivo"] = imagen
        archivos["fotos/" + imagen] = bytes.fromhex(hexa[2:])
        archivos["fotos/" + foto["id"] + ".json"] = como_texto(
            {"formato": FORMATO, "foto": ordenar(ficha)})

    if sueltos:
        doc = {"formato": FORMATO}
        doc.update({tabla: filas for tabla, filas in sorted(sueltos.items())})
        archivos["sueltos.json"] = como_texto(doc)

    return archivos


def gestionados(carpeta):
    """Los archivos de la carpeta que escribe esta herramienta (y puede borrar)."""
    rutas = set()
    for sub in ("creadores", "productoras", "fotos"):
        if (carpeta / sub).is_dir():
            rutas.update(sub + "/" + p.name for p in (carpeta / sub).iterdir()
                         if p.is_file() and not p.name.startswith("."))
    for suelto in ("ajustes.json", "sueltos.json"):
        if (carpeta / suelto).is_file():
            rutas.add(suelto)
    return rutas


def ids_guardados(carpeta):
    """Los ids de los creadores y productoras que hay ahora en la carpeta."""
    ids = set()
    for sub, clave in (("creadores", "creador"), ("productoras", "productora")):
        if not (carpeta / sub).is_dir():
            continue
        for p in (carpeta / sub).glob("*.json"):
            try:
                ids.add(json.loads(p.read_text(encoding="utf-8"))[clave]["id"])
            except (ValueError, KeyError, TypeError):
                ids.add(sub + "/" + p.name)
    return ids


def comprobar_que_no_arrasa(carpeta, datos):
    """Una base vacia o a medias no debe llevarse por delante la copia buena.

    Es justo el caso para el que existe la copia: si la base se pierde, la
    siguiente pasada automatica no puede dejar la carpeta igual de vacia.
    """
    antes = ids_guardados(carpeta)
    ahora = {f["id"] for f in datos.get("creadores", []) + datos.get("productoras", [])}
    faltan = antes - ahora

    if antes and not ahora:
        raise Protegido(
            "La base esta vacia y la carpeta tiene %d creadores y productoras. No toco nada.\n"
            "Si la base se perdio, recuperala con `restaurar`. Si de verdad la vaciaste, "
            "repite con --forzar." % len(antes))
    if len(antes) >= 4 and len(faltan) > len(antes) / 2:
        raise Protegido(
            "En la base faltan %d de los %d creadores y productoras que hay en la carpeta. "
            "No toco nada.\nSi se perdieron, recuperalos con `restaurar`. Si los borraste "
            "a proposito, repite con --forzar." % (len(faltan), len(antes)))


def escribir(carpeta, archivos):
    """Deja la carpeta igual que `archivos`. Devuelve (escritos, borrados)."""
    escritos = borrados = 0
    for ruta, contenido in sorted(archivos.items()):
        destino = carpeta / ruta
        if destino.is_file() and destino.read_bytes() == contenido:
            continue
        destino.parent.mkdir(parents=True, exist_ok=True)
        temporal = destino.with_name("." + destino.name + ".tmp")
        temporal.write_bytes(contenido)
        os.replace(str(temporal), str(destino))
        escritos += 1
    for ruta in sorted(gestionados(carpeta) - set(archivos)):
        (carpeta / ruta).unlink()
        borrados += 1
    for sub in ("creadores", "productoras", "fotos"):
        if (carpeta / sub).is_dir() and not any((carpeta / sub).iterdir()):
            (carpeta / sub).rmdir()
    return escritos, borrados


def guardar(ambiente, carpeta, forzar=False):
    datos = leer_base(ambiente)
    if not forzar:
        comprobar_que_no_arrasa(carpeta, datos)
    escritos, borrados = escribir(carpeta, armar(datos))

    cuenta = ", ".join("%d %s" % (len(datos[t]), t.replace("_", " "))
                       for t in ("creadores", "productoras", "canales", "conexiones", "fotos")
                       if t in datos)
    cambio = ("%d archivos escritos, %d borrados" % (escritos, borrados)
              if escritos or borrados else "sin cambios")
    print("%s: %s. %s." % (ambiente, cuenta, cambio.capitalize()))


# ---------------------------------------------------------------------------
# De los archivos a la base
# ---------------------------------------------------------------------------

def leer_json(ruta):
    try:
        doc = json.loads(ruta.read_text(encoding="utf-8"))
    except ValueError as e:
        raise Fallo("%s no es JSON valido: %s" % (ruta, e))
    formato = doc.get("formato") if isinstance(doc, dict) else None
    if not isinstance(formato, int):
        raise Fallo("%s no tiene la clave \"formato\"." % ruta)
    if formato > FORMATO:
        raise Fallo("%s esta en el formato %d y esta herramienta entiende hasta el %d. "
                    "Actualiza el repositorio (git pull) y repite." % (ruta, formato, FORMATO))
    return doc


def solo_id(referencia):
    return referencia["id"] if isinstance(referencia, dict) else referencia


def leer_carpeta(carpeta, solo=None):
    """Los archivos -> filas por tabla, listas para insertar.

    `solo` deja unicamente a los creadores y productoras cuyo archivo lo lleva
    en el nombre ('juan' vale para creadores/juan-perez-1a2b3c4d.json), con sus
    canales, sus fotos y sus ligas: tambien las que estan escritas en el
    archivo de otro, como aparecer en el canal de una productora.
    """
    filas = defaultdict(list)
    elegidos = set()            # ids de los creadores, productoras y canales que pasan

    def pasa(ruta):
        return solo is None or solo.lower() in ruta.name.lower()

    def canal(k, suyo):
        k = dict(k)
        for creador in k.pop("vinculados", None) or []:
            filas["canales_creadores"].append({"canal_id": k["id"], "creador_id": solo_id(creador)})
        if suyo:
            filas["canales"].append(k)
            elegidos.add(k["id"])

    for ruta in sorted((carpeta / "productoras").glob("*.json")):
        doc = leer_json(ruta)
        suyo = pasa(ruta)
        if suyo:
            filas["productoras"].append(doc["productora"])
            elegidos.add(doc["productora"]["id"])
        for k in doc.get("canales") or []:
            canal(k, suyo)

    for ruta in sorted((carpeta / "creadores").glob("*.json")):
        doc = leer_json(ruta)
        creador = doc["creador"]
        suyo = pasa(ruta)
        if suyo:
            filas["creadores"].append(creador)
            elegidos.add(creador["id"])
            filas["conexiones"].extend(doc.get("conexiones") or [])
        for casa in doc.get("productoras") or []:
            filas["creadores_productoras"].append(
                {"creador_id": creador["id"], "productora_id": solo_id(casa)})
        for k in doc.get("canales") or []:
            canal(k, suyo)

    # De las ligas, las que tocan a alguien elegido. Con la carpeta entera, todas.
    for tabla in ("creadores_productoras", "canales_creadores"):
        filas[tabla] = [l for l in filas[tabla] if elegidos.intersection(l.values())]

    enlaces = " ".join(str(f.get(c) or "") for f in filas["creadores"] + filas["productoras"]
                       for c in ("foto_url", "logo_url"))
    for ruta in sorted((carpeta / "fotos").glob("*.json")):
        foto = dict(leer_json(ruta)["foto"])
        if solo is not None and foto["id"] not in enlaces:
            continue
        imagen = carpeta / "fotos" / str(foto.pop("archivo", ""))
        if not imagen.is_file():
            raise Fallo("Falta la imagen de %s." % ruta)
        foto["datos"] = "\\x" + imagen.read_bytes().hex()
        filas["fotos"].append(foto)

    # Los ajustes son del ambiente entero: no van cuando se recupera a alguien suelto.
    if solo is None:
        if (carpeta / "ajustes.json").is_file():
            filas["ajustes"].extend(leer_json(carpeta / "ajustes.json").get("ajustes") or [])
        if (carpeta / "sueltos.json").is_file():
            for tabla, lista in leer_json(carpeta / "sueltos.json").items():
                if tabla in TABLAS:
                    filas[tabla].extend(lista)

    return {t: filas[t] for t in TABLAS if filas[t]}


EXISTE = "exists (select 1 from public.%s x where x.id = f.%s)"

# Que filas se pueden insertar sin romper una clave foranea. Con la carpeta
# entera y la base vacia pasan todas; importan al recuperar a un creador
# suelto, cuando la productora a la que estaba ligado puede ya no existir.
FILTROS = {
    "canales": "(f.creador_id is not null and %s) or (f.creador_id is null and %s)"
               % (EXISTE % ("creadores", "creador_id"), EXISTE % ("productoras", "productora_id")),
    "conexiones": EXISTE % ("creadores", "creador_id"),
    "creadores_productoras": "%s and %s" % (EXISTE % ("creadores", "creador_id"),
                                            EXISTE % ("productoras", "productora_id")),
    "canales_creadores": "%s and %s" % (EXISTE % ("canales", "canal_id"),
                                        EXISTE % ("creadores", "creador_id")),
}

# Un canal de un creador sobrevive a su productora: se queda sin ella.
EXPRESIONES = {
    ("canales", "productora_id"):
        "case when %s then f.productora_id end" % (EXISTE % ("productoras", "productora_id")),
}

# Los canales de YouTube de quien esta visible, como los vigila el servidor. Al
# retirar a alguien, el servidor da de baja sus canales en el hub; si vuelve,
# esa baja se convierte otra vez en un alta pendiente.
SUSCRIBIR = """
insert into public.suscripciones (channel_id, topic, estado, ultimo_error)
select k.channel_id,
       'https://www.youtube.com/xml/feeds/videos.xml?channel_id=' || k.channel_id,
       'ERROR', 'Recuperado de los archivos: falta suscribirlo'
  from public.canales k
 where k.plataforma = 'youtube' and k.channel_id is not null and k.channel_id <> ''
   and (   (k.creador_id is not null and exists (
                select 1 from public.creadores c where c.id = k.creador_id and c.activo))
        or (k.creador_id is null and exists (
                select 1 from public.productoras p where p.id = k.productora_id and p.activo)))
on conflict (channel_id) do update
   set modo = 'subscribe', estado = 'ERROR', ultimo_error = excluded.ultimo_error
 where suscripciones.modo = 'unsubscribe' or suscripciones.estado = 'CANCELADA';
"""


def sql_de_restaurar(filas, columnas, simular=False):
    """El SQL que inserta lo que falte, en una sola transaccion.

    Solo se insertan las columnas que estan a la vez en el archivo y en la
    tabla. Asi un archivo de antes de agregar una columna entra igual (la
    columna toma su valor por defecto), y uno con una columna que ya no existe
    tambien. Lo que ya esta en la base no se toca: `on conflict do nothing`.
    """
    etiqueta = "$d" + secrets.token_hex(8) + "$"
    partes = ["begin;", "set local timezone = 'UTC';",
              "create temp table _hecho (tabla text, en_archivos int, nuevas int) on commit drop;"]

    for tabla in TABLAS:
        if tabla not in filas or tabla not in columnas:
            continue
        en_archivo = set()
        for fila in filas[tabla]:
            en_archivo.update(fila)
        comunes = [c for c in columnas[tabla] if c in en_archivo]
        if not comunes:
            continue

        texto = json.dumps(filas[tabla], ensure_ascii=False)
        if etiqueta in texto:
            raise Fallo("Los datos contienen la marca interna %s; repite la orden." % etiqueta)

        lista = ", ".join('"%s"' % c for c in comunes)
        valores = ", ".join(
            "%s as \"%s\"" % (EXPRESIONES.get((tabla, c), 'f."%s"' % c), c) for c in comunes)
        partes.append(
            "with f as (select * from jsonb_populate_recordset(null::public.{t}, {e}{j}{e}::jsonb)),\n"
            "     puestas as (insert into public.{t} ({l}) select {v} from f where {w}\n"
            "                 on conflict do nothing returning 1)\n"
            "insert into _hecho select '{t}', (select count(*) from f), (select count(*) from puestas);"
            .format(t=tabla, e=etiqueta, j=texto, l=lista, v=valores,
                    w=FILTROS.get(tabla, "true")))

    if "suscripciones" in columnas and "canales" in columnas and "canales" in filas:
        partes.append(SUSCRIBIR)

    partes.append("select coalesce(jsonb_agg(to_jsonb(h)), '[]'::jsonb)::text from _hecho h;")
    partes.append("rollback;" if simular else "commit;")
    return "\n".join(partes) + "\n"


def restaurar(ambiente, carpeta, solo=None, simular=False):
    if not carpeta.is_dir():
        raise Fallo("No existe %s: no hay nada guardado de %s." % (carpeta, ambiente))
    filas = leer_carpeta(carpeta, solo)
    if not filas.get("creadores") and not filas.get("productoras"):
        raise Fallo("En %s no hay ningun creador ni productora%s."
                    % (carpeta, " cuyo archivo lleve '%s'" % solo if solo else ""))

    columnas = tablas_y_columnas(ambiente)
    if "creadores" not in columnas:
        raise Fallo("La base de %s no tiene tablas todavia. Arranca primero el servidor "
                    "(el las crea) y repite." % ambiente)

    hecho = {h["tabla"]: h for h in psql(ambiente, sql_de_restaurar(filas, columnas, simular))}

    print("%s%s:" % (ambiente, " (simulacro: no se guardo nada)" if simular else ""))
    for tabla in TABLAS:
        if tabla not in filas:
            continue
        nombre = tabla.replace("_", " ")
        if tabla not in hecho:
            print("  %-22s %d en los archivos; esta base no tiene esa tabla" % (nombre, len(filas[tabla])))
            continue
        h = hecho[tabla]
        resto = h["en_archivos"] - h["nuevas"]
        print("  %-22s %d recuperados%s" % (
            nombre, h["nuevas"], ", %d ya estaban o no se pudieron ligar" % resto if resto else ""))
    if "conexiones" in filas and "canales" in columnas:
        print("  AVISO: estos archivos son de antes de los canales multiples. Sus enlaces "
              "quedaron en la tabla antigua `conexiones`, que el servidor ya no lee.")
    if not simular and "canales" in hecho and "suscripciones" in columnas:
        print("  El servidor suscribe los canales de YouTube recuperados en su siguiente "
              "repesca (cada 15 minutos).")


# ---------------------------------------------------------------------------

def main(argumentos=None):
    p = argparse.ArgumentParser(description="El directorio guardado en archivos. Ver datos/README.md.")
    sub = p.add_subparsers(dest="orden")
    sub.required = True

    g = sub.add_parser("guardar", help="base de datos -> archivos")
    g.add_argument("ambiente", choices=sorted(AMBIENTES))
    g.add_argument("--carpeta", help="por defecto, datos/AMBIENTE en este repositorio")
    g.add_argument("--forzar", action="store_true",
                   help="guardar aunque eso borre la mayoria de los archivos")

    r = sub.add_parser("restaurar", help="archivos -> base de datos (solo agrega lo que falta)")
    r.add_argument("ambiente", choices=sorted(AMBIENTES))
    r.add_argument("--carpeta", help="por defecto, datos/AMBIENTE en este repositorio")
    r.add_argument("--solo", metavar="TEXTO",
                   help="solo los creadores y productoras cuyo archivo lleve TEXTO en el nombre")
    r.add_argument("--simular", action="store_true", help="decir que haria, sin guardar nada")

    a = p.parse_args(argumentos)
    carpeta = Path(a.carpeta).resolve() if a.carpeta else RAIZ / "datos" / a.ambiente

    try:
        # Los ids de un ambiente no valen en otro: testing y produccion mandan
        # los avisos al mismo proyecto de Firebase, por el id del creador.
        if carpeta.name != a.ambiente:
            raise Fallo("La carpeta %s no es la de %s. Cada ambiente usa solo la suya."
                        % (carpeta, a.ambiente))
        if a.orden == "guardar":
            guardar(a.ambiente, carpeta, a.forzar)
        else:
            restaurar(a.ambiente, carpeta, a.solo, a.simular)
    except Protegido as e:
        print("PROTEGIDO (%s): %s" % (a.ambiente, e), file=sys.stderr)
        return 3
    except Fallo as e:
        print("ERROR: %s" % e, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
