package com.tuempresa.relay.anuncios;

import com.tuempresa.relay.config.SeguridadConfig.Sesion;
import com.tuempresa.relay.modelo.Dtos;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

/**
 * Las dos maneras que tiene una persona de quitar los anuncios desde la app:
 * canjear un folio de regalo o registrar la compra que hizo en Google Play.
 *
 * Si la cuenta ve anuncios o no lo dice /api/perfil (campos `anuncios` y
 * `sinAnuncios`); aquí solo están las dos acciones que lo cambian.
 */
@RestController
@RequestMapping("/api/anuncios")
public class AnunciosController {

    private final SinAnunciosService servicio;
    private final TiendaGoogle tienda;
    private final Intentos intentos = new Intentos();

    public AnunciosController(SinAnunciosService servicio, TiendaGoogle tienda) {
        this.servicio = servicio;
        this.tienda = tienda;
    }

    /**
     * Canjea un folio de regalo. Vale una sola vez: al usarlo se borra.
     *
     * No hace falta haber guardado la cuenta con Google: un invitado también
     * puede canjearlo. Eso sí, el folio se queda en ESA cuenta; si después la
     * guarda con Google en el mismo teléfono, lo conserva.
     */
    @PostMapping("/folio")
    public Dtos.SinAnuncios canjear(@Valid @RequestBody Dtos.CanjearFolio peticion) {
        UUID uid = Sesion.exigir();
        Instant ahora = Instant.now();

        if (!intentos.puede(uid, ahora)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Demasiados intentos seguidos. Espera unos minutos y vuelve a probar.");
        }

        String codigo = Folios.normalizar(peticion.codigo());
        if (codigo == null) {
            intentos.fallo(uid, ahora);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Ese folio no está completo. Son diez letras y números, como ABCDE-FGHJK.");
        }

        try {
            servicio.canjear(uid, codigo);
        } catch (ResponseStatusException e) {
            // Solo cuenta como intento fallido el folio que no existe.
            if (e.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                intentos.fallo(uid, ahora);
            }
            throw e;
        }

        return new Dtos.SinAnuncios(true, "Listo. Ya no verás anuncios en esta cuenta.");
    }

    /**
     * Registra una compra hecha en Google Play.
     *
     * La app la manda al terminar de pagar, y también al arrancar mientras la
     * cuenta siga viendo anuncios: así se recupera sola una compra que no se
     * llegó a registrar (se cortó la conexión justo después de pagar) o que
     * se hizo en otro teléfono con la misma cuenta de Google Play.
     */
    @PostMapping("/compra")
    public Dtos.SinAnuncios comprar(@Valid @RequestBody Dtos.RegistrarCompra peticion) {
        UUID uid = Sesion.exigir();

        // Primero Google, sin transacción abierta; después, la base de datos.
        TiendaGoogle.Recibo recibo = tienda.confirmar(peticion.producto(), peticion.token());
        servicio.apuntarCompra(uid, peticion.producto(), peticion.token(), recibo);

        return new Dtos.SinAnuncios(true, "Gracias por tu compra. Ya no verás anuncios.");
    }
}
