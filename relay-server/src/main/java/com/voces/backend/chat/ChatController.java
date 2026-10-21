package com.voces.backend.chat;

import com.voces.backend.chat.ChatService.Mensaje;
import com.voces.backend.chat.NovedadesDeChat.Lado;
import com.voces.backend.config.SeguridadConfig.Sesion;
import com.voces.backend.modelo.Repositorios;
import com.voces.backend.modelo.Usuario;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * El chat con el equipo, del lado de la app.
 *
 * La persona solo habla con el equipo, y solo si el panel le activó el chat.
 * No elige con quién: su conversación es la de su propia cuenta. Lo que
 * escribe se guarda cifrado (ver ChatService) y el equipo lo lee cuando puede,
 * en el panel o en su teléfono; y al revés.
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    /** Lo que espera una consulta con {@code esperar=true} a que llegue algo. */
    static final java.time.Duration ESPERA = java.time.Duration.ofSeconds(25);

    public record Escribir(String texto) {}

    private final ChatService chat;
    private final NovedadesDeChat novedades;
    private final Repositorios.Usuarios usuarios;

    public ChatController(ChatService chat, NovedadesDeChat novedades, Repositorios.Usuarios usuarios) {
        this.chat = chat;
        this.novedades = novedades;
        this.usuarios = usuarios;
    }

    /**
     * Los mensajes de su conversación. Con {@code despues}, solo los que
     * llegaron después de ese; con {@code esperar}, si no hay ninguno espera
     * hasta 25 segundos a que llegue (la pantalla del chat abierta).
     */
    @GetMapping("/mensajes")
    public List<Mensaje> mensajes(@RequestParam(defaultValue = "0") long despues,
                                  @RequestParam(defaultValue = "false") boolean esperar) {
        UUID yo = exigirChat();
        // Solo al empezar: si la app se va a segundo plano a media espera,
        // la salida lo borra y nada lo vuelve a poner.
        novedades.visto(yo, Lado.USUARIO);
        List<Mensaje> lista = chat.leer(yo, despues, Lado.USUARIO, null);
        if (lista.isEmpty() && esperar && novedades.esperar(yo, despues, ESPERA)) {
            // Sin transacción abierta durante la espera: no ocupa una conexión.
            lista = chat.leer(exigirChat(), despues, Lado.USUARIO, null);
        }
        return lista;
    }

    @PostMapping("/mensajes")
    public Mensaje escribir(@RequestBody Escribir peticion) {
        return chat.enviar(exigirChat(), Lado.USUARIO, null, peticion.texto());
    }

    /**
     * Cerró la pantalla del chat (o la app pasó a segundo plano): desde
     * ahora, los mensajes del equipo le llegan con aviso. {@code hasta} es el
     * último que llegó a ver, que queda leído.
     */
    @PostMapping("/salir")
    public ResponseEntity<Void> salir(@RequestParam(defaultValue = "0") long hasta) {
        UUID yo = Sesion.exigir();
        novedades.salio(yo, Lado.USUARIO);
        chat.confirmarLeidos(yo, Lado.USUARIO, hasta);
        return ResponseEntity.noContent().build();
    }

    /**
     * El chat en vivo de la app 1.0.13 entraba por aquí. Ya no existe: se le
     * contesta con el motivo, que esa versión enseña en la pantalla del chat.
     */
    @RequestMapping({ "/entrar", "/eventos" })
    public void chatAnterior() {
        throw new ResponseStatusException(HttpStatus.GONE,
                "El chat cambió: ahora los mensajes se guardan y puedes contestar cuando quieras. "
                        + "Actualiza la app para usarlo.");
    }

    // -------------------------------------------------------------------------

    private UUID exigirChat() {
        UUID uid = Sesion.exigir();
        Usuario usuario = usuarios.findById(uid)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesión no válida."));
        if (!usuario.isChatHabilitado()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "El chat con el equipo no está activado para tu cuenta.");
        }
        if (!chat.disponible()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "El chat no está disponible en este momento.");
        }
        return uid;
    }
}
