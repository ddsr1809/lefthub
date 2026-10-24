package com.voces.backend.modelo;

import com.voces.backend.seguridad.Cifrado;
import com.voces.backend.seguridad.CifradorDeDatos;
import com.voces.backend.seguridad.HuellaDeDispositivo;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Cuenta de una persona que usa la app.
 *
 * Nadie ve una pantalla de registro al abrir: se crea una cuenta anonima
 * ligada al identificador del dispositivo. Una sola equivocacion en el teclado
 * durante el alta basta para que una persona mayor abandone el producto. Solo
 * cuando ya hay favoritos que valga la pena conservar se ofrece enlazarla con
 * Google o Apple.
 */
@Entity
@Table(name = "usuarios")
public class Usuario {

    public static final String ANONIMO = "anonimo";
    public static final String GOOGLE = "google";
    public static final String APPLE = "apple";

    /** Por qué una cuenta ya no ve anuncios. */
    public static final String POR_COMPRA = "compra";
    public static final String POR_FOLIO = "folio";
    public static final String POR_PANEL = "panel";

    @Id
    @GeneratedValue
    private UUID id;

    /**
     * Huella del identificador que genera la app en la primera ejecucion
     * (h1:...). El identificador original no se guarda: con él se entra en la
     * cuenta. Ver seguridad/HuellaDeDispositivo y V26.
     */
    @Column(name = "device_id", unique = true)
    @Convert(converter = HuellaDeDispositivo.Columna.class)
    private String deviceId;

    @Column(nullable = false)
    private String proveedor = ANONIMO;

    /** El 'sub' del token de Google o Apple. Estable aunque cambie el correo. */
    @Column(name = "proveedor_sub")
    private String proveedorSub;

    /** Cifrado en la base. Para buscarlo, emailHuella. Ver seguridad/CifradorDeDatos y V26. */
    @Convert(converter = Cifrado.Correo.class)
    private String email;

    /** HMAC del correo en minúsculas: encontrar una cuenta por su correo sin descifrar todas. */
    @Column(name = "email_huella")
    private String emailHuella;

    @Column(name = "es_admin", nullable = false)
    private boolean esAdmin = false;

    @Column(name = "escala_texto", nullable = false)
    private String escalaTexto = "normal";

    @Column(nullable = false)
    private String tema = "sistema";

    @Column(nullable = false)
    private boolean avisos = true;

    /**
     * Quiere ver los videos cortos (Shorts), en su propio apartado. Solo
     * cuenta mientras el panel los tenga permitidos: ver AjustesService.
     */
    @Column(nullable = false)
    private boolean cortos = true;

    /**
     * Quiere una notificación por cada video corto de quienes sigue. Apagado
     * de entrada: ver los cortos en su apartado no trae avisos si no se pide.
     */
    @Column(name = "avisos_cortos", nullable = false)
    private boolean avisosCortos = false;

    /**
     * Ya no ve anuncios: lo compró, canjeó un folio de regalo o se lo puso el
     * equipo desde el panel. Va en la cuenta para que la acompañe si cambia
     * de teléfono. Ver V11.
     */
    @Column(name = "sin_anuncios", nullable = false)
    private boolean sinAnuncios = false;

    /** compra | folio | panel. Null mientras ve anuncios. */
    @Column(name = "sin_anuncios_origen")
    private String sinAnunciosOrigen;

    @Column(name = "sin_anuncios_desde")
    private Instant sinAnunciosDesde;

    /**
     * El equipo le activó el chat desde el panel. Solo con esto en true la
     * persona ve el chat y el equipo puede escribirle. Los mensajes se guardan
     * cifrados en mensajes_chat: ver chat/ChatService, V19 y V23.
     */
    @Column(name = "chat_habilitado", nullable = false)
    private boolean chatHabilitado = false;

    /**
     * El equipo la eligió desde el panel para recomendar canales. Solo
     * cuenta mientras el ajuste 'recomendaciones' esté en "elegidos": ver
     * RecomendacionesService y V20.
     */
    @Column(name = "recomendar_habilitado", nullable = false)
    private boolean recomendarHabilitado = false;

