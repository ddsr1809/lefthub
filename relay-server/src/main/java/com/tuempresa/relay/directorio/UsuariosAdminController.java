package com.tuempresa.relay.directorio;

import com.tuempresa.relay.config.SeguridadConfig.Sesion;
import com.tuempresa.relay.modelo.Creador;
import com.tuempresa.relay.modelo.Dtos;
import com.tuempresa.relay.modelo.Repositorios;
import com.tuempresa.relay.modelo.Usuario;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Sección "Usuarios" del panel.
 *
 * Vive bajo /api/admin, así que la regla de SeguridadConfig ya exige el rol de
 * administrador en cada llamada. Va en su propio controlador porque
 * AdminController trata del directorio y este de las personas que lo usan.
 *
 * Aquí solo se lee. Lo que se muestra lo guardan otros: las fechas y las
 * preferencias, los controladores de la app; la IP, el país, la compañía de
 * internet y el indicio de bot, RegistroDeAcceso en cada inicio de sesión.
 */
@RestController
@RequestMapping("/api/admin/usuarios")
public class UsuariosAdminController {

    private static final Logger log = LoggerFactory.getLogger(UsuariosAdminController.class);

    private static final int TAMANO_PAGINA = 50;
    private static final int DIAS_DE_ALTAS = 30;

    private final Repositorios.Usuarios usuarios;
    private final Repositorios.Creadores creadores;
    private final Repositorios.Reportes reportes;

    public UsuariosAdminController(Repositorios.Usuarios usuarios,
                                   Repositorios.Creadores creadores,
                                   Repositorios.Reportes reportes) {
        this.usuarios = usuarios;
        this.creadores = creadores;
        this.reportes = reportes;
    }

    // -------------------------------------------------------------------------
    // Cifras
    // -------------------------------------------------------------------------

    /**
     * Cifras de conjunto.
     *
     * "Activo" significa que abrió la app: visto_en se actualiza en cada
     * arranque, al renovar la sesión. No mide cuánto tiempo la usó.
     *
     * @param zona zona horaria del navegador, para que "hoy" en la gráfica de
     *             altas sea el hoy de quien mira el panel y no el de UTC.
     */
    @GetMapping("/resumen")
    @Transactional(readOnly = true)
    public Dtos.ResumenUsuarios resumen(@RequestParam(defaultValue = "UTC") String zona) {
        Instant ahora = Instant.now();
        Map<String, Long> proveedores = aMapa(usuarios.porProveedor());

        return new Dtos.ResumenUsuarios(
                usuarios.count(),
                proveedores.getOrDefault(Usuario.ANONIMO, 0L),
                proveedores.getOrDefault(Usuario.GOOGLE, 0L),
                proveedores.getOrDefault(Usuario.APPLE, 0L),
                usuarios.countByVistoEnAfter(ahora.minus(Duration.ofHours(24))),
                usuarios.countByVistoEnAfter(ahora.minus(Duration.ofDays(7))),
                usuarios.countByVistoEnAfter(ahora.minus(Duration.ofDays(30))),
                usuarios.countByCreadoEnAfter(ahora.minus(Duration.ofDays(7))),
                usuarios.countByCreadoEnAfter(ahora.minus(Duration.ofDays(30))),
                usuarios.conFavoritos(),
                usuarios.seguimientos(),
                usuarios.countByPosibleBotTrue(),
                aMapa(usuarios.porEscalaTexto()),
                aMapa(usuarios.porTema()),
                aMapa(usuarios.porPais()),
                altasPorDia(zonaSegura(zona)));
    }

    /** Altas de los últimos 30 días, con los días sin altas incluidos en cero. */
    private List<Dtos.AltasDelDia> altasPorDia(ZoneId zona) {
        LocalDate inicio = LocalDate.now(zona).minusDays(DIAS_DE_ALTAS - 1L);

        Map<LocalDate, Long> porDia = new TreeMap<>();
        for (int i = 0; i < DIAS_DE_ALTAS; i++) {
            porDia.put(inicio.plusDays(i), 0L);
        }

        for (Instant alta : usuarios.altasDesde(inicio.atStartOfDay(zona).toInstant())) {
            // computeIfPresent y no merge: un alta que cayera fuera del rango
            // por un reloj desajustado no debe añadir un día suelto al final.
            porDia.computeIfPresent(alta.atZone(zona).toLocalDate(), (dia, n) -> n + 1);
        }

        List<Dtos.AltasDelDia> lista = new ArrayList<>();
        porDia.forEach((dia, n) -> lista.add(new Dtos.AltasDelDia(dia.toString(), n)));
        return lista;
    }

    // -------------------------------------------------------------------------
    // Listado y detalle
    // -------------------------------------------------------------------------

