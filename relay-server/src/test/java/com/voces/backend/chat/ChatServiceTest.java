package com.voces.backend.chat;

import com.voces.backend.chat.ChatService.Conversacion;
import com.voces.backend.chat.ChatService.Mensaje;
import com.voces.backend.chat.NovedadesDeChat.Lado;
import com.voces.backend.modelo.MensajeChat;
import com.voces.backend.modelo.Repositorios;
import com.voces.backend.modelo.Usuario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * El chat de punta a punta, con la base en memoria: quién lee qué, qué queda
 * leído, cuándo sale un aviso y cómo pasa una conversación a otra cuenta.
 */
class ChatServiceTest {

    private static final UUID YO = UUID.randomUUID();
    private static final UUID OTRA_ADMIN = UUID.randomUUID();

    /** Lo que guarda la base: los mensajes y las cuentas. */
    private final List<MensajeChat> filas = new ArrayList<>();
    private final Map<UUID, Usuario> cuentas = new LinkedHashMap<>();
    private long siguienteId = 1;

    private final AvisoDeChatTest.PushDePrueba push = new AvisoDeChatTest.PushDePrueba();
    private final NovedadesDeChat novedades = new NovedadesDeChat();
    private final String llave = "1:" + llaveAlAzar();
    private final ChatService chat = servicio(new CifradorDeChat(llave));

    private static String llaveAlAzar() {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        return Base64.getEncoder().encodeToString(b);
    }

    // -------------------------------------------------------------------------

    @Test
    @DisplayName("El equipo escribe: se guarda cifrado, la persona recibe aviso y al leerlo queda leído")
    void delEquipoALaPersona() {
        Usuario ana = cuenta("ana@ejemplo.com");

        Mensaje m = chat.enviar(ana.getId(), Lado.ADMIN, YO, "  Hola Ana, ¿todo bien con la app?  ");
        assertEquals("Hola Ana, ¿todo bien con la app?", m.texto());
        assertEquals(1, push.enviados.size(), "la persona no tenía el chat abierto");
        assertFalse(new String(filas.get(0).getCifrado(), StandardCharsets.ISO_8859_1).contains("Hola"),
                "en la base no está el texto");

        assertEquals(1, chat.sinLeerDeLaPersona(ana.getId()));
        List<Mensaje> leidos = chat.leer(ana.getId(), 0, Lado.USUARIO, null);
        assertEquals(1, leidos.size());
        assertEquals("Equipo de Voces", leidos.get(0).autor(), "a la persona no se le dice qué administrador fue");
        assertNotNull(leidos.get(0).leidoEn());
        assertEquals(0, chat.sinLeerDeLaPersona(ana.getId()));
    }

    @Test
    @DisplayName("Con el chat abierto del otro lado no sale aviso")
    void sinAvisoSiEstaAbierto() {
        Usuario ana = cuenta("ana@ejemplo.com");
        novedades.visto(ana.getId(), Lado.USUARIO);
        chat.enviar(ana.getId(), Lado.ADMIN, YO, "¿Sigues ahí?");
        assertTrue(push.enviados.isEmpty());

        novedades.salio(ana.getId(), Lado.USUARIO);
        Usuario luis = cuenta("luis@ejemplo.com");
        chat.enviar(luis.getId(), Lado.USUARIO, null, "Hola");
        assertEquals("chat_equipo", push.enviados.get(0).datos().get("tipo"), "a los administradores");
    }