    /**
     * El equipo la eligió desde el panel para ofrecerle "Conectar con
     * YouTube". Solo cuenta mientras el ajuste 'youtube' esté en "elegidos":
     * ver AjustesService y V21.
     */
    @Column(name = "youtube_habilitado", nullable = false)
    private boolean youtubeHabilitado = false;

    /**
     * El equipo la autorizó desde el panel para agregar creadores, medios y
     * canales desde la app, sin aprobación previa. Ver editor/EditorService
     * y V24.
     */
    @Column(name = "editor_habilitado", nullable = false)
    private boolean editorHabilitado = false;

    /**
     * El equipo la eligió desde el panel para ver los stickers. Solo cuenta
     * mientras el ajuste 'stickers' esté en "elegidos": ver
     * stickers/StickersService y V25.
     */
    @Column(name = "stickers_habilitado", nullable = false)
    private boolean stickersHabilitado = false;

    /**
     * Refresh token de Apple. Sin el guardado no se puede revocar el vinculo
     * al borrar la cuenta, y Apple rechaza la app por incumplir la Guideline
     * 5.1.1(v). Cifrado en la base: con él se actúa ante Apple en nombre de
     * la persona.
     */
    @Column(name = "apple_refresh", columnDefinition = "text")
    @Convert(converter = Cifrado.TokenDeApple.class)
    private String appleRefresh;

