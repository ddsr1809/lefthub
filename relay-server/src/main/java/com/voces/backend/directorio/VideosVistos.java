package com.voces.backend.directorio;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Los videos que cada persona ya abrió o marcó como vistos (tabla
 * videos_vistos). Van con la cuenta: al cerrar sesión y volver a entrar, o en
 * otro teléfono, la app los recupera de aquí.
 *
 * Una marca "a mano" (la persona tocó "Ya lo vi") no se pierde si después
 * llega la misma marca sin ella: abrir un video que ya se marcó como visto
 * no lo devuelve a "solo abierto".
 */
@Service
public class VideosVistos {

    /** Cuántas marcas se guardan por persona: las más recientes. */
    public static final int TOPE = 500;

    /** Un id de video de YouTube, o de otra plataforma con forma parecida. */
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final JdbcTemplate jdbc;

    public VideosVistos(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Las marcas de una persona, de la más reciente a la más vieja. */
    public record Marca(String videoId, boolean aMano) {}

    /** Si un id de video tiene una forma aceptable. Lo demás se ignora. */
    public static boolean idValido(String videoId) {
        return videoId != null && ID.matcher(videoId).matches();
    }

    /**
     * Los ids válidos de una lista, sin repetir y en su orden, hasta el tope.
     * Lo que no tiene forma de id se descarta en silencio: una marca rara no
     * debe hacer fallar el resto.
     */
    public static List<String> limpiar(Collection<String> ids) {
        if (ids == null) return List.of();
        Set<String> limpios = new LinkedHashSet<>();
        for (String id : ids) {
            if (idValido(id)) limpios.add(id);
            if (limpios.size() >= TOPE) break;
        }
        return new ArrayList<>(limpios);
    }

    @Transactional(readOnly = true)
    public List<Marca> de(UUID usuario) {
        return jdbc.query("""
                select video_id, a_mano from videos_vistos
                where usuario_id = ?
                order by marcado_en desc, video_id
                limit ?
                """,
                (fila, n) -> new Marca(fila.getString("video_id"), fila.getBoolean("a_mano")),
                usuario, TOPE);
    }

    /**
     * Apunta uno o varios videos. Si ya estaban, se quedan con su fecha; solo
     * se refresca si ahora se marcan a mano por primera vez.
     *
     * @return cuántos ids válidos se apuntaron
     */
    @Transactional
    public int marcar(UUID usuario, Collection<String> ids, boolean aMano) {
        List<String> limpios = limpiar(ids);
        if (limpios.isEmpty()) return 0;

        jdbc.batchUpdate("""
                insert into videos_vistos (usuario_id, video_id, a_mano)
                values (?, ?, ?)
                on conflict (usuario_id, video_id) do update set
                    marcado_en = case when excluded.a_mano and not videos_vistos.a_mano
                                      then now() else videos_vistos.marcado_en end,
                    a_mano = videos_vistos.a_mano or excluded.a_mano
                """,
                limpios, limpios.size(),
                (ps, id) -> {
                    ps.setObject(1, usuario);
                    ps.setString(2, id);
                    ps.setBoolean(3, aMano);
                });
        recortar(usuario);
        return limpios.size();
    }

    /**
     * Pasa las marcas de una cuenta a otra. Va con la fusión de una cuenta de
     * invitado en la cuenta guardada: lo que se vio en este teléfono antes de
     * entrar se suma a lo de antes. Las de la cuenta de origen se van con ella.
     */
    @Transactional
    public void pasar(UUID de, UUID a) {
        jdbc.update("""
                insert into videos_vistos (usuario_id, video_id, a_mano, marcado_en)
                select ?, video_id, a_mano, marcado_en from videos_vistos where usuario_id = ?
                on conflict (usuario_id, video_id) do update set
                    a_mano = videos_vistos.a_mano or excluded.a_mano,
                    marcado_en = greatest(videos_vistos.marcado_en, excluded.marcado_en)
                """, a, de);
        recortar(a);
    }

    /** Deja solo las TOPE marcas más recientes de la persona. */
    private void recortar(UUID usuario) {
        jdbc.update("""
                delete from videos_vistos v
                where v.usuario_id = ?
                  and v.video_id not in (
                      select video_id from videos_vistos
                      where usuario_id = ?
                      order by marcado_en desc, video_id
                      limit ?)
                """, usuario, usuario, TOPE);
    }
}
