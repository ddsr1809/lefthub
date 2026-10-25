package com.voces.backend.directorio;

import com.voces.backend.directorio.ConsultaDeUsuarios.Consulta;
import com.voces.backend.directorio.ConsultaDeUsuarios.Filtro;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * La lista de la sección Usuarios del panel: qué filtra cada casilla, cómo se
 * combinan, en qué orden salen, y que lo que manda el panel nunca se pegue al
 * texto de la consulta. Sin base de datos: se revisa el texto.
 */
class ConsultaDeUsuariosTest {

    private static final Instant AHORA = Instant.parse("2026-10-21T14:00:00Z");
    private static final Filtro TODO = new Filtro(List.of(), List.of(), "", "");

    @Test
    @DisplayName("Sin filtros: todas las cuentas, las que abrieron la app más recientemente primero")
    void sinFiltros() {
        Consulta c = ConsultaDeUsuarios.armar(TODO, "visto", false, AHORA);
        assertEquals("select u from Usuario u order by case when u.vistoEn is null then 1 else 0 end,"
                + " u.vistoEn desc, u.vistoEn desc, u.id", c.jpql());
        assertEquals("select count(u) from Usuario u", c.cuenta());
        assertTrue(c.parametros().isEmpty());
    }

    @Test
    @DisplayName("Varios tipos a la vez: vale cualquiera de ellos")
    void variosTipos() {
        Consulta c = ConsultaDeUsuarios.armar(new Filtro(List.of("google", "apple"), List.of(), "", ""), "visto", false, AHORA);
        assertTrue(c.jpql().contains(" where u.proveedor in :tipos "));
        assertEquals(List.of("apple", "google"), c.parametros().get("tipos"));
        assertTrue(c.cuenta().endsWith(" where u.proveedor in :tipos"), "la cuenta usa los mismos filtros");
    }

    @Test
    @DisplayName("Los tres tipos marcados es lo mismo que ninguno; uno desconocido se ignora")
    void tiposQueNoFiltran() {
        assertFalse(ConsultaDeUsuarios.armar(new Filtro(List.of("anonimo", "google", "apple"), List.of(), "", ""),
                "visto", false, AHORA).jpql().contains(" where "));
        Consulta c = ConsultaDeUsuarios.armar(new Filtro(List.of("google", "' or 1=1 --"), List.of(), "", ""), "visto", false, AHORA);
        assertEquals(List.of("google"), c.parametros().get("tipos"));
        assertFalse(c.jpql().contains("1=1"));
    }

    @Test
    @DisplayName("Varias marcas a la vez: tiene que cumplirlas todas")
    void variasMarcas() {
        Consulta c = ConsultaDeUsuarios.armar(
                new Filtro(List.of("google"), List.of("bot", "siguen", "activos7"), "", ""), "visto", false, AHORA);
        assertTrue(c.jpql().contains(" where u.proveedor in :tipos and u.vistoEn >= :hace7"
                + " and u.posibleBot = true and size(u.favoritos) > 0 order by"), c.jpql());
        assertEquals(AHORA.minusSeconds(7 * 86400), c.parametros().get("hace7"));
    }

    @Test
    @DisplayName("La llave de la app: con error junta mala, fuera de hora y repetida; sin datos es que no hay registro")
    void marcasDeLlave() {
        Consulta c = ConsultaDeUsuarios.armar(new Filtro(List.of(), List.of("firmamala", "checkfalta"), "", ""), "visto", false, AHORA);
        assertTrue(c.jpql().contains("u.llaveAppCheck = 'falta'"), c.jpql());
        assertTrue(c.jpql().contains("u.llaveFirma in ('mala', 'hora', 'repetida')"), c.jpql());
        assertTrue(ConsultaDeUsuarios.armar(new Filtro(List.of(), List.of("llavesindatos"), "", ""), "visto", false, AHORA)
                .jpql().contains(" where u.llaveVistaEn is null order by"));
    }

    @Test
    @DisplayName("Una marca desconocida no llega a la consulta")
    void marcaDesconocida() {
        Consulta c = ConsultaDeUsuarios.armar(new Filtro(List.of(), List.of("borrar", "admin"), "", ""), "visto", false, AHORA);
        assertTrue(c.jpql().contains(" where u.esAdmin = true order by"), c.jpql());
        assertFalse(c.jpql().contains("borrar"));
    }

    @Test
    @DisplayName("País y búsqueda van como parámetros; sin huellas, la búsqueda mira solo la compañía")
    void paisYTexto() {
        Consulta c = ConsultaDeUsuarios.armar(new Filtro(List.of(), List.of(), "MX", "%telcel%"), "visto", false, AHORA);
        assertTrue(c.jpql().contains(" where u.pais = :pais and (lower(u.red) like :texto) order by"), c.jpql());
        assertEquals("MX", c.parametros().get("pais"));
        assertEquals("%telcel%", c.parametros().get("texto"));
    }

