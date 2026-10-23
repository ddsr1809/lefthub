package com.voces.backend.seguridad;

import com.voces.backend.config.SeguridadConfig.Sesion;
import com.voces.backend.seguridad.ModosDeLaApp.Modo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Panel → Apps → Llave de la app: cómo está cada comprobación y cuántas
 * peticiones la pasan, y el interruptor de cada una (apagado, avisar,
 * exigir). Bajo /api/admin: solo administradores.
 */
@RestController
@RequestMapping("/api/admin/apps/llave")
public class LlaveDeAppAdminController {

    private static final Logger log = LoggerFactory.getLogger(LlaveDeAppAdminController.class);

    private final FirmaDeApp firma;
    private final VerificadorAppCheck appCheck;
    private final ModosDeLaApp modos;
    private final CifrasDeLaApp cifras;

    public LlaveDeAppAdminController(FirmaDeApp firma, VerificadorAppCheck appCheck,
                                     ModosDeLaApp modos, CifrasDeLaApp cifras) {
        this.firma = firma;
        this.appCheck = appCheck;
        this.modos = modos;
        this.cifras = cifras;
    }

    public record Comprobacion(boolean configurada, String modo) {}

    public record Estado(Comprobacion firma, Comprobacion appCheck, List<CifrasDeLaApp.Hora> horas) {}

    /** Lo que manda el panel. Un campo vacío deja ese modo como está. */
    public record Cambio(String firma, String appCheck) {}

    @GetMapping
    public Estado estado() {
        ModosDeLaApp.Modos m = modos.actuales();
        return new Estado(
                new Comprobacion(firma.configurada(), m.firma().nombre()),
                new Comprobacion(appCheck.configurado(), m.appCheck().nombre()),
                cifras.ultimas());
    }

    @PutMapping
    public Estado cambiar(@RequestBody Cambio cambio) {
        Modo f = leer(cambio.firma(), "firma");
        Modo a = leer(cambio.appCheck(), "App Check");
        if (f == Modo.EXIGIR && !firma.configurada()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No se puede exigir la firma: falta APP_LLAVES en el .env del servidor.");
        }
        if (a == Modo.EXIGIR && !appCheck.configurado()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No se puede exigir App Check: falta APP_CHECK_PROYECTO en el .env del servidor.");
        }
        modos.poner(f, a);
        log.info("Modos de la llave de la app cambiados por {}", Sesion.exigir());
        return estado();
    }

    private static Modo leer(String valor, String que) {
        if (valor == null || valor.isBlank()) return null;
        Modo m = Modo.pedido(valor);
        if (m == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El modo de " + que + " tiene que ser apagado, avisar o exigir.");
        }
        return m;
    }
}