    @Test
    @DisplayName("La persona contesta: el equipo lo ve sin leer hasta que lo abre, con el autor de cada mensaje")
    void deLaPersonaAlEquipo() {
        cuenta(OTRA_ADMIN, "beto@equipo.mx");
        Usuario ana = cuenta("ana@ejemplo.com");
        chat.enviar(ana.getId(), Lado.ADMIN, OTRA_ADMIN, "Hola, soy Beto");
        chat.enviar(ana.getId(), Lado.ADMIN, YO, "Y yo también ando por aquí");
        chat.enviar(ana.getId(), Lado.USUARIO, null, "¡Gracias a los dos!");

        assertEquals(1, chat.conversacionesSinLeer());
        assertEquals(1, chat.mensajesSinLeerDelEquipo());

        List<Mensaje> vistos = chat.leer(ana.getId(), 0, Lado.ADMIN, YO);
        assertEquals(List.of("beto@equipo.mx", "Tú"), vistos.stream().filter(Mensaje::deAdmin).map(Mensaje::autor).toList());
        assertEquals(0, chat.conversacionesSinLeer());
        assertNull(filas.get(0).getLeidoEn(), "leer como equipo no marca como leídos los del propio equipo");
    }

    @Test
    @DisplayName("Lo que llega en una espera no cuenta como leído hasta que el teléfono confirma que lo tiene")
    void leidoSoloAlConfirmar() {
        Usuario ana = cuenta("ana@ejemplo.com");
        Mensaje uno = chat.enviar(ana.getId(), Lado.ADMIN, YO, "uno");
        chat.leer(ana.getId(), 0, Lado.USUARIO, null);
        assertEquals(0, chat.sinLeerDeLaPersona(ana.getId()), "al abrir, lo que se pinta queda leído");

        Mensaje dos = chat.enviar(ana.getId(), Lado.ADMIN, YO, "dos");
        List<Mensaje> llegado = chat.leer(ana.getId(), uno.id(), Lado.USUARIO, null);
        assertEquals(List.of("dos"), llegado.stream().map(Mensaje::texto).toList());
        assertNull(llegado.get(0).leidoEn());
        assertEquals(1, chat.sinLeerDeLaPersona(ana.getId()),
                "la app pudo pasar a segundo plano y descartar esa respuesta");

        chat.leer(ana.getId(), dos.id(), Lado.USUARIO, null);
        assertEquals(0, chat.sinLeerDeLaPersona(ana.getId()), "la consulta siguiente lo confirma");

        Mensaje tres = chat.enviar(ana.getId(), Lado.ADMIN, YO, "tres");
        chat.confirmarLeidos(ana.getId(), Lado.USUARIO, tres.id());
        assertEquals(0, chat.sinLeerDeLaPersona(ana.getId()), "o la salida, con el último que vio");
    }

    @Test
    @DisplayName("Después de un mensaje llegan solo los nuevos")
    void soloLosNuevos() {
        Usuario ana = cuenta("ana@ejemplo.com");
        Mensaje primero = chat.enviar(ana.getId(), Lado.ADMIN, YO, "uno");
        chat.enviar(ana.getId(), Lado.ADMIN, YO, "dos");
        chat.enviar(cuenta("otra@ejemplo.com").getId(), Lado.ADMIN, YO, "de otra conversación");

        assertEquals(List.of("dos"), chat.leer(ana.getId(), primero.id(), Lado.USUARIO, null).stream().map(Mensaje::texto).toList());
        assertTrue(chat.leer(ana.getId(), primero.id() + 100, Lado.USUARIO, null).isEmpty());
    }

    @Test
    @DisplayName("La bandeja pone primero lo que espera respuesta, con un vistazo del último mensaje")
    void bandeja() {
        Usuario ana = cuenta("ana@ejemplo.com");
        Usuario luis = cuenta("luis@ejemplo.com");
        Usuario sinMensajes = cuenta("nadie@ejemplo.com");
        chat.enviar(luis.getId(), Lado.USUARIO, null, "Tengo una duda   con\nlos avisos " + "x".repeat(200));
        chat.enviar(ana.getId(), Lado.ADMIN, YO, "Hola Ana");

        List<Conversacion> b = chat.bandeja();
        assertEquals(List.of(luis.getId(), ana.getId(), sinMensajes.getId()), b.stream().map(Conversacion::id).toList());
        assertEquals(1, b.get(0).sinLeer());
        assertTrue(b.get(0).ultimo().startsWith("Tengo una duda con los avisos x"), b.get(0).ultimo());
        assertTrue(b.get(0).ultimo().length() <= ChatService.LARGO_VISTAZO);
        assertTrue(b.get(1).ultimoDeAdmin());
        assertNull(b.get(2).ultimoId());
    }