    @Test
    @DisplayName("El correo y la IP, cifrados, se buscan por su huella exacta")
    void correoEIpPorHuella() {
        Consulta c = ConsultaDeUsuarios.armar(
                new Filtro(List.of(), List.of(), "", "%ana@x.mx%", "h-correo", "h-ip"), "visto", false, AHORA);
        assertTrue(c.jpql().contains(" where (u.emailHuella = :huellaCorreo or u.ipHuella = :huellaIp"
                + " or lower(u.red) like :texto) order by"), c.jpql());
        assertEquals("h-correo", c.parametros().get("huellaCorreo"));
        assertEquals("h-ip", c.parametros().get("huellaIp"));
        assertFalse(c.jpql().contains("u.email "), "nunca se compara la columna cifrada");
        assertFalse(c.jpql().contains("lower(u.email)"));
    }

    @Test
    @DisplayName("Cada columna ordena por su dato; lo que no lo tiene va al final")
    void ordenes() {
        assertTrue(ConsultaDeUsuarios.armar(TODO, "cuenta", true, AHORA).jpql()
                .contains("order by case when u.proveedor is null then 1 else 0 end, u.proveedor asc"),
                "el correo está cifrado: la columna Cuenta agrupa por cómo entra");
        assertTrue(ConsultaDeUsuarios.armar(TODO, "bot", false, AHORA).jpql()
                .contains("case when u.posibleBot = true then 1 else 0 end desc"), "los posibles bots primero");
        assertTrue(ConsultaDeUsuarios.armar(TODO, "sigue", false, AHORA).jpql().contains("size(u.favoritos) desc"));
        assertTrue(ConsultaDeUsuarios.armar(TODO, "alta", true, AHORA).jpql().contains("u.creadoEn asc"));
        assertTrue(ConsultaDeUsuarios.armar(TODO, "pais", true, AHORA).jpql().contains("u.pais asc"));
        assertTrue(ConsultaDeUsuarios.armar(TODO, "tipo", true, AHORA).jpql().contains("u.proveedor asc"));
        assertTrue(ConsultaDeUsuarios.armar(TODO, "cuenta", true, AHORA).jpql().endsWith(", u.vistoEn desc, u.id"),
                "a igualdad, un orden fijo: no cambia de una página a la siguiente");
    }

    @Test
    @DisplayName("Un orden que no existe no llega a la consulta")
    void ordenDesconocido() {
        String jpql = ConsultaDeUsuarios.armar(TODO, "u.id; delete from Usuario u", false, AHORA).jpql();
        assertFalse(jpql.contains("delete"));
        assertTrue(jpql.contains("u.vistoEn desc"));
    }

    private static Filtro versionesYLlaves(List<String> versiones, List<String> llaves) {
        return new Filtro(List.of(), List.of(), "", "", "", "", versiones, llaves);
    }

    @Test
    @DisplayName("Varias versiones de la app a la vez: vale cualquiera, como parámetros")
    void versiones() {
        Consulta c = ConsultaDeUsuarios.armar(versionesYLlaves(List.of("android:1.1.8", "android:1.2.2", "sin"), List.of()),
                "visto", false, AHORA);
        assertTrue(c.jpql().contains(" where ((u.appPlataforma = :vp0 and u.appVersion = :vv0)"
                + " or (u.appPlataforma = :vp1 and u.appVersion = :vv1) or u.appVersion is null) order by"), c.jpql());
        assertEquals("1.1.8", c.parametros().get("vv0"));
        assertEquals("1.2.2", c.parametros().get("vv1"));
        assertEquals("android", c.parametros().get("vp1"));
    }

    @Test
    @DisplayName("Una versión mal escrita o de otra plataforma no llega a la consulta")
    void versionesRaras() {
        Consulta c = ConsultaDeUsuarios.armar(versionesYLlaves(
                List.of("android:1.2.2' or '1'='1", "windows:1.0.0", "1.2.2", "ios:2.0"), List.of()), "visto", false, AHORA);
        assertTrue(c.jpql().contains(" where ((u.appPlataforma = :vp0 and u.appVersion = :vv0)) order by"), c.jpql());
        assertEquals("ios", c.parametros().get("vp0"));
        assertEquals("2.0", c.parametros().get("vv0"));
        assertFalse(c.jpql().contains("'1'='1"));
    }

    @Test
    @DisplayName("Por número de llave, o las que firman sin llave")
    void llaves() {
        Consulta c = ConsultaDeUsuarios.armar(versionesYLlaves(List.of(), List.of("2", "1", "sin", "x", "0")), "visto", false, AHORA);
        assertTrue(c.jpql().contains(" where (u.llaveNumero in :llaves or u.llaveNumero is null) order by"), c.jpql());
        assertEquals(List.of((short) 1, (short) 2), c.parametros().get("llaves"));

        Consulta solo = ConsultaDeUsuarios.armar(versionesYLlaves(List.of(), List.of("1")), "visto", false, AHORA);
        assertTrue(solo.jpql().contains(" where u.llaveNumero in :llaves order by"), solo.jpql());
    }

    @Test
    @DisplayName("Ordenar por versión de la app es por número, no por texto")
    void ordenPorVersion() {
        assertTrue(ConsultaDeUsuarios.armar(TODO, "app", false, AHORA).jpql()
                .contains("case when u.appVersionOrden is null then 1 else 0 end, u.appVersionOrden desc"));
        assertTrue(ConsultaDeUsuarios.armar(TODO, "llave", true, AHORA).jpql().contains("u.llaveNumero asc"));
    }
}
