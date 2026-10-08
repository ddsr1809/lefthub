#!/usr/bin/env bash
# Guarda el directorio (creadores, productoras y canales) de testing y de
# produccion en la carpeta datos/ del repositorio, y lo sube a GitHub.
#
# Se ejecuta como root en el VPS.
#
#   bash /opt/vocesleft/scripts/vps/respaldo-datos.sh instalar
#       Una vez. Crea la llave con la que el VPS sube a GitHub, un clon aparte
#       para los datos y el temporizador que repite la pasada cada 5 minutos.
#       Se puede repetir sin riesgo.
#
#   bash /opt/vocesleft/scripts/vps/respaldo-datos.sh
#       Una pasada: lee las dos bases, actualiza los archivos y, si algo
#       cambio, hace el commit y lo sube. Es lo que ejecuta el temporizador.
#
#   bash /opt/vocesleft/scripts/vps/respaldo-datos.sh restaurar produccion [opciones]
#       Devuelve a la base lo que este en los archivos y falte en ella. Nunca
#       pisa ni borra lo que ya hay.
#         --solo TEXTO     solo a quien lleve TEXTO en el nombre de su archivo
#         --desde COMMIT   los archivos como estaban en ese commit
#         --simular        decir que haria, sin guardar nada
#
#   bash /opt/vocesleft/scripts/vps/respaldo-datos.sh guardar produccion --forzar
#       Cuando la pasada se niega a guardar porque borraria mas de la mitad de
#       los archivos y de verdad los borraste a proposito.
#
# Los datos se suben desde un clon aparte (/opt/vocesleft-datos) para no tocar
# el de /opt/vocesleft, de donde sale el panel. Ese clon se pone igual que
# GitHub al empezar cada pasada: lo que cuenta es lo que hay en las bases.

set -Eeuo pipefail

APP_DIR="${APP_DIR:-/opt/vocesleft}"
DATOS_DIR="${DATOS_DIR:-/opt/vocesleft-datos}"
REPO_SSH="${REPO_SSH:-git@github.com:ddsr1809/lefthub.git}"
RAMA="${RAMA:-master}"
LLAVE="${LLAVE:-/root/.ssh/vocesleft_datos}"
AUTOR_NOMBRE="${AUTOR_NOMBRE:-ddsr1809}"
AUTOR_CORREO="${AUTOR_CORREO:-dam2600@gmail.com}"
CADA="${CADA:-5min}"
UNIDAD="${UNIDAD:-vocesleft-datos}"
DIR_SYSTEMD="${DIR_SYSTEMD:-/etc/systemd/system}"
CANDADO="${CANDADO:-/run/lock/vocesleft-datos.lock}"

AMBIENTES=(testing produccion)
HERRAMIENTA="$APP_DIR/scripts/datos/datos.py"
SSH_GIT="ssh -i $LLAVE -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new -o BatchMode=yes"

paso()  { printf '\n==> %s\n' "$*"; }
morir() { printf '\nERROR: %s\n' "$*" >&2; exit 1; }
git_datos() { git -C "$DATOS_DIR" "$@"; }

es_ambiente() {
  local a
  for a in "${AMBIENTES[@]}"; do [[ "$a" == "$1" ]] && return 0; done
  return 1
}

requisitos() {
  local orden
  for orden in git python3 docker flock; do
    command -v "$orden" >/dev/null || morir "Falta $orden en este servidor."
  done
  [[ -f "$HERRAMIENTA" ]] || morir "No existe $HERRAMIENTA. Trae el codigo: git -C $APP_DIR pull"
}

con_clon() {
  [[ -d "$DATOS_DIR/.git" ]] || morir "Todavia no esta instalado. Ejecuta: bash $0 instalar"
}

# Un solo proceso a la vez: la pasada del temporizador y una orden a mano no
# deben escribir en el clon al mismo tiempo.
candado() {
  exec 9>"$CANDADO"
  if ! flock -n 9; then
    [[ "${1:-}" == "esperar" ]] || { echo "Ya hay una pasada en marcha; esta se salta."; exit 0; }
    flock -w 120 9 || morir "Hay otra pasada que no termina. Mira: journalctl -u $UNIDAD -n 50"
  fi
}

# Deja el clon igual que GitHub. Un commit de datos que no llego a subirse se
# pierde aqui y no importa: la pasada lo vuelve a sacar de la base.
ponerse_al_dia() {
  git_datos fetch --quiet origin "$RAMA" \
    || morir "No pude traer la rama $RAMA de GitHub. Si la llave ya no vale, repite: bash $0 instalar"
  git_datos reset --quiet --hard "origin/$RAMA"
}