    @Test
    @DisplayName("Al fundir una cuenta de invitado, su conversación pasa a la otra y se sigue leyendo")
    void pasarAOtraCuenta() {
        Usuario invitado = cuenta(null);
        Usuario buena = cuenta("ana@ejemplo.com");
        chat.enviar(invitado.getId(), Lado.USUARIO, null, "Escribí como invitada");
        chat.enviar(invitado.getId(), Lado.ADMIN, YO, "Te contestamos");
        chat.leer(invitado.getId(), 0, Lado.ADMIN, YO);
        Instant leido = filas.get(0).getLeidoEn();

        chat.pasar(invitado.getId(), buena.getId());

        assertTrue(chat.leer(invitado.getId(), 0, Lado.USUARIO, null).isEmpty());
        List<Mensaje> ahora = chat.leer(buena.getId(), 0, Lado.ADMIN, YO);
        assertEquals(List.of("Escribí como invitada", "Te contestamos"), ahora.stream().map(Mensaje::texto).toList());
        assertEquals(leido, ahora.get(0).leidoEn(), "lo leído sigue leído");
    }

    @Test
    @DisplayName("Borrar la conversación no deja nada")
    void borrar() {
        Usuario ana = cuenta("ana@ejemplo.com");
        chat.enviar(ana.getId(), Lado.ADMIN, YO, "uno");
        chat.enviar(ana.getId(), Lado.USUARIO, null, "dos");
        assertEquals(2, chat.borrar(ana.getId()));
        assertTrue(filas.isEmpty());
    }

    @Test
    @DisplayName("Ni vacío ni demasiado largo; y sin la llave, lo guardado sale como ilegible")
    void limites() {
        Usuario ana = cuenta("ana@ejemplo.com");
        assertThrows(ResponseStatusException.class, () -> chat.enviar(ana.getId(), Lado.ADMIN, YO, "   "));
        assertThrows(ResponseStatusException.class, () -> chat.enviar(ana.getId(), Lado.ADMIN, YO, null));
        assertThrows(ResponseStatusException.class,
                () -> chat.enviar(ana.getId(), Lado.ADMIN, YO, "a".repeat(ChatService.MAX_TEXTO + 1)));
        chat.enviar(ana.getId(), Lado.ADMIN, YO, "a".repeat(ChatService.MAX_TEXTO));

        ChatService conOtraLlave = servicio(new CifradorDeChat("2:" + llaveAlAzar()));
        assertEquals(ChatService.ILEGIBLE, conOtraLlave.leer(ana.getId(), 0, Lado.USUARIO, null).get(0).texto());

        ChatService sinLlaves = servicio(new CifradorDeChat(""));
        assertFalse(sinLlaves.disponible());
        assertEquals(0, sinLlaves.sinLeerDeLaPersona(ana.getId()));
        assertThrows(ResponseStatusException.class, () -> sinLlaves.leer(ana.getId(), 0, Lado.USUARIO, null));
    }

    // -------------------------------------------------------------------------
    // La base en memoria
    // -------------------------------------------------------------------------

    private ChatService servicio(CifradorDeChat cifrador) {
        return new ChatService(repoDeMensajes(), repoDeCuentas(), cifrador, novedades,
                new AvisoDeChat(push, "secreto-de-prueba-con-32-caracteres!!", java.time.Clock.systemUTC()));
    }

    private Usuario cuenta(String email) {
        return cuenta(UUID.randomUUID(), email);
    }

    private Usuario cuenta(UUID id, String email) {
        Usuario u = new Usuario();
        u.setId(id);
        u.setEmail(email);
        u.setProveedor(email == null ? Usuario.ANONIMO : Usuario.GOOGLE);
        u.setChatHabilitado(true);
        cuentas.put(id, u);
        return u;
    }

