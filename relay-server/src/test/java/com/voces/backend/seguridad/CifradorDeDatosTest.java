package com.voces.backend.seguridad;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * El cifrado de los datos personales: que lo cifrado se lea, que no se pueda
 * mover de columna, que cambiar de llave no pierda nada, y que una llave que
 * falta nunca convierta un dato en null.
 */
class CifradorDeDatosTest {

    private static final String LLAVE_1 = "1:" + base64(1);
    private static final String LLAVE_2 = "2:" + base64(2);
    private static final String INDICE = base64(9);

    private static String base64(int semilla) {
        byte[] b = new byte[32];
        for (int i = 0; i < b.length; i++) b[i] = (byte) (semilla * 31 + i);
        return Base64.getEncoder().encodeToString(b);
    }

    private static CifradorDeDatos cifrador(String llaves) {
        return new CifradorDeDatos(llaves, INDICE);
    }

    @Test
    @DisplayName("Lo cifrado se lee igual, y no se parece a lo original")
    void idaYVuelta() {
        CifradorDeDatos c = cifrador(LLAVE_1);
        String guardado = c.cifrar("ana@ejemplo.mx", CifradorDeDatos.CORREO);

        assertTrue(guardado.startsWith("c1:1:"), guardado);
        assertFalse(guardado.contains("ana"), "el correo no puede verse en la base");
        assertEquals("ana@ejemplo.mx", c.descifrar(guardado, CifradorDeDatos.CORREO));
    }

    @Test
    @DisplayName("El mismo dato cifrado dos veces da textos distintos")
    void ivAlAzar() {
        CifradorDeDatos c = cifrador(LLAVE_1);
        assertNotEquals(c.cifrar("10.0.0.1", CifradorDeDatos.IP), c.cifrar("10.0.0.1", CifradorDeDatos.IP));
    }

    @Test
    @DisplayName("Un valor copiado a otra columna no se descifra: se queda como estaba")
    void ligadoALaColumna() {
        CifradorDeDatos c = cifrador(LLAVE_1);
        String correo = c.cifrar("ana@ejemplo.mx", CifradorDeDatos.CORREO);
        assertEquals(correo, c.descifrar(correo, CifradorDeDatos.IP));
        assertNull(c.intentarDescifrar(correo, CifradorDeDatos.IP));
    }

    @Test
    @DisplayName("Con una llave nueva se cifra con ella, y lo anterior se sigue leyendo")
    void cambioDeLlave() {
        String viejo = cifrador(LLAVE_1).cifrar("secreto", CifradorDeDatos.APPLE);
        CifradorDeDatos nuevo = cifrador(LLAVE_1 + "," + LLAVE_2);

        assertEquals("secreto", nuevo.descifrar(viejo, CifradorDeDatos.APPLE));
        assertTrue(nuevo.cifrar("secreto", CifradorDeDatos.APPLE).startsWith("c1:2:"));
    }

    @Test
    @DisplayName("Sin la llave con que se cifró, el valor se conserva cifrado (nunca null)")
    void llaveQueFalta() {
        String guardado = cifrador(LLAVE_1).cifrar("ana@ejemplo.mx", CifradorDeDatos.CORREO);
        CifradorDeDatos sinLaUno = cifrador(LLAVE_2);

        String leido = sinLaUno.descifrar(guardado, CifradorDeDatos.CORREO);
        assertEquals(guardado, leido, "si fuera null, la siguiente escritura borraría el correo");
        assertEquals(guardado, sinLaUno.cifrar(leido, CifradorDeDatos.CORREO),
                "al guardar se deja tal cual, para leerlo cuando vuelva la llave");
    }

    @Test
    @DisplayName("Lo guardado antes de cifrar se lee tal cual; null sigue siendo null")
    void datosAnteriores() {
        CifradorDeDatos c = cifrador(LLAVE_1);
        assertEquals("ana@ejemplo.mx", c.descifrar("ana@ejemplo.mx", CifradorDeDatos.CORREO));
        assertNull(c.descifrar(null, CifradorDeDatos.CORREO));
        assertNull(c.cifrar(null, CifradorDeDatos.CORREO));
    }

    @Test
    @DisplayName("Un valor cifrado roto no tumba nada: se conserva")
    void valorRoto() {
        CifradorDeDatos c = cifrador(LLAVE_1);
        assertEquals("c1:1:no-es-base64!", c.descifrar("c1:1:no-es-base64!", CifradorDeDatos.CORREO));
        assertEquals("c1:x", c.descifrar("c1:x", CifradorDeDatos.CORREO));
        assertEquals("c1:1:AAAA", c.descifrar("c1:1:AAAA", CifradorDeDatos.CORREO));
    }