    /**
     * @param q         parte del correo o de la IP, o el identificador completo de la cuenta
     * @param filtro    anonimo | google | apple | admin | bot, o vacío para todos
     * @param pais      código de dos letras, o vacío para todos
     * @param orden     "vistos" (última vez que abrió la app) o "nuevos"
     * @param pagina    empieza en 0
     */
    @GetMapping
    @Transactional(readOnly = true)
    public Dtos.PaginaUsuarios listar(@RequestParam(defaultValue = "") String q,
                                      @RequestParam(defaultValue = "") String filtro,
                                      @RequestParam(defaultValue = "") String pais,
                                      @RequestParam(defaultValue = "vistos") String orden,
                                      @RequestParam(defaultValue = "0") int pagina) {

        String texto = q.trim().toLowerCase(Locale.ROOT);

        // Un identificador pegado tal cual va directo a esa cuenta: es lo que
        // se tiene a mano cuando el dato viene de un registro del servidor.
        UUID id = comoUuid(texto);
        if (id != null) {
            List<Dtos.UsuarioAdminDto> uno = usuarios.findById(id)
                    .map(u -> List.of(Dtos.UsuarioAdminDto.de(u)))
                    .orElse(List.of());
            return new Dtos.PaginaUsuarios(uno, uno.size(), 0, 1, TAMANO_PAGINA);
        }

        boolean soloAdmins = "admin".equals(filtro);
        boolean soloBots = "bot".equals(filtro);
        String proveedor = Set.of(Usuario.ANONIMO, Usuario.GOOGLE, Usuario.APPLE).contains(filtro)
                ? filtro : "";

        // % y _ son comodines de LIKE; quitados, el texto se busca literal.
        String limpio = texto.replace("%", "").replace("_", "");
        String patron = limpio.isEmpty() ? "" : "%" + limpio + "%";

        // El id como segundo criterio hace estable el orden entre páginas
        // cuando muchas cuentas comparten la misma fecha.
        Sort criterio = Sort.by(Sort.Direction.DESC, "nuevos".equals(orden) ? "creadoEn" : "vistoEn")
                .and(Sort.by(Sort.Direction.ASC, "id"));

        Page<Usuario> resultado = usuarios.buscar(proveedor, patron,
                pais.trim().toUpperCase(Locale.ROOT), soloAdmins, soloBots,
                PageRequest.of(Math.max(pagina, 0), TAMANO_PAGINA, criterio));

        return new Dtos.PaginaUsuarios(
                resultado.getContent().stream().map(Dtos.UsuarioAdminDto::de).toList(),
                resultado.getTotalElements(),
                resultado.getNumber(),
                Math.max(resultado.getTotalPages(), 1),
                TAMANO_PAGINA);
    }

    /** Una cuenta con los creadores que sigue, para atender a alguien que escribe. */
    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public Dtos.UsuarioDetalle detalle(@PathVariable UUID id) {
        Usuario usuario = buscar(id);

        List<Dtos.CreadorSeguido> sigue = creadores.findAllById(usuario.getFavoritos()).stream()
                .sorted(Comparator.comparing(Creador::getNombre, String.CASE_INSENSITIVE_ORDER))
                .map(c -> new Dtos.CreadorSeguido(c.getId(), c.getNombre(), c.getCategoria(), c.isActivo()))
                .toList();

        return new Dtos.UsuarioDetalle(Dtos.UsuarioAdminDto.de(usuario), sigue,
                reportes.countByUsuarioId(id));
    }

    // -------------------------------------------------------------------------
    // Rol de administrador
    // -------------------------------------------------------------------------

    /**
     * Da o quita el rol. Antes, quitarlo exigía una sentencia SQL a mano.
     *
     * El rol viaja dentro del token de sesión, así que el cambio surte efecto
     * cuando esa persona vuelve a entrar. Ojo al quitarlo: un token ya emitido
     * sigue valiendo hasta que caduca (relay.jwt.dias-validez). El panel lo
     * guarda solo mientras el navegador está abierto, pero quien lo haya
     * copiado conserva el acceso hasta entonces.
     */
    @PostMapping("/{id}/admin")
    @Transactional
    public Dtos.RespuestaSimple cambiarRol(@PathVariable UUID id, @RequestParam boolean valor) {
        Usuario usuario = buscar(id);

        if (!valor && id.equals(Sesion.exigir())) {
            // Sin esto, el último administrador podría dejar el panel sin
            // nadie que pueda entrar.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No puedes quitarte el rol a ti mismo. Pídeselo a otro administrador.");
        }
        if (valor && usuario.esAnonimo()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Una cuenta de invitado no puede entrar al panel. Tiene que guardarla con Google o Apple primero.");
        }

        usuario.setEsAdmin(valor);
        log.info("Rol de administrador {} a {} por {}",
                valor ? "otorgado" : "retirado", usuario.getId(), Sesion.exigir());

        return Dtos.RespuestaSimple.de(valor
                ? "Listo. Pide a esa persona que cierre sesión y vuelva a entrar."
                : "Rol retirado. Si tiene el panel abierto, su sesión sigue valiendo hasta que la cierre o caduque.");
    }

    // -------------------------------------------------------------------------

    private Usuario buscar(UUID id) {
        return usuarios.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Esa cuenta ya no existe."));
    }

    private static Map<String, Long> aMapa(List<Object[]> filas) {
        Map<String, Long> mapa = new LinkedHashMap<>();
        for (Object[] fila : filas) {
            mapa.put(String.valueOf(fila[0]), ((Number) fila[1]).longValue());
        }
        return mapa;
    }

    private static ZoneId zonaSegura(String zona) {
        try {
            return ZoneId.of(zona);
        } catch (Exception e) {
            return ZoneOffset.UTC;
        }
    }

    private static UUID comoUuid(String texto) {
        if (texto.length() != 36) return null;
        try {
            return UUID.fromString(texto);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