    private static void poner(Object objeto, String campo, Object valor) {
        try {
            Field f = objeto.getClass().getDeclaredField(campo);
            f.setAccessible(true);
            f.set(objeto, valor);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<MensajeChat> de(UUID usuario) {
        return filas.stream().filter(m -> m.getUsuarioId().equals(usuario))
                .sorted(Comparator.comparing(MensajeChat::getId)).collect(Collectors.toList());
    }

    private static int tamano(Object pagina) {
        return ((Pageable) pagina).getPageSize();
    }

    @SuppressWarnings("unchecked")
    private Repositorios.MensajesChat repoDeMensajes() {
        return (Repositorios.MensajesChat) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { Repositorios.MensajesChat.class }, (proxy, metodo, a) -> switch (metodo.getName()) {
                    case "save" -> {
                        MensajeChat m = (MensajeChat) a[0];
                        if (m.getId() == null) { poner(m, "id", siguienteId++); filas.add(m); }
                        yield m;
                    }
                    case "saveAll" -> a[0];
                    case "findByUsuarioIdOrderByIdDesc" -> {
                        List<MensajeChat> l = de((UUID) a[0]);
                        Collections.reverse(l);
                        yield l.subList(0, Math.min(l.size(), tamano(a[1])));
                    }
                    case "findByUsuarioIdOrderByIdAsc" -> de((UUID) a[0]);
                    case "findByUsuarioIdAndIdGreaterThanOrderByIdAsc" -> de((UUID) a[0]).stream()
                            .filter(m -> m.getId() > (long) a[1]).limit(tamano(a[2])).toList();
                    case "marcarLeidos" -> {
                        int n = 0;
                        for (MensajeChat m : de((UUID) a[0])) {
                            if (m.isDeAdmin() == (boolean) a[1] && m.getLeidoEn() == null && m.getId() <= (long) a[2]) {
                                poner(m, "leidoEn", a[3]);
                                n++;
                            }
                        }
                        yield n;
                    }
                    case "countByUsuarioIdAndDeAdminAndLeidoEnIsNull" -> de((UUID) a[0]).stream()
                            .filter(m -> m.isDeAdmin() == (boolean) a[1] && m.getLeidoEn() == null).count();
                    case "countByDeAdminFalseAndLeidoEnIsNull" -> filas.stream()
                            .filter(m -> !m.isDeAdmin() && m.getLeidoEn() == null).count();
                    case "conversacionesSinLeer" -> filas.stream()
                            .filter(m -> !m.isDeAdmin() && m.getLeidoEn() == null).map(MensajeChat::getUsuarioId).distinct().count();
                    case "resumenPorConversacion" -> filas.stream().map(MensajeChat::getUsuarioId).distinct().map(u -> {
                        List<MensajeChat> l = de(u);
                        MensajeChat ultimo = l.get(l.size() - 1);
                        long sinLeer = l.stream().filter(m -> !m.isDeAdmin() && m.getLeidoEn() == null).count();
                        // Como la base: el uuid puede llegar como texto.
                        return new Object[] { u.toString(), ultimo.getId(), ultimo.getCreadoEn(), sinLeer };
                    }).toList();
                    case "borrarConversacion" -> {
                        int antes = filas.size();
                        filas.removeIf(m -> m.getUsuarioId().equals(a[0]));
                        yield antes - filas.size();
                    }
                    default -> throw new UnsupportedOperationException(metodo.getName());
                });
    }

    private Repositorios.Usuarios repoDeCuentas() {
        return (Repositorios.Usuarios) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { Repositorios.Usuarios.class }, (proxy, metodo, a) -> switch (metodo.getName()) {
                    case "findByChatHabilitadoTrueOrderByVistoEnDesc" ->
                            cuentas.values().stream().filter(Usuario::isChatHabilitado).toList();
                    case "findAllById" -> {
                        List<Usuario> l = new ArrayList<>();
                        for (Object id : (Iterable<?>) a[0]) if (cuentas.containsKey(id)) l.add(cuentas.get(id));
                        yield l;
                    }
                    default -> throw new UnsupportedOperationException(metodo.getName());
                });
    }
}
