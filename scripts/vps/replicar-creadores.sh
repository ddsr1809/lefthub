#!/usr/bin/env bash
# Copia a testing los creadores que ya existen en produccion y deja encendida
# la copia automatica de los que se guarden despues en el panel de produccion.
#
# Se ejecuta como root en el VPS, cuando testing y produccion ya tienen
# desplegada la version del servidor que trae la copia de creadores:
#
#   git -C /opt/vocesleft pull
#   bash /opt/vocesleft/scripts/vps/replicar-creadores.sh
#
# La primera vez escribe REPLICA_URL y REPLICA_TOKEN en .env.prod y .env.test
# y reinicia los dos servidores para que los lean (cada uno queda fuera de
# linea menos de un minuto). Despues pide a produccion que mande todos sus
# creadores a testing.
#
# Se puede repetir sin riesgo: los creadores ya copiados se actualizan, no se
# duplican, y si la configuracion ya estaba puesta no reinicia nada.
#
#   --reiniciar   Reinicia los dos servidores aunque los .env no cambien.

set -Eeuo pipefail

APP_DIR="${APP_DIR:-/opt/vocesleft}"

reiniciar=0
case "${1:-}" in
  "") ;;
  --reiniciar) reiniciar=1 ;;
  *) echo "Uso: $0 [--reiniciar]" >&2; exit 2 ;;
esac

paso()  { printf '\n==> %s\n' "$*"; }
morir() { printf '\nERROR: %s\n' "$*" >&2; exit 1; }

# Valor de CLAVE=... en un .env, o vacio si no esta.
valor() {
  local linea
  linea="$(grep "^$2=" "$1" | tail -n 1 || true)"
  printf '%s' "${linea#*=}"
}

# Reemplaza la linea CLAVE=... o la agrega si no existe.
poner() {
  local archivo="$1" clave="$2" valor="$3"
  if grep -q "^${clave}=" "$archivo"; then
    sed -i "s|^${clave}=.*|${clave}=${valor}|" "$archivo"
  else
    [[ -z "$(tail -c1 "$archivo")" ]] || echo >> "$archivo"
    printf '%s=%s\n' "$clave" "$valor" >> "$archivo"
  fi
}

compose() {
  local ambiente="$1"; shift
  ( cd "$APP_DIR/runtime/$ambiente" && docker compose \
      --env-file "$APP_DIR/relay-server/.env.$ambiente" --env-file .env.imagen \
      -p "vocesleft-$ambiente" -f docker-compose.yml "$@" )
}

recrear() {
  local ambiente="$1" puerto intento
  puerto="$(valor "$APP_DIR/relay-server/.env.$ambiente" PUERTO_HOST)"

  paso "Reiniciando el servidor de $ambiente para que lea la configuracion"
  compose "$ambiente" up -d --no-deps --force-recreate servidor

  for intento in {1..36}; do
    if curl -fsS -m 5 "http://127.0.0.1:$puerto/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
      echo "    $ambiente responde."
      return 0
    fi
    sleep "${ESPERA:-5}"
  done
  morir "El servidor de $ambiente no respondio en 3 minutos. Mira sus registros:
  cd $APP_DIR/runtime/$ambiente && docker compose --env-file $APP_DIR/relay-server/.env.$ambiente --env-file .env.imagen -p vocesleft-$ambiente -f docker-compose.yml logs --tail=100 servidor"
}

# -----------------------------------------------------------------------------
# 1. Comprobaciones
# -----------------------------------------------------------------------------
env_prod="$APP_DIR/relay-server/.env.prod"
env_test="$APP_DIR/relay-server/.env.test"

for archivo in "$env_prod" "$env_test"; do
  [[ -r "$archivo" ]] || morir "No puedo leer $archivo. Ejecuta el script como root en el VPS."
done

for ambiente in test prod; do
  yml="$APP_DIR/runtime/$ambiente/docker-compose.yml"
  [[ -f "$yml" && -f "$APP_DIR/runtime/$ambiente/.env.imagen" ]] \
    || morir "No existe un despliegue de $ambiente en $APP_DIR/runtime/$ambiente."
  grep -q 'REPLICA_TOKEN' "$yml" \
    || morir "$ambiente todavia no tiene desplegada la version con la copia de creadores.
  Fusiona el cambio en la rama de ese ambiente y espera a que el pipeline termine."
