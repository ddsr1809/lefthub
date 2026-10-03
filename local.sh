#!/usr/bin/env bash
# Ambiente local de Voces Izquierda: base de datos, servidor y panel de administración.
#
# Va en la raíz del repositorio (junto al Makefile).
#
#   ./local.sh                    Levanta todo. Ctrl+C lo apaga (los datos se conservan).
#   ./local.sh admin CORREO       Vuelve administrador a ese correo en la base local.
#   ./local.sh down               Apaga todo, por si quedó algo encendido.
#
# Usa el mismo proyecto de Docker que `make local-up`, así que son intercambiables.
#
# Mientras está encendido mantiene un puente por USB (adb reverse) para que la
# app developer, que busca el servidor en http://localhost:8080, lo encuentre
# tanto en un teléfono físico como en el emulador.

set -euo pipefail

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV="$RAIZ/relay-server/.env.local"
EJEMPLO="$RAIZ/relay-server/.env.local.example"
PUERTO_PANEL=5000

rojo()  { printf '\033[31m%s\033[0m\n' "$*" >&2; }
verde() { printf '\033[32m%s\033[0m\n' "$*"; }
paso()  { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
fallo() { rojo "$*"; exit 1; }

# Lee una variable del .env.local (sin comillas ni espacios sobrantes).
valor() {
  local v
  v="$(grep -E "^$1=" "$ENV" | tail -n 1 | cut -d= -f2- || true)"
  v="${v%$'\r'}"; v="${v%\"}"; v="${v#\"}"; v="${v%\'}"; v="${v#\'}"
  printf '%s' "$v"
}

compose() {
  docker compose --env-file "$ENV" -p vocesleft-local -f "$RAIZ/relay-server/docker-compose.yml" "$@"
}

comprobar_requisitos() {
  [ -f "$RAIZ/relay-server/docker-compose.yml" ] && [ -d "$RAIZ/admin" ] \
    || fallo "Este archivo tiene que estar en la raíz del repositorio lefthub."
  command -v docker >/dev/null || fallo "Falta Docker."
  docker compose version >/dev/null 2>&1 || fallo "Falta el plugin 'docker compose'."
  docker info >/dev/null 2>&1 || fallo "Docker no está corriendo, o tu usuario no tiene permiso para usarlo."
  command -v python3 >/dev/null || fallo "Falta python3 (se usa para servir el panel)."
}

# La primera vez crea el .env.local con los secretos ya generados.
preparar_env() {
  [ -f "$ENV" ] && return 0
  command -v openssl >/dev/null || fallo "Falta openssl (se usa para generar los secretos)."
  cp "$EJEMPLO" "$ENV"
  local clave cliente
  for clave in DB_CLAVE JWT_SECRETO WEBSUB_SECRETO WEBSUB_TOKEN_CALLBACK TOKEN_INTERNO; do
    sed -i "s|^$clave=.*|$clave=$(openssl rand -hex 32)|" "$ENV"
  done
  cliente="$(grep -oE "[0-9]+-[a-z0-9]+\.apps\.googleusercontent\.com" "$RAIZ/admin/config.js" | head -n 1 || true)"
  [ -n "$cliente" ] && sed -i "s|^GOOGLE_CLIENT_ID=.*|GOOGLE_CLIENT_ID=$cliente|" "$ENV"

  paso "Creé relay-server/.env.local con los secretos ya generados"
  echo "Faltan tres valores que solo tú tienes. Ábrelo y rellena:"
  echo "  YOUTUBE_API_KEY         la clave de la YouTube Data API"
  echo "  FCM_PROYECTO_ID         el id del proyecto de Firebase"
  echo "  FCM_CREDENCIALES_HOST   la ruta del .json de la cuenta de servicio de Firebase"
  echo
  echo "Después vuelve a correr ./local.sh"
  exit 0
}

revisar_env() {
  local falta=0 clave fcm
  for clave in DB_CLAVE JWT_SECRETO GOOGLE_CLIENT_ID; do
    [ -n "$(valor "$clave")" ] || { rojo "Falta $clave en relay-server/.env.local"; falta=1; }
  done
  fcm="$(valor FCM_CREDENCIALES_HOST)"
  # Si el archivo no existe, Docker crea una carpeta con ese nombre y el servidor no arranca.
  [ -f "$fcm" ] || { rojo "FCM_CREDENCIALES_HOST apunta a un archivo que no existe: $fcm"; falta=1; }
  [ "$falta" -eq 0 ] || exit 1

  [ -n "$(valor YOUTUBE_API_KEY)" ] || rojo "Aviso: YOUTUBE_API_KEY está vacía; buscar canales no va a funcionar."
  case ",$(valor CORS_ORIGENES)," in
    *",http://localhost:$PUERTO_PANEL,"*) ;;
    *) fallo "Agrega http://localhost:$PUERTO_PANEL a CORS_ORIGENES en relay-server/.env.local" ;;
  esac
}

# adb está en el PATH o dentro del SDK de Android. La ruta del SDK la apunta
# Android Studio en android/local.properties (sdk.dir), esté donde esté.
buscar_adb() {
  local sdk del_proyecto=""
  if command -v adb >/dev/null; then command -v adb; return 0; fi
  if [ -f "$RAIZ/android/local.properties" ]; then
    del_proyecto="$(grep -E '^sdk\.dir=' "$RAIZ/android/local.properties" | tail -n 1 | cut -d= -f2- | tr -d '\r' || true)"
  fi
  for sdk in "$del_proyecto" "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk"; do
    if [ -n "$sdk" ] && [ -x "$sdk/platform-tools/adb" ]; then echo "$sdk/platform-tools/adb"; return 0; fi
  done
  return 0
}

