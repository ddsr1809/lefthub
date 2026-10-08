package com.tuempresa.relay.avisos;

import com.tuempresa.relay.config.RelayProperties;
import com.tuempresa.relay.modelo.Ajuste;
import com.tuempresa.relay.modelo.Canal;
import com.tuempresa.relay.modelo.Creador;
import com.tuempresa.relay.modelo.DispositivoAdmin;
import com.tuempresa.relay.modelo.Publicacion;
import com.tuempresa.relay.modelo.Reporte;
import com.tuempresa.relay.modelo.Repositorios;
import com.tuempresa.relay.modelo.Suscripcion;
import com.tuempresa.relay.push.Emisores;
import com.tuempresa.relay.push.PushService.Emisor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Los avisos al teléfono de quien administra: llegó un reporte, un canal dejó
 * de recibir publicaciones.
 *
 * Llegan al panel instalado como app, o a un navegador que los haya
 * activado, por Web Push (ver {@link WebPush}). No tienen nada que ver con
 * los avisos de publicaciones que reciben los usuarios de la app, que salen
 * de PushService por FCM.
 *
 * Aquí nada debe estorbar a lo que lo provoca: un reporte se guarda aunque el
 * aviso no salga. Por eso los avisos se mandan aparte, cuando lo otro ya
 * quedó guardado, y ningún fallo de aquí llega a quien llamó.
 */
@Service
public class AvisosAdminService {

    private static final Logger log = LoggerFactory.getLogger(AvisosAdminService.class);

    /** Las claves con las que se firman los avisos, en la tabla de ajustes. */
    static final String CLAVE_PUBLICA = "avisos_panel_publica";
    static final String CLAVE_PRIVADA = "avisos_panel_privada";

    /** Nadie necesita más; un tope evita que una cuenta llene la tabla. */
    static final int MAX_POR_CUENTA = 10;

    /** Cuánto tiene que llevar un canal sin suscripción activa para avisar. */
    static final Duration ESPERA_DE_CANAL = Duration.ofMinutes(30);

    /** Un aviso que no se pudo entregar en un día ya no le sirve a nadie. */
    private static final Duration VIGENCIA = Duration.ofHours(24);

    private final Repositorios.DispositivosAdmin dispositivos;
    private final Repositorios.Ajustes ajustes;
    private final Repositorios.Reportes reportes;
    private final Repositorios.Publicaciones publicaciones;
    private final Repositorios.Creadores creadores;
    private final Repositorios.Canales canales;
    private final Repositorios.Suscripciones suscripciones;
    private final Emisores emisores;
    private final RelayProperties config;

    private final WebPush push = new WebPush();
    private final VigiaDeCanales vigia = new VigiaDeCanales(ESPERA_DE_CANAL);

    private WebPush.Claves claves;

    public AvisosAdminService(Repositorios.DispositivosAdmin dispositivos,
                              Repositorios.Ajustes ajustes,
                              Repositorios.Reportes reportes,
                              Repositorios.Publicaciones publicaciones,
                              Repositorios.Creadores creadores,
                              Repositorios.Canales canales,
                              Repositorios.Suscripciones suscripciones,
                              Emisores emisores,
                              RelayProperties config) {
        this.dispositivos = dispositivos;
        this.ajustes = ajustes;
        this.reportes = reportes;
        this.publicaciones = publicaciones;
        this.creadores = creadores;
        this.canales = canales;
        this.suscripciones = suscripciones;
        this.emisores = emisores;
        this.config = config;
    }

    // -------------------------------------------------------------------------
    // Claves
    // -------------------------------------------------------------------------

