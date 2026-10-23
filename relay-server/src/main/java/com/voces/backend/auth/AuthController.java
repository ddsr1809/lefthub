package com.voces.backend.auth;

import com.voces.backend.acceso.RegistroDeAcceso;
import com.voces.backend.chat.ChatService;
import com.voces.backend.config.SeguridadConfig;
import com.voces.backend.cuenta.AppleService;
import com.voces.backend.directorio.VideosVistos;
import com.voces.backend.modelo.Dtos;
import com.voces.backend.modelo.Repositorios;
import com.voces.backend.modelo.Usuario;
import com.voces.backend.seguridad.HuellaDeDispositivo;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Alta y enlace de cuentas.
 *
 * El flujo completo, en orden:
 *
 *   1. La app arranca y llama a /anonimo con el identificador del dispositivo.
 *      Se crea una cuenta al vuelo y se devuelve un token. Cero fricción.
 *   2. Si el usuario decide guardar sus datos, la app hace el inicio de sesión
 *      nativo con Google o Apple y nos manda el token resultante junto con su
 *      deviceId. Verificamos el token contra las claves públicas del proveedor.
 *   3. Si ese proveedor ya tenía cuenta aquí (teléfono anterior), nos pasamos
 *      a ella y arrastramos los favoritos de la anónima. Si no, convertimos la
 *      anónima en permanente.
 *
 * El paso 3 es el que evita que alguien pierda sus creadores al cambiar de
 * teléfono, y es donde fallan la mayoría de implementaciones.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final Repositorios.Usuarios usuarios;
    private final ServicioJwt jwt;
    private final VerificadorIdentidad verificador;
    private final AppleService apple;
    private final RegistroDeAcceso accesos;
    private final Repositorios.Compras compras;
    private final Repositorios.Recomendaciones recomendaciones;
    private final ChatService chat;
    private final VideosVistos vistos;

    public AuthController(Repositorios.Usuarios usuarios, ServicioJwt jwt,
                          VerificadorIdentidad verificador, AppleService apple,
                          RegistroDeAcceso accesos, Repositorios.Compras compras,
                          Repositorios.Recomendaciones recomendaciones, ChatService chat,
                          VideosVistos vistos) {
        this.usuarios = usuarios;
        this.jwt = jwt;
        this.verificador = verificador;
        this.apple = apple;
        this.accesos = accesos;
        this.compras = compras;
        this.recomendaciones = recomendaciones;
        this.chat = chat;
        this.vistos = vistos;
    }

    /**
     * Sesión invisible. Se llama al abrir la app, sin interfaz de por medio.
     *
     * Con el identificador del teléfono se recupera también una cuenta ya
     * guardada con Google o Apple (la sesión caducó tras un mes sin abrir la
     * app, por ejemplo). Pero nunca con el rol de administrador: el panel y
     * el chat del equipo exigen haber entrado con Google o Apple. Así, quien
     * consiguiera el identificador del teléfono del dueño no entra al panel.
     */
    @PostMapping("/anonimo")
    @Transactional
    public Dtos.Sesion anonimo(@Valid @RequestBody Dtos.EntrarAnonimo peticion,
                               HttpServletRequest http) {
        String huella = HuellaDeDispositivo.de(peticion.deviceId());
        Usuario usuario = porDispositivo(peticion.deviceId())
                .orElseGet(() -> {
                    Usuario nuevo = new Usuario();
                    nuevo.setDeviceId(huella);
                    nuevo.setProveedor(Usuario.ANONIMO);
                    return usuarios.save(nuevo);
                });

        usuario.setVistoEn(Instant.now());
        accesos.anotar(usuario, http);
        return sesionDe(usuario, false, false);
    }

    @PostMapping("/google")
    @Transactional
    public Dtos.Sesion google(@Valid @RequestBody Dtos.EntrarConProveedor peticion,
                              HttpServletRequest http) {
        VerificadorIdentidad.Identidad identidad = verificador.verificarGoogle(peticion.token())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "No pudimos verificar tu cuenta de Google. Inténtalo otra vez."));

        return enlazar(identidad, peticion.deviceId(), null, http);
    }

    @PostMapping("/apple")
    @Transactional
    public Dtos.Sesion apple(@Valid @RequestBody Dtos.EntrarConProveedor peticion,
                             HttpServletRequest http) {
        VerificadorIdentidad.Identidad identidad = verificador.verificarApple(peticion.token())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "No pudimos verificar tu cuenta de Apple. Inténtalo otra vez."));

        // El authorizationCode se canjea por un refresh token que hay que
        // guardar para poder revocar el vínculo cuando el usuario borre la
        // cuenta. Sin eso, Apple rechaza la app.
        String refresh = null;
        if (peticion.authorizationCode() != null && !peticion.authorizationCode().isBlank()) {
            refresh = apple.canjearCodigo(peticion.authorizationCode());
        }

        return enlazar(identidad, peticion.deviceId(), refresh, http);
    }

    /**
     * Renueva el token antes de que caduque.
     * La app lo llama al arrancar si le quedan menos de unos días.
     */
    @PostMapping("/renovar")
    @Transactional
    public Dtos.Sesion renovar(@RequestHeader("Authorization") String cabecera,
                               HttpServletRequest http) {
        String token = cabecera.startsWith("Bearer ") ? cabecera.substring(7).trim() : cabecera;

        ServicioJwt.Sesion sesion = jwt.verificar(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Tu sesión caducó. Vuelve a entrar."));

        Usuario usuario = usuarios.findById(sesion.usuarioId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Esa cuenta ya no existe."));

        usuario.setVistoEn(Instant.now());
        accesos.anotar(usuario, http);
        // El rol se conserva solo si la sesión que se renueva ya lo tenía.
        return sesionDe(usuario, false, sesion.esAdmin());
    }

    /**
     * Cerrar sesión en este teléfono. La app la llama antes de olvidar su
     * token, y después pide una sesión invisible nueva.
     *
     * Desliga el teléfono de la cuenta. Sin esto, la sesión invisible que
     * viene después devolvía la misma cuenta de Google o Apple: el botón
     * "Cerrar sesión" no cerraba nada, y quien recibiera el teléfono seguía
     * dentro de la cuenta anterior.
     *
     * A una cuenta de invitado no le hace nada: el teléfono es lo único que
     * la recupera, y desligarlo la dejaría perdida.
     */
    @PostMapping("/salir")
    @Transactional
    public Dtos.RespuestaSimple salir() {
        UUID id = SeguridadConfig.Sesion.exigir();
        usuarios.findById(id).ifPresent(usuario -> {
            if (!usuario.esAnonimo()) {
                usuario.setDeviceId(null);
                log.info("Teléfono desligado de la cuenta {}", id);
            }
        });
        return Dtos.RespuestaSimple.de("Sesión cerrada en este teléfono.");
    }

    // -------------------------------------------------------------------------

    private Dtos.Sesion enlazar(VerificadorIdentidad.Identidad identidad,
                                String deviceId, String appleRefresh,
                                HttpServletRequest http) {

        Optional<Usuario> existente =
                usuarios.findByProveedorAndProveedorSub(identidad.proveedor(), identidad.sub());

        // Quién tiene hoy este dispositivo. device_id es único en la tabla:
        // solo una cuenta puede llevarlo a la vez.
        String huella = HuellaDeDispositivo.de(deviceId);
        Optional<Usuario> dueno = porDispositivo(deviceId);
        Optional<Usuario> anonimo = dueno.filter(Usuario::esAnonimo);

        // Caso A: ya tenía cuenta con este proveedor, probablemente de otro
        // teléfono. Nos pasamos a ella y traemos los favoritos de la anónima.
        if (existente.isPresent()) {
            Usuario usuario = existente.get();
            boolean fusionados = false;

            if (anonimo.isPresent() && !anonimo.get().getId().equals(usuario.getId())) {
                // Copia: el conjunto original es de una cuenta que se borra.
                Set<UUID> entrantes = new HashSet<>(anonimo.get().getFavoritos());
                if (!entrantes.isEmpty()) {
                    usuario.getFavoritos().addAll(entrantes);
                    fusionados = true;
                }
                // Y con los canales que seguía por sí solos.
                Set<UUID> canales = new HashSet<>(anonimo.get().getCanalesSeguidos());
                if (!canales.isEmpty()) {
                    usuario.getCanalesSeguidos().addAll(canales);
                    fusionados = true;
                }
                // Lo mismo con las productoras que seguía.
                Set<UUID> productoras = new HashSet<>(anonimo.get().getProductorasSeguidas());
                if (!productoras.isEmpty()) {
                    usuario.getProductorasSeguidas().addAll(productoras);
                    fusionados = true;
                }
                // Si en este teléfono ya se habían quitado los anuncios (con
                // una compra o un folio, como invitado), la cuenta buena los
                // hereda. Y las compras se le pasan antes del borrado: son de
                // la cuenta que se va, y se irían con ella.
                if (anonimo.get().isSinAnuncios()) {
                    usuario.quitarAnuncios(anonimo.get().getSinAnunciosOrigen(),
                            anonimo.get().getSinAnunciosDesde());
                }
                compras.pasar(anonimo.get().getId(), usuario.getId());
                // Igual con los canales que recomendó como invitado, y con el
                // permiso de recomendar si el equipo se lo había dado.
                recomendaciones.pasar(anonimo.get().getId(), usuario.getId());
                if (anonimo.get().isRecomendarHabilitado()) usuario.setRecomendarHabilitado(true);
                if (anonimo.get().isYoutubeHabilitado()) usuario.setYoutubeHabilitado(true);
                if (anonimo.get().isStickersHabilitado()) usuario.setStickersHabilitado(true);
                // Y la conversación con el equipo, si la tenía: se cifra otra
                // vez para la cuenta que se queda, que hereda también el chat.
                chat.pasar(anonimo.get().getId(), usuario.getId());
                if (anonimo.get().isChatHabilitado()) usuario.setChatHabilitado(true);
                // Y los videos que abrió o marcó como vistos en este teléfono.
                vistos.pasar(anonimo.get().getId(), usuario.getId());
                // El dispositivo pasa a apuntar a la cuenta buena y la anónima
                // desaparece: dejarla suelta acumularía cuentas huérfanas.
                //
                // El orden importa. Hibernate envía los UPDATE antes que los
                // DELETE, así que sin este flush intentaba poner el device_id
                // en la cuenta buena mientras la anónima todavía lo tenía, y
                // la base lo rechazaba por duplicado: la persona veía "Algo
                // falló de nuestro lado" justo al recuperar su cuenta.
                usuarios.delete(anonimo.get());
                usuarios.flush();
                usuario.setDeviceId(huella);
                log.info("Cuenta anónima fusionada en {}", usuario.getId());
            }

            actualizarDatos(usuario, identidad, appleRefresh);
            accesos.anotar(usuario, http);
            return sesionDe(usuario, fusionados, true);
        }

        // Caso B: primer inicio de sesión con este proveedor. Convertimos la
        // cuenta anónima en permanente, conservando todo lo que ya tenía.
        //
        // Si el dispositivo lo llevaba otra cuenta ya guardada (alguien entró
        // antes en este teléfono con otra cuenta de Google o Apple), se le
        // quita primero: dos cuentas no pueden compartir device_id.
        if (dueno.isPresent() && !dueno.get().esAnonimo()) {
            dueno.get().setDeviceId(null);
            usuarios.flush();
        }
        Usuario usuario = anonimo.orElseGet(Usuario::new);
        usuario.setDeviceId(huella);
        usuario.setProveedor(identidad.proveedor());
        usuario.setProveedorSub(identidad.sub());
        actualizarDatos(usuario, identidad, appleRefresh);
        accesos.anotar(usuario, http);

        return sesionDe(usuarios.save(usuario), false, true);
    }

    /**
     * La cuenta ligada a este teléfono, por la huella de su identificador.
     *
     * Si no aparece, se busca también el identificador legible: una cuenta de
     * antes de V26 que CifradoDeDatosExistentes todavía no convirtió (la app
     * abrió justo al arrancar el servidor). Se le pone la huella en el acto.
     * Nunca con algo que ya parezca una huella: así, quien hubiera visto una
     * en la base no puede presentarla como identificador.
     */
    private Optional<Usuario> porDispositivo(String deviceId) {
        String huella = HuellaDeDispositivo.de(deviceId);
        if (huella == null) return Optional.empty();
        Optional<Usuario> usuario = usuarios.findByDeviceId(huella);
        if (usuario.isPresent() || HuellaDeDispositivo.esHuella(deviceId)) return usuario;
        return usuarios.conDispositivoLegible(deviceId).map(u -> {
            u.setDeviceId(huella);
            return u;
        });
    }

    private void actualizarDatos(Usuario usuario, VerificadorIdentidad.Identidad identidad,
                                 String appleRefresh) {
        if (identidad.email() != null && !identidad.email().isBlank()) {
            usuario.setEmail(identidad.email());
        }
        if (appleRefresh != null) {
            usuario.setAppleRefresh(appleRefresh);
        }
        usuario.setVistoEn(Instant.now());
    }

    /** @param admin si esta sesión puede tener el rol (solo si la cuenta lo tiene). */
    private Dtos.Sesion sesionDe(Usuario usuario, boolean fusionados, boolean admin) {
        boolean conRol = admin && usuario.isEsAdmin();
        return new Dtos.Sesion(
                jwt.emitir(usuario, conRol),
                usuario.getId(),
                usuario.getProveedor(),
                usuario.getEmail(),
                conRol,
                fusionados
        );
    }
}
