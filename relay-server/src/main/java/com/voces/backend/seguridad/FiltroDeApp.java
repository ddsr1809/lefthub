package com.voces.backend.seguridad;

import com.voces.backend.version.VersionDeApp;
import com.voces.backend.seguridad.ModosDeLaApp.Modo;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Solo la app original habla con la API: comprueba en cada petición la firma
 * con la llave de la app (FirmaDeApp) y el token de App Check
 * (VerificadorAppCheck), según el modo de cada una (ModosDeLaApp).
 *
 * <h2>Lo que no pasa por aquí</h2>
 *
 * <ul>
 *   <li>Lo que no es /api: el webhook de YouTube (/websub, con su propia
 *       firma), las tareas internas y /actuator.</li>
 *   <li>El panel, que es un navegador y no tiene la llave: /api/admin, y
 *       cualquier petición con una sesión de administrador (las imágenes de
 *       los stickers, por ejemplo).</li>
 *   <li>Entrar con Google o Apple: exige un token real de esa cuenta, y el
 *       panel entra por ahí.</li>
 *   <li>/api/servidor (si el servidor está vivo) y /api/fotos (imágenes
 *       públicas que se pintan en un img).</li>
 * </ul>
 *
 * <h2>Al rechazar</h2>
 *
 * Si la petición no trae nada (una app de antes de la firma), 426: la app la
 * trata como "versión que ya no se atiende" y enseña la pantalla de
 * actualizar. Si trae algo que no cuadra, 403. Si lo único que falla es la
 * hora, la respuesta lo dice (X-App-Motivo: hora) y la app corrige su reloj
 * con X-Servidor-Hora y repite una vez.
 */
public class FiltroDeApp extends OncePerRequestFilter {

    public static final String CABECERA_HORA_SERVIDOR = "X-Servidor-Hora";
    public static final String CABECERA_MOTIVO = "X-App-Motivo";

    static final List<String> EXENTAS = List.of(
            "/api/admin/", "/api/auth/google", "/api/auth/apple", "/api/servidor", "/api/fotos/");

    private final FirmaDeApp firma;
    private final VerificadorAppCheck appCheck;
    private final ModosDeLaApp modos;
    private final CifrasDeLaApp cifras;
    private final RegistroDeLlaves registro;
    private final Clock reloj;

    public FiltroDeApp(FirmaDeApp firma, VerificadorAppCheck appCheck, ModosDeLaApp modos,
                       CifrasDeLaApp cifras, RegistroDeLlaves registro) {
        this(firma, appCheck, modos, cifras, registro, Clock.systemUTC());
    }

    FiltroDeApp(FirmaDeApp firma, VerificadorAppCheck appCheck, ModosDeLaApp modos,
                CifrasDeLaApp cifras, RegistroDeLlaves registro, Clock reloj) {
        this.firma = firma;
        this.appCheck = appCheck;
        this.modos = modos;
        this.cifras = cifras;
        this.registro = registro;
        this.reloj = reloj;
    }

    /** La ruta como la firma la app: sin dominio ni consulta, ya decodificada. */
    static String ruta(HttpServletRequest peticion) {
        String ruta = peticion.getServletPath();
        String resto = peticion.getPathInfo();
        return (ruta == null ? "" : ruta) + (resto == null ? "" : resto);
    }