dispositivos() { "$1" devices 2>/dev/null | awk 'NR > 1 && $2 == "device" { print $1 }'; }

# El puente se pierde cada vez que se desconecta el cable, así que se repone
# cada pocos segundos para todo lo que esté conectado.
mantener_puente() {
  local adb="$1" puerto="$2" serie
  while true; do
    for serie in $(dispositivos "$adb"); do
      "$adb" -s "$serie" reverse tcp:8080 "tcp:$puerto" >/dev/null 2>&1 || true
    done
    sleep 3
  done
}

ADB=""
PUENTE_PID=""

apagar() {
  local codigo=$? serie
  trap - INT TERM EXIT
  if [ -n "$PUENTE_PID" ]; then
    kill "$PUENTE_PID" 2>/dev/null || true
    for serie in $(dispositivos "$ADB"); do
      "$ADB" -s "$serie" reverse --remove tcp:8080 >/dev/null 2>&1 || true
    done
  fi
  paso "Apagando (los datos se conservan)"
  compose down || true
  exit "$codigo"
}

levantar() {
  comprobar_requisitos
  preparar_env
  revisar_env

  local puerto api
  puerto="$(valor PUERTO_HOST)"; puerto="${puerto:-8080}"
  api="http://localhost:$puerto"

  paso "Levantando la base de datos y el servidor (la primera vez tarda unos minutos)"
  trap apagar INT TERM EXIT
  if ! compose up -d --build --wait; then
    rojo "El servidor no arrancó. Últimas líneas del registro:"
    compose logs --tail 40 servidor >&2 || true
    exit 1
  fi

  ADB="$(buscar_adb)"
  if [ -n "$ADB" ]; then
    mantener_puente "$ADB" "$puerto" &
    PUENTE_PID=$!
  fi

  paso "Todo arriba"
  verde "  Panel:     http://localhost:$PUERTO_PANEL"
  verde "  Servidor:  $api"
  if [ -n "$ADB" ]; then
    verde "  App:       variante developerDebug, en teléfono por USB o en emulador"
  else
    rojo  "  App:       no encontré adb, así que la app developer no va a alcanzar el servidor."
    rojo  "             Abre el proyecto android/ en Android Studio una vez, o agrega platform-tools al PATH."
  fi
  echo
  echo  "  Registro del servidor (en otra terminal):  make local-logs"
  echo  "  Volverte administrador (en otra terminal): ./local.sh admin tu-correo@gmail.com"
  echo
  echo  "  Ctrl+C para apagar todo."

  # Sirve admin/ tal cual está en el repositorio, pero responde /config.js con
  # la dirección del servidor local, para no tener que editar el archivo real.
  PANEL_DIR="$RAIZ/admin" PANEL_PUERTO="$PUERTO_PANEL" \
  PANEL_CONFIG="window.VOCESLEFT_CONFIG = { googleClientId: '$(valor GOOGLE_CLIENT_ID)', apiBase: '$api' };" \
  python3 - <<'PY' || true
import functools, http.server, os, sys

config = os.environ["PANEL_CONFIG"].encode()

class Panel(http.server.SimpleHTTPRequestHandler):
    def do_GET(self):
        if self.path.split("?")[0] == "/config.js":
            self.send_response(200)
            self.send_header("Content-Type", "application/javascript; charset=utf-8")
            self.send_header("Content-Length", str(len(config)))
            self.end_headers()
            self.wfile.write(config)
            return
        super().do_GET()

    def end_headers(self):
        # Sin caché: los cambios en admin/ se ven con solo recargar.
        self.send_header("Cache-Control", "no-store")
        super().end_headers()

    def log_message(self, *args):
        pass

http.server.ThreadingHTTPServer.allow_reuse_address = True
try:
    servidor = http.server.ThreadingHTTPServer(
        ("127.0.0.1", int(os.environ["PANEL_PUERTO"])),
        functools.partial(Panel, directory=os.environ["PANEL_DIR"]),
    )
except OSError as error:
    sys.exit(f"No pude abrir el puerto {os.environ['PANEL_PUERTO']} para el panel: {error}")
try:
    servidor.serve_forever()
except KeyboardInterrupt:
    pass
PY
}

nombrar_admin() {
  local correo="${1:-}" salida
  [ -n "$correo" ] || fallo "Uso: ./local.sh admin tu-correo@gmail.com"
  [[ "$correo" =~ ^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+$ ]] || fallo "Eso no parece un correo: $correo"
  comprobar_requisitos
  [ -f "$ENV" ] || fallo "Todavía no existe relay-server/.env.local. Corre ./local.sh primero."

  salida="$(compose exec -T db psql -U "$(valor DB_USUARIO)" -d "$(valor DB_NOMBRE)" \
    -c "update usuarios set es_admin = true where email = '$correo';")" \
    || fallo "No pude conectarme a la base. ¿Está corriendo ./local.sh en otra terminal?"

  if [ "$salida" = "UPDATE 0" ]; then
    fallo "No existe un usuario con ese correo. Entra una vez al panel con esa cuenta de Google y repite."
  fi
  verde "Listo: $correo ya es administrador. Cierra sesión en el panel y vuelve a entrar."
}

case "${1:-up}" in
  up)    levantar ;;
  admin) nombrar_admin "${2:-}" ;;
  down)  comprobar_requisitos; [ -f "$ENV" ] || fallo "No existe relay-server/.env.local."; compose down ;;
  *)     sed -n '2,10p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
