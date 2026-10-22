#!/usr/bin/env bash
# Publica docs/wiki/ en la pestaña Wiki de GitHub.
#
# Se ejecuta en TU LAPTOP, desde la raiz del repositorio:
#
#   bash scripts/publicar-wiki.sh            # publica lo que hay en master
#   bash scripts/publicar-wiki.sh --prueba   # solo enseña qué cambiaría
#   RAMA=testing bash scripts/publicar-wiki.sh
#
# La fuente de verdad es docs/wiki/ del repositorio, revisada en los pull
# requests como el resto del codigo. La pestaña Wiki es solo una copia para
# leerla comodo: cualquier cambio hecho a mano alla se pisa en la siguiente
# publicacion.
#
# Por defecto toma docs/wiki/ de origin/master, que es lo que corre en
# produccion, aunque tengas otra rama abierta. No toca tu copia de trabajo.
#
# Lo que hace con los archivos:
#   - README.md pasa a ser Home (la portada de la wiki).
#   - Cada pagina toma un nombre con mayuscula (servidor.md -> Servidor).
#   - Los enlaces entre paginas pierden el .md, como los espera la wiki.
#   - Los enlaces a archivos del repositorio (../../PIPELINE.md) pasan a ser
#     enlaces completos a GitHub, en la misma rama.
#   - Agrega _Sidebar (indice a un lado) y _Footer (de donde sale la copia).
#
# Antes de la primera vez, en GitHub:
#   1. Settings > General > Features: marca "Wikis".
#   2. Pestaña Wiki > "Create the first page" y guardala tal cual. GitHub no
#      crea el repositorio de la wiki hasta que existe una pagina; este script
#      la reemplaza.
#
# Usa el mismo acceso que tu `git push` normal (SSH o gh auth setup-git).
# Se puede repetir: si nada cambio, no hace commit.

set -Eeuo pipefail

RAMA="${RAMA:-master}"
PRUEBA=false
for arg in "$@"; do
  case "$arg" in
    --prueba) PRUEBA=true ;;
    -h|--help) sed -n '2,32p' "$0"; exit 0 ;;
    *) echo "No conozco la opcion: $arg (usa --prueba o --help)" >&2; exit 2 ;;
  esac
done

# La pagina de origen y el nombre que tendra en la wiki, en el orden del indice.
PAGINAS=(
  "README:Home:Inicio"
  "arquitectura:Arquitectura:Arquitectura"
  "servidor:Servidor:Servidor relay"
  "android:Android:App Android"
  "ios:iOS:App iOS"
  "panel:Panel:Panel de administración"
  "infraestructura:Infraestructura:Infraestructura y ambientes"
  "pipeline:Pipeline:Flujo de ramas y pipeline"
  "desarrollo-local:Desarrollo-local:Desarrollo local"
  "operacion:Operacion:Operación y mantenimiento"
  "historial:Historial:Historial"
  "glosario:Glosario:Glosario y preguntas"
)

falla() { echo "ERROR: $*" >&2; exit 1; }

raiz="$(git rev-parse --show-toplevel 2>/dev/null)" || falla "Corre el script dentro del repositorio lefthub."
cd "$raiz"

origen_url="$(git remote get-url origin)" || falla "El repositorio no tiene remoto origin."
# git@github.com:dueno/repo.git o https://github.com/dueno/repo(.git)
repo="${REPO:-$(sed -E 's#^(git@github\.com:|https://github\.com/)##; s#\.git$##' <<<"$origen_url")}"
[[ "$repo" =~ ^[^/]+/[^/]+$ ]] || falla "No pude sacar dueno/repo de $origen_url"
WIKI_URL="${WIKI_URL:-${origen_url%.git}.wiki.git}"

echo "==> Trayendo origin/$RAMA"
git fetch -q origin "$RAMA" || falla "No pude traer origin/$RAMA."
commit="$(git rev-parse --short "origin/$RAMA")"
git cat-file -e "origin/$RAMA:docs/wiki/README.md" 2>/dev/null \
  || falla "origin/$RAMA todavia no tiene docs/wiki/. Sube primero la wiki a esa rama."

