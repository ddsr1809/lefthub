#!/usr/bin/env bash
# Prepara un VPS (Ubuntu o Debian) para alojar VocesLeft: test y produccion.
#
# Se ejecuta UNA vez como root en el VPS, y se puede repetir sin riesgo: nunca
# sobrescribe un .env, un vhost ni un certificado que ya exista.
#
#   scp scripts/vps/*.sh root@216.238.70.157:/root/
#   ssh root@216.238.70.157
#   bash /root/preparar-vps.sh
#
# Que deja listo:
#   - Docker, Apache, Certbot y Git.
#   - El clon del repositorio en /opt/vocesleft (de ahi sale el panel /admin).
#   - relay-server/.env.test y .env.prod con secretos nuevos.
#   - Un vhost de Apache por dominio, con HTTPS si el DNS ya apunta aqui.
#
# Que NO hace: levantar los contenedores. Eso lo hace el pipeline de GitHub
# (scripts/deploy-vps.sh) en el primer push a development o master.

set -Eeuo pipefail

REPO_URL="${REPO_URL:-https://github.com/ddsr1809/lefthub.git}"
RAMA="${RAMA:-master}"
APP_DIR="${APP_DIR:-/opt/vocesleft}"
DOMINIO_PROD="${DOMINIO_PROD:-leftapp.vocesdeizquierda.com}"
DOMINIO_TEST="${DOMINIO_TEST:-testapp.vocesdeizquierda.com}"
PUERTO_PROD="${PUERTO_PROD:-8080}"
PUERTO_TEST="${PUERTO_TEST:-8081}"
IP_ESPERADA="${IP_ESPERADA:-216.238.70.157}"
DIR_SECRETOS="${DIR_SECRETOS:-/root/.config/vocesleft}"
CORREO_CERTBOT="${CORREO_CERTBOT:-tcs.md.soto@gmail.com}"

pendientes=()
paso()  { printf '\n==> %s\n' "$*"; }
aviso() { printf '    AVISO: %s\n' "$*" >&2; }
falta() { pendientes+=("$*"); }

[[ "$(id -u)" -eq 0 ]] || { echo "Ejecuta este script como root." >&2; exit 1; }
command -v apt-get >/dev/null || { echo "Este script espera Ubuntu o Debian (apt)." >&2; exit 1; }
[[ "$PUERTO_PROD" =~ ^[0-9]+$ && "$PUERTO_TEST" =~ ^[0-9]+$ && "$PUERTO_PROD" != "$PUERTO_TEST" ]] \
  || { echo "PUERTO_PROD y PUERTO_TEST deben ser numeros distintos." >&2; exit 1; }

# -----------------------------------------------------------------------------
# 1. Paquetes
# -----------------------------------------------------------------------------
paso "Instalando paquetes del sistema"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq apache2 certbot python3-certbot-apache git curl openssl ca-certificates iproute2 >/dev/null

if ! docker compose version >/dev/null 2>&1; then
  paso "Instalando Docker y el plugin de Compose"
  curl -fsSL https://get.docker.com | sh >/dev/null
fi
systemctl enable --now docker >/dev/null 2>&1 || true
docker compose version

# -----------------------------------------------------------------------------
# 2. Puertos: detenerse antes de pisar otro sistema que viva en este VPS
# -----------------------------------------------------------------------------
paso "Comprobando que los puertos $PUERTO_PROD y $PUERTO_TEST estan disponibles"
puerto_ocupado_por_otro() {
  local puerto="$1" proyecto="$2"
  local escuchando publicados
  escuchando="$(ss -Htln "sport = :$puerto")"
  [[ -n "$escuchando" ]] || return 1
  # Ocupado: solo es aceptable si lo publica nuestro propio contenedor.
  publicados="$(docker ps --filter "label=com.docker.compose.project=$proyecto" --format '{{.Ports}}')"
  [[ "$publicados" == *":$puerto->"* ]] && return 1
  return 0
}
for par in "$PUERTO_PROD:vocesleft-prod" "$PUERTO_TEST:vocesleft-test"; do
  if puerto_ocupado_por_otro "${par%%:*}" "${par##*:}"; then
    echo "El puerto ${par%%:*} ya lo usa otro proceso (¿el sistema anterior en este mismo VPS?)." >&2
    echo "Detenlo, o vuelve a ejecutar con otros puertos: PUERTO_PROD=8090 PUERTO_TEST=8091 bash $0" >&2
    exit 1
  fi
done

