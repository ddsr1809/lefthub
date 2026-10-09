package com.voces.backend.acceso;

import com.voces.backend.modelo.Usuario;
import com.voces.backend.version.VersionDeApp;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Anota desde dónde se conectó una cuenta la última vez.
 *
 * Se llama en los mismos sitios donde ya se actualizaba `visto_en`: al crear
 * la sesión anónima, al renovarla en cada arranque de la app y al entrar con
 * Google o Apple. No añade ninguna petición ni le pide nada nuevo a las apps;
 * usa lo que cualquier conexión HTTP ya trae consigo.
 *
 * Es un dato personal. Tiene que estar declarado en la política de privacidad
 * y en la ficha de seguridad de datos de Play y de App Store.
 */
@Service
public class RegistroDeAcceso {

    private static final Logger log = LoggerFactory.getLogger(RegistroDeAcceso.class);
    private static final int MAX_AGENTE = 300;

    private final GeoIp geo;

    public RegistroDeAcceso(GeoIp geo) {
        this.geo = geo;
    }

    /** Modifica la entidad; quien llama está dentro de una transacción y la guarda. */
    public void anotar(Usuario usuario, HttpServletRequest peticion) {
        try {
            String ip = ipDelCliente(peticion);
            String agente = recortar(peticion.getHeader("User-Agent"), MAX_AGENTE);
            boolean cambioDeIp = !Objects.equals(ip, usuario.getIp());

            GeoIp.Lugar lugar = geo.buscar(ip);
            if (lugar != null) {
                usuario.setPais(lugar.pais());
                usuario.setAsn(lugar.asn());
                usuario.setRed(lugar.red());
            } else if (cambioDeIp) {
                // IP nueva y sin respuesta: lo que había describía a la IP
                // anterior. Con la misma IP se conserva, porque "sin
                // respuesta" suele ser que las tablas aún se están cargando.
                usuario.setPais(null);
                usuario.setAsn(null);
                usuario.setRed(null);
            }

            usuario.setIp(ip);
            usuario.setAgente(agente);

            // Con qué versión de la app entra. El panel entra por las mismas
            // rutas y no es una app: no pisa lo que hubiera.
            String cabecera = peticion.getHeader(VersionDeApp.CABECERA_PLATAFORMA);
            if (!VersionDeApp.PANEL.equals(VersionDeApp.plataforma(cabecera))) {
                String version = VersionDeApp.limpia(peticion.getHeader(VersionDeApp.CABECERA_VERSION));
                usuario.setAppVersion(version);
                usuario.setAppPlataforma(version == null && cabecera == null
                        ? null : VersionDeApp.plataforma(cabecera));
            }

            String motivo = DetectorDeBots.evaluar(agente, usuario.getRed());
            usuario.setPosibleBot(motivo != null);
            usuario.setMotivoBot(motivo);

        } catch (RuntimeException e) {
            // Anotar de dónde viene alguien nunca debe impedirle entrar.
            log.warn("No se pudo anotar el acceso de {}: {}", usuario.getId(), e.toString());
        }
    }

    /**
     * La IP de quien llama, no la del proxy.
     *
     * El servidor escucha solo en 127.0.0.1 detrás de Apache, así que la
     * conexión que ve Tomcat viene siempre del propio VPS o de la red interna
     * de Docker. La IP real la añade Apache al final de X-Forwarded-For.
     *
     * Se toma el ÚLTIMO valor de la cabecera y no el primero: los anteriores
     * los puede escribir quien hace la petición, y el último lo pone nuestro
     * Apache con lo que él vio. Y solo se confía en la cabecera cuando la
     * conexión llega desde una red privada, es decir, desde el proxy.
     *
     * Si algún día se pone Cloudflare u otro proxy delante de Apache, este
     * último valor pasará a ser la IP de ese proxy y habrá que leer su
     * cabecera propia.
     */
    static String ipDelCliente(HttpServletRequest peticion) {
        String directa = peticion.getRemoteAddr();
        String reenviada = peticion.getHeader("X-Forwarded-For");

        if (reenviada != null && esRedPrivada(directa)) {
            String[] saltos = reenviada.split(",");
            for (int i = saltos.length - 1; i >= 0; i--) {
                String candidata = saltos[i].trim();
                if (GeoIp.Direccion.de(candidata) != null) return candidata;
            }
        }
        return directa;
    }

    static boolean esRedPrivada(String ip) {
        if (ip == null) return false;
        String t = ip.toLowerCase();
        if (t.startsWith("::ffff:")) t = t.substring(7);

        if (t.equals("::1") || t.equals("0:0:0:0:0:0:0:1")) return true;
        if (t.startsWith("fc") || t.startsWith("fd") || t.startsWith("fe80:")) return t.contains(":");
        if (t.startsWith("10.") || t.startsWith("127.") || t.startsWith("192.168.")) return true;
        if (t.startsWith("172.")) {
            String[] partes = t.split("\\.");
            try {
                int segundo = Integer.parseInt(partes[1]);
                return segundo >= 16 && segundo <= 31;
            } catch (RuntimeException e) {
                return false;
            }
        }
        return false;
    }

    private static String recortar(String texto, int maximo) {
        if (texto == null) return null;
        String limpio = texto.trim();
        if (limpio.isEmpty()) return null;
        return limpio.length() <= maximo ? limpio : limpio.substring(0, maximo);
    }
}