trabajo="$(mktemp -d)"
trap 'rm -rf "$trabajo"' EXIT
fuente="$trabajo/fuente"
wiki="$trabajo/wiki"
mkdir -p "$fuente"
git archive "origin/$RAMA" docs/wiki | tar -x -C "$fuente"
fuente="$fuente/docs/wiki"

# Toda pagina de docs/wiki tiene que estar en PAGINAS: si alguien agrega una
# y no la registra aqui, se perderia en silencio.
for archivo in "$fuente"/*.md; do
  base="$(basename "$archivo" .md)"
  registrada=false
  for p in "${PAGINAS[@]}"; do [[ "${p%%:*}" == "$base" ]] && registrada=true; done
  $registrada || falla "docs/wiki/$base.md no esta en PAGINAS de este script. Agregala a la lista."
done

echo "==> Clonando la wiki ($WIKI_URL)"
git clone -q --depth 1 "$WIKI_URL" "$wiki" 2>/dev/null || falla "No pude clonar la wiki.
Revisa que esten activadas (Settings > Features > Wikis) y que exista al menos
una pagina (pestaña Wiki > Create the first page)."

# Se reemplaza todo: lo que la wiki tenga y no venga de docs/wiki se va.
find "$wiki" -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf {} +

# Un solo sed con todas las sustituciones de enlaces.
reglas=()
for p in "${PAGINAS[@]}"; do
  IFS=: read -r origen destino _ <<<"$p"
  reglas+=(-e "s#\]\(${origen}\.md(\#[^)]*)?\)#](${destino}\1)#g")
done
reglas+=(-e "s#\]\(\.\./\.\./([^)]*)\)#](https://github.com/${repo}/blob/${RAMA}/\1)#g")

for p in "${PAGINAS[@]}"; do
  IFS=: read -r origen destino _ <<<"$p"
  [[ -f "$fuente/$origen.md" ]] || continue
  sed -E "${reglas[@]}" "$fuente/$origen.md" \
    | sed -E '1{/^\[← Inicio\]\(Home\)$/d}' \
    | sed -E '1{/^$/d}' > "$wiki/$destino.md"
done

{
  echo "**Wiki de LeftHub**"
  echo
  for p in "${PAGINAS[@]}"; do
    IFS=: read -r origen destino titulo <<<"$p"
    [[ -f "$fuente/$origen.md" ]] && echo "- [$titulo]($destino)"
  done
} > "$wiki/_Sidebar.md"

cat > "$wiki/_Footer.md" <<EOF
Copia de [\`docs/wiki/\`](https://github.com/${repo}/tree/${RAMA}/docs/wiki) en \`${RAMA}\` @ \`${commit}\`. No la edites aquí: los cambios se hacen en el repositorio y se publican con \`scripts/publicar-wiki.sh\`.
EOF

cd "$wiki"
git add -A
if git diff --cached --quiet; then
  echo "==> La wiki ya esta al dia con $RAMA @ $commit. Nada que publicar."
  exit 0
fi

echo "==> Cambios:"
git diff --cached --stat | sed 's/^/    /'

if $PRUEBA; then
  echo "==> Modo prueba: no se publico nada."
  exit 0
fi

# El autor es el mismo que firma tus commits en lefthub, aunque el clon de la
# wiki no tenga configuracion propia.
autor_nombre="$(git -C "$raiz" config user.name)" || falla "Configura user.name en git."
autor_correo="$(git -C "$raiz" config user.email)" || falla "Configura user.email en git."
git -c user.name="$autor_nombre" -c user.email="$autor_correo" \
  commit -q -m "Wiki desde docs/wiki ($RAMA @ $commit)"
git push -q origin HEAD
echo "==> Publicada: https://github.com/${repo}/wiki"
