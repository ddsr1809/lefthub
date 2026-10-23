#!/usr/bin/env bash
# Endurece el VPS: firewall, bloqueo de ataques por fuerza bruta, parches de
# seguridad automaticos, SSH y cabeceras de seguridad en Apache.
#
# Se ejecuta como root en el VPS. Se puede repetir sin riesgo: lo que escribe
# lo reescribe igual, y no toca nada que no sea suyo.
#
#   bash /opt/vocesleft/scripts/vps/endurecer-vps.sh
#
# Que deja:
#   1. Parches de seguridad de Ubuntu/Debian instalados solos cada dia
#      (unattended-upgrades).
#   2. Firewall (ufw): solo entran SSH, HTTP y HTTPS. Postgres y el servidor
#      ya escuchan solo dentro del VPS; esto cierra cualquier otro puerto que
#      algo abra por descuido.
#      ANTES de encenderlo revisa que no haya otro servicio publico escuchando
#      (otro sistema en este VPS). Si lo hay, se detiene y lo dice: agrega sus
#      puertos con PUERTOS_EXTRA="3000 5432" o repite con FORZAR_FIREWALL=si.
#   3. fail2ban: tras 5 intentos fallidos de SSH en 10 minutos, esa IP queda
#      fuera una hora (y una semana si reincide). Tambien frena a quien
#      insiste en /api/auth despues de que el servidor le contesto 429.
#   4. SSH: menos intentos por conexion y sin reenvio X11. Con
#      SSH_SOLO_LLAVES=si ademas apaga las contrasenas (solo llaves), pero
#      unicamente si root ya tiene una llave autorizada. ANTES de cerrar la
#      sesion actual, abre otra con tu llave y comprueba que entras.
#   5. Apache: no dice su version, HSTS (el navegador ya no intenta HTTP),
#      nosniff, Referrer-Policy y, en /admin, una Content-Security-Policy que
#      solo deja cargar codigo del propio panel y de Google (para entrar).
#      Con CSP_PANEL=reporte la CSP solo avisa en la consola del navegador.
#
# Variables:
#   PUERTOS_EXTRA      puertos TCP publicos de otros sistemas, separados por espacio
#   FORZAR_FIREWALL=si encender el firewall aunque haya otros puertos publicos
#   SSH_SOLO_LLAVES=si apagar las contrasenas de SSH (ver arriba)
#   CSP_PANEL=reporte  la CSP del panel solo informa, no bloquea

set -Eeuo pipefail

PUERTOS_EXTRA="${PUERTOS_EXTRA:-}"
FORZAR_FIREWALL="${FORZAR_FIREWALL:-no}"
SSH_SOLO_LLAVES="${SSH_SOLO_LLAVES:-no}"
CSP_PANEL="${CSP_PANEL:-bloquear}"

pendientes=()
paso()  { printf '\n==> %s\n' "$*"; }
aviso() { printf '    AVISO: %s\n' "$*" >&2; }
falta() { pendientes+=("$*"); }

[[ "$(id -u)" -eq 0 ]] || { echo "Ejecuta este script como root." >&2; exit 1; }
command -v apt-get >/dev/null || { echo "Este script espera Ubuntu o Debian (apt)." >&2; exit 1; }
for p in $PUERTOS_EXTRA; do
  [[ "$p" =~ ^[0-9]+$ ]] || { echo "PUERTOS_EXTRA solo lleva numeros: '$p'" >&2; exit 1; }
done

export DEBIAN_FRONTEND=noninteractive

# -----------------------------------------------------------------------------
# 1. Parches de seguridad automaticos
# -----------------------------------------------------------------------------
paso "Parches de seguridad automaticos"
apt-get update -qq
apt-get install -y -qq unattended-upgrades ufw fail2ban >/dev/null
cat > /etc/apt/apt.conf.d/20auto-upgrades <<'APT'
// Escrito por scripts/vps/endurecer-vps.sh
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
APT::Periodic::AutocleanInterval "7";
APT
systemctl enable --now unattended-upgrades >/dev/null 2>&1 || true
echo "    Se instalan solos cada dia. Registro: /var/log/unattended-upgrades/"

