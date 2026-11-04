package com.voces.backend.directorio;

import com.voces.backend.modelo.Canal;
import com.voces.backend.modelo.Dtos;
import com.voces.backend.modelo.Productora;
import com.voces.backend.modelo.Usuario;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;

/**
 * El mapa de selección de una cuenta: qué sigue en Voces y a qué está suscrita
 * en YouTube, por separado y para cada cosa (creadores, medios y canales).
 *
 * Son dos preguntas distintas. De cinco canales, una persona puede seguir los
 * cinco aquí y estar suscrita a tres en YouTube: los cinco salen con
 * {@code voces} y solo tres con {@code youtube}.
 *
 * No tiene tabla propia. Se arma cada vez con lo que ya está guardado (los
 * favoritos, las suscripciones que YouTube contestó y el directorio), de modo
 * que no hay dos versiones de lo mismo que se puedan desalinear. Solo entra lo
 * que dice algo: lo que no se sigue y de lo que YouTube no ha contestado no
 * aparece, y un canal nuevo en el directorio no cambia el mapa de quien no
 * tiene nada que ver con él.
 *
 * La huella resume el mapa. El teléfono guarda el mapa con su huella y, al
 * abrir, solo pregunta si sigue siendo esa. Lleva el id de la cuenta: en un
 * teléfono donde se cambió de cuenta nunca coincide por casualidad. La fecha
 * de la última comprobación de YouTube queda fuera a propósito: volver a
 * comprobar y que todo siga igual no es un mapa distinto.
 */
final class MapaDeSeleccion {

    private static final int LARGO_HUELLA = 32;

    private MapaDeSeleccion() {}

    static Dtos.Mapa de(Usuario usuario, Catalogo.Vista vista, Dtos.SuscripcionesYouTube youtube) {
        Map<UUID, Boolean> enYouTube = new HashMap<>();
        youtube.canalesSuscritos().forEach(id -> enYouTube.put(id, true));
        youtube.canalesNoSuscritos().forEach(id -> enYouTube.put(id, false));

        List<Dtos.EntradaDelMapa> creadores = entradas(
                usuario.getFavoritos(), vista.creadoresVisibles(), vista::canalesDe, enYouTube);

        List<UUID> mediosVisibles = vista.productoras().stream()
                .filter(Productora::isActivo).map(Productora::getId).toList();
        List<Dtos.EntradaDelMapa> medios = entradas(
                usuario.getProductorasSeguidas(), mediosVisibles, vista::canalesDeProductora, enYouTube);

        List<Dtos.CanalDelMapa> canales = canales(usuario, vista, enYouTube);

        return new Dtos.Mapa(huella(usuario.getId(), creadores, medios, canales),
                youtube.verificadoEn(), creadores, medios, canales);
    }

    /**
     * Creadores o medios. En {@code youtube} va lo que se sepa de su canal
     * principal, la misma regla que usa el resumen por creador de
     * SuscripcionesYouTubeService; el detalle está en la lista de canales.
     */
    private static List<Dtos.EntradaDelMapa> entradas(Set<UUID> seguidos, Collection<UUID> visibles,
                                                      Function<UUID, List<Canal>> canalesDe,
                                                      Map<UUID, Boolean> enYouTube) {
        Set<UUID> ids = new TreeSet<>(seguidos);
        ids.addAll(visibles);

        List<Dtos.EntradaDelMapa> lista = new ArrayList<>();
        for (UUID id : ids) {
            boolean voces = seguidos.contains(id);
            Boolean youtube = canalesDe.apply(id).stream()
                    .map(k -> enYouTube.get(k.getId()))
                    .filter(Objects::nonNull)
                    .findFirst().orElse(null);
            if (voces || youtube != null) lista.add(new Dtos.EntradaDelMapa(id, voces, youtube));
        }
        return lista;
    }

    private static List<Dtos.CanalDelMapa> canales(Usuario usuario, Catalogo.Vista vista,
                                                   Map<UUID, Boolean> enYouTube) {
        Set<UUID> sueltos = usuario.getCanalesSeguidos();

        // Por cualquier vía: el mismo reparto que Novedades, más los canales
        // propios de los creadores que sigue.
        Set<UUID> enVoces = new HashSet<>(DirectorioController.canalesSeguidos(usuario, vista));
        for (UUID creadorId : usuario.getFavoritos()) {
            if (vista.creadorVisible(creadorId) == null) continue;
            vista.canalesDe(creadorId).stream().filter(vista::vivo).forEach(k -> enVoces.add(k.getId()));
        }
        // Una cuenta de Instagram también es un "canal", pero no publica videos.
        enVoces.removeIf(id -> vista.canal(id) == null || vista.canal(id).getCanalDeYouTube() == null);

        Set<UUID> ids = new TreeSet<>(enVoces);
        ids.addAll(sueltos);
        ids.addAll(enYouTube.keySet());

        List<Dtos.CanalDelMapa> lista = new ArrayList<>();
        for (UUID id : ids) {
            lista.add(new Dtos.CanalDelMapa(id, enVoces.contains(id), sueltos.contains(id), enYouTube.get(id)));
        }
        return lista;
    }

    /** Las listas ya vienen ordenadas por id, así que el mismo mapa da siempre la misma huella. */
    private static String huella(UUID cuenta, List<Dtos.EntradaDelMapa> creadores,
                                 List<Dtos.EntradaDelMapa> medios, List<Dtos.CanalDelMapa> canales) {
        StringBuilder texto = new StringBuilder(cuenta.toString()).append('\n');
        creadores.forEach(e -> texto.append("creador ").append(e.id()).append(' ')
                .append(e.voces()).append(' ').append(e.youtube()).append('\n'));
        medios.forEach(e -> texto.append("medio ").append(e.id()).append(' ')
                .append(e.voces()).append(' ').append(e.youtube()).append('\n'));
        canales.forEach(e -> texto.append("canal ").append(e.id()).append(' ')
                .append(e.voces()).append(' ').append(e.suelto()).append(' ').append(e.youtube()).append('\n'));

        try {
            byte[] resumen = MessageDigest.getInstance("SHA-256")
                    .digest(texto.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(resumen).substring(0, LARGO_HUELLA);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}
