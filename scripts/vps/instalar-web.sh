#!/usr/bin/env bash
# Publica la pagina web (carpeta web/ del repositorio) en vocesdeizquierda.com.
#
# Se ejecuta como root en el VPS, despues de preparar-vps.sh y de que web/ haya
# llegado al clon con `git -C /opt/vocesleft pull`. Se puede repetir sin riesgo:
# no sobrescribe un vhost ni un certificado que ya exista.
#
#   bash /opt/vocesleft/scripts/vps/instalar-web.sh
#
# La pagina son archivos estaticos servidos por Apache desde el clon, igual que
# el panel /admin. Para actualizarla basta otro `git pull`; no pasa por Docker
# ni por el pipeline.

set -Eeuo pipefail

APP_DIR="${APP_DIR:-/opt/vocesleft}"
DOMINIO_WEB="${DOMINIO_WEB:-vocesdeizquierda.com}"
IP_ESPERADA="${IP_ESPERADA:-216.238.70.157}"
CORREO_CERTBOT="${CORREO_CERTBOT:-tcs.md.soto@gmail.com}"

DIR_WEB="$APP_DIR/web"
VHOST="/etc/apache2/sites-available/$DOMINIO_WEB.conf"

pendientes=()
paso()  { printf '\n==> %s\n' "$*"; }
falta() { pendientes+=("$*"); }

[[ "$(id -u)" -eq 0 ]] || { echo "Ejecuta este script como root." >&2; exit 1; }
command -v apache2ctl >/dev/null && command -v certbot >/dev/null \
  || { echo "Faltan Apache o Certbot. Ejecuta primero scripts/vps/preparar-vps.sh." >&2; exit 1; }
[[ -f "$DIR_WEB/index.html" ]] \
  || { echo "No existe $DIR_WEB/index.html. Trae la pagina con: git -C $APP_DIR pull" >&2; exit 1; }

# -----------------------------------------------------------------------------
# 1. Vhost
# -----------------------------------------------------------------------------
paso "Configurando Apache para $DOMINIO_WEB"
a2enmod -q headers ssl >/dev/null

if [[ -f "$VHOST" ]]; then
  echo "    $VHOST ya existe: no se toca."
else
  # Vhost del puerto 80. Certbot crea a partir de el la copia con TLS
  # (<dominio>-le-ssl.conf) y deja aqui la redireccion a HTTPS.
  cat > "$VHOST" <<CONF
<VirtualHost *:80>
    ServerName $DOMINIO_WEB
    ServerAlias www.$DOMINIO_WEB

    DocumentRoot $DIR_WEB

    <Directory $DIR_WEB>
        Options -Indexes
        AllowOverride None
        Require all granted
        DirectoryIndex index.html
    </Directory>

    # Evita que el navegador se quede con una version vieja de la pagina
    # despues de un git pull.
    Header set Cache-Control "no-cache"

    ErrorLog \${APACHE_LOG_DIR}/$DOMINIO_WEB-error.log
    CustomLog \${APACHE_LOG_DIR}/$DOMINIO_WEB-access.log combined
</VirtualHost>
CONF
  echo "    Creado $VHOST -> $DIR_WEB"
fi
a2ensite -q "$DOMINIO_WEB.conf" >/dev/null
apache2ctl configtest
systemctl reload apache2

# -----------------------------------------------------------------------------
# 2. HTTPS
# -----------------------------------------------------------------------------
paso "Certificado"
apunta_aqui() {
  local ips
  ips="$(getent ahostsv4 "$1" | awk '{print $1}' | sort -u | tr '\n' ' ')"
  [[ " $ips" == *" $IP_ESPERADA "* ]] && return 0
  falta "DNS: crear el registro A de $1 -> $IP_ESPERADA (ahora resuelve a: ${ips:-nada}) y repetir este script"
  return 1
}

# El certificado solo puede incluir los nombres que ya apuntan a este servidor.
nombres=() dominios=()
for nombre in "$DOMINIO_WEB" "www.$DOMINIO_WEB"; do
  if apunta_aqui "$nombre"; then nombres+=("$nombre"); dominios+=(-d "$nombre"); fi
done

if (( ${#nombres[@]} == 0 )); then
  echo "    Ningun nombre apunta todavia a $IP_ESPERADA: no se pide certificado."
elif [[ -z "$CORREO_CERTBOT" ]]; then
  falta "HTTPS: falta CORREO_CERTBOT; repetir con CORREO_CERTBOT=<correo> bash $0"
elif certbot --apache --cert-name "$DOMINIO_WEB" "${dominios[@]}" --non-interactive --agree-tos \
       -m "$CORREO_CERTBOT" --redirect --keep-until-expiring --expand; then
  echo "    HTTPS listo para: ${nombres[*]}"
else
  falta "Certbot fallo para $DOMINIO_WEB. Revisa /var/log/letsencrypt/letsencrypt.log"
fi

# -----------------------------------------------------------------------------
# Resumen
# -----------------------------------------------------------------------------
paso "Listo"
echo "    Pagina:  https://$DOMINIO_WEB  ->  $DIR_WEB"

if (( ${#pendientes[@]} )); then
  printf '\n    Pendiente:\n'
  printf '      - %s\n' "${pendientes[@]}"
else
  printf '\n    No queda nada pendiente.\n'
fi
