package com.voces.backend.version;

import com.voces.backend.RelayApplication;
import com.voces.backend.config.RelayProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Qué servidor es este: su versión y el commit del que salió.
 *
 * Es pública a propósito. Las apps preguntan aquí si el servidor está vivo y
 * si está en mantenimiento. Y sirve para saber, sin entrar al VPS, qué quedó
 * desplegado en cada ambiente:
 *
 *   curl https://testapp.vocesdeizquierda.com/api/servidor
 *
 * No tiene que ver con las "versiones" del directorio (VersionesController),
 * que son copias de los creadores y los medios.
 */
@RestController
public class ServidorController {

    /**
     * @param version           la de build.gradle.kts con la que se compiló
     * @param commit            el commit de la imagen; falta si se compiló a mano
     * @param appMinimaAndroid  la versión mínima de la app de Android, si hay
     * @param appMinimaIos      la versión mínima de la app de iOS, si hay
     * @param mantenimiento     true mientras las apps están fuera; si no, falta
     * @param mensaje           lo que se le dice a la gente durante el mantenimiento
     */
    public record Servidor(String version, String commit,
                           String appMinimaAndroid, String appMinimaIos,
                           Boolean mantenimiento, String mensaje) {}

    private final Servidor servidor;
    private final MantenimientoService mantenimiento;

    public ServidorController(RelayProperties config, MantenimientoService mantenimiento) {
        this.mantenimiento = mantenimiento;
        // La versión viaja en el manifiesto del JAR. Al ejecutar desde el IDE
        // o con bootRun no hay JAR, y por eso no hay versión.
        String version = RelayApplication.class.getPackage().getImplementationVersion();
        this.servidor = new Servidor(
                version == null || version.isBlank() ? "desarrollo" : version,
                oNada(config.servidor().commit()),
                oNada(config.apps().minimaAndroid()),
                oNada(config.apps().minimaIos()),
                null, null);
    }

    /**
     * La app la llama cada vez que se abre, para saber si el servidor está
     * vivo y si está en mantenimiento antes de pedir nada más.
     */
    @GetMapping("/api/servidor")
    public Servidor servidor() {
        MantenimientoService.Estado estado = mantenimiento.estado();
        if (!estado.activo()) return servidor;
        return new Servidor(servidor.version(), servidor.commit(),
                servidor.appMinimaAndroid(), servidor.appMinimaIos(),
                true, estado.mensaje());
    }

    private static String oNada(String valor) {
        return valor == null || valor.isBlank() ? null : valor.trim();
    }
}