subir_cambios() {
  local rutas=() a
  for a in "${AMBIENTES[@]}"; do
    [[ -d "$DATOS_DIR/datos/$a" ]] && rutas+=("datos/$a")
  done
  [[ ${#rutas[@]} -gt 0 ]] || return 0

  git_datos add --all -- "${rutas[@]}"
  if git_datos diff --cached --quiet; then
    echo "Sin cambios que subir."
    return 0
  fi

  local cuales cuantos mensaje
  cuales="$(git_datos diff --cached --name-only | cut -d/ -f2 | sort -u | paste -sd' ' - | sed 's/ / y /')"
  cuantos="$(git_datos diff --cached --name-only | wc -l | tr -d ' ')"
  mensaje="$(
    echo "Datos de $cuales: $cuantos archivos"
    echo
    git_datos diff --cached --name-status | head -n 60
    [[ "$cuantos" -le 60 ]] || echo "... y $((cuantos - 60)) mas"
  )"
  git_datos commit --quiet --message="$mensaje"

  git_datos push --quiet origin "HEAD:$RAMA" \
    || morir "GitHub no acepto el commit. Si alguien subio algo a $RAMA en este momento, la
siguiente pasada lo arregla sola. Si se repite, la llave no tiene permiso de escritura
o la rama esta protegida contra subidas directas."
  echo "Subido a GitHub: $(git_datos log -1 --format='%h %s')"
}

# -----------------------------------------------------------------------------
pasada() {
  requisitos
  con_clon
  candado
  ponerse_al_dia

  local a fallos=0
  for a in "${AMBIENTES[@]}"; do
    python3 "$HERRAMIENTA" guardar "$a" --carpeta "$DATOS_DIR/datos/$a" || fallos=$((fallos + 1))
  done

  # Lo que si se pudo guardar se sube aunque el otro ambiente haya fallado.
  subir_cambios
  [[ "$fallos" -eq 0 ]] || morir "$fallos de ${#AMBIENTES[@]} ambientes no se guardaron (ver arriba)."
}

guardar_uno() {
  local ambiente="${1:-}"
  es_ambiente "$ambiente" || morir "Uso: $0 guardar <${AMBIENTES[*]}> [--forzar]"
  shift
  requisitos
  con_clon
  candado esperar
  ponerse_al_dia
  python3 "$HERRAMIENTA" guardar "$ambiente" --carpeta "$DATOS_DIR/datos/$ambiente" "$@"
  subir_cambios
}

restaurar() {
  local ambiente="${1:-}" desde="" resto=()
  es_ambiente "$ambiente" || morir "Uso: $0 restaurar <${AMBIENTES[*]}> [--solo TEXTO] [--desde COMMIT] [--simular]"
  shift
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --desde)   [[ $# -ge 2 ]] || morir "--desde necesita un commit."; desde="$2"; shift 2 ;;
      --desde=*) desde="${1#--desde=}"; shift ;;
      *)         resto+=("$1"); shift ;;
    esac
  done

  requisitos
  con_clon
  candado esperar
  ponerse_al_dia

  local carpeta="$DATOS_DIR/datos/$ambiente"
  if [[ -n "$desde" ]]; then
    # Los archivos de ese commit, en una carpeta temporal: el clon no se mueve.
    git_datos cat-file -e "$desde^{commit}" 2>/dev/null \
      || morir "No conozco el commit '$desde'. Buscalo en GitHub, en el historial de datos/$ambiente."
    local temporal
    temporal="$(mktemp -d)"
    # shellcheck disable=SC2064
    trap "rm -rf '$temporal'" EXIT
    git_datos archive "$desde" -- "datos/$ambiente" 2>/dev/null | tar -x -C "$temporal" \
      || morir "En el commit '$desde' no existe datos/$ambiente."
    carpeta="$temporal/datos/$ambiente"
    paso "Restaurando $ambiente con los archivos de $(git_datos log -1 --format='%h (%ad) %s' --date=short "$desde")"
  else
    paso "Restaurando $ambiente con los archivos de ahora"
  fi

  python3 "$HERRAMIENTA" restaurar "$ambiente" --carpeta "$carpeta" "${resto[@]}"
}

# -----------------------------------------------------------------------------
instalar() {
  [[ "$(id -u)" -eq 0 ]] || morir "Ejecuta este script como root."
  requisitos
  command -v systemctl >/dev/null || morir "Este script espera systemd (Ubuntu o Debian)."
  command -v ssh-keygen >/dev/null || morir "Falta ssh-keygen."

  paso "Llave para subir a GitHub"
  if [[ ! -f "$LLAVE" ]]; then
    mkdir -p "$(dirname "$LLAVE")" && chmod 700 "$(dirname "$LLAVE")"
    ssh-keygen -q -t ed25519 -N "" -C "vocesleft-datos@$(hostname)" -f "$LLAVE"
    echo "    Creada: $LLAVE"
  else
    echo "    Ya existia: $LLAVE"
  fi

  local respuesta
  if ! respuesta="$(GIT_SSH_COMMAND="$SSH_GIT" git ls-remote --exit-code "$REPO_SSH" "refs/heads/$RAMA" 2>&1)"; then
    cat <<EOF

GitHub no dejo entrar con esta llave:
$(printf '%s\n' "$respuesta" | tail -n 2 | sed 's/^/    /')

Lo normal es que todavia no la conozca. Agregala una vez, desde tu navegador:

  1. Abre https://github.com/ddsr1809/lefthub/settings/keys/new
  2. Title: VPS datos
  3. Key: pega esta linea completa

$(cat "$LLAVE.pub")

  4. Marca "Allow write access" y pulsa "Add key".

Luego repite:  bash $0 instalar
EOF
    exit 1
  fi
  echo "    GitHub la reconoce."

  paso "Clon aparte para los datos en $DATOS_DIR"
  if [[ ! -d "$DATOS_DIR/.git" ]]; then
    GIT_SSH_COMMAND="$SSH_GIT" git clone --quiet --no-checkout --single-branch --branch "$RAMA" \
      "$REPO_SSH" "$DATOS_DIR"
    # Solo hace falta la carpeta datos/: el resto del codigo no se descarga a disco.
    git_datos sparse-checkout set datos
    git_datos checkout --quiet "$RAMA"
  fi
  git_datos remote set-url origin "$REPO_SSH"
  git_datos config core.sshCommand "$SSH_GIT"
  git_datos config user.name "$AUTOR_NOMBRE"
  git_datos config user.email "$AUTOR_CORREO"
  ponerse_al_dia
  git_datos log -1 --format='    %h %ad %s' --date=short

  if git_datos push --quiet --dry-run origin "HEAD:$RAMA" 2>/dev/null; then
    echo "    La llave puede escribir."
  else
    morir "La llave puede leer pero no escribir. En
https://github.com/ddsr1809/lefthub/settings/keys borra 'VPS datos' y agregala
otra vez marcando \"Allow write access\". Luego repite: bash $0 instalar"
  fi

  paso "Temporizador: una pasada cada $CADA"
  cat > "$DIR_SYSTEMD/$UNIDAD.service" <<EOF
[Unit]
Description=VocesLeft: guardar el directorio en datos/ y subirlo a GitHub
After=docker.service network-online.target
Wants=network-online.target

[Service]
Type=oneshot
Environment=APP_DIR=$APP_DIR DATOS_DIR=$DATOS_DIR RAMA=$RAMA LLAVE=$LLAVE
ExecStart=/usr/bin/env bash $APP_DIR/scripts/vps/respaldo-datos.sh
EOF
  cat > "$DIR_SYSTEMD/$UNIDAD.timer" <<EOF
[Unit]
Description=VocesLeft: guardar el directorio cada $CADA

[Timer]
OnBootSec=3min
OnUnitActiveSec=$CADA
AccuracySec=30s

[Install]
WantedBy=timers.target
EOF
  systemctl daemon-reload
  systemctl enable --now --quiet "$UNIDAD.timer"
  echo "    Encendido."

  paso "Primera pasada"
  # En un proceso aparte: si un ambiente todavia no esta desplegado, la
  # instalacion queda hecha igual y el temporizador lo recogera cuando exista.
  if ! bash "$0"; then
    echo
    echo "La instalacion quedo hecha, pero la primera pasada no salio limpia (ver arriba)."
  fi

  cat <<EOF

Listo. A partir de ahora:
  - Los cambios del panel aparecen en GitHub, en datos/, en menos de $CADA.
  - Ver las ultimas pasadas:   journalctl -u $UNIDAD -n 30 --no-pager
  - Ver el temporizador:       systemctl list-timers $UNIDAD.timer
EOF
}

# -----------------------------------------------------------------------------
case "${1:-}" in
  "")         pasada ;;
  instalar)   instalar ;;
  guardar)    shift; guardar_uno "$@" ;;
  restaurar)  shift; restaurar "$@" ;;
  *)          sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac
