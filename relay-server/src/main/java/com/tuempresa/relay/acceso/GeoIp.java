package com.tuempresa.relay.acceso;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

/**
 * De qué país y de qué compañía de internet es una IP.
 *
 * La respuesta sale de dos tablas que el propio servidor descarga y guarda en
 * memoria: las bases gratuitas "Lite" de DB-IP (país y ASN). Ninguna IP de
 * ningún usuario sale del VPS para averiguarlo; la alternativa habitual,
 * preguntarle a un servicio web por cada IP, le entrega a un tercero la lista
 * de quién usa la app y cuándo.
 *
 * La licencia de esas bases (CC BY 4.0) exige un enlace a db-ip.com donde se
 * muestren los datos. Está en la sección Usuarios del panel.
 *
 * Si la descarga falla, el servidor funciona igual: se guarda la IP y el país
 * queda vacío hasta el siguiente intento. Nada de esto puede tumbar un inicio
 * de sesión.
 */
@Service
public class GeoIp {

    private static final Logger log = LoggerFactory.getLogger(GeoIp.class);

    /** Lo que se sabe de una IP. Cualquier campo puede venir en null. */
    public record Lugar(String pais, Long asn, String red) {}

    private final boolean activo;
    private final String urlBase;

    /**
     * Se sustituyen enteras al recargar. volatile basta: quien consulta ve la
     * tabla vieja o la nueva, nunca una a medio llenar.
     */
    private volatile Tabla paises;
    private volatile Tabla redes;
    private volatile YearMonth edicion;

    public GeoIp(@Value("${relay.geo.activo:true}") boolean activo,
                 @Value("${relay.geo.url-base:https://download.db-ip.com/free}") String urlBase) {
        this.activo = activo;
        this.urlBase = urlBase.replaceAll("/+$", "");
    }

    // -------------------------------------------------------------------------
    // Consulta
    // -------------------------------------------------------------------------

    /** @return null si la IP no es válida o las tablas todavía no se cargaron. */
    public Lugar buscar(String ip) {
        Tabla p = paises, r = redes;
        if (ip == null || (p == null && r == null)) return null;

        Direccion d = Direccion.de(ip);
        if (d == null) return null;

        String pais = null;
        if (p != null) {
            int fila = p.buscar(d);
            // ZZ es el código que DB-IP usa para "sin asignar".
            if (fila >= 0 && !"ZZ".equals(p.textos[fila])) pais = p.textos[fila];
        }

        Long asn = null;
        String red = null;
        if (r != null) {
            int fila = r.buscar(d);
            if (fila >= 0) {
                asn = r.numeros[fila];
                red = r.textos[fila];
            }
        }

        return (pais == null && asn == null) ? null : new Lugar(pais, asn, red);
    }

    public boolean estaCargado() {
        return paises != null || redes != null;
    }

    // -------------------------------------------------------------------------
    // Carga
    // -------------------------------------------------------------------------

    /**
     * Medio minuto después de arrancar, para no competir con el arranque en
     * una máquina de un solo núcleo, y luego dos veces al día. Casi todas las
     * pasadas no hacen nada: DB-IP publica una edición al mes, y solo se
     * descarga cuando cambia el mes o cuando el intento anterior falló.
     */
    @Scheduled(initialDelay = 30, fixedDelay = 12 * 60 * 60, timeUnit = TimeUnit.SECONDS)
    public void actualizar() {
        if (!activo) return;

        YearMonth esteMes = YearMonth.now(ZoneOffset.UTC);
        if (esteMes.equals(edicion) && paises != null && redes != null) return;

        // El día 1 la edición nueva puede no estar publicada todavía: se
        // intenta el mes en curso y, si no existe, el anterior.
        for (YearMonth mes : List.of(esteMes, esteMes.minusMonths(1))) {
            try {
                cargar(mes);
                edicion = mes;
                log.info("Tablas de IP cargadas (edición {}): {} rangos de país, {} de red",
                        mes, paises.filas(), redes.filas());
                return;
            } catch (Exception e) {
                log.warn("No se pudo cargar la edición {} de las tablas de IP: {}", mes, e.toString());
            }
        }
        log.warn("Sin tablas de IP: se seguirá guardando la IP, pero sin país ni compañía, hasta el próximo intento.");
    }

    /** Visible para las pruebas, que la apuntan a un servidor local. */
    void cargar(YearMonth mes) throws IOException, InterruptedException {
        HttpClient cliente = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        Tabla p = descargar(cliente, urlBase + "/dbip-country-lite-" + mes + ".csv.gz", false);
        Tabla r = descargar(cliente, urlBase + "/dbip-asn-lite-" + mes + ".csv.gz", true);

        // Solo se publican si llegaron las dos: mezclar el país de un mes con
        // las redes de otro daría respuestas a medias difíciles de explicar.
        paises = p;
        redes = r;
    }

