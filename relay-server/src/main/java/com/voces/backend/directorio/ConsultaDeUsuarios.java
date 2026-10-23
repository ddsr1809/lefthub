package com.voces.backend.directorio;

import com.voces.backend.modelo.Usuario;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * La lista de la sección Usuarios del panel: con los filtros que se elijan, a
 * la vez, y en el orden de la columna que se toque.
 *
 * Como en ConsultaDePublicaciones, la consulta se arma aquí porque cada filtro
 * es opcional. Lo que manda el panel nunca se pega al texto: el orden y los
 * filtros salen de listas cerradas, y los valores van como parámetros.
 */
@Service
public class ConsultaDeUsuarios {

    /** Cómo entra la cuenta. Se puede elegir más de uno: vale cualquiera de los elegidos. */
    static final Set<String> TIPOS = Set.of(Usuario.ANONIMO, Usuario.GOOGLE, Usuario.APPLE);

    /**
     * Rasgos de la cuenta. Si se eligen varios, tiene que cumplirlos todos:
     * "Con Google" y "Posible bot" son las cuentas de Google que parecen bots.
     */
    static final Map<String, String> MARCAS = Map.ofEntries(
            Map.entry("admin", "u.esAdmin = true"),
            Map.entry("bot", "u.posibleBot = true"),
            Map.entry("persona", "u.posibleBot = false"),
            Map.entry("siguen", "size(u.favoritos) > 0"),
            Map.entry("nosiguen", "size(u.favoritos) = 0"),
            Map.entry("sinanuncios", "u.sinAnuncios = true"),
            Map.entry("activos7", "u.vistoEn >= :hace7"),
            Map.entry("nuevos7", "u.creadoEn >= :hace7"),
            // La llave de la app en su última petición (ver seguridad/RegistroDeLlaves).
            Map.entry("firmaok", "u.llaveFirma = 'ok'"),
            Map.entry("firmafalta", "u.llaveFirma = 'falta'"),
            Map.entry("firmamala", "u.llaveFirma in ('mala', 'hora', 'repetida')"),
            Map.entry("checkok", "u.llaveAppCheck = 'ok'"),
            Map.entry("checkfalta", "u.llaveAppCheck = 'falta'"),
            Map.entry("checkmalo", "u.llaveAppCheck = 'malo'"),
            Map.entry("llavesindatos", "u.llaveVistaEn is null"));

    /**
     * Por qué columna se puede ordenar, y con qué expresión.
     *
     * "cuenta" ya no ordena por correo: está cifrado y la base no puede
     * ordenarlo. Agrupa por cómo entra (Apple, Google, invitado).
     */
    static final Map<String, String> ORDENES = Map.of(
            "cuenta", "u.proveedor",
            "tipo", "u.proveedor",
            "pais", "u.pais",
            "sigue", "size(u.favoritos)",
            "alta", "u.creadoEn",
            "visto", "u.vistoEn",
            // Primero los marcados como posibles bots; entre ellos, los más recientes.
            "bot", "case when u.posibleBot = true then 1 else 0 end");

    /**
     * @param tipos  anonimo, google, apple; vacío = todos
     * @param marcas claves de MARCAS; las desconocidas se ignoran
     * @param pais   código de dos letras en mayúsculas, o vacío
     * @param texto  lo que se busca en el nombre de la compañía de internet
     *               (con los % de LIKE ya puestos), o vacío
     * @param huellaCorreo huella del texto como correo exacto, o vacío
     * @param huellaIp     huella del texto como IP exacta, o vacío
     *
     * El correo y la IP están cifrados: solo se pueden encontrar escritos
     * completos, comparando huellas. Ver seguridad/CifradorDeDatos.
     */
    public record Filtro(Collection<String> tipos, Collection<String> marcas, String pais, String texto,
                         String huellaCorreo, String huellaIp) {

        /** Sin búsqueda por correo ni IP. */
        public Filtro(Collection<String> tipos, Collection<String> marcas, String pais, String texto) {
            this(tipos, marcas, pais, texto, "", "");
        }
    }

    record Consulta(String jpql, String cuenta, Map<String, Object> parametros) {}

    public record Pagina(List<Usuario> usuarios, long total) {}

    @PersistenceContext
    private EntityManager em;

    @Transactional(readOnly = true)
    public Pagina buscar(Filtro f, String orden, boolean ascendente, int pagina, int tamano) {
        Consulta c = armar(f, orden, ascendente, Instant.now());

        TypedQuery<Usuario> q = em.createQuery(c.jpql(), Usuario.class);
        c.parametros().forEach(q::setParameter);
        q.setFirstResult(pagina * tamano);
        q.setMaxResults(tamano);

        TypedQuery<Long> n = em.createQuery(c.cuenta(), Long.class);
        c.parametros().forEach(n::setParameter);

        return new Pagina(q.getResultList(), n.getSingleResult());
    }

    /**
     * La consulta y la de contar, con los mismos filtros. Sin base de datos:
     * se prueba como texto.
     *
     * Lo que no tiene el dato (una cuenta de invitado no tiene correo) va al
     * final, se ordene hacia donde se ordene; a igualdad, la que abrió la app
     * más recientemente, y por último el id, para que el orden no cambie de
     * una página a la siguiente.
     */
    static Consulta armar(Filtro f, String orden, boolean ascendente, Instant ahora) {
        Map<String, Object> parametros = new LinkedHashMap<>();
        StringBuilder w = new StringBuilder();

        List<String> tipos = f.tipos() == null ? List.of()
                : f.tipos().stream().filter(TIPOS::contains).distinct().sorted().toList();
        if (!tipos.isEmpty() && tipos.size() < TIPOS.size()) {
            y(w, "u.proveedor in :tipos");
            parametros.put("tipos", tipos);
        }

        if (f.marcas() != null) {
            f.marcas().stream().filter(MARCAS::containsKey).distinct().sorted().forEach(m -> {
                y(w, MARCAS.get(m));
                if (m.endsWith("7")) parametros.put("hace7", ahora.minus(Duration.ofDays(7)));
            });
        }

        if (f.pais() != null && !f.pais().isBlank()) {
            y(w, "u.pais = :pais");
            parametros.put("pais", f.pais());
        }

        if (f.texto() != null && !f.texto().isBlank()) {
            List<String> donde = new java.util.ArrayList<>();
            if (f.huellaCorreo() != null && !f.huellaCorreo().isBlank()) {
                donde.add("u.emailHuella = :huellaCorreo");
                parametros.put("huellaCorreo", f.huellaCorreo());
            }
            if (f.huellaIp() != null && !f.huellaIp().isBlank()) {
                donde.add("u.ipHuella = :huellaIp");
                parametros.put("huellaIp", f.huellaIp());
            }
            donde.add("lower(u.red) like :texto");
            parametros.put("texto", f.texto());
            y(w, "(" + String.join(" or ", donde) + ")");
        }

        String expresion = ORDENES.getOrDefault(orden, ORDENES.get("visto"));
        String jpql = "select u from Usuario u" + w
                + " order by case when " + expresion + " is null then 1 else 0 end, "
                + expresion + (ascendente ? " asc" : " desc")
                + ", u.vistoEn desc, u.id";
        String cuenta = "select count(u) from Usuario u" + w;
        return new Consulta(jpql, cuenta, parametros);
    }

    private static void y(StringBuilder w, String condicion) {
        w.append(w.isEmpty() ? " where " : " and ").append(condicion);
    }
}