    /**
     * El par de claves de este servidor. Se crea solo la primera vez que hace
     * falta y se guarda en la base, así que no hay nada que configurar: ni
     * variables en el .env ni cuenta en ningún servicio.
     *
     * Cambiarlas (borrando las dos filas de `ajustes`) deja sin avisos a
     * todos los dispositivos hasta que cada uno los active otra vez.
     */
    public synchronized WebPush.Claves claves() {
        if (claves != null) return claves;

        Optional<Ajuste> publica = ajustes.findById(CLAVE_PUBLICA);
        Optional<Ajuste> privada = ajustes.findById(CLAVE_PRIVADA);
        if (publica.isPresent() && privada.isPresent()) {
            claves = new WebPush.Claves(publica.get().getValor(), privada.get().getValor());
            return claves;
        }

        WebPush.Claves nuevas = WebPush.nuevasClaves();
        guardar(CLAVE_PRIVADA, nuevas.privada());
        guardar(CLAVE_PUBLICA, nuevas.publica());
        log.info("Claves de los avisos del panel creadas");
        claves = nuevas;
        return claves;
    }

    private void guardar(String clave, String valor) {
        Ajuste ajuste = ajustes.findById(clave).orElseGet(() -> new Ajuste(clave));
        ajuste.setValor(valor);
        ajuste.setActualizadoEn(Instant.now());
        ajustes.save(ajuste);
    }

    /**
     * A quién escribir si el servicio de avisos de un navegador detecta un
     * problema con lo que mandamos. Apple no entrega nada sin esto.
     */
    private String contacto() {
        String url = config.urlPublica();
        return url != null && url.startsWith("https://") ? url : "mailto:avisos@vocesdeizquierda.com";
    }

    // -------------------------------------------------------------------------
    // Dispositivos
    // -------------------------------------------------------------------------

    public List<DispositivoAdmin> de(UUID usuario) {
        return dispositivos.findByUsuarioIdOrderByCreadoEnAsc(usuario);
    }

    /**
     * Da de alta un dispositivo, o lo confirma si ya estaba. El panel llama
     * aquí cada vez que se abre con los avisos activados: así, si el
     * navegador cambió sus claves, el servidor se entera.
     */
    public DispositivoAdmin registrar(UUID usuario, String endpoint, String p256dh,
                                      String auth, String nombre) {

        String motivo = WebPush.motivoDeRechazo(endpoint);
        if (motivo != null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, motivo);
        if (!WebPush.clavesValidas(p256dh, auth)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El navegador mandó unas claves de avisos que no se pueden usar.");
        }

        String direccion = endpoint.trim();
        DispositivoAdmin dispositivo = dispositivos.findByEndpoint(direccion).orElse(null);

        if (dispositivo == null) {
            if (dispositivos.findByUsuarioIdOrderByCreadoEnAsc(usuario).size() >= MAX_POR_CUENTA) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Ya tienes " + MAX_POR_CUENTA + " dispositivos con avisos. Quita alguno de la lista primero.");
            }
            dispositivo = new DispositivoAdmin();
            dispositivo.setEndpoint(direccion);
        }

