package com.voces.backend.directorio;

import com.voces.backend.directorio.CanalesService.Cambio;
import com.voces.backend.directorio.CanalesService.Plan;
import com.voces.backend.modelo.Canal;
import com.voces.backend.modelo.Dtos;
import com.voces.backend.modelo.Suscripcion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Las decisiones al guardar los canales de un creador o de una productora:
 * qué fila se reutiliza, qué se da de alta y de baja en el hub, y cómo se
 * entiende a quien todavía manda un solo enlace por plataforma.
 * Lógica pura, sin red ni base de datos.
 */
class CanalesTest {

    private static final String PRINCIPAL = "UCabcdefghijklmnopqrstuv";
    private static final String CLIPS = "UCzyxwvutsrqponmlkjihgfe";
    private static final String OTRO = "UC0123456789abcdefghijkl";

    private static Canal canal(String plataforma, String url, String channelId) {
        Canal k = new Canal();
        k.setId(UUID.randomUUID());
        k.setPlataforma(plataforma);
        k.setUrl(url);
        k.setChannelId(channelId);
        return k;
    }

    private static Dtos.GuardarCanal pedido(UUID id, String plataforma, String url, String channelId) {
        return new Dtos.GuardarCanal(id, plataforma, null, url, null, channelId, null, null);
    }

    // -------------------------------------------------------------------------
    // Qué fila se reutiliza
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Un canal que se edita conserva su fila, aunque cambie de enlace y de ID")
    void porId() {
        Canal a = canal("youtube", "https://youtube.com/@a", PRINCIPAL);

        Plan plan = CanalesService.planear(List.of(a),
                List.of(pedido(a.getId(), "youtube", "https://youtube.com/@nuevo", OTRO)));

        assertSame(a, plan.asignaciones().get(0).existente());
        assertTrue(plan.sobrantes().isEmpty());
    }

    @Test
    @DisplayName("Sin id, el canal se reconoce por su ID de YouTube y, si no tiene, por el enlace")
    void sinId() {
        Canal yt = canal("youtube", "https://youtube.com/@a", PRINCIPAL);
        Canal tiktok = canal("tiktok", "https://tiktok.com/@a", null);

        Plan plan = CanalesService.planear(List.of(yt, tiktok), List.of(
                pedido(null, "tiktok", "https://tiktok.com/@a", null),
                pedido(UUID.randomUUID(), "youtube", "https://youtube.com/channel/" + PRINCIPAL, PRINCIPAL)));

        assertSame(tiktok, plan.asignaciones().get(0).existente());
        assertSame(yt, plan.asignaciones().get(1).existente(),
                "un id de otro servidor no impide reconocerlo por su channel_id");
        assertTrue(plan.sobrantes().isEmpty());
    }

    @Test
    @DisplayName("Lo que no estaba es nuevo, y lo que no se pide sobra")
    void altasYBajas() {
        Canal principal = canal("youtube", "https://youtube.com/@a", PRINCIPAL);
        Canal viejo = canal("twitch", "https://twitch.tv/a", null);

        Plan plan = CanalesService.planear(List.of(principal, viejo), List.of(
                pedido(principal.getId(), "youtube", "https://youtube.com/@a", PRINCIPAL),
                pedido(null, "youtube", "https://youtube.com/@a-clips", CLIPS)));

        assertSame(principal, plan.asignaciones().get(0).existente());
        assertNull(plan.asignaciones().get(1).existente());
        assertEquals(List.of(viejo), plan.sobrantes());
    }

    @Test
    @DisplayName("Un pedido sin id no le quita la fila a otro que sí la nombra")
    void elIdMandaSobreElEnlace() {
        Canal a = canal("web", "https://ejemplo.mx", null);
        Canal b = canal("web", "https://ejemplo.mx/blog", null);

        // El primero no trae id y su enlace coincide con el de `a`; el
        // segundo nombra a `a` por id y le cambia el enlace.
        Plan plan = CanalesService.planear(List.of(a, b), List.of(
                pedido(null, "web", "https://ejemplo.mx", null),
                pedido(a.getId(), "web", "https://ejemplo.mx/nuevo", null)));

        assertSame(a, plan.asignaciones().get(1).existente());
        assertNull(plan.asignaciones().get(0).existente());
        assertEquals(List.of(b), plan.sobrantes());
    }

