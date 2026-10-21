package com.voces.backend.chat;

import com.voces.backend.chat.NovedadesDeChat.Lado;
import com.voces.backend.modelo.MensajeChat;
import com.voces.backend.modelo.Repositorios;
import com.voces.backend.modelo.Usuario;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;

/**
 * El chat entre el equipo y una cuenta: una conversación por cuenta, guardada
 * y cifrada.
 *
 * Aquí se cifra antes de guardar y se descifra al leer (CifradorDeChat); la
 * base solo ve bytes. Al leer se marcan como leídos los mensajes del otro
 * lado, y al guardar se despierta a quien esté esperando (NovedadesDeChat) y,
 * si el otro lado no tiene la conversación abierta, se le manda un aviso que
 * no lleva el texto (AvisoDeChat).
 *
 * Nada de lo que se escribe pasa por el registro del servidor.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** Lo más largo que puede ser un mensaje. */
    public static final int MAX_TEXTO = 2000;

    /** Cuántos mensajes se dan de una vez: los últimos, al abrir la conversación. */
    static final int POR_LECTURA = 200;

    /** Lo que se enseña del último mensaje en la bandeja. */
    static final int LARGO_VISTAZO = 90;

    /** Lo que sale cuando un mensaje no se puede descifrar. */
    static final String ILEGIBLE = "[Este mensaje no se puede leer]";

    /**
     * Un mensaje como lo ve quien lo pide.
     *
     * @param autor quién lo escribió, en palabras. A la persona nunca se le
     *              dice qué administrador fue: siempre "Equipo de Voces".
     */
    public record Mensaje(long id, String texto, boolean deAdmin, String autor,
                          Instant creadoEn, Instant leidoEn) {}

    /** Una conversación en la bandeja del equipo. */
    public record Conversacion(UUID id, String proveedor, String email, Instant vistoEn,
                               String appVersion, String appPlataforma,
                               Long ultimoId, Instant ultimoEn, String ultimo, boolean ultimoDeAdmin,
                               long sinLeer) {}

    private final Repositorios.MensajesChat mensajes;
    private final Repositorios.Usuarios usuarios;
    private final CifradorDeChat cifrador;
    private final NovedadesDeChat novedades;
    private final AvisoDeChat aviso;

    public ChatService(Repositorios.MensajesChat mensajes, Repositorios.Usuarios usuarios,
                       CifradorDeChat cifrador, NovedadesDeChat novedades, AvisoDeChat aviso) {
        this.mensajes = mensajes;
        this.usuarios = usuarios;
        this.cifrador = cifrador;
        this.novedades = novedades;
        this.aviso = aviso;
    }

    public boolean disponible() {
        return cifrador.listo();
    }

    // -------------------------------------------------------------------------
    // Leer
    // -------------------------------------------------------------------------

    /**
     * Los mensajes de la conversación de esa cuenta. Con {@code despues} en 0,
     * los últimos; si no, los que llegaron después de ese.
     *
     * Qué cuenta como leído: con {@code despues} en 0 (se abre la
     * conversación) lo que se entrega, porque se pinta en el acto. Si no, lo
     * que el que pide ya tiene, hasta {@code despues}: lo que se le entrega en
     * una espera larga puede no verse nunca (la app pasó a segundo plano y
     * esa respuesta se descarta), y no debe salir como leído. Lo confirma la
     * consulta siguiente, o la salida (ver {@link #confirmarLeidos}).
     *
     * @param quienLee quién pide: de su lado se marcan leídos los del otro
     * @param yo       el administrador que lee, para poner "Tú" en los suyos;
     *                 null si lee la persona
     */
    @Transactional
    public List<Mensaje> leer(UUID usuarioId, long despues, Lado quienLee, UUID yo) {
        cifrador.exigir();
        // Los del otro lado: si lee el equipo, los de la persona, y al revés.
        boolean delOtro = quienLee == Lado.USUARIO;
        Instant ahora = Instant.now();

        List<MensajeChat> filas;
        long leidosHasta;
        if (despues <= 0) {
            filas = new ArrayList<>(mensajes.findByUsuarioIdOrderByIdDesc(usuarioId, PageRequest.of(0, POR_LECTURA)));
            Collections.reverse(filas);
            leidosHasta = filas.isEmpty() ? 0 : filas.get(filas.size() - 1).getId();
        } else {
            filas = mensajes.findByUsuarioIdAndIdGreaterThanOrderByIdAsc(usuarioId, despues, PageRequest.of(0, POR_LECTURA));
            leidosHasta = despues;
        }
        if (leidosHasta > 0) mensajes.marcarLeidos(usuarioId, delOtro, leidosHasta, ahora);
        if (filas.isEmpty()) return List.of();
        long marcados = leidosHasta;

        Map<UUID, String> admins = quienLee == Lado.ADMIN ? nombresDeAdmins(filas) : Map.of();
        return filas.stream().map(m -> {
            Instant leido = m.getLeidoEn() == null && m.isDeAdmin() == delOtro && m.getId() <= marcados
                    ? ahora : m.getLeidoEn();
            return new Mensaje(m.getId(), texto(m), m.isDeAdmin(), autor(m, quienLee, yo, admins),
                    m.getCreadoEn(), leido);
        }).toList();
    }

    /**
     * Lo que ese lado ya vio, hasta {@code hasta}, queda leído. Lo manda la
     * pantalla del chat al cerrarse, con el último mensaje que pintó.
     */
    @Transactional
    public void confirmarLeidos(UUID usuarioId, Lado quienLee, long hasta) {
        if (hasta > 0 && disponible()) {
            mensajes.marcarLeidos(usuarioId, quienLee == Lado.USUARIO, hasta, Instant.now());
        }
    }

    // -------------------------------------------------------------------------
    // Escribir
    // -------------------------------------------------------------------------

    /**
     * Guarda un mensaje cifrado, despierta a quien espera y avisa al otro lado
     * si no tiene la conversación abierta.
     *
     * @param autorId el administrador que escribe; null si escribe la persona
     */
    @Transactional
    public Mensaje enviar(UUID usuarioId, Lado quienEscribe, UUID autorId, String texto) {
        String limpio = validar(texto);
        boolean deAdmin = quienEscribe == Lado.ADMIN;

        CifradorDeChat.Cifrado c = cifrador.cifrar(usuarioId, deAdmin, limpio);
        MensajeChat guardado = mensajes.save(new MensajeChat(usuarioId, deAdmin, deAdmin ? autorId : null,
                c.llave(), c.iv(), c.datos()));

        // Después de confirmar el guardado: quien espera vuelve a consultar al
        // despertar, y antes del commit no lo encontraría. Y el aviso a FCM,
        // que tarda, no retiene la conexión a la base.
        long id = guardado.getId();
        Lado otro = deAdmin ? Lado.USUARIO : Lado.ADMIN;
        alConfirmar(() -> {
            novedades.anunciar(usuarioId, id);
            if (!novedades.presente(usuarioId, otro)) {
                if (deAdmin) aviso.avisarPersona(usuarioId); else aviso.avisarEquipo(usuarioId);
            }
        });

        // Solo que pasó. Nunca qué se dijo.
        log.info("Mensaje de chat {} en la conversación de {}", deAdmin ? "del equipo" : "de la cuenta", usuarioId);
        return new Mensaje(guardado.getId(), limpio, deAdmin,
                deAdmin ? "Tú" : null, guardado.getCreadoEn(), null);
    }

    static String validar(String texto) {
        String limpio = texto == null ? "" : texto.strip();
        if (limpio.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escribe algo antes de enviar.");
        }
        if (limpio.length() > MAX_TEXTO) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El mensaje es muy largo. Pártelo en dos (" + MAX_TEXTO + " letras como mucho).");
        }
        return limpio;
    }

    // -------------------------------------------------------------------------
    // Bandeja y contadores
    // -------------------------------------------------------------------------

    /** Las cuentas con chat, la que tiene el mensaje más reciente primero. */
    @Transactional(readOnly = true)
    public List<Conversacion> bandeja() {
        Map<UUID, Object[]> resumen = new HashMap<>();
        for (Object[] fila : mensajes.resumenPorConversacion()) {
            resumen.put(fila[0] instanceof UUID u ? u : UUID.fromString(fila[0].toString()), fila);
        }

        List<Conversacion> lista = new ArrayList<>();
        for (Usuario u : usuarios.findByChatHabilitadoTrueOrderByVistoEnDesc()) {
            Object[] r = resumen.get(u.getId());
            Long ultimoId = null;
            Instant ultimoEn = null;
            String vistazo = null;
            boolean ultimoDeAdmin = false;
            long sinLeer = 0;
            if (r != null) {
                ultimoId = ((Number) r[1]).longValue();
                ultimoEn = comoInstante(r[2]);
                sinLeer = ((Number) r[3]).longValue();
                List<MensajeChat> ultimo = mensajes.findByUsuarioIdOrderByIdDesc(u.getId(), PageRequest.of(0, 1));
                if (!ultimo.isEmpty()) {
                    vistazo = recortar(texto(ultimo.get(0)));
                    ultimoDeAdmin = ultimo.get(0).isDeAdmin();
                }
            }
            lista.add(new Conversacion(u.getId(), u.getProveedor(), u.getEmail(), u.getVistoEn(),
                    u.getAppVersion(), u.getAppPlataforma(), ultimoId, ultimoEn, vistazo, ultimoDeAdmin, sinLeer));
        }

        // Primero las que tienen algo sin leer; después, la más reciente.
        lista.sort(Comparator.comparing((Conversacion c) -> c.sinLeer() == 0)
                .thenComparing(c -> c.ultimoEn() == null ? Instant.EPOCH : c.ultimoEn(), Comparator.reverseOrder()));
        return lista;
    }

    /** Los mensajes del equipo que la persona no ha leído. */
    @Transactional(readOnly = true)
    public long sinLeerDeLaPersona(UUID usuarioId) {
        return disponible() ? mensajes.countByUsuarioIdAndDeAdminAndLeidoEnIsNull(usuarioId, true) : 0;
    }

    /** Cuántas conversaciones tienen mensajes de la persona que nadie del equipo ha leído. */
    @Transactional(readOnly = true)
    public long conversacionesSinLeer() {
        return disponible() ? mensajes.conversacionesSinLeer() : 0;
    }

    @Transactional(readOnly = true)
    public long mensajesSinLeerDelEquipo() {
        return disponible() ? mensajes.countByDeAdminFalseAndLeidoEnIsNull() : 0;
    }

    // -------------------------------------------------------------------------
    // Borrar y pasar
    // -------------------------------------------------------------------------

    /** Borra toda la conversación de esa cuenta. No se puede deshacer. */
    @Transactional
    public int borrar(UUID usuarioId) {
        int n = mensajes.borrarConversacion(usuarioId);
        novedades.olvidar(usuarioId);
        log.info("Conversación de chat de {} borrada ({} mensajes)", usuarioId, n);
        return n;
    }

    /**
     * Pasa la conversación de una cuenta a otra, al fundir una de invitado en
     * la que se queda. Cada mensaje se cifra otra vez: el cifrado va atado a
     * la cuenta. Si ya había mensajes en las dos, quedan juntos en orden.
     * Llamarlo antes de borrar la cuenta que se va, o se irían con ella.
     */
    @Transactional
    public void pasar(UUID de, UUID a) {
        List<MensajeChat> lista = mensajes.findByUsuarioIdOrderByIdAsc(de);
        if (lista.isEmpty()) return;
        if (!cifrador.listo()) {
            // Sin llaves no se pueden volver a cifrar: se pierden con la cuenta
            // que se va. Mejor decirlo que dejarlos ilegibles en la otra.
            log.warn("No se pudo pasar la conversación de {} a {}: falta CHAT_LLAVES", de, a);
            return;
        }
        for (MensajeChat m : lista) {
            String claro = cifrador.descifrar(de, m.isDeAdmin(), m.getLlave(), m.getIv(), m.getCifrado());
            if (claro == null) claro = ILEGIBLE;
            CifradorDeChat.Cifrado c = cifrador.cifrar(a, m.isDeAdmin(), claro);
            m.pasarA(a, c.llave(), c.iv(), c.datos());
        }
        mensajes.saveAll(lista);
        log.info("Conversación de chat pasada de {} a {} ({} mensajes)", de, a, lista.size());
    }

    // -------------------------------------------------------------------------

    /** Lo hace al confirmarse la transacción en curso, o ya si no hay ninguna. */
    private static void alConfirmar(Runnable tarea) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    tarea.run();
                }
            });
        } else {
            tarea.run();
        }
    }

    private String texto(MensajeChat m) {
        String claro = cifrador.descifrar(m.getUsuarioId(), m.isDeAdmin(), m.getLlave(), m.getIv(), m.getCifrado());
        return claro != null ? claro : ILEGIBLE;
    }

    private static String autor(MensajeChat m, Lado quienLee, UUID yo, Map<UUID, String> admins) {
        if (!m.isDeAdmin()) return null;
        if (quienLee == Lado.USUARIO) return "Equipo de Voces";
        if (m.getAutorId() != null && m.getAutorId().equals(yo)) return "Tú";
        String nombre = m.getAutorId() != null ? admins.get(m.getAutorId()) : null;
        return nombre != null ? nombre : "Equipo";
    }

    private Map<UUID, String> nombresDeAdmins(List<MensajeChat> filas) {
        Set<UUID> ids = new HashSet<>();
        filas.forEach(m -> { if (m.getAutorId() != null) ids.add(m.getAutorId()); });
        Map<UUID, String> nombres = new HashMap<>();
        usuarios.findAllById(ids).forEach(u -> nombres.put(u.getId(), u.getEmail() != null ? u.getEmail() : "Equipo"));
        return nombres;
    }

    static String recortar(String texto) {
        String una = texto.replaceAll("\\s+", " ").strip();
        return una.length() <= LARGO_VISTAZO ? una : una.substring(0, LARGO_VISTAZO - 1).stripTrailing() + "…";
    }

    private static Instant comoInstante(Object valor) {
        if (valor == null) return null;
        if (valor instanceof Instant i) return i;
        if (valor instanceof java.sql.Timestamp t) return t.toInstant();
        if (valor instanceof java.time.OffsetDateTime o) return o.toInstant();
        return Instant.parse(valor.toString());
    }
}