# -----------------------------------------------------------------------------
# 3. Repositorio
# -----------------------------------------------------------------------------
paso "Clonando el repositorio en $APP_DIR (rama $RAMA)"
if [[ -d "$APP_DIR/.git" ]]; then
  git -C "$APP_DIR" fetch --quiet origin
  git -C "$APP_DIR" checkout --quiet "$RAMA"
  git -C "$APP_DIR" pull --quiet --ff-only origin "$RAMA"
elif [[ -e "$APP_DIR" ]]; then
  echo "$APP_DIR existe y no es un clon de Git. Muevelo o borralo y repite." >&2
  exit 1
else
  git clone --quiet --branch "$RAMA" "$REPO_URL" "$APP_DIR"
fi
git -C "$APP_DIR" log -1 --format='    %h %ad %s' --date=short

# -----------------------------------------------------------------------------
# 4. Archivos .env (uno por ambiente, con secretos propios)
# -----------------------------------------------------------------------------
install -d -m 700 "$DIR_SECRETOS"

# Reemplaza la linea CLAVE=... o la agrega si no existe.
poner() {
  local archivo="$1" clave="$2" valor="$3"
  if grep -q "^${clave}=" "$archivo"; then
    sed -i "s|^${clave}=.*|${clave}=${valor}|" "$archivo"
  else
    printf '%s=%s\n' "$clave" "$valor" >> "$archivo"
  fi
}

