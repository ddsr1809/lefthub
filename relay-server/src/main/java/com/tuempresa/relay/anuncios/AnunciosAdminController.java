package com.tuempresa.relay.anuncios;

import com.tuempresa.relay.config.RelayProperties;
import com.tuempresa.relay.config.SeguridadConfig.Sesion;
import com.tuempresa.relay.directorio.AjustesService;
import com.tuempresa.relay.modelo.Dtos;
import com.tuempresa.relay.modelo.Folio;
import com.tuempresa.relay.modelo.Repositorios;
import com.tuempresa.relay.modelo.Usuario;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sección "Anuncios" del panel: el estado, los folios de regalo y quitarle
 * los anuncios a mano a una cuenta.
 *
 * Vive bajo /api/admin, así que SeguridadConfig ya exige el rol de
 * administrador en cada llamada. El interruptor de encender y apagar los
 * anuncios no está aquí: es un ajuste más de PUT /api/admin/ajustes.
 */
@RestController
@RequestMapping("/api/admin")
public class AnunciosAdminController {

    private final SinAnunciosService servicio;
    private final AjustesService ajustes;
    private final TiendaGoogle tienda;
    private final Repositorios.Usuarios usuarios;
    private final RelayProperties config;

    public AnunciosAdminController(SinAnunciosService servicio, AjustesService ajustes,
                                   TiendaGoogle tienda, Repositorios.Usuarios usuarios,
                                   RelayProperties config) {
        this.servicio = servicio;
        this.ajustes = ajustes;
        this.tienda = tienda;
        this.usuarios = usuarios;
        this.config = config;
    }

    @GetMapping("/anuncios")
    @Transactional(readOnly = true)
    public Dtos.AnunciosAdminDto estado() {
        // Siempre los tres motivos, aunque alguno esté en cero.
        Map<String, Long> porMotivo = new LinkedHashMap<>();
        porMotivo.put(Usuario.POR_COMPRA, 0L);
        porMotivo.put(Usuario.POR_FOLIO, 0L);
        porMotivo.put(Usuario.POR_PANEL, 0L);
        for (Object[] fila : usuarios.sinAnunciosPorOrigen()) {
            porMotivo.merge(String.valueOf(fila[0]), ((Number) fila[1]).longValue(), Long::sum);
        }

        return new Dtos.AnunciosAdminDto(
                ajustes.anuncios(),
                tienda.lista(),
                config.compras().producto(),
                config.compras().paquete(),
                porMotivo,
                servicio.sinUsar().stream().map(AnunciosAdminController::aDto).toList(),
                servicio.cuantosSinUsar());
    }

    /**
     * Crea folios de regalo. Devuelve solo los recién creados, para copiarlos
     * y repartirlos.
     */
    @PostMapping("/folios")
    public List<Dtos.FolioDto> crearFolios(@Valid @RequestBody Dtos.CrearFolios peticion) {
        return servicio.crear(peticion.cantidad(), peticion.nota(), Sesion.exigir()).stream()
                .map(AnunciosAdminController::aDto)
                .toList();
    }

    /** Anula un folio que todavía no se usó. Se acepta con guion o sin él. */
    @DeleteMapping("/folios/{codigo}")
    public Dtos.RespuestaSimple anularFolio(@PathVariable String codigo) {
        String limpio = Folios.normalizar(codigo);
        if (limpio == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ese folio no tiene la forma de un folio.");
        }
        servicio.anular(limpio);
        return Dtos.RespuestaSimple.de("Folio anulado. Ya no se puede canjear.");
    }

    /**
     * Quita o devuelve los anuncios a una cuenta, a mano. Para atender a
     * quien pagó y sigue viéndolos, o para regalárselo a alguien sin folio.
     *
     * Devolvérselos a quien los compró dura poco: su teléfono vuelve a
     * presentar la compra la próxima vez que abra la app, Google la confirma
     * y se le quitan otra vez. Para eso hay que devolverle el dinero en Play
     * Console, que cancela la compra.
     */
    @PostMapping("/usuarios/{id}/sin-anuncios")
    public Dtos.RespuestaSimple ponerSinAnuncios(@PathVariable UUID id, @RequestParam boolean valor) {
        servicio.ponerDesdeElPanel(id, valor, Sesion.exigir());
        return Dtos.RespuestaSimple.de(valor
                ? "Listo. Esa cuenta deja de ver anuncios en cuanto la app refresque, en un minuto como mucho."
                : "Esa cuenta vuelve a ver anuncios.");
    }

    private static Dtos.FolioDto aDto(Folio folio) {
        return new Dtos.FolioDto(Folios.pintar(folio.getCodigo()), folio.getNota(), folio.getCreadoEn());
    }
}
