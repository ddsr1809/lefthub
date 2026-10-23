package com.voces.backend.seguridad;

import com.voces.backend.modelo.Ajuste;
import com.voces.backend.modelo.Repositorios;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * Qué se hace con una petición de la app que no trae la firma o el token de
 * App Check buenos. Una decisión para cada uno, desde el panel (Apps → Llave
 * de la app), sin desplegar nada.
 *
 * <ul>
 *   <li><b>apagado</b>: no se mira.</li>
 *   <li><b>avisar</b> (lo normal al empezar): se mira y se cuenta, pero pasa.
 *       Sirve para ver cuántas peticiones fallarían antes de exigirlo.</li>
 *   <li><b>exigir</b>: la que falla no pasa.</li>
 * </ul>
 *
 * Se guarda en dos filas de `ajustes`, por ambiente. Se relee de la base como
 * mucho cada medio minuto, como el modo mantenimiento, y al momento cuando
 * cambia desde aquí.
 */
@Service
public class ModosDeLaApp {

    private static final Logger log = LoggerFactory.getLogger(ModosDeLaApp.class);

    static final String FIRMA = "llave_app_firma";
    static final String APP_CHECK = "llave_app_appcheck";
    private static final Duration VIGENCIA = Duration.ofSeconds(30);

    public enum Modo {
        APAGADO, AVISAR, EXIGIR;

        public String nombre() { return name().toLowerCase(Locale.ROOT); }

        /** Lo guardado, o AVISAR si no hay nada o no se entiende. */
        static Modo de(String valor) {
            if (valor == null) return AVISAR;
            try {
                return valueOf(valor.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return AVISAR;
            }
        }

        /** El que manda el panel; null si no es ninguno. */
        public static Modo pedido(String valor) {
            if (valor == null) return null;
            for (Modo m : values()) if (m.nombre().equals(valor.trim().toLowerCase(Locale.ROOT))) return m;
            return null;
        }
    }

    public record Modos(Modo firma, Modo appCheck) {}

    private final Repositorios.Ajustes ajustes;
    private volatile Modos modos = new Modos(Modo.AVISAR, Modo.AVISAR);
    private volatile Instant leidoEn = Instant.EPOCH;

    public ModosDeLaApp(Repositorios.Ajustes ajustes) {
        this.ajustes = ajustes;
    }

    public Modos actuales() {
        if (Duration.between(leidoEn, Instant.now()).compareTo(VIGENCIA) > 0) {
            try {
                modos = leer();
            } catch (RuntimeException e) {
                // Si la base no contesta, lo último que se leyó.
                log.warn("No se pudieron leer los modos de la llave de la app: {}", e.toString());
            }
            leidoEn = Instant.now();
        }
        return modos;
    }

    /** Cambia uno o los dos. null deja el que estaba. */
    @Transactional
    public Modos poner(Modo firma, Modo appCheck) {
        Modos antes = leer();
        Modos nuevos = new Modos(firma != null ? firma : antes.firma(), appCheck != null ? appCheck : antes.appCheck());
        guardar(FIRMA, nuevos.firma().nombre());
        guardar(APP_CHECK, nuevos.appCheck().nombre());
        modos = nuevos;
        leidoEn = Instant.now();
        log.info("Llave de la app: firma {}, App Check {}", nuevos.firma().nombre(), nuevos.appCheck().nombre());
        return nuevos;
    }

    private Modos leer() {
        return new Modos(
                Modo.de(ajustes.findById(FIRMA).map(Ajuste::getValor).orElse(null)),
                Modo.de(ajustes.findById(APP_CHECK).map(Ajuste::getValor).orElse(null)));
    }

    private void guardar(String clave, String valor) {
        Ajuste ajuste = ajustes.findById(clave).orElseGet(() -> new Ajuste(clave));
        ajuste.setValor(valor);
        ajuste.setActualizadoEn(Instant.now());
        ajustes.save(ajuste);
    }
}
