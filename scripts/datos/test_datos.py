#!/usr/bin/env python3
"""Pruebas de datos.py que no necesitan base de datos.

    python3 -m unittest discover -s scripts/datos     (o: make datos-test)
"""

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import datos  # noqa: E402

JUAN = "11111111-1111-1111-1111-111111111111"
ANA = "22222222-2222-2222-2222-222222222222"
CASA = "aaaa0001-0000-0000-0000-000000000001"
FOTO = "f0f0f0f0-0000-0000-0000-000000000001"


def base():
    """Lo que devolveria la base: un creador con dos canales, otro sin, y una productora."""
    return {
        "productoras": [{"id": CASA, "nombre": "Estudio X", "activo": True}],
        "creadores": [
            {"id": JUAN, "nombre": "Juan Pérez", "activo": True,
             "foto_url": "https://servidor/api/fotos/" + FOTO},
            {"id": ANA, "nombre": "Ana María Ñandú", "activo": True, "foto_url": None},
        ],
        "canales": [
            {"id": "c2", "creador_id": JUAN, "productora_id": CASA, "plataforma": "youtube",
             "url": "u2", "channel_id": "UCclips", "orden": 1, "creado_en": "2026-01-01"},
            {"id": "c1", "creador_id": JUAN, "productora_id": None, "plataforma": "youtube",
             "url": "u1", "channel_id": "UCjuan", "orden": 0, "creado_en": "2026-01-01"},
            {"id": "c3", "creador_id": None, "productora_id": CASA, "plataforma": "youtube",
             "url": "u3", "channel_id": "UCcasa", "orden": 0, "creado_en": "2026-01-01"},
        ],
        "creadores_productoras": [{"creador_id": JUAN, "productora_id": CASA}],
        "canales_creadores": [{"canal_id": "c3", "creador_id": ANA}],
        "ajustes": [{"clave": "cortos", "valor": "true"}],
        "fotos": [{"id": FOTO, "origen": "instagram/juan", "tipo": "image/png",
                   "datos": "\\x89504e47"}],
    }


def normal(tablas):
    return {t: sorted(json.dumps(f, sort_keys=True) for f in filas) for t, filas in tablas.items()}


class Archivos(unittest.TestCase):

    def test_nombres_legibles_y_estables(self):
        self.assertEqual(datos.slug("  María José (Clips) "), "maria-jose-clips")
        self.assertEqual(datos.slug("ツ"), "")
        nombres = datos.nombres_de_archivo([{"id": JUAN, "nombre": "Juan Pérez"},
                                            {"id": ANA, "nombre": "ツ"}])
        self.assertEqual(nombres[JUAN], "juan-perez-11111111.json")
        self.assertEqual(nombres[ANA], "22222222.json")

    def test_dos_ids_que_empiezan_igual_no_chocan(self):
        otro = JUAN[:8] + "-9999-9999-9999-999999999999"
        nombres = datos.nombres_de_archivo([{"id": JUAN, "nombre": "A"}, {"id": otro, "nombre": "A"}])
        self.assertEqual(len(set(nombres.values())), 2)

    def test_cada_creador_lleva_sus_canales_en_orden(self):
        archivos = datos.armar(base())
        juan = json.loads(archivos["creadores/juan-perez-11111111.json"])
        self.assertEqual([k["id"] for k in juan["canales"]], ["c1", "c2"])
        self.assertEqual(juan["productoras"], [{"id": CASA, "nombre": "Estudio X"}])
        casa = json.loads(archivos["productoras/estudio-x-aaaa0001.json"])
        self.assertEqual(casa["canales"][0]["vinculados"], [{"id": ANA, "nombre": "Ana María Ñandú"}])
        self.assertEqual(archivos["fotos/" + FOTO + ".png"], bytes.fromhex("89504e47"))
        self.assertNotIn("sueltos.json", archivos)

    def test_no_depende_del_orden_en_que_responde_la_base(self):
        al_reves = {t: list(reversed(f)) for t, f in base().items()}
        self.assertEqual(datos.armar(base()), datos.armar(al_reves))

    def test_lo_que_no_encaja_no_se_tira(self):
        d = base()
        d["canales"].append({"id": "c9", "creador_id": "no-existe", "productora_id": None,
                             "plataforma": "x", "url": "u9"})
        self.assertEqual(json.loads(datos.armar(d)["sueltos.json"])["canales"][0]["id"], "c9")

    def test_ida_y_vuelta(self):
        with tempfile.TemporaryDirectory() as tmp:
            carpeta = Path(tmp) / "produccion"
            datos.escribir(carpeta, datos.armar(base()))
            self.assertEqual(normal(datos.leer_carpeta(carpeta)), normal(base()))

    def test_escribir_deja_la_carpeta_igual_y_respeta_lo_ajeno(self):
        with tempfile.TemporaryDirectory() as tmp:
            carpeta = Path(tmp) / "produccion"
            self.assertEqual(datos.escribir(carpeta, datos.armar(base())), (6, 0))
            (carpeta / "NOTAS.md").write_text("mias")
            self.assertEqual(datos.escribir(carpeta, datos.armar(base())), (0, 0))

            d = base()
            d["creadores"] = [c for c in d["creadores"] if c["id"] != ANA]
            d["canales_creadores"] = []
            d["fotos"] = []
            self.assertEqual(datos.escribir(carpeta, datos.armar(d)), (1, 3))
            self.assertTrue((carpeta / "NOTAS.md").is_file())
            self.assertFalse((carpeta / "fotos").exists())

    def test_solo_trae_a_uno_con_sus_ligas_y_su_foto(self):
        with tempfile.TemporaryDirectory() as tmp:
            carpeta = Path(tmp) / "produccion"
            datos.escribir(carpeta, datos.armar(base()))

            juan = datos.leer_carpeta(carpeta, "juan")
            self.assertEqual([c["id"] for c in juan["creadores"]], [JUAN])
            self.assertEqual(sorted(k["id"] for k in juan["canales"]), ["c1", "c2"])
            self.assertEqual(len(juan["fotos"]), 1)
            self.assertNotIn("productoras", juan)
            self.assertNotIn("ajustes", juan)
            self.assertNotIn("canales_creadores", juan)

            # Ana no tiene canal propio: aparece en el de la productora, y eso
            # esta escrito en el archivo de la productora.
            ana = datos.leer_carpeta(carpeta, "ana-maria")
            self.assertEqual(ana["canales_creadores"], [{"canal_id": "c3", "creador_id": ANA}])
            self.assertNotIn("canales", ana)
            self.assertNotIn("fotos", ana)

    def test_formato_mas_nuevo_se_rechaza(self):
        with tempfile.TemporaryDirectory() as tmp:
            ruta = Path(tmp) / "x.json"
            ruta.write_text(json.dumps({"formato": datos.FORMATO + 1, "creador": {"id": "x"}}))
            with self.assertRaises(datos.Fallo):
                datos.leer_json(ruta)


