package com.voces.backend.chat;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Las conversaciones del chat interno, solo en memoria.
 *
 * Una sala por cuenta: el equipo habla con esa persona, y si otro
 * administrador abre la misma conversación, la toma. Cada lado manda su llave
 * pública y recoge la del otro; con las dos, los teléfonos y el panel acuerdan
 * entre ellos la clave con la que cifran (ECDH P-256 → HKDF → AES-256-GCM).
 * Aquí nunca llega esa clave, así que lo que pasa por este servidor son bytes
 * que no puede leer.
 *
 * Y tampoco los guarda: nada va a la base ni a disco ni al registro. Un
 * mensaje espera en el buzón del otro lado solo hasta que lo recoge, que es
 * la siguiente consulta, segundos después. Al cerrar la conversación, o si
 * nadie pregunta en {@link #SIN_NOTICIAS}, la sala desaparece con lo que
 * tuviera dentro. Reiniciar el servidor las borra todas.
 *
 * Cada lado entra con una "conexión": un identificador al azar que se le da
 * al entrar y que pide en cada llamada. Así, si la misma persona abre el chat
 * en otro teléfono (o el administrador en otra pestaña), la conexión anterior
 * recibe "cerrada" y deja de recoger mensajes que ya no son suyos.
 */
@Component
public class SalasDeChat {

    private static final Logger log = LoggerFactory.getLogger(SalasDeChat.class);

    /** Sin preguntar en este tiempo, ese lado se fue: cerró la app o el panel. */
    static final Duration SIN_NOTICIAS = Duration.ofMinutes(2);

    /** Sin un solo mensaje en este tiempo, la conversación se cierra sola. */
    static final Duration INACTIVIDAD = Duration.ofMinutes(30);

    /** Cuánto espera una consulta a que llegue algo antes de volver vacía. */
    public static final Duration ESPERA = Duration.ofSeconds(25);

    /** Mensajes sin recoger por lado. Más es que el otro lado no los lee. */
    static final int MAX_PENDIENTES = 200;

    /** Un mensaje cifrado, en base64. Unos 2000 caracteres de texto caben de sobra. */
    static final int MAX_CIFRADO = 12_000;

    private static final int LARGO_LLAVE = 65;   // punto P-256 sin comprimir: 0x04 || X || Y
    private static final int LARGO_IV = 12;      // AES-GCM
    private static final int LARGO_ETIQUETA = 16;

    public enum Lado {
        ADMIN, USUARIO;

        Lado otro() { return this == ADMIN ? USUARIO : ADMIN; }
    }

    /**
     * Lo que recoge cada lado. Los campos que no aplican van en null y no
     * viajan (Jackson está configurado con non_null).
     *
     * <ul>
     *   <li>{@code llave}: el otro lado entró o volvió a entrar. Trae su llave pública.</li>
     *   <li>{@code mensaje}: un mensaje cifrado, con su IV.</li>
     *   <li>{@code salio}: la persona cerró el chat en su teléfono. Puede volver.</li>
     *   <li>{@code cerrada}: se acabó la conversación, con el motivo para mostrarlo.</li>
     * </ul>
     */
    public record Evento(String tipo, String clave, String iv, String cifrado, String motivo) {
        static Evento llave(String clave) { return new Evento("llave", clave, null, null, null); }
        static Evento mensaje(String iv, String cifrado) { return new Evento("mensaje", null, iv, cifrado, null); }
        static Evento salio() { return new Evento("salio", null, null, null, null); }
        static Evento cerrada(String motivo) { return new Evento("cerrada", null, null, null, motivo); }
    }

    /** Al entrar: con qué conexión llamar y la llave del otro lado, si ya está. */
    public record Entrada(String conexion, String claveDelOtro) {}

    /** Lo que el panel enseña de una conversación sin abrirla. */
    public record Estado(boolean adminDentro, UUID adminId, boolean usuarioDentro) {
        static final Estado VACIA = new Estado(false, null, false);
    }

    /** Uno de los dos lados de una sala. */
    static final class Parte {
        final UUID quien;
        final String conexion;
        final String clave;
        final BlockingQueue<Evento> buzon = new LinkedBlockingQueue<>();
        volatile Instant visto;

        Parte(UUID quien, String conexion, String clave, Instant ahora) {
            this.quien = quien;
            this.conexion = conexion;
            this.clave = clave;
            this.visto = ahora;
        }
    }

    static final class Sala {
        Parte admin;
        Parte usuario;
        Instant actividad;

        Parte de(Lado lado) { return lado == Lado.ADMIN ? admin : usuario; }

        void poner(Lado lado, Parte parte) {
            if (lado == Lado.ADMIN) admin = parte; else usuario = parte;
        }

        boolean vacia() { return admin == null && usuario == null; }
    }

    private final Map<UUID, Sala> salas = new HashMap<>();

    /**
     * Conexiones que ya no valen, con el porqué. Si estaba esperando, el
     * "cerrada" le llega por su buzón; si estaba entre una consulta y la
     * siguiente, se lo damos aquí en la siguiente. Se olvidan en la limpieza.
     */
    private final Map<String, Despedida> despedidas = new HashMap<>();

    private record Despedida(String motivo, Instant cuando) {}
    private final SecureRandom azar = new SecureRandom();
    private final Clock reloj;

    public SalasDeChat() {
        this(Clock.systemUTC());
    }

    SalasDeChat(Clock reloj) {
        this.reloj = reloj;
    }

    // -------------------------------------------------------------------------
    // Entrar y salir
    // -------------------------------------------------------------------------

    /**
     * Entra un lado a la conversación de esa cuenta, o vuelve a entrar con una
     * llave nueva. Si ese lado ya estaba en otra conexión, esa recibe
     * "cerrada"; si el otro lado ya estaba, recibe la llave nueva.
     *
     * @param usuarioId la cuenta con la que se habla: es la sala
     * @param quien     quien entra: el administrador, o la propia cuenta
     */
    public synchronized Entrada entrar(UUID usuarioId, Lado lado, UUID quien, String clave) {
        validarLlave(clave);
        Instant ahora = reloj.instant();

        Sala sala = salas.computeIfAbsent(usuarioId, id -> new Sala());
        Parte anterior = sala.de(lado);
        Parte nueva = new Parte(quien, nuevaConexion(), clave, ahora);
        sala.poner(lado, nueva);
        sala.actividad = ahora;

        if (anterior != null) {
            despedir(anterior, motivoDeRelevo(lado, anterior.quien.equals(quien)), ahora);
        }

        Parte otro = sala.de(lado.otro());
        if (otro != null) otro.buzon.offer(Evento.llave(clave));

        return new Entrada(nueva.conexion, otro != null ? otro.clave : null);
    }

    /**
     * Un lado se va. Si es el administrador, se acaba la conversación. Si es
     * la persona, el administrador se entera y puede esperar a que vuelva.
     */
    public synchronized void salir(UUID usuarioId, Lado lado, String conexion) {
        Sala sala = salas.get(usuarioId);
        if (sala == null) return;
        Parte parte = sala.de(lado);
        if (parte == null || !parte.conexion.equals(conexion)) return;

        if (lado == Lado.ADMIN) {
            cerrarSala(usuarioId, sala, "El equipo cerró la conversación.");
        } else {
            quitarUsuario(usuarioId, sala);
        }
    }

    /**
     * Cierra la conversación de esa cuenta, esté quien esté dentro. Lo usa el
     * panel al quitarle el chat a la cuenta.
     */
    public synchronized void cerrar(UUID usuarioId, String motivo) {
        Sala sala = salas.get(usuarioId);
        if (sala != null) cerrarSala(usuarioId, sala, motivo);
    }

    // -------------------------------------------------------------------------
    // Mensajes
    // -------------------------------------------------------------------------

    /** Deja un mensaje cifrado en el buzón del otro lado. */
    public synchronized void enviar(UUID usuarioId, Lado lado, String conexion, String iv, String cifrado) {
        validarIv(iv);
        validarCifrado(cifrado);

        Sala sala = exigirSala(usuarioId);
        exigirParte(sala, lado, conexion);

        Parte otro = sala.de(lado.otro());
        if (otro == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, lado == Lado.ADMIN
                    ? "La persona no tiene el chat abierto. Espera a que entre."
                    : "El equipo todavía no está en el chat. Espera un momento.");
        }
        if (otro.buzon.size() >= MAX_PENDIENTES) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Hay demasiados mensajes sin leer. Espera a que la otra persona los vea.");
        }

        otro.buzon.offer(Evento.mensaje(iv, cifrado));
        sala.actividad = reloj.instant();
    }

    /**
     * Recoge lo que haya para este lado. Si no hay nada espera hasta
     * {@code espera} a que llegue algo, para no tener que preguntar cada
     * segundo. Con hilos virtuales la espera no ocupa un hilo del sistema.
     *
     * Lo recogido sale del buzón: no queda copia en ningún sitio.
     */
    public List<Evento> recibir(UUID usuarioId, Lado lado, String conexion, Duration espera) {
        Parte parte;
        synchronized (this) {
            Despedida despedida = conexion != null ? despedidas.remove(conexion) : null;
            if (despedida != null) return List.of(Evento.cerrada(despedida.motivo()));

            parte = exigirParte(exigirSala(usuarioId), lado, conexion);
            parte.visto = reloj.instant();
        }

        List<Evento> eventos = new ArrayList<>();
        try {
            Evento primero = parte.buzon.poll(espera.toMillis(), TimeUnit.MILLISECONDS);
            if (primero != null) {
                eventos.add(primero);
                parte.buzon.drainTo(eventos);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // También al terminar: mientras espera, sigue presente.
        parte.visto = reloj.instant();
        if (eventos.stream().anyMatch(e -> "cerrada".equals(e.tipo()))) {
            synchronized (this) { despedidas.remove(conexion); }
        }
        return eventos;
    }

    // -------------------------------------------------------------------------
    // Para el panel
    // -------------------------------------------------------------------------

    public synchronized Estado estado(UUID usuarioId) {
        Sala sala = salas.get(usuarioId);
        if (sala == null) return Estado.VACIA;
        return new Estado(sala.admin != null, sala.admin != null ? sala.admin.quien : null,
                sala.usuario != null);
    }

    // -------------------------------------------------------------------------
    // Limpieza
    // -------------------------------------------------------------------------

    /**
     * Quita a quien dejó de preguntar (cerró la app sin despedirse, se quedó
     * sin red) y cierra las conversaciones paradas. Cada minuto basta: lo
     * único que cuesta esperar es memoria.
     */
    @Scheduled(fixedDelay = 60_000)
    public synchronized void limpiar() {
        Instant ahora = reloj.instant();
        despedidas.values().removeIf(d -> d.cuando().plus(SIN_NOTICIAS).isBefore(ahora));

        for (Map.Entry<UUID, Sala> e : List.copyOf(salas.entrySet())) {
            UUID usuarioId = e.getKey();
            Sala sala = e.getValue();

            if (sala.actividad.plus(INACTIVIDAD).isBefore(ahora)) {
                cerrarSala(usuarioId, sala, "La conversación se cerró por inactividad.");
                continue;
            }
            if (sala.admin != null && callado(sala.admin, ahora)) {
                cerrarSala(usuarioId, sala, "El equipo salió del chat.");
                continue;
            }
            if (sala.usuario != null && callado(sala.usuario, ahora)) {
                quitarUsuario(usuarioId, sala);
            }
        }
    }

    /** Al apagar el servidor, quien esté dentro se entera en vez de quedarse esperando. */
    @PreDestroy
    public synchronized void cerrarTodas() {
        for (Map.Entry<UUID, Sala> e : List.copyOf(salas.entrySet())) {
            cerrarSala(e.getKey(), e.getValue(), "El servidor se está reiniciando. Vuelve a abrir el chat en un momento.");
        }
    }

    synchronized int cuantas() {
        return salas.size();
    }

    // -------------------------------------------------------------------------

    private boolean callado(Parte parte, Instant ahora) {
        return parte.visto.plus(SIN_NOTICIAS).isBefore(ahora);
    }

    private void quitarUsuario(UUID usuarioId, Sala sala) {
        sala.usuario = null;
        if (sala.admin != null) {
            sala.admin.buzon.offer(Evento.salio());
        } else {
            salas.remove(usuarioId);
        }
    }

    private void cerrarSala(UUID usuarioId, Sala sala, String motivo) {
        salas.remove(usuarioId);
        Instant ahora = reloj.instant();
        for (Parte parte : new Parte[] { sala.admin, sala.usuario }) {
            if (parte != null) despedir(parte, motivo, ahora);
        }
        // Solo que pasó, nunca qué se dijo.
        log.info("Chat con {} cerrado", usuarioId);
    }

    /** Esa conexión ya no vale. Lo que tuviera pendiente se tira: ya no lo podría leer nadie. */
    private void despedir(Parte parte, String motivo, Instant ahora) {
        parte.buzon.clear();
        parte.buzon.offer(Evento.cerrada(motivo));
        despedidas.put(parte.conexion, new Despedida(motivo, ahora));
    }

    private Sala exigirSala(UUID usuarioId) {
        Sala sala = salas.get(usuarioId);
        if (sala == null || sala.vacia()) {
            throw new ResponseStatusException(HttpStatus.GONE, "La conversación ya terminó.");
        }
        return sala;
    }

    private static Parte exigirParte(Sala sala, Lado lado, String conexion) {
        Parte parte = sala.de(lado);
        if (parte == null || conexion == null || !parte.conexion.equals(conexion)) {
            throw new ResponseStatusException(HttpStatus.GONE,
                    "Esta conversación se abrió en otro lugar o ya terminó.");
        }
        return parte;
    }

    private static String motivoDeRelevo(Lado lado, boolean mismaPersona) {
        if (lado == Lado.USUARIO) return "Abriste el chat en otro teléfono.";
        return mismaPersona
                ? "Abriste esta conversación en otra ventana."
                : "Otro administrador tomó esta conversación.";
    }

    private String nuevaConexion() {
        byte[] bytes = new byte[18];
        azar.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // -------------------------------------------------------------------------
    // Validación del formato. El contenido no se puede validar: va cifrado.
    // -------------------------------------------------------------------------

    static void validarLlave(String clave) {
        byte[] bytes = decodificar(clave, "La llave del chat no es válida.");
        if (bytes.length != LARGO_LLAVE || bytes[0] != 0x04) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La llave del chat no es válida.");
        }
    }

    static void validarIv(String iv) {
        byte[] bytes = decodificar(iv, "El mensaje llegó incompleto.");
        if (bytes.length != LARGO_IV) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El mensaje llegó incompleto.");
        }
    }

    static void validarCifrado(String cifrado) {
        if (cifrado != null && cifrado.length() > MAX_CIFRADO) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "El mensaje es demasiado largo.");
        }
        byte[] bytes = decodificar(cifrado, "El mensaje llegó incompleto.");
        if (bytes.length <= LARGO_ETIQUETA) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El mensaje llegó incompleto.");
        }
    }

    private static byte[] decodificar(String base64, String error) {
        if (base64 == null || base64.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error);
        }
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error);
        }
    }
}
