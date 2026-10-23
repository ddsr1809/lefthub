package com.voces.backend.seguridad;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Al arrancar, cifra lo que todavía esté legible en la base y completa las
 * huellas que falten.
 *
 * La primera vez después de V26 es cuando hay trabajo: todos los
 * identificadores de teléfono (a huella), correos, IPs, tokens de Apple,
 * comprobantes y órdenes de compra, y autores de altas desde la app. Las siguientes no encuentra nada que hacer y termina en una
 * consulta por tabla. También pone al día lo que se hubiera restaurado de un
 * respaldo anterior al cifrado.
 *
 * Recorre cada tabla una sola vez, por orden de id, de a poco: un valor que
 * no se pudiera leer no la deja dando vueltas. Cada fila se actualiza solo si
 * nadie la cambió mientras tanto; si alguien entró justo en ese momento, su
 * fila ya la guardó cifrada el propio Hibernate.
 *
 * Nunca registra un dato: solo cuántos.
 */
@Component
public class CifradoDeDatosExistentes implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CifradoDeDatosExistentes.class);
    private static final int LOTE = 200;

    private final JdbcTemplate jdbc;
    private final CifradorDeDatos cifrador;

    public CifradoDeDatosExistentes(JdbcTemplate jdbc, CifradorDeDatos cifrador) {
        this.jdbc = jdbc;
        this.cifrador = cifrador;
    }

    @Override
    public void run(ApplicationArguments args) {
        int dispositivos = dispositivos();
        int usuarios = usuarios();
        int compras = compras();
        int altas = altas();
        if (dispositivos + usuarios + compras + altas > 0) {
            log.info("Datos personales protegidos al arrancar: {} teléfono(s), {} cuenta(s), {} compra(s), "
                    + "{} alta(s) desde la app", dispositivos, usuarios, compras, altas);
        }
        avisarIlegibles();
    }

    /**
     * Cambia cada device_id legible por su huella (ver HuellaDeDispositivo).
     * Se hace aquí y no en V26: si el servidor nuevo no llegara a arrancar y
     * el despliegue volviera a la versión anterior, esta tiene que seguir
     * encontrando las cuentas por su teléfono.
     *
     * Si ya existe una cuenta con esa huella (la versión anterior creó otra
     * para el mismo teléfono mientras estuvo de vuelta), la legible se deja
     * como está: la de la huella es la que ese teléfono abre.
     */
    int dispositivos() {
        return jdbc.update("""
                update usuarios u
                   set device_id = 'h1:' || encode(sha256(convert_to(u.device_id, 'UTF8')), 'hex')
                 where u.device_id is not null
                   and u.device_id !~ '^h1:[0-9a-f]{64}$'
                   and not exists (
                         select 1 from usuarios o
                          where o.device_id = 'h1:' || encode(sha256(convert_to(u.device_id, 'UTF8')), 'hex'))
                """);
    }

    // -------------------------------------------------------------------------

    int usuarios() {
        String pendientes = """
                select id::text as id, email, email_huella, ip, ip_huella, apple_refresh
                  from usuarios
                 where id::text > ?
                   and ((email is not null and (email not like 'c1:%' or email_huella is null))
                     or (ip is not null and (ip not like 'c1:%' or ip_huella is null))
                     or (apple_refresh is not null and apple_refresh not like 'c1:%'))
                 order by id::text
                 limit ?
                """;
        String actualizar = """
                update usuarios
                   set email = ?, email_huella = ?, ip = ?, ip_huella = ?, apple_refresh = ?
                 where id = cast(? as uuid)
                   and email is not distinct from ?
                   and ip is not distinct from ?
                   and apple_refresh is not distinct from ?
                """;
        int hechos = 0;
        String desde = "";
        while (true) {
            List<Map<String, Object>> filas = jdbc.queryForList(pendientes, desde, LOTE);
            if (filas.isEmpty()) return hechos;
            for (Map<String, Object> f : filas) {
                desde = (String) f.get("id");
                String email = (String) f.get("email");
                String ip = (String) f.get("ip");
                String apple = (String) f.get("apple_refresh");

                Columna correo = columna(email, (String) f.get("email_huella"), CifradorDeDatos.CORREO);
                Columna direccion = columna(ip, (String) f.get("ip_huella"), CifradorDeDatos.IP);
                String appleCifrado = cifrador.cifrar(apple, CifradorDeDatos.APPLE);

                boolean igual = Objects.equals(correo.guardado(), email)
                        && Objects.equals(correo.huella(), f.get("email_huella"))
                        && Objects.equals(direccion.guardado(), ip)
                        && Objects.equals(direccion.huella(), f.get("ip_huella"))
                        && Objects.equals(appleCifrado, apple);
                if (igual) continue;   // ilegible con estas llaves: se queda como está

                hechos += jdbc.update(actualizar,
                        correo.guardado(), correo.huella(), direccion.guardado(), direccion.huella(), appleCifrado,
                        desde, email, ip, apple);
            }
        }
    }

    int compras() {
        String pendientes = """
                select id::text as id, token, token_huella, orden
                  from compras
                 where id::text > ?
                   and (token not like 'c1:%' or token_huella is null
                     or (orden is not null and orden not like 'c1:%'))
                 order by id::text
                 limit ?
                """;
        String actualizar = """
                update compras
                   set token = ?, token_huella = ?, orden = ?
                 where id = cast(? as uuid)
                   and token is not distinct from ?
                   and orden is not distinct from ?
                """;
        int hechos = 0;
        String desde = "";
        while (true) {
            List<Map<String, Object>> filas = jdbc.queryForList(pendientes, desde, LOTE);
            if (filas.isEmpty()) return hechos;
            for (Map<String, Object> f : filas) {
                desde = (String) f.get("id");
                String token = (String) f.get("token");
                String orden = (String) f.get("orden");

                Columna comprobante = columna(token, (String) f.get("token_huella"), CifradorDeDatos.COMPROBANTE);
                String ordenCifrada = cifrador.cifrar(orden, CifradorDeDatos.ORDEN);
                if (Objects.equals(comprobante.guardado(), token)
                        && Objects.equals(comprobante.huella(), f.get("token_huella"))
                        && Objects.equals(ordenCifrada, orden)) {
                    continue;
                }
                hechos += jdbc.update(actualizar,
                        comprobante.guardado(), comprobante.huella(), ordenCifrada,
                        desde, token, orden);
            }
        }
    }

    int altas() {
        String pendientes = """
                select id, autor from altas_desde_app
                 where id > ? and autor is not null and autor not like 'c1:%'
                 order by id
                 limit ?
                """;
        int hechos = 0;
        long desde = 0;
        while (true) {
            List<Map<String, Object>> filas = jdbc.queryForList(pendientes, desde, LOTE);
            if (filas.isEmpty()) return hechos;
            for (Map<String, Object> f : filas) {
                desde = ((Number) f.get("id")).longValue();
                String autor = (String) f.get("autor");
                hechos += jdbc.update(
                        "update altas_desde_app set autor = ? where id = ? and autor is not distinct from ?",
                        cifrador.cifrar(autor, CifradorDeDatos.AUTOR_DE_ALTA), desde, autor);
            }
        }
    }

    // -------------------------------------------------------------------------

    /** Cómo queda una columna con huella: el valor cifrado y la huella del legible. */
    record Columna(String guardado, String huella) {}

    /**
     * Cifra lo que esté legible y calcula la huella del valor legible. Lo
     * que ya estaba cifrado y no se puede leer se deja como está, con la
     * huella que tuviera.
     */
    Columna columna(String guardado, String huellaGuardada, String campo) {
        if (guardado == null) return new Columna(null, null);
        String legible = cifrador.descifrar(guardado, campo);
        if (CifradorDeDatos.estaCifrado(legible)) {
            return new Columna(guardado, huellaGuardada);
        }
        String huella = cifrador.huella(legible, campo);
        return new Columna(cifrador.cifrar(legible, campo), Objects.requireNonNullElse(huella, huellaGuardada));
    }

    /**
     * Si hay datos cifrados con una llave que ya no está, lo dice fuerte en
     * el registro: esos datos no se pueden leer hasta reponerla.
     */
    void avisarIlegibles() {
        List<String> muestras = jdbc.queryForList(
                "select email from usuarios where email like 'c1:%' order by visto_en desc limit 20", String.class);
        long ilegibles = muestras.stream()
                .filter(m -> CifradorDeDatos.estaCifrado(cifrador.descifrar(m, CifradorDeDatos.CORREO)))
                .count();
        if (ilegibles > 0) {
            log.error("{} de los {} correos más recientes no se pueden descifrar con DATOS_LLAVES. "
                    + "¿Se cambió o se perdió una llave? Repón la anterior: los datos siguen guardados.",
                    ilegibles, muestras.size());
        }
    }
}
