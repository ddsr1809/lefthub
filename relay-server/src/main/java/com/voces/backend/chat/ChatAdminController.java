package com.voces.backend.chat;

import com.voces.backend.chat.ChatController.Entrar;
import com.voces.backend.chat.ChatController.Enviar;
import com.voces.backend.chat.ChatController.Salir;
import com.voces.backend.chat.SalasDeChat.Evento;
import com.voces.backend.chat.SalasDeChat.Lado;
import com.voces.backend.config.SeguridadConfig.Sesion;
import com.voces.backend.modelo.Dtos;
import com.voces.backend.modelo.Repositorios;
import com.voces.backend.modelo.Usuario;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * El chat interno, del lado del panel.
 *
 * Vive bajo /api/admin, así que SeguridadConfig ya exige el rol de
 * administrador en cada llamada. Aquí se enciende el chat por cuenta y se
 * habla con ella. Como en la app, los mensajes van cifrados de extremo a
 * extremo y no se guardan: ver {@link SalasDeChat}.
 */
@RestController
@RequestMapping("/api/admin")
public class ChatAdminController {

    private static final Logger log = LoggerFactory.getLogger(ChatAdminController.class);

    /** Una cuenta con el chat activado, para la sección Chat del panel. */
    public record CuentaConChat(
            UUID id,
            String proveedor,
            String email,
            Instant vistoEn,
            String appVersion,
            String appPlataforma,
            /** Tiene ahora mismo la pantalla del chat abierta. */
            boolean enElChat,
            /** Algún administrador tiene abierta su conversación. */
            boolean conAdmin,
            /** Ese administrador eres tú. */
            boolean conmigo
    ) {}

    /** Entrar desde el panel: además, si se le mandó el aviso al teléfono. */
    public record EntradaAdmin(String conexion, String claveDelOtro, boolean avisado) {}

    private final SalasDeChat salas;
    private final AvisoDeChat aviso;
    private final Repositorios.Usuarios usuarios;

    public ChatAdminController(SalasDeChat salas, AvisoDeChat aviso, Repositorios.Usuarios usuarios) {
        this.salas = salas;
        this.aviso = aviso;
        this.usuarios = usuarios;
    }

    // -------------------------------------------------------------------------
    // Permiso
    // -------------------------------------------------------------------------

    /**
     * Activa o quita el chat a una cuenta. Quitarlo cierra en el acto la
     * conversación que tuviera abierta.
     */
    @PostMapping("/usuarios/{id}/chat")
    @Transactional
    public Dtos.RespuestaSimple cambiarChat(@PathVariable UUID id, @RequestParam boolean valor) {
        Usuario usuario = buscar(id);
        usuario.setChatHabilitado(valor);
        if (!valor) salas.cerrar(id, "El equipo desactivó el chat.");

        log.info("Chat {} para {} por {}", valor ? "activado" : "desactivado", id, Sesion.exigir());
        return Dtos.RespuestaSimple.de(valor
                ? "Chat activado. Lo verá en Ajustes de la app en un minuto como mucho, con la versión que trae el chat."
                : "Chat desactivado. Si tenía una conversación abierta, se cerró.");
    }

    @GetMapping("/chat")
    @Transactional(readOnly = true)
    public List<CuentaConChat> cuentas() {
        UUID yo = Sesion.exigir();
        return usuarios.findByChatHabilitadoTrueOrderByVistoEnDesc().stream()
                .map(u -> {
                    SalasDeChat.Estado e = salas.estado(u.getId());
                    return new CuentaConChat(u.getId(), u.getProveedor(), u.getEmail(), u.getVistoEn(),
                            u.getAppVersion(), u.getAppPlataforma(),
                            e.usuarioDentro(), e.adminDentro(), yo.equals(e.adminId()));
                })
                .toList();
    }

    // -------------------------------------------------------------------------
    // Conversación
    // -------------------------------------------------------------------------

    /**
     * Abre (o retoma con una llave nueva) la conversación con esa cuenta. Si
     * la persona no tiene el chat abierto, le llega un aviso al teléfono sin
     * nada de la conversación.
     */
    @PostMapping("/chat/{id}/entrar")
    public EntradaAdmin entrar(@PathVariable UUID id, @RequestBody Entrar peticion) {
        UUID yo = Sesion.exigir();
        Usuario usuario = buscar(id);
        if (!usuario.isChatHabilitado()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Esta cuenta no tiene el chat activado. Actívalo primero en su ficha.");
        }

        SalasDeChat.Entrada entrada = salas.entrar(id, Lado.ADMIN, yo, peticion.clave());
        boolean avisado = entrada.claveDelOtro() == null && aviso.invitar(id);

        log.info("Chat con {} abierto por {}", id, yo);
        return new EntradaAdmin(entrada.conexion(), entrada.claveDelOtro(), avisado);
    }

    @GetMapping("/chat/{id}/eventos")
    public List<Evento> eventos(@PathVariable UUID id, @RequestParam String conexion) {
        return salas.recibir(id, Lado.ADMIN, conexion, SalasDeChat.ESPERA);
    }

    @PostMapping("/chat/{id}/mensajes")
    public ResponseEntity<Void> enviar(@PathVariable UUID id, @RequestBody Enviar peticion) {
        salas.enviar(id, Lado.ADMIN, peticion.conexion(), peticion.iv(), peticion.cifrado());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/chat/{id}/salir")
    public ResponseEntity<Void> salir(@PathVariable UUID id, @RequestBody Salir peticion) {
        salas.salir(id, Lado.ADMIN, peticion.conexion());
        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------------------

    private Usuario buscar(UUID id) {
        return usuarios.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Esa cuenta ya no existe."));
    }
}
