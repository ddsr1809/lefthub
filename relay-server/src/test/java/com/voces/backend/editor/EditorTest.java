package com.voces.backend.editor;

import com.voces.backend.modelo.Canal;
import com.voces.backend.modelo.Dtos;
import com.voces.backend.modelo.Usuario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

/** Quién puede agregar al directorio desde la app y cómo se limpia lo que manda. */
class EditorTest {

    private static Usuario cuenta(String proveedor, boolean editor, boolean admin) {
        Usuario u = new Usuario();
        u.setProveedor(proveedor);
        u.setEditorHabilitado(editor);
        u.setEsAdmin(admin);
        return u;
    }

    @Test
    @DisplayName("Empieza apagado: ni un administrador puede si el panel no lo autorizó")
    void apagadoDeEntrada() {
        assertFalse(EditorService.puede(cuenta(Usuario.GOOGLE, false, false)));
        assertFalse(EditorService.puede(cuenta(Usuario.GOOGLE, false, true)), "ser admin no basta");
        assertFalse(EditorService.puede(null));
    }

    @Test
    @DisplayName("Una cuenta guardada y autorizada puede; una de invitado, aunque la marquen, no")
    void autorizada() {
        assertTrue(EditorService.puede(cuenta(Usuario.GOOGLE, true, false)));
        assertTrue(EditorService.puede(cuenta(Usuario.APPLE, true, false)));
        assertTrue(EditorService.puede(cuenta(Usuario.GOOGLE, true, true)));
        assertFalse(EditorService.puede(cuenta(Usuario.ANONIMO, true, false)));
    }

    @Test
    @DisplayName("El nombre se limpia, se recorta a 60 y uno vacío o de una letra no vale")
    void nombre() {
        assertEquals("Rosa Luna", EditorService.nombre("  Rosa   Luna ", "falta"));
        assertEquals(60, EditorService.nombre("a".repeat(100), "falta").length());
        assertThrows(ResponseStatusException.class, () -> EditorService.nombre("   ", "falta"));
        assertThrows(ResponseStatusException.class, () -> EditorService.nombre(null, "falta"));
        assertThrows(ResponseStatusException.class, () -> EditorService.nombre("A", "falta"));
    }

    @Test
    @DisplayName("La descripción es opcional y no pasa de 600")
    void descripcion() {
        assertNull(EditorService.descripcion("  "));
        assertNull(EditorService.descripcion(null));
        assertEquals(600, EditorService.descripcion("b".repeat(900)).length());
    }

    @Test
    @DisplayName("El canal se guarda como lo guarda el panel: con su @handle si lo tiene")
    void canalPedido() {
        String id = "UCabcdefghijklmnopqrstuv";
        Dtos.GuardarCanal conHandle = EditorService.canalPedido(
                new Dtos.DatosDeCanal(id, "Rosa", null, null, "@rosaluna", "100"));
        assertEquals(Canal.YOUTUBE, conHandle.plataforma());
        assertEquals("rosaluna", conHandle.handle());
        assertEquals("https://www.youtube.com/@rosaluna", conHandle.url());
        assertEquals(id, conHandle.channelId());

        Dtos.GuardarCanal sinHandle = EditorService.canalPedido(
                new Dtos.DatosDeCanal(id, "Rosa", null, null, null, null));
        assertNull(sinHandle.handle());
        assertEquals("https://www.youtube.com/channel/" + id, sinHandle.url());
    }

    @Test
    @DisplayName("La arroba del handle sobra")
    void sinArroba() {
        assertEquals("rosa", EditorService.sinArroba("@rosa"));
        assertEquals("rosa", EditorService.sinArroba("rosa"));
        assertNull(EditorService.sinArroba("@"));
        assertNull(EditorService.sinArroba(" "));
    }
}