crear_env() {
  local ambiente="$1" dominio="$2" puerto="$3"
  local archivo="$APP_DIR/relay-server/.env.$ambiente"
  local ejemplo="$APP_DIR/relay-server/.env.$ambiente.example"

  if [[ -f "$archivo" ]]; then
    echo "    $archivo ya existe: no se toca."
  else
    [[ -f "$ejemplo" ]] || { echo "Falta $ejemplo en el repositorio." >&2; exit 1; }
    ( umask 077; cp "$ejemplo" "$archivo" )
    # Por si el ejemplo no termina en salto de linea.
    [[ -z "$(tail -c1 "$archivo")" ]] || echo >> "$archivo"

    poner "$archivo" PUERTO_HOST "$puerto"
    poner "$archivo" DB_NOMBRE "relay_$ambiente"
    poner "$archivo" RELAY_URL_PUBLICA "https://$dominio"
    # El panel vive en el mismo dominio que la API.
    poner "$archivo" CORS_ORIGENES "https://$dominio"
    poner "$archivo" FCM_CREDENCIALES_HOST "$DIR_SECRETOS/fcm-$ambiente.json"

    # Cinco secretos distintos por ambiente. Nunca se comparten entre test y
    # prod: con el mismo JWT_SECRETO, un token de test abriria produccion.
    local clave
    for clave in DB_CLAVE JWT_SECRETO WEBSUB_SECRETO WEBSUB_TOKEN_CALLBACK TOKEN_INTERNO; do
      poner "$archivo" "$clave" "$(openssl rand -hex 32)"
    done
    chmod 600 "$archivo"
    echo "    Creado $archivo con secretos nuevos."
  fi

  # Lo que solo tu puedes rellenar.
  local vacias=() clave
  for clave in YOUTUBE_API_KEY GOOGLE_CLIENT_ID FCM_PROYECTO_ID; do
    grep -q "^${clave}=..*" "$archivo" || vacias+=("$clave")
  done
  if (( ${#vacias[@]} )); then
    falta "Rellenar en $archivo: ${vacias[*]}"
  fi
  local fcm
  fcm="$(grep '^FCM_CREDENCIALES_HOST=' "$archivo" | cut -d= -f2-)"
  # El contenedor corre como el usuario `relay` (uid 100, gid 101 en la imagen
  # Alpine). El JSON se monta tal cual, asi que ese grupo tiene que poder
  # leerlo: root:101 con modo 640. Con 600 el servidor no puede abrirlo.
  [[ -s "$fcm" ]] || falta "Copiar la cuenta de servicio de Firebase a $fcm (chown root:101, chmod 640)"
}

paso "Preparando los archivos .env"
crear_env prod "$DOMINIO_PROD" "$PUERTO_PROD"
crear_env test "$DOMINIO_TEST" "$PUERTO_TEST"

# -----------------------------------------------------------------------------
# 5. Apache: un vhost por dominio
# -----------------------------------------------------------------------------
paso "Configurando Apache"
a2enmod -q proxy proxy_http headers rewrite ssl >/dev/null

crear_vhost() {
  local dominio="$1" puerto="$2"
  local archivo="/etc/apache2/sites-available/$dominio.conf"

  if [[ -f "$archivo" ]]; then
    echo "    $archivo ya existe: no se toca."
  else
    # Vhost del puerto 80. Certbot crea a partir de el la copia con TLS
    # (<dominio>-le-ssl.conf) y deja aqui la redireccion a HTTPS.
    cat > "$archivo" <<VHOST
<VirtualHost *:80>
    ServerName $dominio

    ProxyRequests Off
    ProxyPreserveHost On
    RequestHeader set X-Forwarded-Proto expr=%{REQUEST_SCHEME}

    # El orden importa: las excepciones van antes del ProxyPass general.
    ProxyPass /.well-known/acme-challenge/ !
    ProxyPass /admin !

    # El panel se sirve como archivos estaticos desde el clon del repositorio.
    # Sus llamadas a /api/... van al mismo dominio, sin CORS.
    RedirectMatch 301 ^/admin\$ /admin/
    Alias /admin/ $APP_DIR/admin/

    <Directory $APP_DIR/admin>
        Options -Indexes
        Require all granted
        DirectoryIndex index.html
    </Directory>

    # Evita que el navegador se quede con una version vieja del panel despues
    # de un git pull, y que los buscadores lo indexen.
    <LocationMatch "^/admin/">
        Header set Cache-Control "no-cache"
        Header set X-Robots-Tag "noindex, nofollow"
    </LocationMatch>

    # Todo lo demas va al relay-server, que solo escucha en 127.0.0.1.
    # mod_proxy agrega X-Forwarded-For con la IP real del cliente.
    ProxyPass / http://127.0.0.1:$puerto/
    ProxyPassReverse / http://127.0.0.1:$puerto/

    ErrorLog \${APACHE_LOG_DIR}/$dominio-error.log
    CustomLog \${APACHE_LOG_DIR}/$dominio-access.log combined
</VirtualHost>
VHOST
    echo "    Creado $archivo -> 127.0.0.1:$puerto"
  fi
  a2ensite -q "$dominio.conf" >/dev/null
}

crear_vhost "$DOMINIO_PROD" "$PUERTO_PROD"
crear_vhost "$DOMINIO_TEST" "$PUERTO_TEST"
apache2ctl configtest
systemctl enable --now apache2 >/dev/null 2>&1 || true
systemctl reload apache2

# -----------------------------------------------------------------------------
# 6. HTTPS
# -----------------------------------------------------------------------------
paso "Certificados"
ips_locales="$(ip -4 -o addr show | awk '{print $4}' | cut -d/ -f1 | tr '\n' ' ')"
if [[ " $ips_locales" != *" $IP_ESPERADA "* ]]; then
  aviso "Este servidor no tiene la IP $IP_ESPERADA. Si es correcto, repite con IP_ESPERADA=<la ip publica>."
fi

certificar() {
  local dominio="$1" ips
  if [[ -f "/etc/apache2/sites-available/$dominio-le-ssl.conf" ]]; then
    echo "    $dominio ya tiene HTTPS."
    return
  fi
  ips="$(getent ahostsv4 "$dominio" | awk '{print $1}' | sort -u | tr '\n' ' ')"
  if [[ " $ips" != *" $IP_ESPERADA "* ]]; then
    falta "DNS: crear el registro A de $dominio -> $IP_ESPERADA (ahora resuelve a: ${ips:-nada}) y repetir este script"
    return
  fi
  if [[ -z "$CORREO_CERTBOT" ]]; then
    falta "HTTPS de $dominio: falta CORREO_CERTBOT; repetir con CORREO_CERTBOT=<correo> bash $0"
    return
  fi
  if certbot --apache -d "$dominio" --non-interactive --agree-tos \
       -m "$CORREO_CERTBOT" --redirect --keep-until-expiring; then
    echo "    HTTPS listo para $dominio."
  else
    falta "Certbot fallo para $dominio. Revisa /var/log/letsencrypt/letsencrypt.log"
  fi
}
certificar "$DOMINIO_PROD"
certificar "$DOMINIO_TEST"

# -----------------------------------------------------------------------------
# Resumen
# -----------------------------------------------------------------------------
paso "Listo"
cat <<RESUMEN
    Repositorio:   $APP_DIR  (valor del secreto VPS_APP_DIR en GitHub)
    Produccion:    https://$DOMINIO_PROD -> 127.0.0.1:$PUERTO_PROD  (.env.prod)
    Pruebas:       https://$DOMINIO_TEST -> 127.0.0.1:$PUERTO_TEST  (.env.test)
    Credenciales:  $DIR_SECRETOS

    Hasta el primer despliegue del pipeline, los dominios responden 503: el
    proxy ya esta, pero todavia no hay contenedor detras.
RESUMEN

if (( ${#pendientes[@]} )); then
  printf '\n    Pendiente antes del primer despliegue:\n'
  printf '      - %s\n' "${pendientes[@]}"
else
  printf '\n    No queda nada pendiente en el VPS.\n'
fi
