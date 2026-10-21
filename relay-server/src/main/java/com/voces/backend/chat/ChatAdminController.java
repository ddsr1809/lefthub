package com.voces.backend.chat;

import com.voces.backend.chat.ChatController.Escribir;
import com.voces.backend.chat.ChatService.Conversacion;
import com.voces.backend.chat.ChatService.Mensaje;
import com.voces.backend.chat.NovedadesDeChat.Lado;
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

import java.util.List;
import java.util.UUID;

/**
 * El chat del lado del equipo: el panel y la app de un administrador.
 *
 * Vive bajo /api/admin, así que SeguridadConfig ya exige el rol de
 * administrador en cada llamada. Cualquier administrador ve y contesta todas
 * las conversaciones; cada mensaje recuerda quién del equipo lo escribió.
 */
@RestController
@RequestMapping("/api/admin")
public class ChatAdminController {

    private static final Logger log = LoggerFactory.getLogger(ChatAdminController.class);

    /** Para los contadores del panel y de la app. */
    public record SinLeer(long conversaciones, long mensajes, boolean disponible) {}

    private final ChatService chat;
    private final NovedadesDeChat novedades;
    private final Repositorios.Usuarios usuarios;

    public ChatAdminController(ChatService chat, NovedadesDeChat novedades, Repositorios.Usuarios usuarios) {
        this.chat = chat;
        this.novedades = novedades;
        this.usuarios = usuarios;
    }

    // -------------------------------------------------------------------------
    // Permiso
    // -------------------------------------------------------------------------

    /**
     * Activa o quita el chat a una cuenta. Quitarlo borra su conversación: si
     * el equipo ya no habla con esa persona, sus mensajes no tienen por qué
     * quedarse guardados.
     */
    @PostMapping("/usuarios/{id}/chat")
    @Transactional
    public Dtos.RespuestaSimple cambiarChat(@PathVariable UUID id, @RequestParam boolean valor) {
        Usuario usuario = buscar(id);
        usuario.setChatHabilitado(valor);
        int borrados = valor ? 0 : chat.borrar(id);

        log.info("Chat {} para {} por {}", valor ? "activado" : "desactivado", id, Sesion.exigir());
        return Dtos.RespuestaSimple.de(valor
                ? "Chat activado. En un minuto como mucho verá «Hablar con el equipo» en Ajustes de la app."
                : borrados > 0
                    ? "Chat quitado y conversación borrada (" + borrados + (borrados == 1 ? " mensaje)." : " mensajes).")
                    : "Chat quitado.");
    }

    // -------------------------------------------------------------------------
    // Bandeja
    // -------------------------------------------------------------------------

    @GetMapping("/chat")
    public List<Conversacion> bandeja() {
        if (!chat.disponible()) return List.of();
        return chat.bandeja();
    }

    @GetMapping("/chat/sin-leer")
    public SinLeer sinLeer() {
        return new SinLeer(chat.conversacionesSinLeer(), chat.mensajesSinLeerDelEquipo(), chat.disponible());
    }

    // -------------------------------------------------------------------------
    // Conversación
    // -------------------------------------------------------------------------

    /** Igual que en la app: con {@code esperar}, espera hasta 25 segundos a que llegue algo. */
    @GetMapping("/chat/{id}/mensajes")
    public List<Mensaje> mensajes(@PathVariable UUID id,
                                  @RequestParam(defaultValue = "0") long despues,
                                  @RequestParam(defaultValue = "false") boolean esperar) {
        UUID yo = Sesion.exigir();
        exigirChat(id);
        novedades.visto(id, Lado.ADMIN);
        List<Mensaje> lista = chat.leer(id, despues, Lado.ADMIN, yo);
        if (lista.isEmpty() && esperar && novedades.esperar(id, despues, ChatController.ESPERA)) {
            exigirChat(id);
            lista = chat.leer(id, despues, Lado.ADMIN, yo);
        }
        return lista;
    }

    @PostMapping("/chat/{id}/mensajes")
    public Mensaje escribir(@PathVariable UUID id, @RequestBody Escribir peticion) {
        UUID yo = Sesion.exigir();
        exigirChat(id);
        return chat.enviar(id, Lado.ADMIN, yo, peticion.texto());
    }

    /**
     * Cerró la conversación: desde ahora, lo que conteste la persona llega
     * con aviso. {@code hasta} es el último mensaje que llegó a ver.
     */
    @PostMapping("/chat/{id}/salir")
    public ResponseEntity<Void> salir(@PathVariable UUID id, @RequestParam(defaultValue = "0") long hasta) {
        novedades.salio(id, Lado.ADMIN);
        chat.confirmarLeidos(id, Lado.ADMIN, hasta);
        return ResponseEntity.noContent().build();
    }

    /** Borra los mensajes de esa conversación. La cuenta sigue con el chat activado. */
    @DeleteMapping("/chat/{id}")
    public Dtos.RespuestaSimple borrar(@PathVariable UUID id) {
        buscar(id);
        int n = chat.borrar(id);
        log.info("Conversación de {} borrada por {}", id, Sesion.exigir());
        return Dtos.RespuestaSimple.de(n == 0 ? "No había mensajes que borrar."
                : "Conversación borrada (" + n + (n == 1 ? " mensaje)." : " mensajes)."));
    }

    // -------------------------------------------------------------------------

    private void exigirChat(UUID id) {
        if (!chat.disponible()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "El chat no está disponible: falta CHAT_LLAVES en el servidor.");
        }
        if (!buscar(id).isChatHabilitado()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Esta cuenta no tiene el chat activado. Actívalo primero en su ficha.");
        }
    }

    private Usuario buscar(UUID id) {
        return usuarios.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Esa cuenta ya no existe."));
    }
}
