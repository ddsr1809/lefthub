package com.voces.backend.version;

import com.voces.backend.modelo.Ajuste;
import com.voces.backend.modelo.Repositorios;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * El modo mantenimiento: un interruptor del panel que deja a las apps fuera
 * mientras se trabaja en el servidor.
 *
 * Encendido, FiltroVersionMinima responde 503 a todas las peticiones de las
 * apps con el mensaje que se haya escrito, y la app lo enseña a pantalla
 * completa. El panel sigue funcionando, y el servidor sigue recibiendo las
 * publicaciones de YouTube y mandando los avisos: lo único que se corta es
 * la app.
 *
 * Se guarda en dos filas de `ajustes`, por ambiente.
 */
@Service
public class MantenimientoService {

    static final String ACTIVO = "mantenimiento";
    static final String MENSAJE = "mantenimiento_mensaje";

    /** Lo que se le dice a la gente si no se escribió otra cosa. */
    public static final String MENSAJE_NORMAL =
            "Estamos haciendo mejoras en el servidor. Vuelve a intentarlo en un rato.";

    static final int LARGO_MENSAJE = 300;

    // Se consulta en cada petición de la app. Se relee de la base como mucho
    // cada medio minuto, y al momento cuando cambia desde aquí.
    private static final Duration VIGENCIA = Duration.ofSeconds(30);

    private static final Logger log = LoggerFactory.getLogger(MantenimientoService.class);

    /**
     * @param activo  si las apps están fuera
     * @param mensaje lo que se les dice; nunca viene vacío
     */
    public record Estado(boolean activo, String mensaje) {}

    private final Repositorios.Ajustes ajustes;

    private volatile Estado estado = new Estado(false, MENSAJE_NORMAL);
    private volatile Instant leidoEn = Instant.EPOCH;

    public MantenimientoService(Repositorios.Ajustes ajustes) {
        this.ajustes = ajustes;
    }

    public Estado estado() {
        if (Duration.between(leidoEn, Instant.now()).compareTo(VIGENCIA) > 0) {
            try {
                estado = leer();
            } catch (RuntimeException e) {
                // Si la base no contesta se queda con lo último que leyó:
                // esta consulta nunca debe tumbar una petición.
                log.warn("No se pudo leer el modo mantenimiento: {}", e.toString());
            }
            leidoEn = Instant.now();
        }
        return estado;
    }

    private Estado leer() {
        boolean activo = ajustes.findById(ACTIVO).map(a -> "true".equals(a.getValor())).orElse(false);
        String mensaje = ajustes.findById(MENSAJE).map(Ajuste::getValor).orElse("");
        return new Estado(activo, limpiar(mensaje));
    }

    /** Enciende o apaga el mantenimiento. Un mensaje vacío deja el normal. */
    @Transactional
    public Estado poner(boolean activo, String mensaje) {
        guardar(ACTIVO, String.valueOf(activo));
        // Con null se conserva el mensaje que hubiera.
        // Vacío se guarda vacío: así "el mensaje normal" es siempre el de ahora.
        if (mensaje != null) guardar(MENSAJE, mensaje.isBlank() ? "" : limpiar(mensaje));

        Estado nuevo = new Estado(activo,
                mensaje != null ? limpiar(mensaje) : leer().mensaje());
        estado = nuevo;
        leidoEn = Instant.now();
        log.info("Modo mantenimiento {}", activo ? "encendido" : "apagado");
        return nuevo;
    }

    private void guardar(String clave, String valor) {
        Ajuste ajuste = ajustes.findById(clave).orElseGet(() -> new Ajuste(clave));
        ajuste.setValor(valor);
        ajuste.setActualizadoEn(Instant.now());
        ajustes.save(ajuste);
    }

    /** El mensaje tal como se enseña: sin espacios de más y nunca vacío. */
    static String limpiar(String mensaje) {
        String texto = mensaje == null ? "" : mensaje.trim().replaceAll("\\s+", " ");
        if (texto.isEmpty()) return MENSAJE_NORMAL;
        return texto.length() > LARGO_MENSAJE ? texto.substring(0, LARGO_MENSAJE) : texto;
    }
}
