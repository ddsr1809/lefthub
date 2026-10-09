package com.voces.backend.directorio;

import com.voces.backend.modelo.Ajuste;
import com.voces.backend.modelo.Repositorios;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Los ajustes generales que el equipo cambia desde el panel.
 *
 * Son dos: si la app muestra los videos cortos (Shorts) y si muestra
 * anuncios. Se leen de la base cada vez, sin caché: es una consulta por clave
 * primaria, y así un cambio en el panel vale desde la siguiente petición, sin
 * reiniciar nada.
 */
@Service
public class AjustesService {

    /** Si la app muestra los videos cortos. Sin fila en la tabla, no. */
    static final String CORTOS = "cortos";

    /** Si la app muestra anuncios en Novedades. Sin fila en la tabla, no. */
    static final String ANUNCIOS = "anuncios";

    private final Repositorios.Ajustes ajustes;

    public AjustesService(Repositorios.Ajustes ajustes) {
        this.ajustes = ajustes;
    }

    /**
     * ¿Están permitidos los videos cortos?
     *
     * Apagado (lo normal mientras nadie lo encienda), un Short se guarda pero
     * ni avisa ni sale en ningún sitio, y la app no ofrece la opción.
     */
    @Transactional(readOnly = true)
    public boolean cortos() {
        return encendido(CORTOS);
    }

    @Transactional
    public void ponerCortos(boolean permitidos) {
        poner(CORTOS, permitidos);
    }

    /**
     * ¿Muestra anuncios la app?
     *
     * Apagado (lo normal mientras nadie lo encienda), la app no pide ningún
     * anuncio ni ofrece quitarlos. Encendido, los ve todo el mundo menos las
     * cuentas que los quitaron: eso lo dice cada cuenta, no este ajuste.
     */
    @Transactional(readOnly = true)
    public boolean anuncios() {
        return encendido(ANUNCIOS);
    }

    @Transactional
    public void ponerAnuncios(boolean encendidos) {
        poner(ANUNCIOS, encendidos);
    }

    private boolean encendido(String clave) {
        return ajustes.findById(clave).map(a -> "true".equals(a.getValor())).orElse(false);
    }

    private void poner(String clave, boolean valor) {
        Ajuste ajuste = ajustes.findById(clave).orElseGet(() -> new Ajuste(clave));
        ajuste.setValor(String.valueOf(valor));
        ajuste.setActualizadoEn(Instant.now());
        ajustes.save(ajuste);
    }
}
