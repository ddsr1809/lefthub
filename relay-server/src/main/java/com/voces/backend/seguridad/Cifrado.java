package com.voces.backend.seguridad;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Las columnas cifradas, vistas desde las entidades.
 *
 * Cada entidad marca la columna con {@code @Convert(converter = ...)} y sigue
 * trabajando con el texto legible: Hibernate cifra al escribir y descifra al
 * leer. Ver CifradorDeDatos para el formato y las llaves.
 *
 * Lo que no se puede hacer con una columna cifrada es buscar dentro de ella
 * desde una consulta (like, lower, order by): el texto guardado es distinto
 * cada vez. Para buscar por igualdad está la huella de al lado.
 */
public abstract class Cifrado implements AttributeConverter<String, String> {

    private final String campo;

    protected Cifrado(String campo) {
        this.campo = campo;
    }

    @Override
    public String convertToDatabaseColumn(String texto) {
        return texto == null ? null : CifradorDeDatos.actual().cifrar(texto, campo);
    }

    @Override
    public String convertToEntityAttribute(String guardado) {
        return guardado == null ? null : CifradorDeDatos.actual().descifrar(guardado, campo);
    }

    @Converter
    public static class Correo extends Cifrado {
        public Correo() { super(CifradorDeDatos.CORREO); }
    }

    @Converter
    public static class Ip extends Cifrado {
        public Ip() { super(CifradorDeDatos.IP); }
    }

    @Converter
    public static class TokenDeApple extends Cifrado {
        public TokenDeApple() { super(CifradorDeDatos.APPLE); }
    }

    @Converter
    public static class Comprobante extends Cifrado {
        public Comprobante() { super(CifradorDeDatos.COMPROBANTE); }
    }

    @Converter
    public static class Orden extends Cifrado {
        public Orden() { super(CifradorDeDatos.ORDEN); }
    }

    @Converter
    public static class AutorDeAlta extends Cifrado {
        public AutorDeAlta() { super(CifradorDeDatos.AUTOR_DE_ALTA); }
    }
}
