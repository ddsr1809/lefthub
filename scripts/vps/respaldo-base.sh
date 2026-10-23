#!/usr/bin/env bash
# Respaldo cifrado de las bases de VocesLeft (produccion y pruebas), con sus
# llaves y secretos, que solo puede abrir el dueno.
#
# Se ejecuta como root en el VPS.
#
#   bash /opt/vocesleft/scripts/vps/respaldo-base.sh instalar
#       Una vez. Instala age y el temporizador diario (3:15 de Hermosillo).
#       Necesita antes la llave PUBLICA del dueno (ver "La llave", abajo).
#
#   bash /opt/vocesleft/scripts/vps/respaldo-base.sh
#       Una pasada: lo que ejecuta el temporizador.
#
#   bash /opt/vocesleft/scripts/vps/respaldo-base.sh estado
#       Los respaldos que hay y cuando toca el siguiente.
#
# Que guarda, por ambiente, en /var/backups/vocesleft/<fecha>/:
#   <ambiente>-base.dump.age      pg_dump completo (usuarios, compras, chat...)
#   <ambiente>-secretos.tar.age   su .env (con DATOS_LLAVES, CHAT_LLAVES,
#                                 JWT_SECRETO...) y la cuenta de Firebase
#
# Sin las llaves del .env, la base restaurada no sirve: los correos, IPs y
# mensajes del chat van cifrados. Por eso van juntas, y por eso todo se cifra
# con la llave publica del dueno: el VPS puede cifrar, pero no descifrar. Si
# alguien se lleva el disco o los respaldos, no puede abrir nada. Nada se
# escribe sin cifrar en el disco ni un momento: pg_dump y tar van directo a age.
#
# La llave
#   En TU computadora (no en el VPS), una sola vez:
#     age-keygen -o ~/vocesleft-respaldos.key      (brew/apt install age)
#   Guarda ese archivo en un lugar seguro y con copia (un gestor de
#   contrasenas, una USB guardada). Sin el, los respaldos no se abren.
#   Copia la linea "public key: age1..." al VPS:
#     echo 'age1...' > /root/.config/vocesleft/respaldo.pub
#
# Bajar los respaldos a tu computadora (conviene hacerlo seguido: un respaldo
# que solo vive en el VPS se pierde con el VPS):
#   rsync -av root@216.238.70.157:/var/backups/vocesleft/ ~/respaldos-vocesleft/
#
# Restaurar (en tu computadora, o en un VPS nuevo copiando ahi la llave privada
# solo mientras dura):
#   age -d -i ~/vocesleft-respaldos.key prod-secretos.tar.age | tar x
#   age -d -i ~/vocesleft-respaldos.key prod-base.dump.age > prod.dump
#   docker compose ... exec -T db pg_restore -U relay -d relay_prod --clean --if-exists < prod.dump
#   Ver docs/wiki/seguridad.md, "Restaurar un respaldo".
#
# Variables:
#   RESPALDO_DIR      donde se guardan (/var/backups/vocesleft)
#   RESPALDO_DIAS     cuantos dias se conservan en el VPS (14)
#   RESPALDO_PUB      archivo con la llave publica (/root/.config/vocesleft/respaldo.pub)
#   RESPALDO_RCLONE   opcional: destino de rclone (p. ej. "b2:vocesleft") al
#                     que se copia cada respaldo, ya cifrado. rclone se
#                     configura aparte con `rclone config`.

set -Eeuo pipefail

APP_DIR="${APP_DIR:-/opt/vocesleft}"
RESPALDO_DIR="${RESPALDO_DIR:-/var/backups/vocesleft}"
RESPALDO_DIAS="${RESPALDO_DIAS:-14}"
RESPALDO_PUB="${RESPALDO_PUB:-/root/.config/vocesleft/respaldo.pub}"
RESPALDO_RCLONE="${RESPALDO_RCLONE:-}"
UNIDAD="${UNIDAD:-vocesleft-respaldo}"
DIR_SYSTEMD="${DIR_SYSTEMD:-/etc/systemd/system}"
AMBIENTES=(prod test)

paso()  { printf '\n==> %s\n' "$*"; }
morir() { printf '\nERROR: %s\n' "$*" >&2; exit 1; }

[[ "$(id -u)" -eq 0 ]] || morir "Ejecuta este script como root."
[[ "$RESPALDO_DIAS" =~ ^[0-9]+$ ]] || morir "RESPALDO_DIAS tiene que ser un numero."

llave_publica() {
  [[ -s "$RESPALDO_PUB" ]] || morir "Falta la llave publica del dueno en $RESPALDO_PUB.
  En tu computadora: age-keygen -o ~/vocesleft-respaldos.key
  Y en el VPS:       echo 'age1...' > $RESPALDO_PUB"
  grep -qE '^age1[0-9a-z]{58}$' "$RESPALDO_PUB" \
    || morir "$RESPALDO_PUB no tiene una llave publica de age (una linea que empieza por age1)."
}

# El contenedor de Postgres de un ambiente, o vacio si no esta corriendo.
contenedor_db() {
  docker ps -q \
    --filter "label=com.docker.compose.project=vocesleft-$1" \
    --filter "label=com.docker.compose.service=db" | head -n 1
}

