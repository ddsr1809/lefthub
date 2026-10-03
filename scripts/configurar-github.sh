#!/usr/bin/env bash
# Deja GitHub listo para desplegar VocesLeft al VPS.
#
# Se ejecuta en TU LAPTOP (no en el VPS), desde la raiz del repositorio:
#
#   gh auth login                 # solo la primera vez
#   bash scripts/configurar-github.sh
#
# Que hace:
#   1. Crea una llave SSH exclusiva para el pipeline y la autoriza en el VPS.
#   2. Escanea las huellas del VPS y comprueba que son las del servidor real.
#   3. Crea los Environments `test` y `prod` (prod solo acepta la rama master).
#   4. Guarda los seis secretos del pipeline en cada Environment.
#
# Se puede repetir: reutiliza la llave si ya existe y sobrescribe los secretos.

set -Eeuo pipefail

REPO="${REPO:-ddsr1809/lefthub}"
VPS_HOST="${VPS_HOST:-216.238.70.157}"
VPS_PORT="${VPS_PORT:-22}"
VPS_USER="${VPS_USER:-root}"
VPS_APP_DIR="${VPS_APP_DIR:-/opt/vocesleft}"
LLAVE="${LLAVE:-$HOME/.ssh/vocesleft_actions_ci}"
# Nombres adicionales del mismo servidor. Se escanean tambien para que el
# secreto siga sirviendo si algun dia VPS_HOST pasa a ser un dominio.
DOMINIOS="${DOMINIOS:-leftapp.vocesdeizquierda.com testapp.vocesdeizquierda.com}"

paso() { printf '\n==> %s\n' "$*"; }
morir() { echo "ERROR: $*" >&2; exit 1; }

for herramienta in gh ssh ssh-keygen ssh-keyscan ssh-copy-id; do
  command -v "$herramienta" >/dev/null || morir "Falta '$herramienta'. Este script va en tu laptop; instala la CLI de GitHub desde https://cli.github.com"
done
gh auth status >/dev/null 2>&1 || morir "Inicia sesion primero con: gh auth login"
gh repo view "$REPO" --json name >/dev/null || morir "No puedo ver el repositorio $REPO con esta cuenta."

destino="${VPS_USER}@${VPS_HOST}"

# -----------------------------------------------------------------------------
# 1. Llave exclusiva del pipeline
# -----------------------------------------------------------------------------
paso "Llave SSH del pipeline"
if [[ -f "$LLAVE" ]]; then
  echo "    Ya existe $LLAVE: se reutiliza."
else
  # Sin passphrase: GitHub Actions no puede responder una pregunta interactiva.
  ssh-keygen -q -t ed25519 -N "" -C vocesleft-github-actions-ci -f "$LLAVE"
  echo "    Creada $LLAVE"
fi

paso "Autorizando la llave en $destino (puede pedirte la clave de root del VPS)"
ssh-copy-id -i "$LLAVE.pub" -p "$VPS_PORT" "$destino" >/dev/null

# El mismo modo no interactivo que usa el pipeline.
quien="$(SSH_AUTH_SOCK='' ssh -o BatchMode=yes -o IdentitiesOnly=yes -i "$LLAVE" -p "$VPS_PORT" "$destino" whoami)" \
  || morir "La llave no permite entrar sin clave. Revisa ~/.ssh/authorized_keys en el VPS."
echo "    Entrada sin clave correcta como: $quien"

# -----------------------------------------------------------------------------
# 2. Huellas del servidor
# -----------------------------------------------------------------------------
paso "Escaneando las huellas del VPS"
conocidos="$(mktemp)"
trap 'rm -f "$conocidos"' EXIT

# Sin -H a proposito: con nombres cifrados no se puede ver a que host
# pertenece cada linea, y un desajuste con VPS_HOST cuesta horas encontrar.
# shellcheck disable=SC2086  # DOMINIOS es una lista separada por espacios
ssh-keyscan -p "$VPS_PORT" -t ed25519,ecdsa,rsa "$VPS_HOST" $DOMINIOS 2>/dev/null > "$conocidos" || true
grep -q "ssh-ed25519" "$conocidos" || morir "ssh-keyscan no obtuvo la llave ED25519 de $VPS_HOST:$VPS_PORT."

huella_real="$(SSH_AUTH_SOCK='' ssh -o BatchMode=yes -o IdentitiesOnly=yes -i "$LLAVE" -p "$VPS_PORT" "$destino" \
  "ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub" | awk '{print $2}')"
huellas_escaneadas="$(ssh-keygen -lf "$conocidos" | awk '/ED25519/ {print $2}' | sort -u)"
if [[ "$huellas_escaneadas" != "$huella_real" ]]; then
  echo "    Huella del servidor:  $huella_real" >&2
  echo "    Huellas escaneadas:   $(echo "$huellas_escaneadas" | tr '\n' ' ')" >&2
  morir "Las huellas no coinciden. Algun dominio apunta a otro servidor; no se guardo nada."
fi
echo "    $(grep -c . "$conocidos") llaves escaneadas; la ED25519 coincide con la del servidor ($huella_real)."

# -----------------------------------------------------------------------------
# 3. Environments
# -----------------------------------------------------------------------------
paso "Creando los Environments test y prod en $REPO"
gh api --silent -X PUT "repos/$REPO/environments/test"

# prod solo acepta despliegues desde master.
gh api --silent -X PUT "repos/$REPO/environments/prod" --input - <<'JSON'
{"deployment_branch_policy": {"protected_branches": false, "custom_branch_policies": true}}
JSON
ramas="$(gh api "repos/$REPO/environments/prod/deployment-branch-policies" --jq '.branch_policies[].name')"
if ! grep -qx "master" <<<"$ramas"; then
  gh api --silent -X POST "repos/$REPO/environments/prod/deployment-branch-policies" -f name=master
fi
echo "    test: cualquier rama.   prod: solo master."

# -----------------------------------------------------------------------------
# 4. Secretos (los mismos en los dos Environments: es un solo VPS)
# -----------------------------------------------------------------------------
for ambiente in test prod; do
  paso "Guardando secretos en el Environment '$ambiente'"
  gh secret set VPS_HOST        --repo "$REPO" --env "$ambiente" --body "$VPS_HOST"
  gh secret set VPS_PORT        --repo "$REPO" --env "$ambiente" --body "$VPS_PORT"
  gh secret set VPS_USER        --repo "$REPO" --env "$ambiente" --body "$VPS_USER"
  gh secret set VPS_APP_DIR     --repo "$REPO" --env "$ambiente" --body "$VPS_APP_DIR"
  gh secret set VPS_SSH_KEY     --repo "$REPO" --env "$ambiente" < "$LLAVE"
  gh secret set VPS_KNOWN_HOSTS --repo "$REPO" --env "$ambiente" < "$conocidos"
done

paso "Listo"
cat <<RESUMEN
    El pipeline ya puede entrar a $destino:$VPS_PORT y desplegar en $VPS_APP_DIR.

    Recomendado, a mano: en GitHub > Settings > Environments > prod activa
    "Required reviewers" para que produccion pida tu aprobacion.

    Siguiente paso: fusiona development en testing (despliega test) y,
    cuando test este sano, fusiona testing en master (despliega prod).
RESUMEN
