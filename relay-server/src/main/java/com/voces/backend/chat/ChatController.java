package com.voces.backend.chat;

import com.voces.backend.chat.SalasDeChat.Evento;
import com.voces.backend.chat.SalasDeChat.Lado;
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
 * El chat interno, del lado de la app.
 *
 * La persona solo puede hablar con el equipo, y solo si el panel le activó el
 * chat. No elige con quién: su sala es la de su propia cuenta. Lo que manda y
 * recibe va cifrado de extremo a extremo; este controlador solo lo pasa a
 * {@link SalasDeChat}, que lo tiene en memoria hasta que el otro lado lo
 * recoge.
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    public record Entrar(String clave) {}

    public record Enviar(String conexion, String iv, String cifrado) {}

    public record Salir(String conexion) {}

    private final SalasDeChat salas;
    private final Repositorios.Usuarios usuarios;

    public ChatController(SalasDeChat salas, Repositorios.Usuarios usuarios) {
        this.salas = salas;
        this.usuarios = usuarios;
    }

    /**
     * Entra al chat con una llave pública recién hecha. Devuelve la del
     * equipo si ya está dentro; si no, llega después como evento "llave".
     */
    @PostMapping("/entrar")
    public SalasDeChat.Entrada entrar(@RequestBody Entrar peticion) {
        UUID uid = Sesion.exigir();
        Usuario usuario = usuarios.findById(uid)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesión no válida."));
        if (!usuario.isChatHabilitado()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "El chat con el equipo no está activado para tu cuenta.");
        }
        return salas.entrar(uid, Lado.USUARIO, uid, peticion.clave());
    }

    /** Espera hasta 25 segundos a que llegue algo. Lo recogido ya no está en el servidor. */
    @GetMapping("/eventos")
    public List<Evento> eventos(@RequestParam String conexion) {
        return salas.recibir(Sesion.exigir(), Lado.USUARIO, conexion, SalasDeChat.ESPERA);
    }

    @PostMapping("/mensajes")
    public ResponseEntity<Void> enviar(@RequestBody Enviar peticion) {
        salas.enviar(Sesion.exigir(), Lado.USUARIO, peticion.conexion(), peticion.iv(), peticion.cifrado());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/salir")
    public ResponseEntity<Void> salir(@RequestBody Salir peticion) {
        salas.salir(Sesion.exigir(), Lado.USUARIO, peticion.conexion());
        return ResponseEntity.noContent().build();
    }
}