# -----------------------------------------------------------------------------
# 2. Firewall
# -----------------------------------------------------------------------------
paso "Firewall (ufw)"

# Los puertos de SSH que usa sshd ahora mismo: cerrarlos dejaria a todos fuera.
puertos_ssh="$(sshd -T 2>/dev/null | awk '$1 == "port" {print $2}' | sort -u | tr '\n' ' ')"
[[ -n "${puertos_ssh// /}" ]] || puertos_ssh="22"
permitidos=" $puertos_ssh 80 443 $PUERTOS_EXTRA "

# Que mas escucha hacia fuera (no en 127.0.0.1 ni ::1).
publicos="$(ss -Htln | awk '{print $4}' \
  | grep -vE '^(127\.|\[?::1\]?:|\[::ffff:127\.)' \
  | sed -E 's/.*:([0-9]+)$/\1/' | sort -un | tr '\n' ' ')"
ajenos=()
for p in $publicos; do
  [[ "$permitidos" == *" $p "* ]] || ajenos+=("$p")
done

if (( ${#ajenos[@]} )) && [[ "$FORZAR_FIREWALL" != "si" ]]; then
  aviso "Hay servicios escuchando hacia fuera en: ${ajenos[*]}. El firewall los cortaria."
  falta "Firewall sin encender. Si esos puertos deben seguir abiertos: PUERTOS_EXTRA=\"${ajenos[*]}\" bash $0 ; si no: FORZAR_FIREWALL=si bash $0"
else
  # Sin reset: las reglas que ya hubiera se conservan. Y SSH con allow, no
  # limit: el despliegue abre varias conexiones seguidas, y de quien prueba
  # contrasenas ya se encarga fail2ban.
  ufw default deny incoming >/dev/null
  ufw default allow outgoing >/dev/null
  for p in $puertos_ssh; do ufw allow "$p/tcp" comment 'SSH' >/dev/null; done
  ufw allow 80/tcp comment 'HTTP' >/dev/null
  ufw allow 443/tcp comment 'HTTPS' >/dev/null
  for p in $PUERTOS_EXTRA; do ufw allow "$p/tcp" comment 'PUERTOS_EXTRA' >/dev/null; done
  ufw --force enable >/dev/null
  echo "    Entran solo: SSH ($puertos_ssh), 80, 443${PUERTOS_EXTRA:+, $PUERTOS_EXTRA}."
  echo "    Nota: Docker publica sus puertos por su cuenta, sin pasar por ufw. Los de"
  echo "    VocesLeft van solo a 127.0.0.1; no publiques otros en 0.0.0.0."
fi

# -----------------------------------------------------------------------------
# 3. fail2ban
# -----------------------------------------------------------------------------
paso "fail2ban"

# Quien sigue llamando a /api/auth despues de que el servidor le dijo 429.
cat > /etc/fail2ban/filter.d/vocesleft-auth.conf <<'FILTRO'
# Escrito por scripts/vps/endurecer-vps.sh
[Definition]
failregex = ^<HOST> \S+ \S+ \[[^\]]+\] "POST /api/auth/\S* HTTP/[0-9.]+" 429
ignoreregex =
FILTRO

# En Ubuntu reciente no hay /var/log/auth.log: SSH escribe en el journal.
if [[ -f /var/log/auth.log ]]; then backend_ssh=auto; else backend_ssh=systemd; fi
# Sin ningun log de acceso de Apache todavia, esa jaula no puede arrancar (y
# tumbaria a fail2ban entero): se enciende cuando existan.
if compgen -G "/var/log/apache2/*-access.log" >/dev/null; then jaula_auth=true; else jaula_auth=false; fi

cat > /etc/fail2ban/jail.d/vocesleft.conf <<JAULA
# Escrito por scripts/vps/endurecer-vps.sh
[DEFAULT]
banaction = ufw

[sshd]
enabled  = true
backend  = $backend_ssh
maxretry = 5
findtime = 10m
bantime  = 1h

# Quien reincide vuelve a caer: una semana fuera.
[recidive]
enabled  = true
findtime = 1d
bantime  = 1w

# Una inundacion de inicios de sesion que ya frena el servidor (429).
[vocesleft-auth]
enabled  = $jaula_auth
port     = http,https
filter   = vocesleft-auth
logpath  = /var/log/apache2/*-access.log
maxretry = 30
findtime = 10m
bantime  = 15m
JAULA

# Sin ufw encendido, que fail2ban bloquee con iptables directamente.
if ! ufw status 2>/dev/null | grep -q "Status: active"; then
  sed -i 's/^banaction = ufw$/banaction = iptables-multiport/' /etc/fail2ban/jail.d/vocesleft.conf
fi
systemctl enable fail2ban >/dev/null 2>&1 || true
systemctl restart fail2ban
sleep 2
fail2ban-client status 2>/dev/null | sed 's/^/    /' || aviso "fail2ban no responde: journalctl -u fail2ban -n 30"

# -----------------------------------------------------------------------------
# 4. SSH
# -----------------------------------------------------------------------------
paso "SSH"
install -d -m 755 /etc/ssh/sshd_config.d
if ! grep -qE '^\s*Include\s+/etc/ssh/sshd_config\.d/' /etc/ssh/sshd_config; then
  aviso "/etc/ssh/sshd_config no incluye sshd_config.d: no se cambia SSH."
  falta "SSH sin endurecer: agrega 'Include /etc/ssh/sshd_config.d/*.conf' al principio de /etc/ssh/sshd_config y repite"
else
  conf=/etc/ssh/sshd_config.d/10-vocesleft.conf
  {
    echo "# Escrito por scripts/vps/endurecer-vps.sh"
    echo "MaxAuthTries 4"
    echo "LoginGraceTime 30"
    echo "PermitEmptyPasswords no"
    echo "X11Forwarding no"
  } > "$conf.nuevo"

  if [[ "$SSH_SOLO_LLAVES" == "si" ]]; then
    if [[ -s /root/.ssh/authorized_keys ]] && grep -qE '^(ssh-|ecdsa-|sk-)' /root/.ssh/authorized_keys; then
      {
        echo "PasswordAuthentication no"
        echo "KbdInteractiveAuthentication no"
        echo "PermitRootLogin prohibit-password"
      } >> "$conf.nuevo"
      echo "    Contrasenas apagadas: solo se entra con llave."
      aviso "NO cierres esta sesion hasta comprobar en otra terminal que entras con tu llave."
    else
      aviso "root no tiene ninguna llave en /root/.ssh/authorized_keys: las contrasenas se quedan."
      falta "Para SSH solo con llaves: copia tu llave publica (ssh-copy-id root@<vps>) y repite con SSH_SOLO_LLAVES=si"
    fi
  else
    falta "Recomendado: SSH solo con llaves. Con tu llave ya autorizada, repite con SSH_SOLO_LLAVES=si"
  fi

  mv -f "$conf.nuevo" "$conf"
  chmod 644 "$conf"
  if sshd -t; then
    systemctl reload ssh 2>/dev/null || systemctl reload sshd
    echo "    $conf aplicado."
  else
    rm -f "$conf"
    aviso "sshd rechazo la configuracion; se quito $conf y SSH sigue como estaba."
  fi
fi

# -----------------------------------------------------------------------------
# 5. Apache
# -----------------------------------------------------------------------------
paso "Cabeceras de seguridad en Apache"
if ! command -v apache2ctl >/dev/null; then
  falta "Apache no esta instalado: ejecuta primero preparar-vps.sh y repite"
else
  a2enmod -q headers >/dev/null

  if [[ "$CSP_PANEL" == "reporte" ]]; then cabecera_csp="Content-Security-Policy-Report-Only"; else cabecera_csp="Content-Security-Policy"; fi

  # El panel solo carga su propio codigo, y de Google lo necesario para el
  # boton de entrar. Las imagenes (fotos, miniaturas de YouTube) vienen de
  # cualquier https. Si alguien lograra colar un <script> en una ficha, el
  # navegador no lo ejecutaria.
  csp="default-src 'self'; script-src 'self' https://accounts.google.com/gsi/client; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com https://accounts.google.com/gsi/style; font-src 'self' https://fonts.gstatic.com; img-src 'self' data: blob: https:; connect-src 'self' https://accounts.google.com/gsi/; frame-src https://accounts.google.com/gsi/; frame-ancestors 'none'; base-uri 'self'; form-action 'self'; object-src 'none'"

  cat > /etc/apache2/conf-available/vocesleft-seguridad.conf <<CONF
# Escrito por scripts/vps/endurecer-vps.sh. Se reescribe cada vez que corre.

# Que no diga que version de Apache ni de Ubuntu hay.
ServerTokens Prod
ServerSignature Off
TraceEnable Off

<IfModule mod_headers.c>
    # Solo por HTTPS: el navegador ya no intenta HTTP durante un ano.
    Header always set Strict-Transport-Security "max-age=31536000" "expr=%{HTTPS} == 'on'"
    Header always setifempty X-Content-Type-Options "nosniff"
    Header always setifempty X-Frame-Options "SAMEORIGIN"
    Header always setifempty Referrer-Policy "strict-origin-when-cross-origin"
    Header always setifempty Permissions-Policy "camera=(), microphone=(), geolocation=(), payment=()"
</IfModule>

<LocationMatch "^/admin/">
    Header always set $cabecera_csp "$csp"
    Header always set X-Frame-Options "DENY"
</LocationMatch>
CONF
  a2enconf -q vocesleft-seguridad >/dev/null
  # La configuracion por defecto de Ubuntu repite ServerTokens: la nuestra
  # tiene que ganar, asi que la suya se apaga si existe.
  if [[ -f /etc/apache2/conf-enabled/security.conf ]] && grep -qE '^\s*ServerTokens\s+OS' /etc/apache2/conf-enabled/security.conf; then
    sed -i -E 's/^\s*ServerTokens\s+OS/ServerTokens Prod/; s/^\s*ServerSignature\s+On/ServerSignature Off/' /etc/apache2/conf-enabled/security.conf
  fi
  if apache2ctl configtest 2>/dev/null; then
    systemctl reload apache2
    echo "    Aplicado (/etc/apache2/conf-available/vocesleft-seguridad.conf). CSP del panel: $cabecera_csp."
  else
    a2disconf -q vocesleft-seguridad >/dev/null
    apache2ctl configtest || true
    aviso "Apache rechazo la configuracion; se desactivo vocesleft-seguridad y queda como estaba."
  fi
fi

# -----------------------------------------------------------------------------
# 6. Permisos de los secretos
# -----------------------------------------------------------------------------
paso "Permisos de los secretos"
for archivo in /opt/vocesleft/relay-server/.env.prod /opt/vocesleft/relay-server/.env.test; do
  [[ -f "$archivo" ]] && chmod 600 "$archivo" && echo "    $archivo: 600"
done
[[ -d /root/.config/vocesleft ]] && chmod 700 /root/.config/vocesleft && echo "    /root/.config/vocesleft: 700"

# -----------------------------------------------------------------------------
paso "Listo"
if (( ${#pendientes[@]} )); then
  printf '\n    Pendiente:\n'
  printf '      - %s\n' "${pendientes[@]}"
fi
