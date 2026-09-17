#!/usr/bin/env bash
set -Eeuo pipefail

ambiente="${1:-}"
imagen="${2:-}"

if [[ "$ambiente" != "test" && "$ambiente" != "prod" ]]; then
  echo "Ambiente invalido: usa test o prod." >&2
  exit 2
fi

: "${VPS_HOST:?Falta VPS_HOST}"
: "${VPS_USER:?Falta VPS_USER}"
: "${VPS_APP_DIR:?Falta VPS_APP_DIR}"
: "${GHCR_USER:?Falta GHCR_USER}"
: "${GHCR_TOKEN:?Falta GHCR_TOKEN}"
: "${imagen:?Falta la imagen a desplegar}"

puerto="${VPS_PORT:-22}"

[[ "$puerto" =~ ^[0-9]+$ ]] || { echo "VPS_PORT debe ser numerico." >&2; exit 2; }
[[ "$VPS_USER" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "VPS_USER contiene caracteres no permitidos." >&2; exit 2; }
[[ "$VPS_HOST" =~ ^[A-Za-z0-9.:-]+$ ]] || { echo "VPS_HOST contiene caracteres no permitidos." >&2; exit 2; }
[[ "$VPS_APP_DIR" =~ ^/[A-Za-z0-9._/-]+$ ]] || { echo "VPS_APP_DIR debe ser una ruta absoluta sin espacios." >&2; exit 2; }
[[ "$GHCR_USER" =~ ^[A-Za-z0-9-]+$ ]] || { echo "GHCR_USER contiene caracteres no permitidos." >&2; exit 2; }
[[ "$imagen" =~ ^ghcr\.io/[A-Za-z0-9._/-]+:[A-Za-z0-9._-]+$ ]] || { echo "Nombre de imagen GHCR invalido." >&2; exit 2; }

destino="${VPS_USER}@${VPS_HOST}"
directorio_servidor="${VPS_APP_DIR%/}/relay-server"
archivo_env=".env.${ambiente}"
proyecto="relay-${ambiente}"
ssh_opciones=(-p "$puerto" -o BatchMode=yes -o StrictHostKeyChecking=yes)

# El compose forma parte del artefacto de despliegue. Los .env y los datos no
# se copian: permanecen exclusivamente en el VPS.
ssh "${ssh_opciones[@]}" "$destino" bash -s -- "$directorio_servidor" <<'REMOTO_DIR'
set -Eeuo pipefail
mkdir -p "$1"
REMOTO_DIR
scp -P "$puerto" -o BatchMode=yes -o StrictHostKeyChecking=yes \
  relay-server/docker-compose.yml \
  "$destino:$directorio_servidor/docker-compose.yml"

# El token de GitHub solo viaja por stdin y se elimina del VPS al terminar.
printf '%s' "$GHCR_TOKEN" | ssh "${ssh_opciones[@]}" "$destino" \
  "docker login ghcr.io --username '$GHCR_USER' --password-stdin >/dev/null"

estado=0
ssh "${ssh_opciones[@]}" "$destino" bash -s -- \
  "$directorio_servidor" "$archivo_env" "$proyecto" "$imagen" <<'REMOTO' || estado=$?
set -Eeuo pipefail

directorio="$1"
archivo_env="$2"
proyecto="$3"
imagen="$4"

cd "$directorio"
test -f docker-compose.yml
test -f "$archivo_env"

compose=(docker compose --env-file "$archivo_env" -p "$proyecto" -f docker-compose.yml)
"${compose[@]}" config --quiet

contenedor_anterior="$("${compose[@]}" ps -q servidor 2>/dev/null || true)"
imagen_anterior=""
if [[ -n "$contenedor_anterior" ]]; then
  imagen_anterior="$(docker inspect --format '{{.Config.Image}}' "$contenedor_anterior")"
fi

docker pull "$imagen"
"${compose[@]}" up -d db
SERVIDOR_IMAGEN="$imagen" "${compose[@]}" up -d --no-deps --force-recreate servidor

esperar_salud() {
  local intento contenedor salud
  for intento in {1..24}; do
    contenedor="$("${compose[@]}" ps -q servidor 2>/dev/null || true)"
    if [[ -n "$contenedor" ]]; then
      salud="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$contenedor")"
      if [[ "$salud" == "healthy" ]]; then
        return 0
      fi
      if [[ "$salud" == "unhealthy" || "$salud" == "exited" || "$salud" == "dead" ]]; then
        return 1
      fi
    fi
    sleep 5
  done
  return 1
}

if esperar_salud; then
  "${compose[@]}" ps
  echo "Despliegue saludable: $imagen"
  exit 0
fi

echo "El healthcheck fallo para $imagen" >&2
"${compose[@]}" logs --tail=120 servidor >&2 || true

if [[ -n "$imagen_anterior" && "$imagen_anterior" != "$imagen" ]]; then
  echo "Restaurando imagen anterior: $imagen_anterior" >&2
  SERVIDOR_IMAGEN="$imagen_anterior" "${compose[@]}" up -d --no-deps --force-recreate servidor
  if esperar_salud; then
    echo "Rollback completado correctamente." >&2
  else
    echo "ATENCION: el rollback tampoco paso el healthcheck." >&2
  fi
else
  echo "No existe una imagen anterior para hacer rollback." >&2
fi

exit 1
REMOTO

ssh "${ssh_opciones[@]}" "$destino" "docker logout ghcr.io >/dev/null 2>&1 || true" || true
exit "$estado"