    private Tabla descargar(HttpClient cliente, String url, boolean esAsn)
            throws IOException, InterruptedException {

        HttpRequest peticion = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .header("User-Agent", "tubehub-relay")
                .GET()
                .build();

        HttpResponse<InputStream> respuesta =
                cliente.send(peticion, HttpResponse.BodyHandlers.ofInputStream());

        try (InputStream cuerpo = respuesta.body()) {
            if (respuesta.statusCode() != 200) {
                throw new IOException("HTTP " + respuesta.statusCode() + " en " + url);
            }
            try (BufferedReader lector = new BufferedReader(new InputStreamReader(
                    new GZIPInputStream(cuerpo, 1 << 16), StandardCharsets.UTF_8), 1 << 16)) {
                Tabla tabla = leer(lector, esAsn);
                if (tabla.filas() == 0) throw new IOException("Archivo vacío: " + url);
                return tabla;
            }
        }
    }

    /**
     * Lee el CSV de DB-IP sin guardarlo en disco.
     *
     *   país:  ip_inicio,ip_fin,XX
     *   ASN:   ip_inicio,ip_fin,numero,"Nombre, con comas si hace falta"
     *
     * Una fila que no se entiende se salta: una línea rara entre un millón no
     * debe dejar al servidor sin tablas.
     */
    static Tabla leer(BufferedReader lector, boolean esAsn) throws IOException {
        Constructor v4 = new Constructor(), v6 = new Constructor();
        // Un mismo nombre aparece en miles de rangos: se guarda una sola vez.
        Map<String, String> unicos = new HashMap<>();

        String linea;
        while ((linea = lector.readLine()) != null) {
            List<String> campos = partir(linea);
            if (campos.size() < (esAsn ? 4 : 3)) continue;

            Direccion inicio = Direccion.de(campos.get(0));
            Direccion fin = Direccion.de(campos.get(1));
            if (inicio == null || fin == null || inicio.v6 != fin.v6) continue;

            long numero = 0;
            String texto;
            if (esAsn) {
                try {
                    numero = Long.parseLong(campos.get(2).trim());
                } catch (NumberFormatException e) {
                    continue;
                }
                texto = campos.get(3).trim();
            } else {
                texto = campos.get(2).trim();
            }
            if (texto.isEmpty()) continue;
            texto = unicos.computeIfAbsent(texto, t -> t);

            (inicio.v6 ? v6 : v4).agregar(inicio.valor, fin.valor, texto, numero);
        }
        return new Tabla(v4, v6);
    }

    /** CSV según RFC 4180: comillas dobles opcionales, "" como comilla literal. */
    static List<String> partir(String linea) {
        List<String> campos = new ArrayList<>(4);
        StringBuilder actual = new StringBuilder();
        boolean entreComillas = false;

        for (int i = 0; i < linea.length(); i++) {
            char c = linea.charAt(i);
            if (entreComillas) {
                if (c == '"') {
                    if (i + 1 < linea.length() && linea.charAt(i + 1) == '"') {
                        actual.append('"');
                        i++;
                    } else {
                        entreComillas = false;
                    }
                } else {
                    actual.append(c);
                }
            } else if (c == '"') {
                entreComillas = true;
            } else if (c == ',') {
                campos.add(actual.toString());
                actual.setLength(0);
            } else {
                actual.append(c);
            }
        }
        campos.add(actual.toString());
        return campos;
    }

    // -------------------------------------------------------------------------
    // Direcciones
    // -------------------------------------------------------------------------

    /**
     * Una IP reducida a un número de 64 bits.
     *
     * IPv4 cabe entera. De IPv6 se guardan los 64 bits altos, el prefijo de
     * red: los otros 64 identifican al aparato dentro de esa red. Así cada
     * rango ocupa 16 bytes y las dos tablas juntas retienen unos 40 MB.
     *
     * El precio: en la edición de octubre de 2026, 225 de los 345.868 rangos
     * IPv6 de país eran más finos que un /64. Para una IP dentro de uno de
     * esos se devuelve el país de un rango vecino del mismo /64.
     *
     * Se interpreta a mano, sin InetAddress: con un texto que no sea una IP,
     * InetAddress.getByName haría una consulta DNS.
     */
    record Direccion(boolean v6, long valor) {

        static Direccion de(String texto) {
            if (texto == null) return null;
            String t = texto.trim();
            if (t.isEmpty() || t.length() > 45) return null;

            if (t.indexOf(':') < 0) {
                long v = v4(t);
                return v < 0 ? null : new Direccion(false, v);
            }

            // ::ffff:1.2.3.4 es una IPv4 con disfraz de IPv6.
            if (t.indexOf('.') > 0) {
                String cola = t.substring(t.lastIndexOf(':') + 1);
                long v = v4(cola);
                boolean mapeada = t.toLowerCase().startsWith("::ffff:");
                return (v < 0 || !mapeada) ? null : new Direccion(false, v);
            }
            return v6(t);
        }

