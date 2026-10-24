package com.voces.backend.directorio;

import com.voces.backend.modelo.Canal;
import com.voces.backend.modelo.Clase;
import com.voces.backend.modelo.Creador;
import com.voces.backend.modelo.Dtos;
import com.voces.backend.modelo.Productora;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** La copia a testing lleva el tipo de cuenta del creador, del medio y de cada canal. */
class ClaseEnReplicaTest {

    private static Canal canal(String clase) {
        Canal k = new Canal();
        k.setId(UUID.randomUUID());
        k.setPlataforma(Canal.YOUTUBE);
        k.setUrl("https://www.youtube.com/channel/UC1");
        k.setChannelId("UC1");
        k.setClase(clase);
        return k;
    }

    @Test
    void elCreadorYSusCanales() {
        Creador c = new Creador();
        c.setId(UUID.randomUUID());
        c.setNombre("Ana");
        c.setClase(Clase.POLITICA);

        Dtos.GuardarCreador cuerpo = ReplicaService.cuerpoDe(c, List.of(canal(null), canal(Clase.GENERAL)));
        assertEquals(Clase.POLITICA, cuerpo.clase());
        assertEquals("", cuerpo.canales().get(0).clase(), "sin clase propia viaja vacía: la de su dueño");
        assertEquals(Clase.GENERAL, cuerpo.canales().get(1).clase());
    }

    @Test
    void elMedio() {
        Productora p = new Productora();
        p.setId(UUID.randomUUID());
        p.setNombre("Canal Once");
        p.setClase(Clase.TELEVISION_PUBLICA);
        assertEquals(Clase.TELEVISION_PUBLICA, ReplicaService.cuerpoDe(p, List.of()).clase());
    }
}