pasada() {
  command -v age >/dev/null || morir "Falta age. Ejecuta: bash $0 instalar"
  llave_publica

  local fecha destino ambiente id env fcm hechos=0
  fecha="$(date -u +%Y-%m-%dT%H%MZ)"
  destino="$RESPALDO_DIR/$fecha"
  umask 077
  install -d -m 700 "$RESPALDO_DIR" "$destino"

  for ambiente in "${AMBIENTES[@]}"; do
    id="$(contenedor_db "$ambiente")"
    if [[ -z "$id" ]]; then
      echo "    $ambiente: su base no esta corriendo; se salta."
      continue
    fi

    # La base, directo de pg_dump a age.
    docker exec -i "$id" sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' \
      | age -R "$RESPALDO_PUB" -o "$destino/$ambiente-base.dump.age"

    # Las llaves y secretos de ese ambiente, directo de tar a age.
    env="$APP_DIR/relay-server/.env.$ambiente"
    fcm="$(grep -E '^FCM_CREDENCIALES_HOST=' "$env" 2>/dev/null | cut -d= -f2- || true)"
    local archivos=()
    [[ -f "$env" ]] && archivos+=("$env")
    [[ -n "$fcm" && -f "$fcm" ]] && archivos+=("$fcm")
    if (( ${#archivos[@]} )); then
      tar -c -P -f - "${archivos[@]}" 2>/dev/null | age -R "$RESPALDO_PUB" -o "$destino/$ambiente-secretos.tar.age"
    fi

    echo "    $ambiente: $(du -h "$destino/$ambiente-base.dump.age" | cut -f1) de base, cifrado."
    hechos=$((hechos + 1))
  done

  if (( hechos == 0 )); then
    rmdir "$destino" 2>/dev/null || true
    morir "No habia ninguna base corriendo: no se respaldo nada."
  fi

  # Los de hace mas de RESPALDO_DIAS dias se borran del VPS.
  find "$RESPALDO_DIR" -mindepth 1 -maxdepth 1 -type d -name '20*' -mtime +"$RESPALDO_DIAS" -exec rm -rf {} +

  if [[ -n "$RESPALDO_RCLONE" ]]; then
    if command -v rclone >/dev/null; then
      rclone copy "$destino" "$RESPALDO_RCLONE/$fecha" && echo "    Copiado a $RESPALDO_RCLONE/$fecha."
    else
      echo "    AVISO: RESPALDO_RCLONE esta puesto pero rclone no esta instalado." >&2
    fi
  fi

  echo "    Listo: $destino"
}

instalar() {
  command -v systemctl >/dev/null || morir "Este script espera systemd (Ubuntu o Debian)."
  paso "Instalando age"
  command -v age >/dev/null || { apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq age >/dev/null; }
  age --version

  install -d -m 700 "$(dirname "$RESPALDO_PUB")"
  llave_publica

  paso "Temporizador diario"
  local script
  script="$(readlink -f "$0")"
  cat > "$DIR_SYSTEMD/$UNIDAD.service" <<EOF
[Unit]
Description=Respaldo cifrado de las bases de VocesLeft
After=docker.service
Requires=docker.service

[Service]
Type=oneshot
Environment=RESPALDO_DIR=$RESPALDO_DIR RESPALDO_DIAS=$RESPALDO_DIAS RESPALDO_PUB=$RESPALDO_PUB
Environment=RESPALDO_RCLONE=$RESPALDO_RCLONE
ExecStart=/usr/bin/env bash $script
EOF
  cat > "$DIR_SYSTEMD/$UNIDAD.timer" <<EOF
[Unit]
Description=Respaldo cifrado diario de VocesLeft

[Timer]
# 3:15 en Hermosillo (UTC-7, sin horario de verano).
OnCalendar=*-*-* 10:15:00 UTC
Persistent=true
RandomizedDelaySec=10m

[Install]
WantedBy=timers.target
EOF
  systemctl daemon-reload
  systemctl enable --now --quiet "$UNIDAD.timer"

  paso "Primer respaldo"
  pasada
  cat <<FIN

    Listo. Cada dia a las 3:15 (Hermosillo) se guarda un respaldo cifrado en
    $RESPALDO_DIR y se conservan $RESPALDO_DIAS dias.

    - Ver el temporizador:  systemctl list-timers $UNIDAD.timer
    - Ver la ultima pasada: journalctl -u $UNIDAD -n 30
    - Bajarlos a tu equipo: rsync -av root@<vps>:$RESPALDO_DIR/ ~/respaldos-vocesleft/
FIN
}

estado() {
  paso "Respaldos en $RESPALDO_DIR"
  local hay
  hay="$(find "$RESPALDO_DIR" -mindepth 1 -maxdepth 1 -type d -name '20*' -printf '%f\n' 2>/dev/null | sort | tail -n 20)"
  if [[ -n "$hay" ]]; then sed 's/^/    /' <<<"$hay"; else echo "    Ninguno todavia."; fi
  paso "Siguiente"
  systemctl list-timers "$UNIDAD.timer" --no-pager 2>/dev/null || echo "    El temporizador no esta instalado."
}

case "${1:-}" in
  instalar) instalar ;;
  estado)   estado ;;
  "")       pasada ;;
  *)        morir "Uso: bash $0 [instalar|estado]" ;;
esac
