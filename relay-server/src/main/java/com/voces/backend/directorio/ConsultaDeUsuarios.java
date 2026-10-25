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
            "bot", "case when u.posibleBot = true then 1 else 0 end",
            // Por número y no por texto: 1.1.10 va después de 1.1.9 (V30).
            "app", "u.appVersionOrden",
            "llave", "u.llaveNumero");

    /** Lo más que se puede elegir de una vez en los filtros de versión y de llave. */
    static final int MAX_ELEGIDOS = 30;

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
                         String huellaCorreo, String huellaIp,
                         Collection<String> versiones, Collection<String> llaves) {

        /** Sin búsqueda por correo ni IP. */
        public Filtro(Collection<String> tipos, Collection<String> marcas, String pais, String texto) {
            this(tipos, marcas, pais, texto, "", "");
        }

        /** Sin filtros de versión ni de llave. */
        public Filtro(Collection<String> tipos, Collection<String> marcas, String pais, String texto,
                      String huellaCorreo, String huellaIp) {
            this(tipos, marcas, pais, texto, huellaCorreo, huellaIp, List.of(), List.of());
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

        // Versiones de la app: "android:1.2.2", o "sin" (la app no lo dijo).
        // Vale cualquiera de las elegidas.
        if (f.versiones() != null) {
            List<String> donde = new java.util.ArrayList<>();
            int i = 0;
            for (String v : f.versiones().stream().distinct().limit(MAX_ELEGIDOS).toList()) {
                if ("sin".equals(v)) {
                    donde.add("u.appVersion is null");
                    continue;
                }
                int dos = v.indexOf(':');
                if (dos < 0) continue;
                String plataforma = v.substring(0, dos), version = v.substring(dos + 1);
                if (!Set.of("android", "ios").contains(plataforma) || !version.matches("[0-9]{1,4}(\\.[0-9]{1,4}){0,3}")) continue;
                donde.add("(u.appPlataforma = :vp" + i + " and u.appVersion = :vv" + i + ")");
                parametros.put("vp" + i, plataforma);
                parametros.put("vv" + i, version);
                i++;
            }
            if (!donde.isEmpty()) y(w, "(" + String.join(" or ", donde) + ")");
        }

        // Número de llave con que firma: "1", "2"... o "sin". Vale cualquiera.
        if (f.llaves() != null) {
            List<Short> numeros = new java.util.ArrayList<>();
            boolean sinLlave = false;
            for (String l : f.llaves().stream().distinct().limit(MAX_ELEGIDOS).toList()) {
                if ("sin".equals(l)) { sinLlave = true; continue; }
                try {
                    short n = Short.parseShort(l);
                    if (n >= 1 && n <= 999) numeros.add(n);
                } catch (NumberFormatException e) { /* se ignora */ }
            }
            List<String> donde = new java.util.ArrayList<>();
            if (!numeros.isEmpty()) {
                donde.add("u.llaveNumero in :llaves");
                parametros.put("llaves", numeros.stream().sorted().toList());
            }
            if (sinLlave) donde.add("u.llaveNumero is null");
            if (!donde.isEmpty()) y(w, donde.size() == 1 ? donde.get(0) : "(" + String.join(" or ", donde) + ")");
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
