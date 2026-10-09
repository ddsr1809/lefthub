package com.voces.backend.websub;

import com.voces.backend.config.RelayProperties;
import com.voces.backend.directorio.AjustesService;
import com.voces.backend.modelo.*;
import com.voces.backend.push.Emisores;
import com.voces.backend.push.PushService;
import com.voces.backend.youtube.YouTubeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class WebSubService {

    private static final Logger log = LoggerFactory.getLogger(WebSubService.class);

    private final Repositorios.Canales canales;
    private final Repositorios.Publicaciones publicaciones;
    private final Repositorios.Suscripciones suscripciones;
    private final RestClient http;
    private final RelayProperties config;
    private final YouTubeClient youtube;
    private final PushService push;
    private final Emisores emisores;
    private final LectorDeFeed lector;
    private final AjustesService ajustes;
    private final TransactionTemplate transaccionNueva;

    // El hub y el vigilante pueden querer revisar los directos a la vez. Con
    // el cerrojo, solo uno manda el aviso de "está en vivo".
    private final ReentrantLock cerrojoDirectos = new ReentrantLock();

    public WebSubService(Repositorios.Canales canales,
                         Repositorios.Publicaciones publicaciones,
                         Repositorios.Suscripciones suscripciones,
                         RestClient http, RelayProperties config,
                         YouTubeClient youtube, PushService push, Emisores emisores,
                         LectorDeFeed lector, AjustesService ajustes,
                         PlatformTransactionManager gestorDeTransacciones) {
        this.canales = canales;
        this.publicaciones = publicaciones;
        this.suscripciones = suscripciones;
        this.http = http;
        this.config = config;
        this.youtube = youtube;
        this.push = push;
        this.emisores = emisores;
        this.lector = lector;
        this.ajustes = ajustes;

        this.transaccionNueva = new TransactionTemplate(gestorDeTransacciones);
        this.transaccionNueva.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // -------------------------------------------------------------------------
    // Alta y baja de suscripciones
    // -------------------------------------------------------------------------

    public void suscribir(String channelId) { handshake(channelId, "subscribe"); }

    public void desuscribir(String channelId) { handshake(channelId, "unsubscribe"); }

    /**
     * Manda el handshake al hub, que responde 202 y despues nos llama por GET
     * para verificar la intencion.
     *
     * Dejamos constancia en la base ANTES de llamar, y en su propia
     * transaccion: Google verifica de forma asincrona y su GET puede llegar
     * antes de que esta peticion termine. Si el registro no estuviera
     * confirmado, rechazariamos nuestra propia suscripcion.
     */
    public void handshake(String channelId, String modo) {
        if (config.urlPublica().isBlank()) {
            throw new IllegalStateException(
                    "Falta RELAY_URL_PUBLICA. El hub no sabría a dónde entregar los avisos.");
        }

        String topic = RelayProperties.feedDe(channelId);

        // La anotación @Transactional(REQUIRES_NEW) que había aquí no hacía
        // nada: suscribir() llama a este método sobre `this`, sin pasar por el
        // proxy de Spring. Desde el panel, el registro quedaba dentro de la
        // transacción del controlador, sin confirmar, y el GET de verificación
        // del hub no lo encontraba. Con la plantilla, el commit ocurre de
        // verdad antes de hablar con el hub.
        transaccionNueva.executeWithoutResult(estado -> {
            Suscripcion registro = suscripciones.findById(channelId).orElseGet(Suscripcion::new);
            registro.setChannelId(channelId);
            registro.setTopic(topic);
            registro.setModo(modo);
            registro.setEstado(Suscripcion.PENDIENTE);
            registro.setSolicitadoEn(Instant.now());
            registro.setUltimoError(null);
            suscripciones.saveAndFlush(registro);
        });

        MultiValueMap<String, String> formulario = new LinkedMultiValueMap<>();
        formulario.add("hub.mode", modo);
        formulario.add("hub.topic", topic);
        formulario.add("hub.callback", config.urlWebhook());
        formulario.add("hub.verify", "async");
        formulario.add("hub.secret", config.websub().secreto());
        formulario.add("hub.lease_seconds", String.valueOf(config.websub().leaseSegundos()));

        try {
            int codigo = enviarAlHub(formulario);
            log.info("Handshake {} enviado para {} (HTTP {})", modo, channelId, codigo);

        } catch (Exception e) {
            String motivo = recortar(e.getMessage(), 500);
            transaccionNueva.executeWithoutResult(estado ->
                    suscripciones.findById(channelId).ifPresent(registro -> {
                        registro.setEstado(Suscripcion.ERROR);
                        registro.setUltimoError(motivo);
                        suscripciones.save(registro);
                    }));
            throw new IllegalStateException("El hub rechazó la petición: " + e.getMessage(), e);
        }
    }

    /**
     * El hub de Google es un App Engine antiguo y responde entre medio segundo
     * y veinte segundos. Cuando pasa de veinte devuelve 503 con Retry-After,
     * y eso ocurre en una fracción notable de las peticiones. Con un solo
     * intento, las suscripciones fallaban al azar y no se reintentaban hasta
     * el ciclo de renovación, cuatro días después.
     *
     * Reintentamos solo lo que puede mejorar: 5xx, 429 y fallos de red. Un 400
     * o un 404 son nuestros y repetirlos no los arregla.
     */
    private int enviarAlHub(MultiValueMap<String, String> formulario) {
        RuntimeException ultimo = null;

        for (int intento = 1; intento <= 3; intento++) {
            try {
                return http.post()
                        .uri(config.websub().hub())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(formulario)
                        .retrieve()
                        .toBodilessEntity()
                        .getStatusCode().value();

            } catch (HttpClientErrorException e) {
                if (e.getStatusCode().value() != 429) throw e;   // 4xx: no insistir
                ultimo = e;
            } catch (HttpServerErrorException | ResourceAccessException e) {
                ultimo = e;                                      // 5xx y tiempos agotados
            }

            log.warn("Intento {} de 3 fallido contra el hub: {}", intento, ultimo.getMessage());

            if (intento < 3) {
                try {
                    Thread.sleep(intento * 4000L);               // 4s, luego 8s
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw ultimo;
    }

    public void reintentarNoActivas() {
        List<Suscripcion> atrasadas = new ArrayList<>();
        atrasadas.addAll(suscripciones.findByEstado(Suscripcion.ERROR));
        atrasadas.addAll(suscripciones.findByEstado(Suscripcion.PENDIENTE));
        if (atrasadas.isEmpty()) return;

        log.info("Repescando {} suscripciones que no llegaron a ACTIVA", atrasadas.size());
        for (Suscripcion s : atrasadas) {
            try {
                // Una baja pendiente se reintenta como baja. Antes se volvía a
                // suscribir un canal que el panel había pedido cancelar.
                handshake(s.getChannelId(),
                        "unsubscribe".equals(s.getModo()) ? "unsubscribe" : "subscribe");
            } catch (Exception e) {
                log.warn("Repesca fallida para {}: {}", s.getChannelId(), e.getMessage());
            }
        }
    }
    // -------------------------------------------------------------------------
    // Verificación de intención (GET del hub)
    // -------------------------------------------------------------------------

    /**
     * Devuelve el challenge a responder tal cual, o null si no procede.
     *
     * El hub exige HTTP 200 con el valor exacto de hub.challenge como texto
     * plano. Si respondemos JSON, una redireccion o cualquier otro codigo,
     * descarta la suscripcion en silencio y nunca sabriamos por que dejaron de
     * llegar avisos.
     */
    @Transactional
    public String verificarIntencion(String modo, String topic, String challenge, long lease) {
        String channelId = lector.channelIdDelTopic(topic);
        if (channelId == null) {
            log.warn("Topic no reconocido: {}", topic);
            return null;
        }

        Optional<Suscripcion> encontrada = suscripciones.findById(channelId);
        if (encontrada.isEmpty()) {
            // Sin esta comprobación, cualquiera podría registrar nuestro
            // webhook como destino de feeds ajenos y usarnos de altavoz.
            log.warn("Verificación de un canal que nunca solicitamos: {}", channelId);
            return null;
        }

        Suscripcion registro = encontrada.get();
        registro.setEstado("subscribe".equals(modo) ? Suscripcion.ACTIVA : Suscripcion.CANCELADA);
        registro.setLeaseSegundos(lease > 0 ? lease : null);
        registro.setExpiraEn(lease > 0 ? Instant.now().plusSeconds(lease) : null);
        registro.setVerificadoEn(Instant.now());
        registro.setUltimoError(null);
        suscripciones.save(registro);

        log.info("Suscripción {} verificada para {} (lease {}s)", modo, channelId, lease);
        return challenge;
    }

    // -------------------------------------------------------------------------
    // Llegada de contenido (POST del hub)
    // -------------------------------------------------------------------------

    public void procesarAviso(byte[] cuerpo) {
        LectorDeFeed.Feed feed = lector.leer(cuerpo);

        for (String videoId : feed.borrados()) {
            marcarRetirado(videoId);
        }

        for (LectorDeFeed.Entrada entrada : feed.entradas()) {
            try {
                procesarEntrada(entrada);
            } catch (Exception e) {
                // Un fallo en una entrada no debe tumbar el lote ni provocar
                // reintentos del hub, que acabarían cancelando la suscripción.
                log.error("Fallo procesando la entrada {}", entrada.videoId(), e);
            }
        }
    }

    @Transactional
    public void marcarRetirado(String videoId) {
        publicaciones.findByVideoId(videoId).ifPresent(p -> {
            p.setEstado(Publicacion.RETIRADO);
            publicaciones.save(p);
            log.info("Video {} marcado como retirado", videoId);
        });
    }

    @Transactional
    public void procesarEntrada(LectorDeFeed.Entrada entrada) {
        // 1. Encontrar el canal y en nombre de quién se avisa: su creador, su
        //    productora o los dos. Si el canal no está en el directorio curado
        //    o su dueño está oculto, no hay a quién avisar.
        Optional<Canal> encontrado = canales.porCanalDeYouTube(entrada.channelId());
        if (encontrado.isEmpty()) {
            log.debug("Canal {} fuera del directorio", entrada.channelId());
            return;
        }

        Canal canal = encontrado.get();
        Optional<PushService.Emisor> quien = emisores.de(canal);
        if (quien.isEmpty()) return;

        PushService.Emisor emisor = quien.get();

        // 2. Idempotencia. El hub entrega "al menos una vez" y YouTube reenvía
        //    la entrada cada vez que el creador edita el título. El índice
        //    único sobre video_id es quien decide de verdad: si dos hilos
        //    llegan a la vez, uno inserta y el otro recibe la violación.
        Optional<Publicacion> conocida = publicaciones.findByVideoId(entrada.videoId());
        if (conocida.isPresent()) {
            // La excepción son los directos programados: YouTube vuelve a
            // mandar la entrada cuando arrancan, y ese segundo aviso es el que
            // importa. Antes se descartaba aquí por repetido.
            if (Publicacion.DIRECTO_PROGRAMADO.equals(conocida.get().getDirecto())) {
                revisarDirectos();
            } else {
                log.debug("Entrada repetida ignorada: {}", entrada.videoId());
            }
            return;
        }

        // 3. Descartar backfill: al suscribirnos, el hub reenvía entradas
        //    recientes del feed y no queremos avisar de videos de la semana
        //    pasada como si acabaran de salir.
        Instant limite = Instant.now().minusSeconds(config.websub().antiguedadMaximaHoras() * 3600);
        boolean esViejo = entrada.publicado() != null && entrada.publicado().isBefore(limite);

        // 4. Enriquecer: 1 unidad de cuota. El XML de WebSub llega sin
        //    descripción ni miniatura, así que sin este paso no se puede armar
        //    una notificación presentable.
        YouTubeClient.DetalleDeVideo detalle = youtube.detallesDeVideo(entrada.videoId());

        Publicacion p = new Publicacion();
        p.setVideoId(entrada.videoId());
        p.setCreadorId(canal.getCreadorId());
        p.setCanalId(canal.getId());
        p.setPlataforma("youtube");
        p.setTitulo(detalle != null ? detalle.titulo()
                : (entrada.titulo() != null ? entrada.titulo() : "Video nuevo"));
        p.setDescripcion(detalle != null ? detalle.descripcion() : null);
        p.setMiniaturaUrl(detalle != null && detalle.miniatura() != null
                ? detalle.miniatura()
                : "https://i.ytimg.com/vi/" + entrada.videoId() + "/hqdefault.jpg");
        p.setDuracion(detalle != null ? detalle.duracion() : null);
        p.setTipo(tipoDe(entrada.videoId(), detalle));
        p.setEnVivo(detalle != null && detalle.enVivo());
        p.setDirecto(detalle != null ? detalle.directo() : Publicacion.DIRECTO_NO);
        p.setUrl("https://www.youtube.com/watch?v=" + entrada.videoId());
        p.setPublicadoEn(entrada.publicado() != null ? entrada.publicado() : Instant.now());
        p.setEstado(Publicacion.OK);

        try {
            publicaciones.saveAndFlush(p);
        } catch (DataIntegrityViolationException e) {
            // Otro hilo se nos adelantó con el mismo video. Es exactamente lo
            // que queremos que pase: solo uno manda la push.
            log.debug("Carrera resuelta por el índice único: {}", entrada.videoId());
            return;
        }

        // Un directo programado todavía no es noticia: se guarda y el
        // vigilante avisa cuando arranque de verdad.
        if (Publicacion.DIRECTO_PROGRAMADO.equals(p.getDirecto())) {
            log.info("Directo {} programado; se avisará cuando arranque", entrada.videoId());
            return;
        }

        // La fecha de un directo es la de cuando se programó, que puede ser de
        // hace días. Si está al aire ahora, el corte de antigüedad no aplica.
        boolean alAire = Publicacion.DIRECTO_EN_VIVO.equals(p.getDirecto());

        if (esViejo && !alAire) {
            log.info("Video {} guardado sin notificar (publicado {})",
                    entrada.videoId(), entrada.publicado());
            return;
        }

        // 5. Avisar. Un video corto va aparte: solo si el equipo los tiene
        //    encendidos en el panel, y solo a quienes los quieren ver. Apagados,
        //    se queda guardado y nada más; si un día se encienden, ya está ahí.
        boolean enviado;
        if (p.esCorto()) {
            if (!ajustes.cortos()) {
                log.info("Video corto {} guardado sin notificar: están apagados en el panel",
                        entrada.videoId());
                return;
            }
            enviado = push.avisarCorto(emisor, entrada.videoId(), p.getTitulo(), p.getMiniaturaUrl());
        } else {
            enviado = push.avisarPublicacion(emisor, entrada.videoId(), p.getTitulo(),
                    p.getMiniaturaUrl(), detalle);
        }

        // Solo se marca si FCM aceptó el mensaje. Antes quedaba en true aunque
        // el envío fallara, y la base de datos decía "notificado" sin serlo.
        p.setNotificado(enviado);
        if (alAire) p.setDirectoAvisado(enviado);
        publicaciones.save(p);
    }

    /**
     * Video normal o corto.
     *
     * La duración sola no basta: un Short dura como mucho tres minutos, pero
     * también hay videos normales así de breves (un avance, un comunicado), y
     * tratarlos de cortos sería esconderlos. Por eso, a los que duran poco se
     * les pregunta además a YouTube, que es quien lo sabe. A los largos y a
     * los directos no hace falta.
     */
    private String tipoDe(String videoId, YouTubeClient.DetalleDeVideo detalle) {
        if (detalle == null) return Publicacion.TIPO_VIDEO;

        boolean hayDuda = Publicacion.TIPO_CORTO.equals(detalle.tipo())
                && Publicacion.DIRECTO_NO.equals(detalle.directo());

        return YouTubeClient.tipoDe(detalle.tipo(), detalle.directo(),
                hayDuda ? youtube.esShort(videoId) : null);
    }

    // -------------------------------------------------------------------------
    // Directos
    // -------------------------------------------------------------------------

    /**
     * Revisa los directos programados o al aire y actúa sobre los que cambiaron.
     *
     * El hub no garantiza un aviso en el momento en que una transmisión
     * arranca, así que no se puede depender de él. Esto pregunta a la Data API
     * por todos los pendientes de una vez: 1 unidad de cuota por cada 50.
     */
    public void revisarDirectos() {
        cerrojoDirectos.lock();
        try {
            List<Publicacion> pendientes = publicaciones.findByDirectoIn(
                    List.of(Publicacion.DIRECTO_PROGRAMADO, Publicacion.DIRECTO_EN_VIVO));
            if (pendientes.isEmpty()) return;

            Map<String, YouTubeClient.DetalleDeVideo> detalles = youtube.detallesDeVideos(
                    pendientes.stream().map(Publicacion::getVideoId).toList());

            // null es un fallo de la API, no "ya no existen". Sin esta
            // distinción, un corte de red daría todos los directos por
            // terminados.
            if (detalles == null) return;

            for (Publicacion p : pendientes) {
                try {
                    actualizarDirecto(p, detalles.get(p.getVideoId()));
                } catch (Exception e) {
                    log.error("Fallo revisando el directo {}", p.getVideoId(), e);
                }
            }
        } finally {
            cerrojoDirectos.unlock();
        }
    }

    private void actualizarDirecto(Publicacion p, YouTubeClient.DetalleDeVideo detalle) {
        String antes = p.getDirecto();

        // Un video que la API ya no devuelve se borró o se hizo privado.
        String ahora = detalle != null ? detalle.directo() : Publicacion.DIRECTO_TERMINADO;

        boolean alAire = Publicacion.DIRECTO_EN_VIVO.equals(ahora);
        boolean faltaAvisar = alAire && !p.isDirectoAvisado();

        if (ahora.equals(antes) && !faltaAvisar) return;

        p.setDirecto(ahora);
        p.setEnVivo(alAire);
        if (detalle != null) {
            // El título de un directo suele cambiar entre que se programa y
            // que sale al aire.
            p.setTitulo(detalle.titulo());
            p.setDuracion(detalle.duracion());
        }

        if (faltaAvisar) {
            Optional<PushService.Emisor> emisor = emisores.de(p);

            if (emisor.isPresent()) {
                boolean enviado = push.avisarDirecto(emisor.get(), p.getVideoId(),
                        p.getTitulo(), p.getMiniaturaUrl());
                // Si FCM falló, queda sin marcar y se reintenta en la
                // siguiente pasada, mientras el directo siga al aire.
                p.setDirectoAvisado(enviado);
                if (enviado) p.setNotificado(true);
            } else {
                p.setDirectoAvisado(true);
            }
        }

        publicaciones.save(p);
        log.info("Directo {}: {} -> {}", p.getVideoId(), antes, ahora);
    }

    /**
     * Lee el feed público de cada canal y procesa lo que el hub no entregó.
     *
     * Cubre tres huecos: lo que ya estaba publicado o programado cuando se dio
     * de alta el canal, los avisos que el hub pierde, y los que entrega con
     * horas de retraso. Leer el feed no gasta cuota; solo los videos nuevos
     * cuestan 1 unidad al enriquecerlos.
     */
    public void sondearFeeds() {
        for (Canal vigilado : canales.vivosDeYouTube()) {
            String canal = vigilado.getCanalDeYouTube();
            if (canal == null) continue;

            try {
                byte[] xml = http.get()
                        .uri("https://www.youtube.com/feeds/videos.xml?channel_id={canal}", canal)
                        .retrieve()
                        .body(byte[].class);
                if (xml == null || xml.length == 0) continue;

                for (LectorDeFeed.Entrada entrada : lector.leer(xml).entradas()) {
                    if (!canal.equals(entrada.channelId())) continue;
                    if (publicaciones.existsByVideoId(entrada.videoId())) continue;

                    log.info("Video {} de {} encontrado por sondeo", entrada.videoId(), canal);
                    procesarEntrada(entrada);
                }
            } catch (Exception e) {
                // El feed público falla de vez en cuando. No es grave: se
                // vuelve a intentar en la siguiente pasada.
                log.warn("Sondeo fallido para {}: {}", canal, e.getMessage());
            }

            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Renovación de arrendamientos
    // -------------------------------------------------------------------------

    /**
     * El hub recorta el arrendamiento a unos 10 días como máximo, sin importar
     * cuánto pidamos. Pasado ese plazo deja de enviar avisos y no notifica
     * nada: la app simplemente se queda muda. Renovamos cada 4 días para que
     * dos fallos seguidos no rompan el servicio.
     */
    public Dtos.ResultadoRenovacion renovarTodas() {
        List<Canal> vigilados = canales.vivosDeYouTube();

        int renovados = 0;
        int fallidos = 0;
        List<String> errores = new ArrayList<>();

        for (Canal vigilado : vigilados) {
            String canal = vigilado.getCanalDeYouTube();
            if (canal == null) continue;

            try {
                // Reenviar el handshake es idempotente: si la suscripción sigue
                // viva, el hub simplemente extiende el plazo.
                suscribir(canal);
                renovados++;
            } catch (Exception e) {
                fallidos++;
                errores.add(canal + ": " + e.getMessage());
                log.error("Renovación fallida para {}", canal, e);
            }

            // Ritmo suave para no disparar el límite de peticiones del hub.
            try {
                Thread.sleep(120);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        log.info("Ciclo de renovación terminado: {} renovados, {} fallidos", renovados, fallidos);
        return new Dtos.ResultadoRenovacion(renovados, fallidos,
                errores.subList(0, Math.min(errores.size(), 20)));
    }

    private String recortar(String texto, int maximo) {
        if (texto == null) return null;
        return texto.length() <= maximo ? texto : texto.substring(0, maximo);
    }
}
