#!/usr/bin/env bash
# Trae del VPS anterior (TubeHub) lo que no se puede generar de nuevo:
# las claves de Google, YouTube y Apple, y las cuentas de servicio de Firebase.
#
# Se ejecuta en el VPS NUEVO, como root, despues de preparar-vps.sh:
#
#   bash /root/copiar-config-anterior.sh
#
# Te pedira la clave de root del servidor anterior una sola vez.
#
# No copia los secretos propios (JWT, WebSub, base de datos): el servidor nuevo
# tiene los suyos. Tampoco pisa nada: solo rellena valores que esten vacios.
# Los datos de Postgres no se tocan aqui; ver MIGRACION-VOCESLEFT.md.

set -Eeuo pipefail

ORIGEN="${ORIGEN:-root@216.238.90.94}"       # "local" lee de este mismo equipo
ORIGEN_PUERTO="${ORIGEN_PUERTO:-22}"
ORIGEN_DIR="${ORIGEN_DIR:-/opt/tubehub}"
APP_DIR="${APP_DIR:-/opt/vocesleft}"

# APPLE_BUNDLE_ID no se copia: el identificador de la app cambio.
CLAVES=(YOUTUBE_API_KEY GOOGLE_CLIENT_ID FCM_PROYECTO_ID APPLE_TEAM_ID APPLE_KEY_ID APPLE_CLAVE_PRIVADA)

[[ "$(id -u)" -eq 0 ]] || { echo "Ejecuta este script como root." >&2; exit 1; }

control="$(mktemp -u /tmp/vocesleft-ssh.XXXXXX)"
ssh_op=(-p "$ORIGEN_PUERTO" -o ControlMaster=auto -o ControlPath="$control" -o ControlPersist=120)
cerrar() { [[ "$ORIGEN" == local ]] || ssh "${ssh_op[@]}" -O exit "$ORIGEN" >/dev/null 2>&1 || true; }
trap cerrar EXIT

# shellcheck disable=SC2029  # el comando se arma aqui a proposito
en_origen() {
  if [[ "$ORIGEN" == local ]]; then bash -c "$1"; else ssh "${ssh_op[@]}" "$ORIGEN" "$1"; fi
}

valor_de() { grep -m1 "^$2=" <<<"$1" | cut -d= -f2- || true; }

# Reemplaza CLAVE=... sin interpretar el valor: una clave privada trae barras
# invertidas y simbolos que sed o `awk -v` alterarian.
fijar() {
  local archivo="$1" temporal
  temporal="$(mktemp "$archivo.XXXXXX")"
  K="$2" V="$3" awk '
    BEGIN { k = ENVIRON["K"]; v = ENVIRON["V"] }
    index($0, k "=") == 1 { print k "=" v; hecho = 1; next }
    { print }
    END { if (!hecho) print k "=" v }
  ' "$archivo" > "$temporal"
  chmod 600 "$temporal"
  mv -f -- "$temporal" "$archivo"
}

for ambiente in prod test; do
  printf '\n==> Ambiente %s\n' "$ambiente"
  nuevo="$APP_DIR/relay-server/.env.$ambiente"
  [[ -f "$nuevo" ]] || { echo "Falta $nuevo. Ejecuta antes preparar-vps.sh." >&2; exit 1; }

  anterior="$(en_origen "cat '$ORIGEN_DIR/relay-server/.env.$ambiente'")" \
    || { echo "    No pude leer el .env.$ambiente anterior; se omite." >&2; continue; }
  actual="$(cat "$nuevo")"

  for clave in "${CLAVES[@]}"; do
    viejo="$(valor_de "$anterior" "$clave")"
    mio="$(valor_de "$actual" "$clave")"
    if [[ -n "$mio" ]]; then
      echo "    $clave: ya tiene valor, no se toca."
    elif [[ -z "$viejo" ]]; then
      echo "    $clave: vacia tambien en el servidor anterior."
    else
      fijar "$nuevo" "$clave" "$viejo"
      echo "    $clave: copiada."
    fi
  done

  if [[ -n "$(valor_de "$anterior" APPLE_BUNDLE_ID)" && -z "$(valor_de "$actual" APPLE_BUNDLE_ID)" ]]; then
    echo "    APPLE_BUNDLE_ID: no se copia. Pon a mano el identificador nuevo de la app de iPhone."
  fi

  # Cuenta de servicio de Firebase.
  fcm_viejo="$(valor_de "$anterior" FCM_CREDENCIALES_HOST)"
  fcm_nuevo="$(valor_de "$actual" FCM_CREDENCIALES_HOST)"
  if [[ -s "$fcm_nuevo" ]]; then
    echo "    $fcm_nuevo: ya existe, no se toca."
  elif [[ -z "$fcm_viejo" || -z "$fcm_nuevo" ]]; then
    echo "    FCM_CREDENCIALES_HOST vacia: no hay JSON que copiar."
  else
    install -d -m 700 "$(dirname "$fcm_nuevo")"
    ( umask 077; en_origen "cat '$fcm_viejo'" > "$fcm_nuevo.tmp" )
    if [[ ! -s "$fcm_nuevo.tmp" ]]; then
      rm -f "$fcm_nuevo.tmp"
      echo "    No pude leer $fcm_viejo en el servidor anterior." >&2
      continue
    fi
    # Mismo dueno numerico y mismo modo que en el servidor anterior: asi lo
    # puede leer el usuario del contenedor (gid 101). Si no se pudo averiguar,
    # root:101 con 640.
    permisos="$(en_origen "stat -c '%u:%g %a' '$fcm_viejo'" 2>/dev/null || true)"
    [[ "$permisos" =~ ^[0-9]+:[0-9]+\ [0-7]{3,4}$ ]] || permisos="0:101 640"
    chown "${permisos% *}" "$fcm_nuevo.tmp"
    chmod "${permisos#* }" "$fcm_nuevo.tmp"
    mv -f -- "$fcm_nuevo.tmp" "$fcm_nuevo"
    echo "    $fcm_nuevo: copiado (${permisos})."
  fi
done

printf '\n==> Listo. Lo que siga vacio:\n'
for ambiente in prod test; do
  nuevo="$APP_DIR/relay-server/.env.$ambiente"
  vacias="$(grep -E '^(YOUTUBE_API_KEY|GOOGLE_CLIENT_ID|FCM_PROYECTO_ID)=$' "$nuevo" | cut -d= -f1 | tr '\n' ' ' || true)"
  echo "    .env.$ambiente: ${vacias:-nada, esta completo}"
done
