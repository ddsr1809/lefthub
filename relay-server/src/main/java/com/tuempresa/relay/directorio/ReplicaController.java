package com.tuempresa.relay.directorio;

import com.tuempresa.relay.config.RelayProperties;
import com.tuempresa.relay.modelo.Dtos;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Las dos rutas de la copia de creadores. Como el resto de /internal, quien
 * llama es una máquina sin cuenta de usuario y se identifica con una cabecera
 * compartida. Con el token equivocado o sin configurar, la ruta no existe.
 */
@RestController
public class ReplicaController {

    private final ReplicaService replica;
    private final RelayProperties config;

    public ReplicaController(ReplicaService replica, RelayProperties config) {
        this.replica = replica;
        this.config = config;
    }

    /** Testing: recibe un creador de producción. */
    @PostMapping(ReplicaService.RUTA)
    public ResponseEntity<Dtos.RespuestaSimple> recibir(
            @RequestHeader(name = ReplicaService.CABECERA, required = false) String token,
            @RequestBody Dtos.GuardarCreador peticion
    ) {
        if (!replica.autoriza(token)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        ReplicaService.Recibido recibido = replica.recibir(peticion);

        // Después de recibir(): la transacción ya confirmó, y el GET de
        // verificación del hub va a encontrar al creador.
        replica.sincronizarDespues(recibido);

        return ResponseEntity.ok(Dtos.RespuestaSimple.de(
                recibido.nuevo() ? "Creador creado." : "Creador actualizado."));
    }

    /**
     * Producción: copia a testing todos los creadores que ya existen. Se puede
     * repetir: los que ya están copiados se actualizan, no se duplican.
     */
    @PostMapping("/internal/replicar-creadores")
    public ResponseEntity<Dtos.ResultadoReplica> replicarTodos(
            @RequestHeader(name = "X-Token-Interno", required = false) String token
    ) {
        if (!ReplicaService.coincide(config.renovacion().tokenInterno(), token)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.ok(replica.enviarTodos());
    }
}
