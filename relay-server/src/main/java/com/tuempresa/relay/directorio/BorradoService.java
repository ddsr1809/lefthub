package com.tuempresa.relay.directorio;

import com.tuempresa.relay.directorio.CanalesService.Cambio;
import com.tuempresa.relay.modelo.Creador;
import com.tuempresa.relay.modelo.Dtos;
import com.tuempresa.relay.modelo.Productora;
import com.tuempresa.relay.modelo.Repositorios;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Borrar de golpe una parte entera del directorio: todos los creadores, todas
 * las productoras, todas las publicaciones o todos los reportes.
 *
 * Es lo más destructivo que se puede hacer desde el panel, así que va en dos
 * pasos y el segundo no vale sin el primero:
 *
 *   1. {@link #preparar}: el servidor cuenta lo que se borraría y da un
 *      número de seis cifras, al azar, que caduca a los cinco minutos.
 *   2. {@link #ejecutar}: solo borra si llega ese mismo número, de la misma
 *      persona y para las mismas partes que se contaron.
 *
 * El número lo pone el servidor y no el panel a propósito: una llamada suelta
 * a la ruta de borrar (un error de programación, una pestaña vieja, un script)
 * no puede borrar nada, porque no hay número que acertar sin haber pedido
 * antes la cuenta de lo que se va a perder.
 *
 * Antes de borrar creadores o productoras se corta una versión automática del
 * directorio: no se restaura sola, pero queda escrito cómo estaba todo.
 */
@Service
public class BorradoService {

    private static final Logger log = LoggerFactory.getLogger(BorradoService.class);

    public static final String CREADORES = "creadores";
    public static final String PRODUCTORAS = "productoras";
    public static final String PUBLICACIONES = "publicaciones";
    public static final String REPORTES = "reportes";

    /** Lo que se puede borrar de golpe. Ni usuarios, ni administradores, ni ajustes. */
    static final Set<String> PARTES = Set.of(CREADORES, PRODUCTORAS, PUBLICACIONES, REPORTES);

    static final Duration VIGENCIA = Duration.ofMinutes(5);
    static final int FALLOS_PERMITIDOS = 3;

    /** Lo que alguien pidió borrar y todavía no ha confirmado. */
    record Pendiente(String codigo, Set<String> partes, Instant caduca, int fallos) {
        Pendiente conOtroFallo() { return new Pendiente(codigo, partes, caduca, fallos + 1); }
    }

    // Uno por persona: pedir otra cuenta sustituye a la anterior. En memoria,
    // porque no tiene que sobrevivir a nada: si el servidor se reinicia, se
    // vuelve a pedir.
    private final Map<UUID, Pendiente> pendientes = new ConcurrentHashMap<>();
    private final SecureRandom azar = new SecureRandom();

    private final Repositorios.Creadores creadores;
    private final Repositorios.Productoras productoras;
    private final Repositorios.Canales canales;
    private final Repositorios.Publicaciones publicaciones;
    private final Repositorios.Reportes reportes;
    private final Repositorios.Fotos fotos;
    private final CreadoresService servicioDeCreadores;
    private final ProductorasService servicioDeProductoras;
    private final CanalesService canalesService;
    private final VersionesService versiones;
    private final TransactionTemplate transaccion;

    @PersistenceContext
    private EntityManager em;

    public BorradoService(Repositorios.Creadores creadores, Repositorios.Productoras productoras,
                          Repositorios.Canales canales, Repositorios.Publicaciones publicaciones,
                          Repositorios.Reportes reportes, Repositorios.Fotos fotos,
                          CreadoresService servicioDeCreadores, ProductorasService servicioDeProductoras,
                          CanalesService canalesService, VersionesService versiones,
                          PlatformTransactionManager gestorDeTransacciones) {
        this.creadores = creadores;
        this.productoras = productoras;
        this.canales = canales;
        this.publicaciones = publicaciones;
        this.reportes = reportes;
        this.fotos = fotos;
        this.servicioDeCreadores = servicioDeCreadores;
        this.servicioDeProductoras = servicioDeProductoras;
        this.canalesService = canalesService;
        this.versiones = versiones;
        this.transaccion = new TransactionTemplate(gestorDeTransacciones);
    }

    // -------------------------------------------------------------------------
    // Paso 1: contar y dar el número
    // -------------------------------------------------------------------------

    public Dtos.BorradoPreparado preparar(UUID quien, Collection<String> pedido) {
        Set<String> partes = validar(pedido);

        String codigo = String.format("%06d", azar.nextInt(1_000_000));
        pendientes.put(quien, new Pendiente(codigo, partes, Instant.now().plus(VIGENCIA), 0));

        return new Dtos.BorradoPreparado(codigo, VIGENCIA.toSeconds(), List.copyOf(partes),
                transaccion.execute(estado -> contar(partes)));
    }

    /** Cuánto hay ahora mismo de cada cosa que se perdería. */
    private Dtos.BorradoCuenta contar(Set<String> partes) {
        boolean deCreadores = partes.contains(CREADORES);
        boolean deProductoras = partes.contains(PRODUCTORAS);

        long cuantosCanales = deCreadores && deProductoras ? canales.count()
                : deCreadores ? canales.findAll().stream().filter(k -> k.getCreadorId() != null).count()
                : deProductoras ? canales.findAll().stream().filter(k -> k.getCreadorId() == null).count()
                : 0;

        // Con un creador o una productora se van también sus videos; cuántos
        // exactamente depende de qué canal es de quién, así que solo se da la
        // cifra cuando se borran todos.
        boolean todasLasPublicaciones = partes.contains(PUBLICACIONES) || (deCreadores && deProductoras);

        return new Dtos.BorradoCuenta(
                deCreadores ? creadores.count() : 0,
                deProductoras ? productoras.count() : 0,
                cuantosCanales,
                todasLasPublicaciones ? publicaciones.count() : 0,
                partes.contains(REPORTES) ? reportes.count() : 0);
    }

    // -------------------------------------------------------------------------
    // Paso 2: comprobar el número y borrar
    // -------------------------------------------------------------------------

    public Dtos.BorradoHecho ejecutar(UUID quien, Collection<String> pedido, String codigo) {
        Set<String> partes = validar(pedido);
        comprobar(quien, partes, codigo);

        // Las bajas del hub se apuntan dentro y se mandan después: si la
        // transacción no llega a confirmarse, no se da de baja nada.
        Set<String> bajas = new LinkedHashSet<>();
        Dtos.BorradoHecho hecho = transaccion.execute(estado -> borrar(quien, partes, bajas));

        log.warn("Borrado masivo por {}: {} -> {}", quien, partes, hecho);
        darDeBajaDespues(bajas);
        return hecho;
    }

    private void comprobar(UUID quien, Set<String> partes, String codigo) {
        Pendiente pendiente = pendientes.get(quien);

        if (pendiente == null || Instant.now().isAfter(pendiente.caduca())) {
            pendientes.remove(quien);
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "El número caducó o no se había pedido. Vuelve a empezar.");
        }
        if (!pendiente.partes().equals(partes)) {
            pendientes.remove(quien);
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Lo que se pide borrar no es lo que se había marcado. Vuelve a empezar.");
        }
        if (!iguales(pendiente.codigo(), codigo == null ? "" : codigo.replaceAll("\\s", ""))) {
            Pendiente conFallo = pendiente.conOtroFallo();
            if (conFallo.fallos() >= FALLOS_PERMITIDOS) {
                pendientes.remove(quien);
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "El número no coincide y ya van " + FALLOS_PERMITIDOS + " intentos. Vuelve a empezar.");
            }
            pendientes.put(quien, conFallo);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El número no coincide. Revísalo y vuelve a escribirlo.");
        }
        // Vale una sola vez.
        pendientes.remove(quien);
    }

    private Dtos.BorradoHecho borrar(UUID quien, Set<String> partes, Set<String> bajas) {
        boolean deCreadores = partes.contains(CREADORES);
        boolean deProductoras = partes.contains(PRODUCTORAS);

        Integer version = null;
        if (deCreadores || deProductoras) {
            // La nota la lee una persona en la sección Versiones: ahí las
            // productoras se llaman medios.
            version = versiones.cortar("Antes de borrar: "
                    + String.join(", ", partes).replace(PRODUCTORAS, "medios"), quien, true);
        }

        long antesDePublicaciones = publicaciones.count();
        long borradosCanales = canales.count();
        long borradosCreadores = 0;
        long borradasProductoras = 0;
        long borradosReportes = 0;

        if (deCreadores) {
            for (Creador creador : creadores.findAll()) {
                anotar(bajas, servicioDeCreadores.alRetirar(creador));
                // Sus canales, sus publicaciones y quién lo sigue se van en
                // cascada por las claves foráneas.
                creadores.delete(creador);
                borradosCreadores++;
            }
            soltar();
        }

        if (deProductoras) {
            for (Productora productora : productoras.findAll()) {
                // Sus canales propios y lo que publicaron se van con ella;
                // los de sus creadores solo dejan de estar ligados a ella.
                anotar(bajas, servicioDeProductoras.retirar(productora));
                borradasProductoras++;
            }
            soltar();
        }

        if (partes.contains(PUBLICACIONES)) {
            publicaciones.deleteAllInBatch();
        }
        if (partes.contains(REPORTES)) {
            borradosReportes = reportes.count();
            reportes.deleteAllInBatch();
        }
        // Sin creadores ni productoras, las copias de sus fotos no son de nadie.
        if (deCreadores && deProductoras) {
            fotos.deleteAllInBatch();
        }
        soltar();

        return new Dtos.BorradoHecho(
                new Dtos.BorradoCuenta(borradosCreadores, borradasProductoras,
                        borradosCanales - canales.count(),
                        antesDePublicaciones - publicaciones.count(), borradosReportes),
                version);
    }

    /** La base ya borró en cascada cosas que Hibernate aún tiene en memoria: que las olvide. */
    private void soltar() {
        em.flush();
        em.clear();
    }

    private static void anotar(Set<String> bajas, Cambio cambio) {
        bajas.addAll(cambio.bajas());
    }

    /**
     * Las bajas del hub, sin hacer esperar a quien pulsó el botón: son una
     * llamada por canal a un servicio lento. Si alguna falla no pasa nada
     * grave: un aviso de un canal que ya no está en el directorio se ignora,
     * y la suscripción caduca sola en unos días.
     */
    private void darDeBajaDespues(Set<String> bajas) {
        if (bajas.isEmpty()) return;
        CompletableFuture.runAsync(() -> {
            try {
                canalesService.sincronizarWebSub(new Cambio(bajas, Set.of()));
            } catch (Exception e) {
                log.error("Bajas del hub tras el borrado masivo: {}", e.getMessage());
            }
        });
    }

    // -------------------------------------------------------------------------

    /** Las partes pedidas, sin repetir y en un orden fijo; falla si alguna no existe o no hay ninguna. */
    static Set<String> validar(Collection<String> pedido) {
        if (pedido == null || pedido.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Marca qué quieres borrar.");
        }
        Set<String> partes = new TreeSet<>();
        for (String parte : pedido) {
            if (parte == null || !PARTES.contains(parte)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "No se puede borrar «" + parte + "» de golpe.");
            }
            partes.add(parte);
        }
        return partes;
    }

    /** Comparación que tarda lo mismo acierte o no. */
    private static boolean iguales(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