    static boolean exenta(String ruta) {
        for (String e : EXENTAS) {
            boolean coincide = e.endsWith("/")
                    ? ruta.startsWith(e) || ruta.equals(e.substring(0, e.length() - 1))
                    : ruta.equals(e) || ruta.startsWith(e + "/");
            if (coincide) return true;
        }
        return false;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest peticion) {
        return !ruta(peticion).startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest peticion,
                                    @NonNull HttpServletResponse respuesta,
                                    @NonNull FilterChain cadena) throws ServletException, IOException {
        Instant ahora = reloj.instant();
        // La hora del servidor, para que la app corrija la suya al firmar.
        respuesta.setHeader(CABECERA_HORA_SERVIDOR, String.valueOf(ahora.getEpochSecond()));

        String ruta = ruta(peticion);
        boolean opciones = "OPTIONS".equalsIgnoreCase(peticion.getMethod());
        if (opciones || exenta(ruta) || esAdministrador()) {
            // Un administrador que usa la app tampoco pasa por las
            // comprobaciones, pero su versión de la app sí se anota.
            if (!opciones && !exenta(ruta)) anotarVersion(peticion);
            cadena.doFilter(peticion, respuesta);
            return;
        }

        ModosDeLaApp.Modos m = modos.actuales();
        FirmaDeApp.Resultado rf = m.firma() == Modo.APAGADO || !firma.configurada() ? null
                : firma.verificar(
                        peticion.getHeader(FirmaDeApp.CABECERA_LLAVE),
                        peticion.getHeader(FirmaDeApp.CABECERA_HORA),
                        peticion.getHeader(FirmaDeApp.CABECERA_NONCE),
                        peticion.getHeader(FirmaDeApp.CABECERA_FIRMA),
                        peticion.getMethod(), ruta, ahora);
        VerificadorAppCheck.Resultado ra = m.appCheck() == Modo.APAGADO || !appCheck.configurado() ? null
                : appCheck.verificar(peticion.getHeader(VerificadorAppCheck.CABECERA));

        boolean fallaFirma = m.firma() == Modo.EXIGIR && rf != null && rf != FirmaDeApp.Resultado.OK;
        boolean fallaCheck = m.appCheck() == Modo.EXIGIR && ra != null
                && ra != VerificadorAppCheck.Resultado.OK && ra != VerificadorAppCheck.Resultado.SIN_VERIFICAR;
        boolean rechazar = fallaFirma || fallaCheck;
        if (rf != null || ra != null) cifras.anotar(rf, ra, rechazar);
        // Y en la cuenta, si la petición trae sesión: así el panel sabe quién
        // trae la llave y quién no, con qué número de llave, de qué app viene
        // su App Check y con qué versión de la app (ver RegistroDeLlaves).
        if (registro != null) {
            String[] version = versionDeLaApp(peticion);
            registro.anotar(cuenta(), rf, rf != null ? numeroDeLlave(peticion.getHeader(FirmaDeApp.CABECERA_LLAVE)) : null,
                    ra, ra == VerificadorAppCheck.Resultado.OK
                            ? VerificadorAppCheck.appDelToken(peticion.getHeader(VerificadorAppCheck.CABECERA)) : null,
                    version[0], version[1]);
        }

        if (!rechazar) {
            cadena.doFilter(peticion, respuesta);
            return;
        }

        boolean sinNada = (rf == null || rf == FirmaDeApp.Resultado.FALTA)
                && (ra == null || ra == VerificadorAppCheck.Resultado.FALTA);
        if (sinNada) {
            responder(respuesta, 426, null,
                    "Esta versión de la app ya no funciona. Actualízala desde Google Play para seguir usándola.");
        } else {
            boolean soloHora = rf == FirmaDeApp.Resultado.HORA && !fallaCheck;
            responder(respuesta, 403, soloHora ? "hora" : "app",
                    "No pudimos comprobar que esta es la app original de Voces de Izquierda. "
                            + "Instálala o actualízala desde Google Play.");
        }
    }

    private void anotarVersion(HttpServletRequest peticion) {
        if (registro == null) return;
        String[] version = versionDeLaApp(peticion);
        if (version[1] != null) registro.anotarVersion(cuenta(), version[0], version[1]);
    }

    /**
     * {plataforma, versión} de la app que hace la petición. Vacío si no lo
     * dice o si es el panel, que entra con su propia cabecera.
     */
    static String[] versionDeLaApp(HttpServletRequest peticion) {
        String cabecera = peticion.getHeader(VersionDeApp.CABECERA_PLATAFORMA);
        String plataforma = VersionDeApp.plataforma(cabecera);
        String version = VersionDeApp.limpia(peticion.getHeader(VersionDeApp.CABECERA_VERSION));
        if (VersionDeApp.PANEL.equals(plataforma) || version == null) return new String[]{null, null};
        return new String[]{plataforma, version};
    }

    /** El número de la cabecera X-App-Llave, o null si no viene o no es un número razonable. */
    static Short numeroDeLlave(String cabecera) {
        if (cabecera == null || cabecera.isBlank()) return null;
        try {
            short n = Short.parseShort(cabecera.trim());
            return n >= 1 && n <= 999 ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** La cuenta de la sesión de esta petición, o null si no trae. */
    private static java.util.UUID cuenta() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.getPrincipal() instanceof java.util.UUID id ? id : null;
    }

    private static boolean esAdministrador() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.isAuthenticated()
                && a.getAuthorities().stream().anyMatch(p -> "ROLE_ADMIN".equals(p.getAuthority()));
    }

    private static void responder(HttpServletResponse respuesta, int estado, String motivo, String mensaje)
            throws IOException {
        respuesta.setStatus(estado);
        if (motivo != null) respuesta.setHeader(CABECERA_MOTIVO, motivo);
        respuesta.setContentType("application/json");
        respuesta.setCharacterEncoding(StandardCharsets.UTF_8.name());
        respuesta.getWriter().write("{\"status\":" + estado + ",\"message\":\"" + mensaje + "\"}");
    }
}
