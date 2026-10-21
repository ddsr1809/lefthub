package com.voces.backend.directorio;

import com.voces.backend.directorio.CanalesService.Cambio;
import com.voces.backend.modelo.*;
import com.voces.backend.youtube.YouTubeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Guardar un creador, un medio o la ficha de un canal, con todo lo que va
 * detrás: la suscripción al hub de YouTube y la copia a testing.
 *
 * Lo usan el panel (AdminController) y las cuentas autorizadas desde la app
 * (editor/EditorController). Así lo que se agrega desde el teléfono queda
 * exactamente igual que lo que se agrega desde el panel.
 */
@Service
public class GuardadoDelDirectorio {

    private static final Logger log = LoggerFactory.getLogger(GuardadoDelDirectorio.class);

    static final Pattern ID_CANAL = Pattern.compile("(UC[\\w-]{22})");
    private static final Pattern HANDLE = Pattern.compile("@([\\w.-]+)");

    private final Repositorios.Creadores creadores;
    private final Repositorios.Productoras productoras;
    private final CreadoresService servicioDeCreadores;
    private final ProductorasService servicioDeProductoras;
    private final CanalesService canalesService;
    private final ReplicaService replica;
    private final YouTubeClient youtube;

    public GuardadoDelDirectorio(Repositorios.Creadores creadores, Repositorios.Productoras productoras,
                                 CreadoresService servicioDeCreadores, ProductorasService servicioDeProductoras,
                                 CanalesService canalesService, ReplicaService replica, YouTubeClient youtube) {
        this.creadores = creadores;
        this.productoras = productoras;
        this.servicioDeCreadores = servicioDeCreadores;
        this.servicioDeProductoras = servicioDeProductoras;
        this.canalesService = canalesService;
        this.replica = replica;
        this.youtube = youtube;
    }

    @Transactional
    public Dtos.CreadorGuardado guardarCreador(Dtos.GuardarCreador peticion) {
        Creador creador = peticion.id() != null
                ? creadores.findById(peticion.id()).orElseGet(Creador::new)
                : new Creador();

        Cambio cambio = servicioDeCreadores.aplicar(creador, peticion);

        // Sincronizar WebSub: bajas de los canales que dejó, altas de los que
        // tiene, o baja de todos si quedó oculto.
        String avisoSuscripcion = null;
        try {
            canalesService.sincronizarWebSub(cambio);
        } catch (Exception e) {
            // El creador queda guardado aunque el hub falle; la renovación
            // programada vuelve a intentarlo en el siguiente ciclo.
            log.error("No se pudo sincronizar la suscripción de {}", creador.getId(), e);
            avisoSuscripcion = e.getMessage();
        }

        // En producción, copiar a testing. En los demás ambientes no hace
        // nada. Tampoco lanza: si testing falla, se avisa y ya.
        String avisoReplica = replica.enviar(creador);

        return new Dtos.CreadorGuardado(creador.getId(), avisoSuscripcion, avisoReplica);
    }

    /**
     * Alta o edición de una productora, con sus canales propios y, si vienen,
     * los creadores que figuran en ella. Que un canal de un creador sea de la
     * productora se marca al guardar ese creador.
     */
    @Transactional
    public Dtos.ProductoraGuardada guardarProductora(Dtos.GuardarProductora peticion) {
        Productora productora = peticion.id() != null
                ? productoras.findById(peticion.id()).orElseGet(Productora::new)
                : new Productora();

        Cambio cambio = servicioDeProductoras.aplicar(productora, peticion);

        String avisoSuscripcion = null;
        try {
            canalesService.sincronizarWebSub(cambio);
        } catch (Exception e) {
            log.error("No se pudo sincronizar la suscripción de la productora {}", productora.getId(), e);
            avisoSuscripcion = e.getMessage();
        }

        String avisoReplica = replica.enviar(productora);

        return new Dtos.ProductoraGuardada(productora.getId(), avisoSuscripcion, avisoReplica);
    }

    /**
     * Alta o edición de la ficha de un canal de YouTube: de quién es, de qué
     * productora y con qué otros creadores aparece.
     */
    @Transactional
    public Dtos.CanalGuardado guardarCanal(Dtos.GuardarFichaDeCanal peticion) {
        CanalesService.Ficha ficha = canalesService.guardarFicha(peticion);

        String avisoSuscripcion = null;
        try {
            canalesService.sincronizarWebSub(ficha.cambio());
        } catch (Exception e) {
            log.error("No se pudo sincronizar la suscripción del canal {}", ficha.canal().getId(), e);
            avisoSuscripcion = e.getMessage();
        }

        return new Dtos.CanalGuardado(ficha.canal().getId(), avisoSuscripcion, copiarDuenos(ficha));
    }

    /**
     * Un canal no viaja solo a testing: va dentro de su dueño. Aquí se
     * vuelven a copiar el de ahora y, si cambió, el de antes.
     *
     * @return el primer motivo de fallo, o null si todo se copió.
     */
    public String copiarDuenos(CanalesService.Ficha ficha) {
        String aviso = null;

        for (Productora p : productoras.findAllById(ficha.productoras())) {
            String fallo = replica.enviar(p);
            if (aviso == null) aviso = fallo;
        }
        for (Creador c : creadores.findAllById(ficha.creadores())) {
            String fallo = replica.enviar(c);
            if (aviso == null) aviso = fallo;
        }
        return aviso;
    }

    /**
     * Los datos públicos de un canal de YouTube. Acepta un ID UC..., un
     * @handle o una URL completa. Coste: 1 o 2 unidades de cuota.
     */
    public Dtos.DatosDeCanal buscarCanal(String query) {
        String entrada = query == null ? "" : query.trim();
        if (entrada.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Escribe un ID de canal, un @handle o una URL.");
        }

        // WebSub exige el ID canónico: los handles no valen como hub.topic.
        String channelId;
        Matcher idDirecto = ID_CANAL.matcher(entrada);

        if (idDirecto.find()) {
            channelId = idDirecto.group(1);
        } else {
            Matcher conArroba = HANDLE.matcher(entrada);
            String handle = conArroba.find()
                    ? conArroba.group(1)
                    : (entrada.startsWith("@") ? entrada.substring(1) : entrada);
            channelId = youtube.resolverHandle(handle);
        }

        if (channelId == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No se encontró ese canal. Prueba con el ID que empieza por UC.");
        }

        Dtos.DatosDeCanal datos = youtube.detallesDeCanal(channelId);
        if (datos == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "El canal existe pero YouTube no devolvió datos.");
        }
        return datos;
    }
}