done

url_test="$(valor "$env_test" RELAY_URL_PUBLICA)"
[[ -n "$url_test" ]] || morir "Falta RELAY_URL_PUBLICA en $env_test."

# -----------------------------------------------------------------------------
# 2. Configuracion (solo escribe lo que falta)
# -----------------------------------------------------------------------------
paso "Revisando la configuracion de la copia"
cambio=0

token="$(valor "$env_test" REPLICA_TOKEN)"
[[ -n "$token" ]] || token="$(valor "$env_prod" REPLICA_TOKEN)"
[[ -n "$token" ]] || token="$(openssl rand -hex 32)"

for archivo in "$env_test" "$env_prod"; do
  if [[ "$(valor "$archivo" REPLICA_TOKEN)" != "$token" ]]; then
    poner "$archivo" REPLICA_TOKEN "$token"
    echo "    REPLICA_TOKEN escrito en $archivo"
    cambio=1
  fi
done

# Testing solo recibe. Con REPLICA_URL puesto dejaria de aceptar copias.
if [[ -n "$(valor "$env_test" REPLICA_URL)" ]]; then
  poner "$env_test" REPLICA_URL ""
  echo "    REPLICA_URL vaciado en $env_test"
  cambio=1
fi

if [[ -z "$(valor "$env_prod" REPLICA_URL)" ]]; then
  poner "$env_prod" REPLICA_URL "$url_test"
  echo "    REPLICA_URL=$url_test escrito en $env_prod"
  cambio=1
fi

(( cambio )) || echo "    Ya estaba puesta: produccion copia a $(valor "$env_prod" REPLICA_URL)"

if (( cambio || reiniciar )); then
  # Testing primero: tiene que estar listo para recibir cuando produccion envie.
  recrear test
  recrear prod
fi

# -----------------------------------------------------------------------------
# 3. Copiar lo que ya existe
# -----------------------------------------------------------------------------
paso "Copiando a testing los creadores de produccion"

puerto_prod="$(valor "$env_prod" PUERTO_HOST)"
token_interno="$(valor "$env_prod" TOKEN_INTERNO)"
[[ -n "$token_interno" ]] || morir "Falta TOKEN_INTERNO en $env_prod."

respuesta="$(mktemp)"
trap 'rm -f "$respuesta"' EXIT

codigo="$(curl -sS -m 900 -o "$respuesta" -w '%{http_code}' -X POST \
  "http://127.0.0.1:${puerto_prod}/internal/replicar-creadores" \
  -H "X-Token-Interno: ${token_interno}")" \
  || morir "No se pudo hablar con produccion en el puerto $puerto_prod."

case "$codigo" in
  200) ;;
  404) morir "Produccion no reconoce la ruta. O todavia no tiene desplegada la version con la
  copia de creadores, o TOKEN_INTERNO cambio en $env_prod sin reiniciar el servidor
  (en ese caso repite con --reiniciar)." ;;
  409) morir "Produccion esta corriendo sin REPLICA_URL. Repite con --reiniciar." ;;
  *)   morir "Produccion respondio $codigo: $(cat "$respuesta")" ;;
esac

fallo=0
if command -v python3 >/dev/null; then
  python3 - "$respuesta" <<'PY' || fallo=1
import json, sys

r = json.load(open(sys.argv[1]))
print(f"    Creadores en produccion: {r['total']}")
print(f"    Copiados a testing:      {r['replicados']}")
print(f"    Fallidos:                {r['fallidos']}")
for error in r.get("errores", []):
    print(f"      - {error}")
sys.exit(1 if r["fallidos"] else 0)
PY
else
  cat "$respuesta"; echo
  grep -q '"fallidos":0' "$respuesta" || fallo=1
fi

if (( fallo )); then
  morir "Algunos creadores no se copiaron. Corrige lo que dicen los mensajes y repite el script."
fi

cat <<FIN

Listo. Testing pide ahora las suscripciones al hub de YouTube, de una en una y
en segundo plano: en $url_test/admin pasan a "Activa" en unos minutos.
FIN