class Proteccion(unittest.TestCase):

    def carpeta_con(self, tmp, cuantos):
        carpeta = Path(tmp) / "produccion"
        creadores = [{"id": "%08d-0000-0000-0000-000000000000" % n, "nombre": "C%d" % n}
                     for n in range(cuantos)]
        datos.escribir(carpeta, datos.armar({"creadores": creadores}))
        return carpeta, creadores

    def test_base_vacia_no_arrasa(self):
        with tempfile.TemporaryDirectory() as tmp:
            carpeta, _ = self.carpeta_con(tmp, 1)
            with self.assertRaises(datos.Protegido):
                datos.comprobar_que_no_arrasa(carpeta, {"creadores": [], "productoras": []})

    def test_perder_mas_de_la_mitad_no_arrasa(self):
        with tempfile.TemporaryDirectory() as tmp:
            carpeta, creadores = self.carpeta_con(tmp, 6)
            datos.comprobar_que_no_arrasa(carpeta, {"creadores": creadores[:3]})
            with self.assertRaises(datos.Protegido):
                datos.comprobar_que_no_arrasa(carpeta, {"creadores": creadores[:2]})

    def test_carpeta_nueva_y_borrados_normales_pasan(self):
        with tempfile.TemporaryDirectory() as tmp:
            datos.comprobar_que_no_arrasa(Path(tmp) / "nueva", {"creadores": []})
            carpeta, creadores = self.carpeta_con(tmp, 3)
            datos.comprobar_que_no_arrasa(carpeta, {"creadores": creadores[:1]})


class Restaurar(unittest.TestCase):

    def test_solo_las_columnas_que_estan_en_los_dos_lados(self):
        filas = {"creadores": [{"id": JUAN, "nombre": "Juan", "columna_que_ya_no_existe": 1}]}
        columnas = {"creadores": ["id", "nombre", "columna_nueva"]}
        sql = datos.sql_de_restaurar(filas, columnas)
        self.assertIn('insert into public.creadores ("id", "nombre") select', sql)
        self.assertIn("on conflict do nothing", sql)
        self.assertTrue(sql.rstrip().endswith("commit;"))
        self.assertTrue(datos.sql_de_restaurar(filas, columnas, simular=True).rstrip().endswith("rollback;"))

    def test_tabla_que_la_base_no_tiene_se_salta(self):
        sql = datos.sql_de_restaurar({"fotos": [{"id": FOTO}]}, {"creadores": ["id"]})
        self.assertNotIn("public.fotos", sql)

    def test_no_hay_ambientes_cruzados(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(datos.main(["restaurar", "testing", "--carpeta", tmp + "/produccion"]), 1)


if __name__ == "__main__":
    unittest.main()
