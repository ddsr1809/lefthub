package com.voces.backend.version;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Sección "Apps" del panel: qué versiones hay instaladas, cuáles se dejan de
 * atender y el modo mantenimiento, que deja fuera a todas.
 *
 * Vive bajo /api/admin, así que la regla de SeguridadConfig ya exige el rol de
 * administrador en cada llamada.
 */
@RestController
@RequestMapping("/api/admin/apps")
public class AppsAdminController {

    /**
     * @param plataforma android | ios
     * @param version    la versión, o null para las apps que no dicen cuál son
     * @param baja       true para dejar de atenderla, false para volver a hacerlo
     */
    public record CambiarBaja(String plataforma, String version, Boolean baja) {}

    /**
     * @param activo  true para dejar a las apps fuera, false para volver a atenderlas
     * @param mensaje lo que se le dice a la gente; null conserva el que hubiera
     */
    public record CambiarMantenimiento(Boolean activo, String mensaje) {}

    private final AppsService apps;
    private final MantenimientoService mantenimiento;

    public AppsAdminController(AppsService apps, MantenimientoService mantenimiento) {
        this.apps = apps;
        this.mantenimiento = mantenimiento;
    }

    @GetMapping("/mantenimiento")
    public MantenimientoService.Estado mantenimiento() {
        return mantenimiento.estado();
    }

    @PutMapping("/mantenimiento")
    public MantenimientoService.Estado cambiarMantenimiento(@RequestBody CambiarMantenimiento peticion) {
        if (peticion.activo() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Di si el mantenimiento se enciende o se apaga.");
        }
        if (peticion.mensaje() != null && peticion.mensaje().trim().length() > MantenimientoService.LARGO_MENSAJE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El mensaje no puede pasar de " + MantenimientoService.LARGO_MENSAJE + " letras.");
        }
        return mantenimiento.poner(peticion.activo(), peticion.mensaje());
    }

    @GetMapping
    public AppsService.Apps ver() {
        return apps.resumen();
    }

    @PutMapping("/baja")
    public AppsService.Apps cambiar(@RequestBody CambiarBaja peticion) {
        String plataforma = peticion.plataforma() == null ? "" : peticion.plataforma().trim().toLowerCase();
        if (!VersionDeApp.ANDROID.equals(plataforma) && !VersionDeApp.IOS.equals(plataforma)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Di si la versión es de Android o de iOS.");
        }
        if (peticion.baja() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Di si la versión se da de baja o se vuelve a atender.");
        }
        if (peticion.version() != null && !peticion.version().isBlank()
                && VersionDeApp.limpia(peticion.version()) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Esa versión no se entiende. Escríbela como 1.0.0.");
        }
        apps.poner(plataforma, peticion.version(), peticion.baja());
        return apps.resumen();
    }
}