    // -------------------------------------------------------------------------
    // Limpieza de lo que manda el panel
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Se recortan los espacios, y lo que queda vacío se guarda como ausente")
    void normaliza() {
        List<Dtos.GuardarCanal> limpios = CanalesService.normalizar(List.of(
                new Dtos.GuardarCanal(null, " youtube ", "  ", " https://youtube.com/@a ", "", " " + PRINCIPAL + " ", null, null)));

        Dtos.GuardarCanal k = limpios.get(0);
        assertEquals("youtube", k.plataforma());
        assertNull(k.nombre());
        assertEquals("https://youtube.com/@a", k.url());
        assertNull(k.handle());
        assertEquals(PRINCIPAL, k.channelId());
    }

    @Test
    @DisplayName("Sin lista no hay canales; con una plataforma inventada, sin enlace o con un canal repetido, no se guarda")
    void rechaza() {
        assertTrue(CanalesService.normalizar(null).isEmpty());

        assertEquals(400, assertThrows(ResponseStatusException.class, () -> CanalesService.normalizar(
                List.of(pedido(null, "myspace", "https://myspace.com/a", null)))).getStatusCode().value());

        assertEquals(400, assertThrows(ResponseStatusException.class, () -> CanalesService.normalizar(
                List.of(pedido(null, "youtube", "  ", PRINCIPAL)))).getStatusCode().value());

        assertEquals(400, assertThrows(ResponseStatusException.class, () -> CanalesService.normalizar(List.of(
                pedido(null, "youtube", "https://youtube.com/@a", PRINCIPAL),
                pedido(null, "youtube", "https://youtube.com/@b", PRINCIPAL)))).getStatusCode().value());
    }

    @Test
    @DisplayName("Con qué creadores aparece un canal: sin lista no se toca; con lista, sin repetidos ni huecos")
    void creadoresDelCanal() {
        UUID ana = UUID.randomUUID();

        List<Dtos.GuardarCanal> limpios = CanalesService.normalizar(List.of(
                new Dtos.GuardarCanal(null, "youtube", null, "https://youtube.com/@a", null, PRINCIPAL, null, null),
                new Dtos.GuardarCanal(null, "youtube", null, "https://youtube.com/@b", null, CLIPS, null,
                        java.util.Arrays.asList(ana, null, ana))));

        assertNull(limpios.get(0).creadores());
        assertEquals(List.of(ana), limpios.get(1).creadores());
    }

    @Test
    @DisplayName("Guardar \"lo mismo que había\" conserva con quién aparece el canal")
    void comoPedidoLlevaLosCreadores() {
        UUID ana = UUID.randomUUID();
        Canal oficial = canal("youtube", "https://youtube.com/@casa", PRINCIPAL);
        oficial.getVinculados().add(ana);

        assertEquals(List.of(ana), CanalesService.comoPedido(oficial).creadores());
    }

    @Test
    @DisplayName("El nombre de un canal no pasa de 60 caracteres")
    void nombreLargo() {
        String largo = "x".repeat(200);
        List<Dtos.GuardarCanal> limpios = CanalesService.normalizar(List.of(
                new Dtos.GuardarCanal(null, "web", largo, "https://ejemplo.mx", null, null, null, null)));

        assertEquals(CanalesService.MAXIMO_NOMBRE, limpios.get(0).nombre().length());
    }

    // -------------------------------------------------------------------------
    // El formato anterior: un enlace por plataforma
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("El formato anterior actualiza el canal principal y deja en paz a los demás")
    void formatoAnterior() {
        UUID productora = UUID.randomUUID();

        Canal principal = canal("youtube", "https://youtube.com/@a", PRINCIPAL);
        principal.setProductoraId(productora);
        Canal clips = canal("youtube", "https://youtube.com/@a-clips", CLIPS);
        clips.setNombre("Clips");

        List<Dtos.GuardarCanal> pedidos = CanalesService.desdeConexiones(
                List.of(principal, clips),
                Map.of("youtube", new Dtos.ConexionDto("youtube", "https://youtube.com/@nuevo", "nuevo", OTRO),
                       "tiktok", new Dtos.ConexionDto("tiktok", "https://tiktok.com/@a", null, null)));

        assertEquals(3, pedidos.size());

        // El principal: misma fila, misma productora, datos nuevos.
        assertEquals(principal.getId(), pedidos.get(0).id());
        assertEquals(OTRO, pedidos.get(0).channelId());
        assertEquals(productora, pedidos.get(0).productoraId());

        // El de clips, que ese formato no sabe nombrar: tal cual.
        assertEquals(clips.getId(), pedidos.get(1).id());
        assertEquals(CLIPS, pedidos.get(1).channelId());
        assertEquals("Clips", pedidos.get(1).nombre());

        // Una plataforma que no tenía: canal nuevo.
        assertNull(pedidos.get(2).id());
        assertEquals("tiktok", pedidos.get(2).plataforma());
    }

