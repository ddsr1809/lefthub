package com.tuempresa.relay.directorio;

import com.tuempresa.relay.modelo.Ajuste;
import com.tuempresa.relay.modelo.Repositorios;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Los ajustes generales que el equipo cambia desde el panel.
 *
 * Por ahora uno: si la app muestra los videos cortos (Shorts). Se lee de la
 * base cada vez, sin caché: es una consulta por clave primaria, y así un
 * cambio en el panel vale desde la siguiente petición, sin reiniciar nada.
 */
@Service
public class AjustesService {

    /** Si la app muestra los videos cortos. Sin fila en la tabla, no. */
    static final String CORTOS = "cortos";

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
        return ajustes.findById(CORTOS).map(a -> "true".equals(a.getValor())).orElse(false);
    }

    @Transactional
    public void ponerCortos(boolean permitidos) {
        Ajuste ajuste = ajustes.findById(CORTOS).orElseGet(() -> new Ajuste(CORTOS));
        ajuste.setValor(String.valueOf(permitidos));
        ajuste.setActualizadoEn(Instant.now());
        ajustes.save(ajuste);
    }
}