        private static long v4(String t) {
            String[] partes = t.split("\\.", -1);
            if (partes.length != 4) return -1;
            long v = 0;
            for (String p : partes) {
                if (p.isEmpty() || p.length() > 3) return -1;
                int n = 0;
                for (int i = 0; i < p.length(); i++) {
                    char c = p.charAt(i);
                    if (c < '0' || c > '9') return -1;
                    n = n * 10 + (c - '0');
                }
                if (n > 255) return -1;
                v = (v << 8) | n;
            }
            return v;
        }

        private static Direccion v6(String t) {
            int zona = t.indexOf('%');          // fe80::1%eth0
            if (zona >= 0) t = t.substring(0, zona);

            int doble = t.indexOf("::");
            if (doble != t.lastIndexOf("::")) return null;

            String[] antes, despues;
            if (doble >= 0) {
                antes = grupos(t.substring(0, doble));
                despues = grupos(t.substring(doble + 2));
                if (antes == null || despues == null || antes.length + despues.length > 7) return null;
            } else {
                antes = grupos(t);
                despues = new String[0];
                if (antes == null || antes.length != 8) return null;
            }

            int[] g = new int[8];
            try {
                for (int i = 0; i < antes.length; i++) g[i] = hex(antes[i]);
                for (int i = 0; i < despues.length; i++) g[8 - despues.length + i] = hex(despues[i]);
            } catch (NumberFormatException e) {
                return null;
            }

            long alto = ((long) g[0] << 48) | ((long) g[1] << 32) | ((long) g[2] << 16) | g[3];
            return new Direccion(true, alto);
        }

        private static String[] grupos(String t) {
            if (t.isEmpty()) return new String[0];
            String[] g = t.split(":", -1);
            for (String x : g) if (x.isEmpty() || x.length() > 4) return null;
            return g;
        }

        private static int hex(String t) {
            return Integer.parseInt(t, 16);
        }
    }

    // -------------------------------------------------------------------------
    // Tablas
    // -------------------------------------------------------------------------

    /** Rangos de una familia de direcciones mientras se va leyendo el archivo. */
    static final class Constructor {
        long[] inicio = new long[1 << 16];
        long[] fin = new long[1 << 16];
        long[] numero = new long[1 << 16];
        String[] texto = new String[1 << 16];
        int n;

        void agregar(long desde, long hasta, String t, long num) {
            if (n == inicio.length) {
                int nuevo = n + (n >> 1);
                inicio = Arrays.copyOf(inicio, nuevo);
                fin = Arrays.copyOf(fin, nuevo);
                numero = Arrays.copyOf(numero, nuevo);
                texto = Arrays.copyOf(texto, nuevo);
            }
            inicio[n] = desde;
            fin[n] = hasta;
            numero[n] = num;
            texto[n] = t;
            n++;
        }
    }

    /**
     * Rangos ordenados por su inicio, con las IPv4 primero y las IPv6 después.
     * La consulta es una búsqueda binaria: unas veinte comparaciones.
     */
    static final class Tabla {
        private final long[] inicio, fin;
        final long[] numeros;
        final String[] textos;
        private final int cuantasV4;

        Tabla(Constructor v4, Constructor v6) {
            int total = v4.n + v6.n;
            inicio = new long[total];
            fin = new long[total];
            numeros = new long[total];
            textos = new String[total];
            cuantasV4 = v4.n;
            copiar(v4, 0);
            copiar(v6, v4.n);
        }

        /** DB-IP ya entrega el archivo ordenado; si alguna vez no, se ordena aquí. */
        private void copiar(Constructor c, int desde) {
            Integer[] orden = null;
            for (int i = 1; i < c.n; i++) {
                if (Long.compareUnsigned(c.inicio[i - 1], c.inicio[i]) > 0) {
                    orden = new Integer[c.n];
                    for (int j = 0; j < c.n; j++) orden[j] = j;
                    Arrays.sort(orden, (a, b) -> Long.compareUnsigned(c.inicio[a], c.inicio[b]));
                    break;
                }
            }
            for (int i = 0; i < c.n; i++) {
                int origen = orden == null ? i : orden[i];
                inicio[desde + i] = c.inicio[origen];
                fin[desde + i] = c.fin[origen];
                numeros[desde + i] = c.numero[origen];
                textos[desde + i] = c.texto[origen];
            }
        }

        int filas() {
            return inicio.length;
        }

        /** @return la fila cuyo rango contiene la dirección, o -1. */
        int buscar(Direccion d) {
            int bajo = d.v6 ? cuantasV4 : 0;
            int alto = (d.v6 ? inicio.length : cuantasV4) - 1;
            int candidata = -1;

            // El último rango que empieza en la dirección o antes. La
            // comparación es sin signo porque las IPv6 usan los 64 bits.
            while (bajo <= alto) {
                int medio = (bajo + alto) >>> 1;
                if (Long.compareUnsigned(inicio[medio], d.valor) <= 0) {
                    candidata = medio;
                    bajo = medio + 1;
                } else {
                    alto = medio - 1;
                }
            }
            if (candidata < 0) return -1;
            return Long.compareUnsigned(d.valor, fin[candidata]) <= 0 ? candidata : -1;
        }
    }
}
