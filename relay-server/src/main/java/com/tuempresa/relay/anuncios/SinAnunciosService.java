package com.tuempresa.relay.anuncios;

import com.tuempresa.relay.modelo.Compra;
import com.tuempresa.relay.modelo.Folio;
import com.tuempresa.relay.modelo.Repositorios;
import com.tuempresa.relay.modelo.Usuario;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Quitarle los anuncios a una cuenta, y los folios de regalo que lo hacen.
 *
 * Hay tres caminos y los tres acaban en lo mismo, la marca en la cuenta
 * (usuarios.sin_anuncios): una compra que Google Play confirmó, un folio de
 * regalo, o alguien del equipo desde el panel.
 */
@Service
public class SinAnunciosService {

    private static final Logger log = LoggerFactory.getLogger(SinAnunciosService.class);

    /** Folios que se pueden pedir de una vez desde el panel. */
    public static final int FOLIOS_POR_TANDA = 100;

    /** Los que enseña el panel: los más recientes. El total se cuenta aparte. */
    public static final int FOLIOS_A_LA_VISTA = 500;

    private final Repositorios.Usuarios usuarios;
    private final Repositorios.Folios folios;
    private final Repositorios.Compras compras;

    public SinAnunciosService(Repositorios.Usuarios usuarios, Repositorios.Folios folios,
                              Repositorios.Compras compras) {
        this.usuarios = usuarios;
        this.folios = folios;
        this.compras = compras;
    }

    // -------------------------------------------------------------------------
    // Folios de regalo
    // -------------------------------------------------------------------------

    /**
     * Canjea un folio: lo borra y quita los anuncios a la cuenta.
     *
     * El borrado y la marca van en la misma transacción. Si algo falla
     * después de borrar, se deshacen los dos y el folio sigue valiendo.
     *
     * @param codigo ya limpio, como lo deja Folios.normalizar
     */
    @Transactional
    public void canjear(UUID usuarioId, String codigo) {
        Usuario usuario = buscar(usuarioId);

        // Antes de tocar el folio: a quien ya no ve anuncios no le serviría
        // de nada, y gastárselo sería quitarle un regalo que puede dar a otro.
        if (usuario.isSinAnuncios()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Tu cuenta ya no tiene anuncios. Ese folio sigue sin usar: puedes regalarlo.");
        }

        if (folios.gastar(codigo) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Ese folio no existe o ya se usó. Revisa que esté bien escrito.");
        }

        usuario.quitarAnuncios(Usuario.POR_FOLIO);
        log.info("Folio canjeado por {}", usuarioId);
    }

    /** Inventa folios nuevos y los guarda. Los devuelve del primero al último. */
    @Transactional
    public List<Folio> crear(int cantidad, String nota, UUID creadoPor) {
        if (cantidad < 1 || cantidad > FOLIOS_POR_TANDA) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Se pueden crear de 1 a " + FOLIOS_POR_TANDA + " folios cada vez.");
        }

        String apunte = (nota == null || nota.isBlank()) ? null : nota.trim();

        List<Folio> nuevos = new ArrayList<>();
        while (nuevos.size() < cantidad) {
            String codigo = Folios.nuevo();
            // Que salga uno repetido es cosa de una entre billones, pero
            // comprobarlo cuesta una consulta y evita pisar un folio ya dado.
            boolean repetido = folios.existsById(codigo)
                    || nuevos.stream().anyMatch(f -> f.getCodigo().equals(codigo));
            if (!repetido) nuevos.add(new Folio(codigo, apunte, creadoPor));
        }

        folios.saveAll(nuevos);
        log.info("{} folios creados por {}", cantidad, creadoPor);
        return nuevos;
    }

    @Transactional(readOnly = true)
    public List<Folio> sinUsar() {
        return folios.findAllByOrderByCreadoEnDesc(PageRequest.of(0, FOLIOS_A_LA_VISTA));
    }

    @Transactional(readOnly = true)
    public long cuantosSinUsar() {
        return folios.count();
    }

    /** Anula un folio que no se ha usado. Si ya no está, es que alguien lo canjeó. */
    @Transactional
    public void anular(String codigo) {
        if (codigo == null || folios.gastar(codigo) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Ese folio ya no está: alguien lo usó, o ya se había anulado.");
        }
    }

    // -------------------------------------------------------------------------
    // Compras
    // -------------------------------------------------------------------------

    /**
     * Apunta una compra que TiendaGoogle ya confirmó y quita los anuncios.
     *
     * La verificación con Google se hace antes de llamar aquí, fuera de la
     * transacción: no hay que tener una conexión de la base ocupada mientras
     * se espera a un servicio de fuera.
     */
    @Transactional
    public void apuntarCompra(UUID usuarioId, String producto, String token,
                              TiendaGoogle.Recibo recibo) {
        Usuario usuario = buscar(usuarioId);

        // El mismo comprobante puede llegar otra vez: la app lo reenvía al
        // arrancar mientras la cuenta no figure sin anuncios, y la persona
        // puede tener la app en dos teléfonos. Es la misma compra, una fila.
        Compra compra = compras.findByToken(token).orElseGet(Compra::new);
        compra.setUsuarioId(usuarioId);
        compra.setProducto(producto);
        compra.setToken(token);
        compra.setOrden(recibo.orden());
        compra.setCompradoEn(recibo.compradoEn());

        try {
            compras.saveAndFlush(compra);
        } catch (DataIntegrityViolationException e) {
            // Dos peticiones con el mismo comprobante a la vez: la otra ya lo
            // guardó. La app lo reintenta sola y entonces encuentra la fila.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Tu compra se está registrando. Espera un momento.");
        }

        usuario.quitarAnuncios(Usuario.POR_COMPRA, recibo.compradoEn());
        log.info("Compra {} apuntada a {}", recibo.orden(), usuarioId);
    }

    // -------------------------------------------------------------------------
    // Desde el panel
    // -------------------------------------------------------------------------

    /** El equipo quita o devuelve los anuncios a una cuenta, a mano. */
    @Transactional
    public Usuario ponerDesdeElPanel(UUID usuarioId, boolean sinAnuncios, UUID quien) {
        Usuario usuario = usuarios.findById(usuarioId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Esa cuenta ya no existe."));

        if (sinAnuncios) {
            usuario.quitarAnuncios(Usuario.POR_PANEL);
        } else {
            usuario.devolverAnuncios();
        }

        log.info("Anuncios {} a {} por {}", sinAnuncios ? "quitados" : "devueltos", usuarioId, quien);
        return usuario;
    }

    private Usuario buscar(UUID id) {
        return usuarios.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Tu sesión ya no es válida. Vuelve a abrir la app."));
    }
}
