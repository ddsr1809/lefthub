package com.tuempresa.relay.avisos;

import com.tuempresa.relay.config.SeguridadConfig.Sesion;
import com.tuempresa.relay.modelo.DispositivoAdmin;
import com.tuempresa.relay.modelo.Dtos;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Sección "App y avisos" del panel: qué dispositivos de la cuenta reciben
 * avisos, y activarlos o quitarlos.
 *
 * Vive bajo /api/admin, así que la regla de SeguridadConfig ya exige el rol
 * de administrador. Cada quien ve y toca solo sus propios dispositivos.
 */
@RestController
@RequestMapping("/api/admin/avisos")
public class AvisosAdminController {

    private final AvisosAdminService avisos;

    public AvisosAdminController(AvisosAdminService avisos) {
        this.avisos = avisos;
    }

    /**
     * @param clavePublica la que el navegador necesita para suscribirse: los
     *                     avisos solo le llegarán firmados con su pareja
     */
    public record Estado(String clavePublica, List<Dispositivo> dispositivos) {}

    public record Dispositivo(UUID id, String nombre, String endpoint, Instant creadoEn,
                              Instant vistoEn, Instant ultimoEnvio, String ultimoError) {

        static Dispositivo de(DispositivoAdmin d) {
            return new Dispositivo(d.getId(), d.getNombre(), d.getEndpoint(), d.getCreadoEn(),
                    d.getVistoEn(), d.getUltimoEnvio(), d.getUltimoError());
        }
    }

    /** Lo que entrega el navegador al suscribirse, más un nombre para la lista. */
    public record Alta(String endpoint, String p256dh, String auth, String nombre) {}

    public record Referencia(String endpoint) {}

    @GetMapping
    public Estado estado() {
        return new Estado(avisos.claves().publica(), misDispositivos());
    }

    @PostMapping("/dispositivos")
    public Estado activar(@RequestBody Alta alta) {
        if (alta == null || alta.endpoint() == null || alta.p256dh() == null || alta.auth() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Faltan los datos del dispositivo.");
        }
        avisos.registrar(Sesion.exigir(), alta.endpoint(), alta.p256dh(), alta.auth(), alta.nombre());
        return estado();
    }

    /** Este dispositivo deja de recibir avisos. La dirección va en el cuerpo: no cabe bien en una URL. */
    @PostMapping("/dispositivos/baja")
    public Estado desactivar(@RequestBody Referencia referencia) {
        avisos.quitar(Sesion.exigir(), referencia != null ? referencia.endpoint() : null);
        return estado();
    }

    /** Quita de la lista otro dispositivo de la cuenta: un teléfono que ya no se usa. */
    @DeleteMapping("/dispositivos/{id}")
    public Estado quitar(@PathVariable UUID id) {
        if (!avisos.quitar(Sesion.exigir(), id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ese dispositivo ya no está en tu lista.");
        }
        return estado();
    }

    /**
     * Manda un aviso de prueba a este dispositivo y dice si su servicio de
     * avisos lo aceptó. Cuando no, responde igualmente 200 con {@code ok} en
     * falso y el motivo: la petición al panel salió bien, lo que falló está
     * más allá, y un 502 se confundiría con el del servidor arrancando.
     */
    @PostMapping("/prueba")
    public Dtos.RespuestaSimple probar(@RequestBody Referencia referencia) {
        String motivo = avisos.probar(Sesion.exigir(), referencia != null ? referencia.endpoint() : null);
        return motivo != null
                ? new Dtos.RespuestaSimple(false, motivo)
                : Dtos.RespuestaSimple.de("Aviso de prueba enviado. Debe aparecer en unos segundos.");
    }

    private List<Dispositivo> misDispositivos() {
        return avisos.de(Sesion.exigir()).stream().map(Dispositivo::de).toList();
    }
}
