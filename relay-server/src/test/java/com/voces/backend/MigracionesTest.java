package com.voces.backend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Las migraciones de Flyway, antes de que lleguen a un servidor.
 *
 * Dos con el mismo número (V27__a.sql y V27__b.sql, hechas en paralelo en dos
 * cambios distintos) pasan todas las demás pruebas, porque ninguna arranca
 * Flyway, y el servidor se cae al desplegar: "Found more than one migration
 * with version 27". Ya pasó (octubre de 2026). Esta prueba lo detiene en
 * "Probar backend", antes de construir la imagen.
 */
class MigracionesTest {

    private static final Pattern NOMBRE = Pattern.compile("V(\\d+)__[a-z0-9_]+\\.sql");

    private static List<String> migraciones() throws IOException, URISyntaxException {
        URL carpeta = MigracionesTest.class.getClassLoader().getResource("db/migration");
        assertNotNull(carpeta, "No encuentro db/migration en el classpath");
        try (Stream<Path> archivos = Files.list(Path.of(carpeta.toURI()))) {
            return archivos.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    @DisplayName("No hay dos migraciones con el mismo número")
    void numerosUnicos() throws Exception {
        Map<Integer, List<String>> porNumero = new TreeMap<>();
        for (String nombre : migraciones()) {
            Matcher m = NOMBRE.matcher(nombre);
            if (!m.matches()) continue;
            porNumero.computeIfAbsent(Integer.parseInt(m.group(1)), k -> new ArrayList<>()).add(nombre);
        }
        List<String> repetidas = porNumero.values().stream().filter(l -> l.size() > 1).map(Object::toString).toList();
        assertTrue(repetidas.isEmpty(), "Migraciones con el mismo número: " + repetidas
                + ". Renumera la que todavía no llegó a ningún servidor con el siguiente libre.");
    }

    @Test
    @DisplayName("Todas se llaman V<número>__<nombre>.sql y no falta ningún número")
    void nombresYSecuencia() throws Exception {
        List<Integer> numeros = new ArrayList<>();
        for (String nombre : migraciones()) {
            Matcher m = NOMBRE.matcher(nombre);
            assertTrue(m.matches(), "Nombre de migración que Flyway no lee bien: " + nombre);
            numeros.add(Integer.parseInt(m.group(1)));
        }
        numeros.sort(null);
        for (int i = 0; i < numeros.size(); i++) {
            assertEquals(i + 1, numeros.get(i).intValue(), "Falta la migración V" + (i + 1) + " o hay un salto en la numeración");
        }
    }
}
