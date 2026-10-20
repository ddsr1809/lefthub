package com.voces.backend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Que Spring sepa con qué constructor crear cada pieza del servidor.
 *
 * Con un solo constructor no hay duda. Con varios (uno para el servidor y
 * otro para las pruebas, por ejemplo), Spring usa el marcado con @Autowired
 * o, si no hay ninguno marcado, el que no lleva argumentos. Si no hay ni lo
 * uno ni lo otro, el servidor no arranca ("No default constructor found").
 *
 * Las demás pruebas crean las clases a mano y no lo notan: así llegó el
 * fallo de AvisoDeChat hasta el despliegue a testing. Esta lo detecta antes.
 */
class ConstructoresDeBeansTest {

    @Test
    @DisplayName("Cada pieza con varios constructores tiene uno marcado o uno sin argumentos")
    void constructores() throws Exception {
        ClassPathScanningCandidateComponentProvider escaner = new ClassPathScanningCandidateComponentProvider(true);

        List<String> revisadas = new ArrayList<>();
        List<String> sinConstructorClaro = new ArrayList<>();

        for (BeanDefinition definicion : escaner.findCandidateComponents("com.voces.backend")) {
            // Sin inicializar la clase: solo se miran sus constructores.
            Class<?> clase = Class.forName(definicion.getBeanClassName(), false, getClass().getClassLoader());
            revisadas.add(clase.getSimpleName());

            Constructor<?>[] constructores = clase.getDeclaredConstructors();
            if (constructores.length <= 1) continue;

            boolean marcado = Arrays.stream(constructores).anyMatch(c -> c.isAnnotationPresent(Autowired.class));
            boolean sinArgumentos = Arrays.stream(constructores).anyMatch(c -> c.getParameterCount() == 0);
            if (!marcado && !sinArgumentos) sinConstructorClaro.add(clase.getName());
        }

        assertFalse(revisadas.isEmpty(), "El escaneo no encontró ninguna pieza: revisa el paquete.");
        assertTrue(revisadas.contains("AvisoDeChat"), "El escaneo no llegó al chat: " + revisadas);
        assertTrue(sinConstructorClaro.isEmpty(),
                "Spring no sabrá qué constructor usar en " + sinConstructorClaro
                        + ". Marca con @Autowired el que debe usar el servidor.");
    }
}