    @Test
    @DisplayName("La huella no depende de mayúsculas ni espacios, y cambia con la columna y la llave")
    void huellas() {
        CifradorDeDatos c = cifrador(LLAVE_1);
        String h = c.huella("Ana@Ejemplo.MX ", CifradorDeDatos.CORREO);

        assertEquals(64, h.length());
        assertEquals(h, c.huella("ana@ejemplo.mx", CifradorDeDatos.CORREO));
        assertNotEquals(h, c.huella("ana@ejemplo.mx", CifradorDeDatos.IP));
        assertNotEquals(h, new CifradorDeDatos(LLAVE_1, base64(5)).huella("ana@ejemplo.mx", CifradorDeDatos.CORREO));
        assertEquals(h, cifrador(LLAVE_2).huella("ana@ejemplo.mx", CifradorDeDatos.CORREO),
                "cambiar DATOS_LLAVES no cambia las huellas");
        assertNull(c.huella(null, CifradorDeDatos.CORREO));
        assertNull(c.huella("  ", CifradorDeDatos.CORREO));
    }

    @Test
    @DisplayName("El comprobante de compra se compara tal cual, sin pasarlo a minúsculas")
    void huellaDelComprobante() {
        CifradorDeDatos c = cifrador(LLAVE_1);
        assertNotEquals(c.huella("AbC", CifradorDeDatos.COMPROBANTE), c.huella("abc", CifradorDeDatos.COMPROBANTE));
    }

    @Test
    @DisplayName("Sin llaves, o mal escritas, el servidor no arranca y dice por qué")
    void llavesQueFaltan() {
        assertTrue(assertThrows(IllegalStateException.class, () -> new CifradorDeDatos("", INDICE))
                .getMessage().contains("DATOS_LLAVES"));
        assertTrue(assertThrows(IllegalStateException.class, () -> new CifradorDeDatos(LLAVE_1, ""))
                .getMessage().contains("DATOS_INDICE"));
        assertThrows(IllegalStateException.class, () -> new CifradorDeDatos("1:corta", INDICE));
        assertThrows(IllegalStateException.class, () -> new CifradorDeDatos("x:" + base64(1), INDICE));
        assertThrows(IllegalStateException.class, () -> new CifradorDeDatos("0:" + base64(1), INDICE));
        assertThrows(IllegalStateException.class, () -> new CifradorDeDatos(LLAVE_1 + "," + LLAVE_1, INDICE));
        assertThrows(IllegalStateException.class, () -> new CifradorDeDatos(LLAVE_1, "no base64!"));
    }

    @Test
    @DisplayName("Las columnas de las entidades usan el cifrador del servidor")
    void convertidores() {
        CifradorDeDatos c = cifrador(LLAVE_1);   // queda como el actual
        Cifrado.Correo columna = new Cifrado.Correo();

        String guardado = columna.convertToDatabaseColumn("ana@ejemplo.mx");
        assertTrue(CifradorDeDatos.estaCifrado(guardado));
        assertEquals("ana@ejemplo.mx", columna.convertToEntityAttribute(guardado));
        assertEquals("ana@ejemplo.mx", c.descifrar(guardado, CifradorDeDatos.CORREO));
        assertNull(columna.convertToDatabaseColumn(null));
        assertEquals(c.huella("ana@ejemplo.mx", CifradorDeDatos.CORREO),
                CifradorDeDatos.huellaSiSePuede("ana@ejemplo.mx", CifradorDeDatos.CORREO));
    }

    @Test
    @DisplayName("Al arrancar: lo legible se cifra con su huella; lo ilegible se queda como está")
    void cifradoAlArrancar() {
        CifradorDeDatos c = cifrador(LLAVE_1);
        CifradoDeDatosExistentes tarea = new CifradoDeDatosExistentes(null, c);

        CifradoDeDatosExistentes.Columna legible = tarea.columna("ana@ejemplo.mx", null, CifradorDeDatos.CORREO);
        assertTrue(CifradorDeDatos.estaCifrado(legible.guardado()));
        assertEquals("ana@ejemplo.mx", c.descifrar(legible.guardado(), CifradorDeDatos.CORREO));
        assertEquals(c.huella("ana@ejemplo.mx", CifradorDeDatos.CORREO), legible.huella());

        String ajeno = cifrador(LLAVE_2).cifrar("otra@ejemplo.mx", CifradorDeDatos.CORREO);
        CifradoDeDatosExistentes.Columna ilegible = tarea.columna(ajeno, "h-anterior", CifradorDeDatos.CORREO);
        assertEquals(ajeno, ilegible.guardado());
        assertEquals("h-anterior", ilegible.huella());

        assertEquals(new CifradoDeDatosExistentes.Columna(null, null), tarea.columna(null, null, CifradorDeDatos.CORREO));
    }
}
