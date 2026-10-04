package com.tuempresa.relay.directorio;

import com.tuempresa.relay.config.RelayProperties;
import com.tuempresa.relay.modelo.Conexion;
import com.tuempresa.relay.modelo.Creador;
import com.tuempresa.relay.modelo.Dtos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Las decisiones de la copia de creadores de producción a testing: quién
 * envía y quién recibe, con qué token, y sobre qué fila se guarda cada copia.
 * Lógica pura, sin red ni base de datos.
 */
class ReplicaTest {

    private static final String CANAL = "UCabcdefghijklmnopqrstuv";

    private static Creador creador(String nombre, UUID origen) {
        Creador c = new Creador();
        c.setId(UUID.randomUUID());
        c.setNombre(nombre);
        c.setOrigenId(origen);
        return c;
    }

    private static Dtos.GuardarCreador peticion(Map<String, Dtos.ConexionDto> conexiones) {
        return new Dtos.GuardarCreador(UUID.randomUUID(), "Canal Once", "noticias",
                null, null, conexiones, true);
    }

    // -------------------------------------------------------------------------
    // Quién envía y quién recibe
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Producción envía y no recibe; testing recibe y no envía; local, ninguna de las dos")
    void papeles() {
        var produccion = new RelayProperties.Replica("https://testapp.example", "secreto");
        assertTrue(produccion.envia());
        assertFalse(produccion.recibe(), "el token compartido no debe abrir producción");

        var testing = new RelayProperties.Replica("", "secreto");
        assertFalse(testing.envia());
        assertTrue(testing.recibe());

        var local = new RelayProperties.Replica("", "");
        assertFalse(local.envia());
        assertFalse(local.recibe());
    }

    @Test
    @DisplayName("El token tiene que coincidir entero, y sin token configurado no pasa nadie")
    void token() {
        assertTrue(ReplicaService.coincide("secreto", "secreto"));
        assertFalse(ReplicaService.coincide("secreto", "secret"));
        assertFalse(ReplicaService.coincide("secreto", null));
        assertFalse(ReplicaService.coincide("", ""));
        assertFalse(ReplicaService.coincide(null, null));
    }

    @Test
    @DisplayName("La URL de destino aguanta una barra final en REPLICA_URL")
    void destino() {
        String esperado = "https://testapp.example/internal/replica/creadores";
        assertEquals(esperado, ReplicaService.destino("https://testapp.example"));
        assertEquals(esperado, ReplicaService.destino("https://testapp.example/ "));
    }

    // -------------------------------------------------------------------------
    // Qué viaja
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Lo que se envía lleva el id de producción y todas las conexiones")
    void cuerpo() {
        Creador c = creador("Canal Once", null);
        c.setCategoria("noticias");
        c.setActivo(false);
        c.setConexiones(Map.of("youtube",
                new Conexion("https://www.youtube.com/@CanalOnceIPN", "CanalOnceIPN", CANAL)));

        Dtos.GuardarCreador cuerpo = ReplicaService.cuerpoDe(c);

        assertEquals(c.getId(), cuerpo.id());
        assertEquals("Canal Once", cuerpo.nombre());
        assertEquals("noticias", cuerpo.categoria());
        assertFalse(cuerpo.estaActivo());
        assertEquals(CANAL, cuerpo.conexiones().get("youtube").channelId());
        assertEquals(CANAL, ReplicaService.canalDe(cuerpo));
    }

    @Test
    @DisplayName("Un creador sin YouTube, o con el canal en blanco, no tiene canal que buscar")
    void sinCanal() {
        assertNull(ReplicaService.canalDe(peticion(null)));
        assertNull(ReplicaService.canalDe(peticion(Map.of("tiktok",
                new Dtos.ConexionDto("tiktok", "https://tiktok.com/@x", null, null)))));
        assertNull(ReplicaService.canalDe(peticion(Map.of("youtube",
                new Dtos.ConexionDto("youtube", "https://youtube.com/@x", "x", " ")))));
    }

    // -------------------------------------------------------------------------
    // Sobre qué fila se guarda la copia
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Un creador que testing no conoce se crea con id propio")
    void nuevo() {
        Creador destino = ReplicaService.elegirDestino(null, null);
        assertNull(destino.getId());
        assertNull(destino.getOrigenId());
    }

    @Test
    @DisplayName("Una copia que ya existe se actualiza, aunque el creador cambie de canal")
    void yaCopiado() {
        Creador copia = creador("Canal Once", UUID.randomUUID());

        assertSame(copia, ReplicaService.elegirDestino(copia, null));
        assertSame(copia, ReplicaService.elegirDestino(copia, copia));
    }

    @Test
    @DisplayName("Un creador dado de alta a mano en testing con ese canal se adopta, no se duplica")
    void adoptado() {
        Creador aMano = creador("Once (prueba)", null);
        assertSame(aMano, ReplicaService.elegirDestino(null, aMano));
    }

    @Test
    @DisplayName("Nunca quedan dos creadores con el mismo canal de YouTube")
    void canalOcupado() {
        Creador copia = creador("Canal Once", UUID.randomUUID());
        Creador otro = creador("Otro", null);
        Creador copiaDeOtro = creador("Copia de otro", UUID.randomUUID());

        var porOtro = assertThrows(ResponseStatusException.class,
                () -> ReplicaService.elegirDestino(copia, otro));
        assertEquals(409, porOtro.getStatusCode().value());
        assertTrue(porOtro.getReason().contains("Otro"));

        var porCopia = assertThrows(ResponseStatusException.class,
                () -> ReplicaService.elegirDestino(null, copiaDeOtro));
        assertEquals(409, porCopia.getStatusCode().value());
    }
}
