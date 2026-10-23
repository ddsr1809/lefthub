package com.voces.backend.seguridad;

import org.springframework.boot.autoconfigure.orm.jpa.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.stereotype.Component;

/**
 * La base no se abre hasta que el cifrador de datos está listo.
 *
 * Los convertidores de las columnas cifradas le piden el cifrador a
 * CifradorDeDatos.actual(). Si algo leyera una cuenta durante el arranque
 * antes de que Spring hubiera creado el cifrador, fallaría. Con esto Spring
 * crea primero el cifrador y después JPA, igual que hace con Flyway.
 */
@Component
public class JpaEsperaAlCifrador extends EntityManagerFactoryDependsOnPostProcessor {

    public JpaEsperaAlCifrador() {
        super(CifradorDeDatos.class);
    }
}
