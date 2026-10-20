package com.voces.backend.directorio;

import com.voces.backend.modelo.Ajuste;
import com.voces.backend.modelo.Repositorios;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Los ajustes generales que el equipo cambia desde el panel.
 *
 * Son cuatro: si la app muestra los videos cortos (Shorts), si muestra
 * anuncios, si ofrece comprobar las suscripciones de YouTube y quién puede
 * recomendar canales. Se leen de la base cada vez, sin caché: es una consulta por clave
 * primaria, y así un cambio en el panel vale desde la siguiente petición, sin
 * reiniciar nada.
 */
@Service
public class AjustesService {

    /** Si la app muestra los videos cortos. Sin fila en la tabla, no. */
    static final String CORTOS = "cortos";

    /** Si la app muestra anuncios en Novedades. Sin fila en la tabla, no. */
    static final String ANUNCIOS = "anuncios";

    /** Si la app ofrece comprobar las suscripciones de YouTube. Sin fila en la tabla, no. */
    static final String YOUTUBE = "youtube";

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

    /**
     * ¿Ofrece la app comprobar las suscripciones de YouTube?
     *
     * Pide el permiso youtube.readonly, que Google trata como sensible.
     * Mientras el proyecto no tenga aprobada la verificación de OAuth, solo
     * 100 cuentas en toda su vida pueden darlo y Google les enseña un aviso de
     * "app no verificada". Por eso va apagado hasta que la aprueben: así se
     * enciende sin publicar otra versión de la app.
     */
    @Transactional(readOnly = true)
    public boolean youtube() {
        return encendido(YOUTUBE);
    }

    @Transactional
    public void ponerYoutube(boolean encendido) {
        poner(YOUTUBE, encendido);
    }

    // -------------------------------------------------------------------------
    // Recomendaciones de canales
    // -------------------------------------------------------------------------

    /** Quién puede recomendar canales. Sin fila en la tabla, nadie. */
    static final String RECOMENDACIONES = "recomendaciones";

    /** Nadie, ni los administradores. Es como empieza. */
    public static final String RECOMENDAR_APAGADO = "apagado";
    /** Solo los administradores: para probarlo antes de abrirlo. */
    public static final String RECOMENDAR_ADMINS = "admins";
    /** Los administradores y las cuentas que el equipo eligió una por una. */
    public static final String RECOMENDAR_ELEGIDOS = "elegidos";
    /** Cualquier cuenta. */
    public static final String RECOMENDAR_TODOS = "todos";

    public static boolean alcanceValido(String alcance) {
        return RECOMENDAR_APAGADO.equals(alcance) || RECOMENDAR_ADMINS.equals(alcance)
                || RECOMENDAR_ELEGIDOS.equals(alcance) || RECOMENDAR_TODOS.equals(alcance);
    }

    /**
     * Quién puede recomendar canales: apagado, admins, elegidos o todos.
     *
     * Apagado (lo normal mientras nadie lo encienda), la app no enseña la
     * sección a nadie, tampoco a los administradores. Un valor que no se
     * reconoce cuenta como apagado.
     */
    @Transactional(readOnly = true)
    public String recomendaciones() {
        String valor = ajustes.findById(RECOMENDACIONES).map(Ajuste::getValor).orElse(RECOMENDAR_APAGADO);
        return alcanceValido(valor) ? valor : RECOMENDAR_APAGADO;
    }

    @Transactional
    public void ponerRecomendaciones(String alcance) {
        if (!alcanceValido(alcance)) {
            throw new IllegalArgumentException("Alcance desconocido: " + alcance);
        }
        Ajuste ajuste = ajustes.findById(RECOMENDACIONES).orElseGet(() -> new Ajuste(RECOMENDACIONES));
        ajuste.setValor(alcance);
        ajuste.setActualizadoEn(Instant.now());
        ajustes.save(ajuste);
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