    /**
     * Los tokens de sesión emitidos antes de este momento ya no valen. Lo
     * fija "cerrar sesión en todos lados" (la persona o el equipo desde el
     * panel). Null = valen todos los que no hayan caducado. Ver
     * seguridad/EstadoDeSesiones y V26.
     */
    @Column(name = "sesiones_desde")
    private Instant sesionesDesde;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "favoritos", joinColumns = @JoinColumn(name = "usuario_id"))
    @Column(name = "creador_id")
    private Set<UUID> favoritos = new LinkedHashSet<>();

    /** Productoras que sigue. Aparte de los creadores: son dos listas. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "favoritos_productoras", joinColumns = @JoinColumn(name = "usuario_id"))
    @Column(name = "productora_id")
    private Set<UUID> productorasSeguidas = new LinkedHashSet<>();

    /** Canales de YouTube que sigue por sí solos, sin seguir a su dueño. Ver V14. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "favoritos_canales", joinColumns = @JoinColumn(name = "usuario_id"))
    @Column(name = "canal_id")
    private Set<UUID> canalesSeguidos = new LinkedHashSet<>();

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn = Instant.now();

    @Column(name = "visto_en", nullable = false)
    private Instant vistoEn = Instant.now();

    // --- Última conexión -----------------------------------------------------
    // Los llena RegistroDeAcceso cada vez que la app abre sesión. Describen el
    // acceso más reciente, no un historial. LimpiezaDeAccesos borra la IP, la
    // compañía y el agente de quien lleva meses sin abrir la app.

    /** Cifrada en la base. Para buscarla, ipHuella. */
    @Convert(converter = Cifrado.Ip.class)
    private String ip;

    @Column(name = "ip_huella")
    private String ipHuella;

    /** Código ISO de dos letras: MX, US, ES... */
    private String pais;

    /** Número de sistema autónomo de la red desde la que se conecta. */
    private Long asn;

    /**
     * Nombre de la compañía de internet. No es `proveedor`, que dice con qué
     * entra la persona (anónimo, Google o Apple).
     */
    private String red;

    /** Cabecera User-Agent: qué aplicación hizo la petición. */
    private String agente;

    /** La versión de la app con la que entró la última vez. Ver V13. */
    @Column(name = "app_version")
    private String appVersion;

    /** android | ios. Null si la app no lo dijo. */
    @Column(name = "app_plataforma")
    private String appPlataforma;

    /** La versión como número, para ordenar (1.2.2 -> 1002002). La pone setAppVersion. Ver V30. */
    @Column(name = "app_version_orden")
    private Integer appVersionOrden;

    // --- Llave de la app -----------------------------------------------------
    // Cómo llegó su última petición: con la firma de la llave de la app y con
    // el token de App Check, o sin ellos. Los escribe solo
    // seguridad/RegistroDeLlaves, por su cuenta y una vez por minuto: por eso
    // Hibernate nunca los toca al guardar la cuenta (no pisaría lo último con
    // lo que leyó antes). Ver V27.

    /** ok | falta | mala | hora | repetida. Null = sin datos todavía. */
    @Column(name = "llave_firma", insertable = false, updatable = false)
    private String llaveFirma;

    /** ok | falta | malo | sin_verificar. Null = sin datos todavía. */
    @Column(name = "llave_app_check", insertable = false, updatable = false)
    private String llaveAppCheck;

    @Column(name = "llave_vista_en", insertable = false, updatable = false)
    private Instant llaveVistaEn;

    /** Con qué llave (el número de APP_LLAVES) firmó su última petición. Ver V30. */
    @Column(name = "llave_numero", insertable = false, updatable = false)
    private Short llaveNumero;

    /** La app de Firebase de su último App Check bueno (el `sub` del token). Ver V30. */
    @Column(name = "llave_app_check_app", insertable = false, updatable = false)
    private String llaveAppCheckApp;

    @Column(name = "posible_bot", nullable = false)
    private boolean posibleBot = false;

    @Column(name = "motivo_bot")
    private String motivoBot;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }

    public String getProveedor() { return proveedor; }
    public void setProveedor(String proveedor) { this.proveedor = proveedor; }

    public String getProveedorSub() { return proveedorSub; }
    public void setProveedorSub(String proveedorSub) { this.proveedorSub = proveedorSub; }

    public String getEmail() { return email; }
    public void setEmail(String email) {
        this.email = email;
        this.emailHuella = CifradorDeDatos.huellaSiSePuede(email, CifradorDeDatos.CORREO);
    }

    public String getEmailHuella() { return emailHuella; }

    public boolean isEsAdmin() { return esAdmin; }
    public void setEsAdmin(boolean esAdmin) { this.esAdmin = esAdmin; }

    public String getEscalaTexto() { return escalaTexto; }
    public void setEscalaTexto(String escalaTexto) { this.escalaTexto = escalaTexto; }

    public String getTema() { return tema; }
    public void setTema(String tema) { this.tema = tema; }

    public boolean isAvisos() { return avisos; }
    public void setAvisos(boolean avisos) { this.avisos = avisos; }

    public boolean isCortos() { return cortos; }
    public void setCortos(boolean cortos) { this.cortos = cortos; }

    public boolean isAvisosCortos() { return avisosCortos; }
    public void setAvisosCortos(boolean avisosCortos) { this.avisosCortos = avisosCortos; }

    public boolean isSinAnuncios() { return sinAnuncios; }
    public String getSinAnunciosOrigen() { return sinAnunciosOrigen; }
    public Instant getSinAnunciosDesde() { return sinAnunciosDesde; }

    /**
     * Le quita los anuncios. Si ya no los veía, se queda con el motivo y la
     * fecha de la primera vez: es lo que explica por qué no los ve.
     */
    public void quitarAnuncios(String origen) {
        quitarAnuncios(origen, Instant.now());
    }

    public void quitarAnuncios(String origen, Instant desde) {
        if (sinAnuncios) return;
        sinAnuncios = true;
        sinAnunciosOrigen = origen;
        sinAnunciosDesde = desde != null ? desde : Instant.now();
    }

    /** Vuelve a ver anuncios. Solo lo hace el equipo, desde el panel. */
    public void devolverAnuncios() {
        sinAnuncios = false;
        sinAnunciosOrigen = null;
        sinAnunciosDesde = null;
    }

    public boolean isChatHabilitado() { return chatHabilitado; }
    public void setChatHabilitado(boolean chatHabilitado) { this.chatHabilitado = chatHabilitado; }

    public boolean isRecomendarHabilitado() { return recomendarHabilitado; }
    public void setRecomendarHabilitado(boolean recomendarHabilitado) { this.recomendarHabilitado = recomendarHabilitado; }

    public boolean isYoutubeHabilitado() { return youtubeHabilitado; }
    public void setYoutubeHabilitado(boolean youtubeHabilitado) { this.youtubeHabilitado = youtubeHabilitado; }

    public boolean isEditorHabilitado() { return editorHabilitado; }
    public void setEditorHabilitado(boolean editorHabilitado) { this.editorHabilitado = editorHabilitado; }

    public boolean isStickersHabilitado() { return stickersHabilitado; }
    public void setStickersHabilitado(boolean stickersHabilitado) { this.stickersHabilitado = stickersHabilitado; }

    public String getAppleRefresh() { return appleRefresh; }
    public void setAppleRefresh(String appleRefresh) { this.appleRefresh = appleRefresh; }

    public Instant getSesionesDesde() { return sesionesDesde; }

    /**
     * Cierra todas sus sesiones: los tokens emitidos hasta ahora dejan de
     * valer, y el teléfono deja de estar ligado a la cuenta. Sin lo segundo,
     * la app volvería a entrar sola con su identificador de instalación.
     */
    public void cerrarSesiones(Instant ahora) {
        this.sesionesDesde = ahora;
        this.deviceId = null;
    }

    public Set<UUID> getFavoritos() { return favoritos; }
    public void setFavoritos(Set<UUID> favoritos) {
        this.favoritos = favoritos != null ? favoritos : new LinkedHashSet<>();
    }

    public Set<UUID> getCanalesSeguidos() { return canalesSeguidos; }

    public Set<UUID> getProductorasSeguidas() { return productorasSeguidas; }
    public void setProductorasSeguidas(Set<UUID> productorasSeguidas) {
        this.productorasSeguidas = productorasSeguidas != null ? productorasSeguidas : new LinkedHashSet<>();
    }

    public Instant getCreadoEn() { return creadoEn; }
    public void setCreadoEn(Instant creadoEn) { this.creadoEn = creadoEn; }

    public Instant getVistoEn() { return vistoEn; }
    public void setVistoEn(Instant vistoEn) { this.vistoEn = vistoEn; }

    public String getIp() { return ip; }
    public void setIp(String ip) {
        this.ip = ip;
        this.ipHuella = CifradorDeDatos.huellaSiSePuede(ip, CifradorDeDatos.IP);
    }

    public String getIpHuella() { return ipHuella; }

    public String getPais() { return pais; }
    public void setPais(String pais) { this.pais = pais; }

    public Long getAsn() { return asn; }
    public void setAsn(Long asn) { this.asn = asn; }

    public String getRed() { return red; }
    public void setRed(String red) { this.red = red; }

    public String getAgente() { return agente; }
    public void setAgente(String agente) { this.agente = agente; }

    public String getAppVersion() { return appVersion; }
    public void setAppVersion(String appVersion) {
        this.appVersion = appVersion;
        this.appVersionOrden = com.voces.backend.version.VersionDeApp.orden(appVersion);
    }

    public Integer getAppVersionOrden() { return appVersionOrden; }
    public Short getLlaveNumero() { return llaveNumero; }
    public String getLlaveAppCheckApp() { return llaveAppCheckApp; }

    public String getAppPlataforma() { return appPlataforma; }
    public void setAppPlataforma(String appPlataforma) { this.appPlataforma = appPlataforma; }

    public String getLlaveFirma() { return llaveFirma; }
    public String getLlaveAppCheck() { return llaveAppCheck; }
    public Instant getLlaveVistaEn() { return llaveVistaEn; }

    public boolean isPosibleBot() { return posibleBot; }
    public void setPosibleBot(boolean posibleBot) { this.posibleBot = posibleBot; }

    public String getMotivoBot() { return motivoBot; }
    public void setMotivoBot(String motivoBot) { this.motivoBot = motivoBot; }

    @Transient
    public boolean esAnonimo() { return ANONIMO.equals(proveedor); }
}