    @Test
    @DisplayName("Si el formato anterior ya no trae una plataforma, se va su canal principal y nada más")
    void formatoAnteriorQuita() {
        Canal principal = canal("youtube", "https://youtube.com/@a", PRINCIPAL);
        Canal clips = canal("youtube", "https://youtube.com/@a-clips", CLIPS);
        Canal web = canal("web", "https://ejemplo.mx", null);

        List<Dtos.GuardarCanal> pedidos = CanalesService.desdeConexiones(
                List.of(principal, clips, web), Map.of());

        assertEquals(1, pedidos.size());
        assertEquals(clips.getId(), pedidos.get(0).id());
    }

    @Test
    @DisplayName("El canal principal de cada plataforma es el primero, en el orden de siempre")
    void principales() {
        List<Dtos.ConexionDto> conexiones = Dtos.ConexionDto.principales(List.of(
                canal("tiktok", "https://tiktok.com/@a", null),
                canal("youtube", "https://youtube.com/@a", PRINCIPAL),
                canal("youtube", "https://youtube.com/@a-clips", CLIPS)));

        assertEquals(2, conexiones.size());
        assertEquals("youtube", conexiones.get(0).plataforma());
        assertEquals(PRINCIPAL, conexiones.get(0).channelId());
        assertEquals("tiktok", conexiones.get(1).plataforma());
    }

    // -------------------------------------------------------------------------
    // Qué se hace en el hub
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Visible: se dan de alta todos sus canales y de baja los que dejó")
    void cambioVisible() {
        Cambio cambio = Cambio.de(List.of(PRINCIPAL, OTRO), List.of(PRINCIPAL, CLIPS), true);

        assertEquals(Set.of(PRINCIPAL, CLIPS), cambio.altas());
        assertEquals(Set.of(OTRO), cambio.bajas());
    }

    @Test
    @DisplayName("Oculto: se dan de baja todos, los de antes y los de ahora")
    void cambioOculto() {
        Cambio cambio = Cambio.de(List.of(PRINCIPAL), List.of(PRINCIPAL, CLIPS), false);

        assertTrue(cambio.altas().isEmpty());
        assertEquals(Set.of(PRINCIPAL, CLIPS), cambio.bajas());
    }

    @Test
    @DisplayName("Sin canales de YouTube no hay nada que hacer en el hub")
    void cambioVacio() {
        assertTrue(Cambio.de(List.of(), List.of(), true).vacio());
        assertTrue(CanalesService.deYouTube(List.of(
                canal("tiktok", "https://tiktok.com/@a", null),
                canal("youtube", "https://youtube.com/@a", " "),
                canal("web", "https://ejemplo.mx", PRINCIPAL))).isEmpty(),
                "solo cuenta un canal de YouTube con su ID");
    }

    // -------------------------------------------------------------------------
    // El testigo del panel
    // -------------------------------------------------------------------------

    private static Suscripcion suscripcion(String estado, Instant expira) {
        Suscripcion s = new Suscripcion();
        s.setEstado(estado);
        s.setExpiraEn(expira);
        return s;
    }

    @Test
    @DisplayName("El testigo de un creador con varios canales enseña el que tiene problemas")
    void resumenConProblema() {
        List<Canal> canales = new ArrayList<>(List.of(
                canal("youtube", "https://youtube.com/@a", PRINCIPAL),
                canal("youtube", "https://youtube.com/@a-clips", CLIPS)));

        Suscripcion bien = suscripcion(Suscripcion.ACTIVA, Instant.now().plusSeconds(3600));
        Suscripcion mal = suscripcion(Suscripcion.ERROR, null);

        assertSame(mal, AdminController.resumenDe(canales, Map.of(PRINCIPAL, bien, CLIPS, mal)));
        assertSame(mal, AdminController.resumenDe(canales, Map.of(PRINCIPAL, mal, CLIPS, bien)));
    }

    @Test
    @DisplayName("Si todos están activos, enseña el que vence antes; sin YouTube, nada")
    void resumenSano() {
        List<Canal> canales = List.of(
                canal("youtube", "https://youtube.com/@a", PRINCIPAL),
                canal("youtube", "https://youtube.com/@a-clips", CLIPS));

        Suscripcion tarde = suscripcion(Suscripcion.ACTIVA, Instant.now().plusSeconds(9000));
        Suscripcion pronto = suscripcion(Suscripcion.ACTIVA, Instant.now().plusSeconds(60));

        assertSame(pronto, AdminController.resumenDe(canales, Map.of(PRINCIPAL, tarde, CLIPS, pronto)));
        assertNull(AdminController.resumenDe(
                List.of(canal("tiktok", "https://tiktok.com/@a", null)), Map.of()));
    }
}
