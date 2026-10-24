package com.voces.backend.directorio;

import com.voces.backend.seguridad.VerificadorAppCheck;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Cuántas cuentas hay en cada versión de la app y con qué llave firma cada
 * una: lo que enseña la tarjeta «Versiones y llaves» de Usuarios.
 *
 * Sale de una sola consulta agrupada (Repositorios.Usuarios.versionesYLlaves)
 * y aquí se reparte. Como la versión y la llave de cada cuenta se ponen al día
 * con cada petición de la app (seguridad/RegistroDeLlaves), quien instala la
 * 1.2.2 pasa de la fila de la 1.1.8 a la de la 1.2.2 en un minuto.
 */
public final class ResumenDeVersiones {

    private ResumenDeVersiones() {}

    /** Lo que significa "sin dato" en los repartos: sin versión, sin llave, sin App Check. */
    public static final String SIN = "sin";

    /**
     * Una versión de la app.
     *
     * @param plataforma  android | ios; null si la app no lo dijo
     * @param version     "1.2.2"; null si la app no lo dijo
     * @param porLlave    número de llave ("1", "2", o "sin") → cuentas
     * @param porAppCheck app de App Check ("Producción", o "sin") → cuentas
     */
    public record Version(String plataforma, String version, long cuentas, long activas,
                          Instant ultima, Map<String, Long> porLlave, Map<String, Long> porAppCheck) {}

    /** Un número de llave, o una app de App Check: cuántas cuentas y en qué versiones. */
    public record Grupo(String nombre, long cuentas, long activas, String versionMasVieja, String versionMasNueva) {}

    public record Resumen(List<Version> versiones, List<Grupo> llaves, List<Grupo> appsDeAppCheck) {}

    /**
     * @param filas plataforma, versión, número de llave, app de App Check,
     *              cuentas, activas en el periodo, última vez, orden de la versión
     */
    public static Resumen armar(List<Object[]> filas) {
        Map<String, Acumulado> versiones = new LinkedHashMap<>();
        Map<String, Acumulado> llaves = new TreeMap<>(ResumenDeVersiones::compararLlaves);
        Map<String, Acumulado> apps = new TreeMap<>();

        for (Object[] f : filas) {
            String plataforma = (String) f[0];
            String version = (String) f[1];
            String llave = f[2] == null ? SIN : String.valueOf(((Number) f[2]).intValue());
            String app = f[3] == null ? SIN : VerificadorAppCheck.nombreDeApp((String) f[3]);
            long cuentas = numero(f[4]);
            long activas = numero(f[5]);
            Instant ultima = (Instant) f[6];
            Integer orden = f[7] == null ? null : ((Number) f[7]).intValue();

            Acumulado v = versiones.computeIfAbsent(plataforma + "|" + version, k -> new Acumulado(plataforma, version, orden));
            v.sumar(cuentas, activas, ultima);
            v.porLlave.merge(llave, cuentas, Long::sum);
            v.porAppCheck.merge(app, cuentas, Long::sum);

            llaves.computeIfAbsent(llave, k -> new Acumulado(null, null, null)).sumar(cuentas, activas, ultima, version, orden);
            apps.computeIfAbsent(app, k -> new Acumulado(null, null, null)).sumar(cuentas, activas, ultima, version, orden);
        }

        List<Version> lista = new ArrayList<>();
        versiones.values().stream()
                // Por plataforma, y dentro de cada una de la más nueva a la más vieja; sin versión al final.
                .sorted(Comparator.comparing((Acumulado a) -> a.plataforma == null ? "~" : a.plataforma)
                        .thenComparing(a -> a.orden == null ? Integer.MIN_VALUE : a.orden, Comparator.reverseOrder()))
                .forEach(a -> lista.add(new Version(a.plataforma, a.version, a.cuentas, a.activas, a.ultima,
                        ordenado(a.porLlave, true), ordenado(a.porAppCheck, false))));

        return new Resumen(lista, grupos(llaves), grupos(apps));
    }

    /** Los números de llave en orden numérico, "sin" al final. */
    static int compararLlaves(String a, String b) {
        if (a.equals(b)) return 0;
        if (SIN.equals(a)) return 1;
        if (SIN.equals(b)) return -1;
        return Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
    }

    private static Map<String, Long> ordenado(Map<String, Long> mapa, boolean porNumero) {
        Map<String, Long> salida = porNumero ? new TreeMap<>(ResumenDeVersiones::compararLlaves) : new TreeMap<>(
                (a, b) -> SIN.equals(a) ? (SIN.equals(b) ? 0 : 1) : SIN.equals(b) ? -1 : a.compareTo(b));
        salida.putAll(mapa);
        return new LinkedHashMap<>(salida);
    }

    private static List<Grupo> grupos(Map<String, Acumulado> mapa) {
        List<Grupo> lista = new ArrayList<>();
        mapa.forEach((nombre, a) -> lista.add(new Grupo(nombre, a.cuentas, a.activas, a.masVieja, a.masNueva)));
        if (mapa instanceof TreeMap<?, ?> && !lista.isEmpty() && lista.stream().anyMatch(g -> SIN.equals(g.nombre()))) {
            // "sin" siempre al final, también en las apps (que van por nombre).
            lista.sort(Comparator.comparing((Grupo g) -> SIN.equals(g.nombre())));
        }
        return lista;
    }

    private static long numero(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static final class Acumulado {
        final String plataforma, version;
        final Integer orden;
        long cuentas, activas;
        Instant ultima;
        final Map<String, Long> porLlave = new LinkedHashMap<>();
        final Map<String, Long> porAppCheck = new LinkedHashMap<>();
        String masVieja, masNueva;
        Integer ordenVieja, ordenNueva;

        Acumulado(String plataforma, String version, Integer orden) {
            this.plataforma = plataforma;
            this.version = version;
            this.orden = orden;
        }

        void sumar(long n, long activas, Instant cuando) {
            cuentas += n;
            this.activas += activas;
            if (cuando != null && (ultima == null || cuando.isAfter(ultima))) ultima = cuando;
        }

        void sumar(long n, long activas, Instant cuando, String version, Integer orden) {
            sumar(n, activas, cuando);
            if (version == null || orden == null) return;
            if (ordenVieja == null || orden < ordenVieja) { ordenVieja = orden; masVieja = version; }
            if (ordenNueva == null || orden > ordenNueva) { ordenNueva = orden; masNueva = version; }
        }
    }
}
