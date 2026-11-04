package com.voces.backend.directorio;

import com.voces.backend.config.RelayProperties;
import com.voces.backend.modelo.Dtos;
import com.voces.backend.modelo.Publicacion;
import com.voces.backend.modelo.Repositorios;
import com.voces.backend.youtube.YouTubeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Pone al día las cifras de los videos: vistas, "me gusta", comentarios.
 * Las vistas las enseña la app en cada tarjeta de Novedades, junto a la
 * duración.
 *
 * Las pide a YouTube con videos.list, que cuesta 1 unidad de cuota por cada
 * 50 videos. Hay tres maneras, según la edad del video:
 *
 * <ul>
 *   <li><b>Recientes</b> (publicados en los últimos {@link #RECIENTE}):
 *       una pasada cada 20 minutos ({@link #programada()}), y además
 *       <b>al abrir la app</b>: cuando alguien pide Novedades, las de sus
 *       videos recientes se piden otra vez si tienen más de
 *       {@link #AL_ABRIR} ({@link #alAbrirLaApp}).</li>
 *   <li><b>Los demás</b>: solo a mano, desde el panel, con «Actualizar las
 *       vistas de todos los videos» ({@link #empezarTodos()}). El panel dice
 *       cuándo se hizo por última vez. Un video que nunca ha tenido cifras se
 *       pide en la pasada programada aunque sea viejo, una sola vez.</li>
 *   <li>Lo que el panel tiene en pantalla, con «Actualizar cifras», y la
 *       ficha de un video en el panel, al abrirla.</li>
 * </ul>
 *
 * Con 300 videos recientes, la pasada de cada 20 minutos gasta unas 450
 * unidades al día, de las 10.000 que da YouTube; al abrir la app, hasta unas
 * 900 más en un día con la app abierta todo el tiempo. Actualizar todos
 * cuesta una unidad por cada 50 videos.
 *
 * Solo lee. No cambia título, tipo ni nada de lo que ve la app.
 */
@Service
public class CifrasDeVideos {

    private static final Logger log = LoggerFactory.getLogger(CifrasDeVideos.class);

    /** Hasta qué edad un video es «reciente»: el mismo plazo que «Lo más visto» de la semana. */
    public static final Duration RECIENTE = Duration.ofDays(7);

    /** Cada cuánto se piden las de los recientes. */
    static final int CADA_MINUTOS = 20;

    /**
     * Al abrir la app, se piden otra vez si son más viejas que esto. La app
     * vuelve a pedir Novedades cada dos minutos mientras está abierta: con
     * este margen, cada video reciente se pide como mucho cada diez minutos,
     * la tenga abierta quien la tenga.
     */
    static final Duration AL_ABRIR = Duration.ofMinutes(10);

    /** Lo que espera Novedades a YouTube antes de contestar con las que ya tiene. */
    static final Duration ESPERA_AL_ABRIR = Duration.ofMillis(1500);

    /** Cuántos por pasada: 10 unidades como mucho. También el tamaño de cada tanda de «todos». */
    static final int POR_PASADA = 500;

    /** Lo más que se pide de una vez desde el panel: 4 unidades. */
    static final int MAXIMO_DEL_PANEL = 200;

    /** Lo más que se pide al abrir la app: una sola llamada, 1 unidad. */
    static final int MAXIMO_AL_ABRIR = 50;

    /** Al abrir la ficha de un video en el panel, se piden de nuevo si son más viejas que esto. */
    static final Duration FRESCAS = Duration.ofMinutes(10);

    private final Repositorios.Publicaciones publicaciones;
    private final YouTubeClient youtube;
    private final RelayProperties config;
    private final AjustesService ajustes;
    private final TransactionTemplate transaccionNueva;

    // Una a la vez: la pasada programada, el panel y la app podrían coincidir.
    private final ReentrantLock enMarcha = new ReentrantLock();

    // Lo de al abrir la app y lo de «todos» corre aparte de la petición.
    private final ExecutorService fondo = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "cifras-de-videos");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean pidiendoAlAbrir = new AtomicBoolean(false);

    // La actualización de todos los videos: solo una a la vez.
    private volatile Dtos.CifrasDeTodos todos = Dtos.CifrasDeTodos.parada();

    public CifrasDeVideos(Repositorios.Publicaciones publicaciones, YouTubeClient youtube,
                          RelayProperties config, AjustesService ajustes,
                          PlatformTransactionManager gestorDeTransacciones) {
        this.publicaciones = publicaciones;
        this.youtube = youtube;
        this.config = config;
        this.ajustes = ajustes;
        this.transaccionNueva = new TransactionTemplate(gestorDeTransacciones);
        this.transaccionNueva.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Scheduled(fixedDelay = CADA_MINUTOS, timeUnit = TimeUnit.MINUTES, initialDelay = 5)
    public void programada() {
        try {
            Dtos.CifrasActualizadas r = actualizar(publicaciones.porActualizarCifras(POR_PASADA));
            if (r.pedidos() > 0) {
                log.info("Cifras de videos recientes: {} pedidas, {} al día, {} ya no están en YouTube",
                        r.pedidos(), r.actualizados(), r.noExisten());
            }
            if (r.sinApi() && r.pedidos() > 0) {
                log.warn("Cifras de videos: la Data API no contestó; revisa YOUTUBE_API_KEY y la cuota");
            }
        } catch (RuntimeException e) {
            log.warn("No se pudieron actualizar las cifras de los videos: {}", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Al abrir la app
    // -------------------------------------------------------------------------

    /**
     * Novedades está por contestar con estos videos. Los recientes cuyas
     * cifras tienen más de {@link #AL_ABRIR} se piden ahora a YouTube.
     *
     * Espera la respuesta {@link #ESPERA_AL_ABRIR} como mucho: si llega, las
     * cifras nuevas van ya en esta respuesta; si YouTube tarda, Novedades
     * contesta con las que había y las nuevas se guardan igual para la
     * siguiente vez. Si otra petición ya las está pidiendo, no se repite.
     *
     * Las cifras nuevas se ponen en los objetos de la lista solo en memoria:
     * se guardan en una transacción aparte, porque Novedades lee en una de
     * solo lectura.
     */
    public void alAbrirLaApp(List<Publicacion> lista) {
        List<String> ids = porPedirAlAbrir(lista, Instant.now());
        if (ids.isEmpty() || config.youtube().apiKey().isBlank()) return;
        if (!pidiendoAlAbrir.compareAndSet(false, true)) return;

        CompletableFuture<Map<String, YouTubeClient.DetalleDeVideo>> pedido;
        try {
            pedido = CompletableFuture.supplyAsync(() -> pedirYGuardar(ids), fondo);
        } catch (RuntimeException e) {
            pidiendoAlAbrir.set(false);
            return;
        }
        pedido.whenComplete((r, e) -> pidiendoAlAbrir.set(false));

        try {
            Map<String, YouTubeClient.DetalleDeVideo> detalles =
                    pedido.get(ESPERA_AL_ABRIR.toMillis(), TimeUnit.MILLISECONDS);
            if (detalles == null) return;
            Instant ahora = Instant.now();
            for (Publicacion p : lista) {
                YouTubeClient.DetalleDeVideo d = detalles.get(p.getVideoId());
                if (d != null) d.copiarCifras(p, ahora);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // Tardó o falló: Novedades sale con las que había.
        }
    }

    /**
     * Los de la lista que toca pedir al abrir la app: de YouTube, recientes y
     * con cifras de hace más de {@link #AL_ABRIR} (o sin cifras). Lógica pura
     * para poder probarla.
     */
    static List<String> porPedirAlAbrir(List<Publicacion> lista, Instant ahora) {
        Instant desde = ahora.minus(RECIENTE);
        Instant viejas = ahora.minus(AL_ABRIR);
        return lista.stream()
                .filter(CifrasDeVideos::esYouTube)
                .filter(p -> !"removed".equals(p.getEstado()))
                .filter(p -> p.getPublicadoEn() != null && p.getPublicadoEn().isAfter(desde))
                .filter(p -> p.getCifrasEn() == null || p.getCifrasEn().isBefore(viejas))
                .map(Publicacion::getVideoId)
                .distinct()
                .limit(MAXIMO_AL_ABRIR)
                .toList();
    }

    /** Pide las de esos videos y las guarda. null si no se pudo. */
    private Map<String, YouTubeClient.DetalleDeVideo> pedirYGuardar(List<String> ids) {
        if (!enMarcha.tryLock()) return null;
        try {
            Map<String, YouTubeClient.DetalleDeVideo> detalles = youtube.detallesDeVideos(ids);
            if (detalles == null) return null;
            Instant ahora = Instant.now();
            transaccionNueva.executeWithoutResult(t -> {
                List<Publicacion> guardadas = publicaciones.findByVideoIdIn(ids);
                aplicar(guardadas, detalles, ahora);
                publicaciones.saveAll(guardadas);
            });
            return detalles;
        } catch (RuntimeException e) {
            log.warn("Cifras al abrir la app: {}", e.getMessage());
            return null;
        } finally {
            enMarcha.unlock();
        }
    }

    // -------------------------------------------------------------------------
    // Todos los videos, a mano desde el panel
    // -------------------------------------------------------------------------

    /** Cómo va la actualización de todos, y cuándo terminó la última. */
    public Dtos.CifrasDeTodos estadoDeTodos() {
        long videos = publicaciones.cuantosParaCifras();
        return todos.con(videos, (videos + 49) / 50, ajustes.cifrasDeTodosEn());
    }

    /**
     * Empieza a pedir las cifras de todos los videos de YouTube del
     * directorio, en tandas de {@link #POR_PASADA}. Corre aparte: el panel
     * pregunta cómo va con {@link #estadoDeTodos()}. Si ya está en marcha, no
     * empieza otra.
     */
    public synchronized Dtos.CifrasDeTodos empezarTodos() {
        if (todos.enMarcha()) return estadoDeTodos();
        if (config.youtube().apiKey().isBlank()) {
            todos = Dtos.CifrasDeTodos.parada().conError(
                    "El servidor no tiene YOUTUBE_API_KEY: no puede preguntar a YouTube.");
            return estadoDeTodos();
        }

        List<String> ids = publicaciones.idsParaCifras();
        todos = Dtos.CifrasDeTodos.empezando(ids.size(), Instant.now());
        fondo.execute(() -> pedirTodos(ids));
        return estadoDeTodos();
    }

    private void pedirTodos(List<String> ids) {
        int hechos = 0, actualizados = 0, noExisten = 0;
        String error = null;
        try {
            for (int desde = 0; desde < ids.size(); desde += POR_PASADA) {
                List<String> tanda = ids.subList(desde, Math.min(desde + POR_PASADA, ids.size()));
                Dtos.CifrasActualizadas r = actualizar(publicaciones.findByVideoIdIn(tanda), true);
                if (r.sinApi()) {
                    error = "YouTube dejó de contestar a mitad. Revisa la cuota del día y vuelve a intentarlo.";
                    break;
                }
                hechos += tanda.size();
                actualizados += r.actualizados();
                noExisten += r.noExisten();
                todos = todos.avance(hechos, actualizados, noExisten);
            }
        } catch (RuntimeException e) {
            log.warn("Cifras de todos los videos: {}", e.getMessage());
            error = "Algo falló a mitad: " + e.getMessage();
        }

        Instant fin = Instant.now();
        if (error == null) ajustes.ponerCifrasDeTodosEn(fin);
        todos = todos.terminada(hechos, actualizados, noExisten, fin, error);
        log.info("Cifras de todos los videos: {} de {} pedidas, {} al día, {} ya no están{}",
                hechos, ids.size(), actualizados, noExisten, error != null ? " (" + error + ")" : "");
    }

    // -------------------------------------------------------------------------
    // Desde el panel, lo que tiene en pantalla
    // -------------------------------------------------------------------------

    /** Las de esos videos, ahora. Lo pide el panel para lo que tiene en pantalla. */
    public Dtos.CifrasActualizadas deVideos(Collection<String> videoIds) {
        List<String> ids = videoIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .limit(MAXIMO_DEL_PANEL)
                .toList();
        if (ids.isEmpty()) return new Dtos.CifrasActualizadas(0, 0, 0, false, false);
        return actualizar(publicaciones.findByVideoIdIn(ids));
    }

    /**
     * Las de un video si están viejas. Para la ficha del panel: quien la abre
     * quiere ver el número de ahora. Devuelve si cambió algo.
     */
    public boolean refrescarSiHaceFalta(Publicacion p) {
        if (!esYouTube(p)) return false;
        if (p.getCifrasEn() != null && p.getCifrasEn().isAfter(Instant.now().minus(FRESCAS))) return false;
        return actualizar(List.of(p)).actualizados() > 0;
    }

    Dtos.CifrasActualizadas actualizar(List<Publicacion> lista) {
        return actualizar(lista, false);
    }

    /**
     * Sin @Transactional a propósito, igual que RevisionDeVideos: los cambios
     * se guardan de forma explícita al final.
     *
     * @param esperar si otra actualización está en marcha, esperarla en vez
     *                de contestar «ocupada». Lo usa la de todos los videos.
     */
    Dtos.CifrasActualizadas actualizar(List<Publicacion> lista, boolean esperar) {
        List<Publicacion> pedidos = lista.stream().filter(CifrasDeVideos::esYouTube).toList();
        if (pedidos.isEmpty()) return new Dtos.CifrasActualizadas(0, 0, 0, false, false);
        if (config.youtube().apiKey().isBlank()) {
            return new Dtos.CifrasActualizadas(pedidos.size(), 0, 0, true, false);
        }
        if (esperar) {
            enMarcha.lock();
        } else if (!enMarcha.tryLock()) {
            return new Dtos.CifrasActualizadas(pedidos.size(), 0, 0, false, true);
        }
        try {
            Map<String, YouTubeClient.DetalleDeVideo> detalles = youtube.detallesDeVideos(
                    pedidos.stream().map(Publicacion::getVideoId).distinct().toList());
            if (detalles == null) {
                // La API no contestó: no se toca nada y se reintenta en la siguiente pasada.
                return new Dtos.CifrasActualizadas(pedidos.size(), 0, 0, true, false);
            }

            Resultado r = aplicar(pedidos, detalles, Instant.now());
            publicaciones.saveAll(pedidos);
            return new Dtos.CifrasActualizadas(pedidos.size(), r.actualizados(), r.noExisten(), false, false);
        } finally {
            enMarcha.unlock();
        }
    }

    record Resultado(int actualizados, int noExisten) {}

    /**
     * Pone a cada video lo que contestó YouTube.
     *
     * Uno que no viene en la respuesta se borró o se hizo privado: conserva
     * las últimas cifras que tuvo, y se apunta la hora para no volver a
     * preguntar por él hasta su siguiente turno.
     */
    static Resultado aplicar(List<Publicacion> pedidos, Map<String, YouTubeClient.DetalleDeVideo> detalles,
                             Instant ahora) {
        int actualizados = 0;
        int noExisten = 0;
        for (Publicacion p : pedidos) {
            YouTubeClient.DetalleDeVideo d = detalles.get(p.getVideoId());
            if (d == null) {
                p.setEspectadores(null);
                p.setCifrasEn(ahora);
                noExisten++;
            } else {
                d.copiarCifras(p, ahora);
                actualizados++;
            }
        }
        return new Resultado(actualizados, noExisten);
    }

    private static boolean esYouTube(Publicacion p) {
        return "youtube".equals(p.getPlataforma()) && p.getVideoId() != null && !p.getVideoId().isBlank();
    }
}