        // Si otra persona entra al panel en el mismo navegador, los avisos de
        // ese navegador pasan a ser suyos.
        dispositivo.setUsuarioId(usuario);
        dispositivo.setP256dh(p256dh.trim());
        dispositivo.setAuth(auth.trim());
        if (nombre != null && !nombre.isBlank()) {
            dispositivo.setNombre(Aviso.recortar(nombre, 80));
        }
        dispositivo.setVistoEn(Instant.now());
        return dispositivos.save(dispositivo);
    }

    /** Quita un dispositivo de la cuenta. Devuelve si estaba. */
    public boolean quitar(UUID usuario, String endpoint) {
        if (endpoint == null) return false;
        return dispositivos.findByEndpoint(endpoint.trim())
                .filter(d -> d.getUsuarioId().equals(usuario))
                .map(d -> { dispositivos.delete(d); return true; })
                .orElse(false);
    }

    public boolean quitar(UUID usuario, UUID id) {
        return dispositivos.findById(id)
                .filter(d -> d.getUsuarioId().equals(usuario))
                .map(d -> { dispositivos.delete(d); return true; })
                .orElse(false);
    }

    /**
     * Manda un aviso de prueba a un dispositivo de la cuenta y espera la
     * respuesta. Devuelve null si su servicio de avisos lo aceptó, o el
     * motivo, escrito para leerse en el panel.
     */
    public String probar(UUID usuario, String endpoint) {
        DispositivoAdmin dispositivo = endpoint == null ? null
                : dispositivos.findByEndpoint(endpoint.trim())
                        .filter(d -> d.getUsuarioId().equals(usuario))
                        .orElse(null);
        if (dispositivo == null) {
            return "Este dispositivo no tiene los avisos activados.";
        }

        WebPush.Resultado resultado = entregar(dispositivo, Aviso.dePrueba(), claves());
        if (resultado.entregado()) return null;
        if (resultado.caducada()) {
            return "El navegador ya no acepta avisos para este dispositivo. Actívalos de nuevo.";
        }
        return motivoLegible(resultado);
    }

    // -------------------------------------------------------------------------
    // Qué provoca un aviso
    // -------------------------------------------------------------------------

    /**
     * Alguien reportó un enlace, o quedó apuntada una incidencia.
     *
     * Se llama después de guardar el reporte, dentro de la misma transacción;
     * el aviso sale cuando esa transacción ya quedó confirmada, y fuera de
     * ella. Si cincuenta personas reportan el mismo video, avisa el primero:
     * mientras ese siga sin atender, los demás solo suman en el panel.
     */
    public void reporteNuevo(Reporte reporte) {
        Long id = reporte.getId();
        if (id == null) return;
        despuesDeGuardar(() -> avisarDeReporte(id));
    }

    private void avisarDeReporte(Long id) {
        Reporte reporte = reportes.findById(id).orElse(null);
        if (reporte == null || reporte.isResuelto()) return;

        String videoId = reporte.getVideoId();
        boolean conVideo = videoId != null && !videoId.isBlank();

        long anteriores = conVideo
                ? reportes.countByVideoIdAndResueltoFalseAndIdLessThan(videoId, id)
                : reporte.getCreadorId() != null
                        ? reportes.countByCreadorIdAndVideoIdIsNullAndResueltoFalseAndIdLessThan(reporte.getCreadorId(), id)
                        : 0;
        if (anteriores > 0) return;

        Publicacion publicacion = conVideo ? publicaciones.findByVideoId(videoId).orElse(null) : null;
        UUID creadorId = reporte.getCreadorId() != null ? reporte.getCreadorId()
                : publicacion != null ? publicacion.getCreadorId() : null;
        String creador = creadorId == null ? null
                : creadores.findById(creadorId).map(Creador::getNombre).orElse(null);

        repartir(Aviso.deReporte(reporte.getMotivo(), reporte.getDetalle(), videoId,
                publicacion != null ? publicacion.getTitulo() : null, creador, id));
    }

    /**
     * Mira qué canales llevan un rato sin poder recibir publicaciones y avisa
     * de los nuevos. Lo llama la tarea programada, cada quince minutos.
     *
     * Solo cuentan los canales de un dueño visible, igual que en la sección
     * Canales del panel: de un creador oculto no se espera ningún video.
     */
    public void revisarCanales() {
        List<Suscripcion> paradas = new ArrayList<>();
        paradas.addAll(suscripciones.findByEstado(Suscripcion.ERROR));
        paradas.addAll(suscripciones.findByEstado(Suscripcion.PENDIENTE));

        List<String> conProblema = paradas.stream()
                // Una baja a medias no es un canal que deje de recibir.
                .filter(s -> !"unsubscribe".equals(s.getModo()))
                .map(Suscripcion::getChannelId)
                .toList();

        // Nombre que se lee en el aviso, por canal. Conserva el orden.
        Map<String, String> nombres = new LinkedHashMap<>();
        for (String channelId : vigia.porAvisar(conProblema, Instant.now())) {
            Canal canal = canales.porCanalDeYouTube(channelId).orElse(null);
            if (canal == null) continue;

            Optional<Emisor> dueno = emisores.de(canal);
            if (dueno.isEmpty()) continue;

            String nombre = canal.getNombre() != null && !canal.getNombre().isBlank()
                    ? canal.getNombre() + " (" + dueno.get().nombre() + ")"
                    : dueno.get().nombre();
            nombres.put(channelId, nombre);
        }
        if (nombres.isEmpty()) return;

        log.info("Canales sin suscripción activa desde hace más de {} min: {}",
                ESPERA_DE_CANAL.toMinutes(), nombres.keySet());
        nombres.keySet().forEach(vigia::avisado);
        repartir(Aviso.deCanales(List.copyOf(nombres.values())));
    }

    // -------------------------------------------------------------------------
    // Envío
    // -------------------------------------------------------------------------

    /** Manda el aviso a todos los dispositivos de las cuentas administradoras. */
    void repartir(Aviso aviso) {
        List<DispositivoAdmin> destinos = dispositivos.deAdministradores();
        if (destinos.isEmpty()) {
            log.debug("Aviso sin destinatarios (nadie activó los avisos del panel): {}", aviso.titulo());
            return;
        }

        WebPush.Claves firma = claves();
        int entregados = 0;
        for (DispositivoAdmin destino : destinos) {
            if (entregar(destino, aviso, firma).entregado()) entregados++;
        }
        log.info("Aviso del panel \"{}\": {} de {} dispositivos", aviso.titulo(), entregados, destinos.size());
    }

    private WebPush.Resultado entregar(DispositivoAdmin destino, Aviso aviso, WebPush.Claves firma) {
        WebPush.Resultado resultado = push.enviar(
                new WebPush.Suscripcion(destino.getEndpoint(), destino.getP256dh(), destino.getAuth()),
                firma, contacto(), aviso.carga(), aviso.etiqueta(), VIGENCIA);

        try {
            if (resultado.caducada()) {
                // Desinstalaron la app o quitaron el permiso: no va a volver.
                dispositivos.deleteById(destino.getId());
                log.info("Dispositivo {} dado de baja: su navegador ya no lo reconoce", destino.getId());
            } else {
                dispositivos.findById(destino.getId()).ifPresent(d -> {
                    if (resultado.entregado()) {
                        d.setUltimoEnvio(Instant.now());
                        d.setUltimoError(null);
                    } else {
                        d.setUltimoError(motivoLegible(resultado));
                        log.warn("No se pudo avisar al dispositivo {}: HTTP {} {}",
                                d.getId(), resultado.estado(), resultado.detalle());
                    }
                    dispositivos.save(d);
                });
            }
        } catch (RuntimeException e) {
            // Lo quitaron de la lista mientras se enviaba: nada que apuntar.
            log.debug("No se pudo apuntar el resultado del aviso en {}", destino.getId(), e);
        }
        return resultado;
    }

    private static String motivoLegible(WebPush.Resultado resultado) {
        if (resultado.estado() == 0) {
            return "El servidor no pudo conectar con el servicio de avisos del navegador ("
                    + resultado.detalle() + ").";
        }
        if (resultado.estado() == 401 || resultado.estado() == 403) {
            return "El servicio de avisos del navegador rechazó la firma del servidor (HTTP "
                    + resultado.estado() + "). Desactiva los avisos en este dispositivo y actívalos de nuevo.";
        }
        if (resultado.estado() == 429) {
            return "El servicio de avisos del navegador pide esperar antes de mandar más (HTTP 429).";
        }
        return "El servicio de avisos del navegador respondió HTTP " + resultado.estado()
                + (resultado.detalle().isBlank() ? "." : ": " + resultado.detalle());
    }

    /**
     * Corre la tarea aparte y, si hay una transacción abierta, cuando ya
     * quedó confirmada. Así el aviso nunca habla de algo que al final no se
     * guardó, y ni su espera ni sus fallos tocan a quien lo provocó.
     */
    private void despuesDeGuardar(Runnable tarea) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    aparte(tarea);
                }
            });
        } else {
            aparte(tarea);
        }
    }

    private void aparte(Runnable tarea) {
        Thread.ofVirtual().name("avisos-panel").start(() -> {
            try {
                tarea.run();
            } catch (Exception e) {
                log.error("No se pudo mandar un aviso del panel", e);
            }
        });
    }
}
