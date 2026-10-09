package com.voces.backend.version;

import com.voces.backend.config.RelayProperties;
import com.voces.backend.modelo.Ajuste;
import com.voces.backend.modelo.Repositorios;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Qué versiones de la app hay instaladas y cuáles se dieron de baja.
 *
 * La versión de cada cuenta la anota RegistroDeAcceso en cada inicio de
 * sesión, que es cada vez que se abre la app. Las versiones dadas de baja se
 * guardan en la tabla `ajustes`, en una sola fila: "android:1.0.0 ios:0.1.0".
 *
 * Dar de baja una versión es dejar de atenderla: FiltroVersionMinima responde
 * 426 a sus peticiones y la app enseña el aviso de que hay que actualizar.
 */
@Service
public class AppsService {

    /** La fila de `ajustes` con las versiones dadas de baja. */
    static final String CLAVE = "apps_dadas_de_baja";

    private static final Duration ACTIVA = Duration.ofDays(30);

    // La lista se consulta en cada petición de la app. Se relee de la base
    // como mucho cada medio minuto, y al momento cuando cambia desde aquí.
    private static final Duration VIGENCIA = Duration.ofSeconds(30);

    /**
     * Una versión de la app, con cuánta gente la tiene.
     *
     * @param plataforma android | ios
     * @param version    sus números, o null si la app no dijo cuál es
     * @param usuarios   cuentas cuya última entrada fue con esta versión
     * @param activos    de esas, las que abrieron la app en los últimos 30 días
     * @param ultimaVez  la última vez que alguien entró con ella
     * @param baja       si está dada de baja desde el panel
     * @param bajoMinima si queda fuera por la versión mínima del servidor
     */
    public record VersionInstalada(String plataforma, String version, long usuarios, long activos,
                                   Instant ultimaVez, boolean baja, boolean bajoMinima) {}

    /**
     * @param minimaAndroid la versión mínima de Android del .env, o null
     * @param minimaIos     la de iOS, o null
     * @param versiones     todas las que se han visto o se dieron de baja
     */
    public record Apps(String minimaAndroid, String minimaIos, List<VersionInstalada> versiones) {}

    private static final Logger log = LoggerFactory.getLogger(AppsService.class);

    private final Repositorios.Ajustes ajustes;
    private final Repositorios.Usuarios usuarios;
    private final RelayProperties.Apps config;

    private volatile Set<String> bajas = Set.of();
    private volatile Instant leidasEn = Instant.EPOCH;

    public AppsService(Repositorios.Ajustes ajustes, Repositorios.Usuarios usuarios,
                       RelayProperties config) {
        this.ajustes = ajustes;
        this.usuarios = usuarios;
        this.config = config.apps();
    }

    /** Las versiones dadas de baja, como "android:1.0.0". */
    public Set<String> bajas() {
        if (Duration.between(leidasEn, Instant.now()).compareTo(VIGENCIA) > 0) {
            try {
                bajas = Set.copyOf(leer());
            } catch (RuntimeException e) {
                // Si la base no contesta se queda con lo último que leyó:
                // esta consulta nunca debe tumbar una petición de la app.
                log.warn("No se pudieron leer las versiones dadas de baja: {}", e.toString());
            }
            leidasEn = Instant.now();
        }
        return bajas;
    }

    private Set<String> leer() {
        return ajustes.findById(CLAVE).map(a -> partir(a.getValor())).orElse(Set.of());
    }

    static Set<String> partir(String valor) {
        Set<String> lista = new TreeSet<>();
        if (valor == null) return lista;
        Arrays.stream(valor.trim().split("\\s+"))
                .filter(v -> v.contains(":"))
                .forEach(lista::add);
        return lista;
    }

    /** Da de baja una versión, o la vuelve a atender. */
    @Transactional
    public void poner(String plataforma, String version, boolean baja) {
        Set<String> lista = new TreeSet<>(leer());
        String entrada = VersionDeApp.baja(plataforma, version);
        if (baja) lista.add(entrada); else lista.remove(entrada);

        Ajuste ajuste = ajustes.findById(CLAVE).orElseGet(() -> new Ajuste(CLAVE));
        ajuste.setValor(String.join(" ", lista));
        ajuste.setActualizadoEn(Instant.now());
        ajustes.save(ajuste);

        bajas = Set.copyOf(lista);
        leidasEn = Instant.now();
    }

    /** Lo que enseña el panel: cada versión con su gente y si se atiende. */
    @Transactional(readOnly = true)
    public Apps resumen() {
        Set<String> dadasDeBaja = leer();

        // "android:1.0.2" -> [usuarios, activos], y la última vez aparte.
        Map<String, long[]> cuentas = new LinkedHashMap<>();
        Map<String, Instant> ultimas = new LinkedHashMap<>();

        for (Object[] fila : usuarios.porVersionDeApp()) {
            String clave = VersionDeApp.baja((String) fila[0], (String) fila[1]);
            cuentas.computeIfAbsent(clave, k -> new long[2])[0] += ((Number) fila[2]).longValue();
            Instant vista = (Instant) fila[3];
            if (vista != null) ultimas.merge(clave, vista, (a, b) -> a.isAfter(b) ? a : b);
        }
        for (Object[] fila : usuarios.activosPorVersionDeApp(Instant.now().minus(ACTIVA))) {
            String clave = VersionDeApp.baja((String) fila[0], (String) fila[1]);
            cuentas.computeIfAbsent(clave, k -> new long[2])[1] += ((Number) fila[2]).longValue();
        }
        // Una versión dada de baja sigue en la lista aunque ya nadie la
        // tenga: si no, no habría forma de volver a atenderla.
        dadasDeBaja.forEach(clave -> cuentas.computeIfAbsent(clave, k -> new long[2]));

        List<VersionInstalada> versiones = new ArrayList<>();
        cuentas.forEach((clave, n) -> {
            String plataforma = clave.substring(0, clave.indexOf(':'));
            String numeros = clave.substring(clave.indexOf(':') + 1);
            String version = VersionDeApp.SIN_VERSION.equals(numeros) ? null : numeros;
            String minima = VersionDeApp.IOS.equals(plataforma) ? config.minimaIos() : config.minimaAndroid();
            versiones.add(new VersionInstalada(plataforma, version, n[0], n[1], ultimas.get(clave),
                    VersionDeApp.dadaDeBaja(dadasDeBaja, plataforma, version),
                    !minima.isBlank() && VersionDeApp.comparar(version, minima) < 0));
        });
        // Android antes que iOS, y dentro de cada una la más nueva arriba.
        versiones.sort(Comparator.comparing(VersionInstalada::plataforma)
                .thenComparing((a, b) -> VersionDeApp.comparar(b.version(), a.version())));

        return new Apps(oNada(config.minimaAndroid()), oNada(config.minimaIos()), versiones);
    }

    private static String oNada(String valor) {
        return valor == null || valor.isBlank() ? null : valor.trim();
    }
}
