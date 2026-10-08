package com.tuempresa.relay.directorio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Qué se puede pedir borrar de golpe, y qué no. */
class BorradoTest {

    @Test
    @DisplayName("Las partes pedidas quedan sin repetir y siempre en el mismo orden")
    void partes() {
        assertEquals(List.of("creadores", "productoras"),
                new ArrayList<>(BorradoService.validar(List.of("productoras", "creadores", "productoras"))));
        // El orden fijo importa: lo confirmado tiene que ser igual a lo contado.
        assertEquals(BorradoService.validar(List.of("reportes", "creadores")),
                BorradoService.validar(List.of("creadores", "reportes")));
    }

    @Test
    @DisplayName("Sin marcar nada no hay nada que borrar")
    void vacio() {
        assertThrows(ResponseStatusException.class, () -> BorradoService.validar(null));
        assertThrows(ResponseStatusException.class, () -> BorradoService.validar(List.of()));
    }

    @Test
    @DisplayName("Usuarios, administradores o ajustes no se borran por aquí")
    void loQueNo() {
        for (String parte : List.of("usuarios", "administradores", "ajustes", "todo", "", "CREADORES")) {
            assertThrows(ResponseStatusException.class, () -> BorradoService.validar(List.of(parte)), parte);
        }
        assertThrows(ResponseStatusException.class, () -> BorradoService.validar(Arrays.asList("creadores", null)));
        assertEquals(Set.of("creadores", "productoras", "publicaciones", "reportes"), BorradoService.PARTES);
    }
}
