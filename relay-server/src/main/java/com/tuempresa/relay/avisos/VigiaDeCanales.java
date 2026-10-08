package com.tuempresa.relay.avisos;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decide cuándo un canal que no recibe publicaciones merece un aviso.
 *
 * Una suscripción de YouTube pasa unos segundos sin estar activa cada vez que
 * se da de alta o se renueva, y el servidor la reintenta solo cada quince
 * minutos. Eso no es un problema. Lo es cuando sigue así después de varios
 * reintentos: entonces se avisa, una sola vez, y no se vuelve a avisar de ese
 * canal hasta que se arregle y falle de nuevo.
 *
 * Lleva la cuenta en memoria. Si el servidor se reinicia con un canal todavía
 * fallando, se vuelve a avisar de él pasada la espera: un recordatorio de más
 * es preferible a guardar este estado en la base.
 */
final class VigiaDeCanales {

    private final Duration espera;
    private final Map<String, Instant> desde = new HashMap<>();
    private final Set<String> avisados = new HashSet<>();

    VigiaDeCanales(Duration espera) {
        this.espera = espera;
    }

    /**
     * Anota cuáles tienen problemas ahora y devuelve los que ya llevan la
     * espera cumplida y de los que todavía no se avisó.
     *
     * Los que ya no vienen en la lista se arreglaron: se olvidan, y si
     * vuelven a fallar empiezan de cero.
     */
    synchronized List<String> porAvisar(Collection<String> conProblemaAhora, Instant ahora) {
        Set<String> actuales = new HashSet<>(conProblemaAhora);
        desde.keySet().retainAll(actuales);
        avisados.retainAll(actuales);

        List<String> listos = new ArrayList<>();
        for (String canal : conProblemaAhora) {
            Instant primeraVez = desde.putIfAbsent(canal, ahora);
            if (primeraVez == null || avisados.contains(canal)) continue;
            if (Duration.between(primeraVez, ahora).compareTo(espera) >= 0 && !listos.contains(canal)) {
                listos.add(canal);
            }
        }
        return listos;
    }

    /**
     * Ya se avisó de ese canal. Va aparte de {@link #porAvisar}: de un canal
     * de un creador oculto no se avisa, y si después lo hacen visible y sigue
     * fallando, entonces sí.
     */
    synchronized void avisado(String canal) {
        if (desde.containsKey(canal)) avisados.add(canal);
    }
}
