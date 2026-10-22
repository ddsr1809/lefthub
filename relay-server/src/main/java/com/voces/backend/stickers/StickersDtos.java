package com.voces.backend.stickers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Lo que entra y sale por las rutas de stickers. */
public final class StickersDtos {

    private StickersDtos() {}

    // --- La app --------------------------------------------------------------

    /**
     * Un paquete listo para usar. Las direcciones son relativas al servidor y
     * llevan la versión (?v=): la app las guarda en su carpeta y, si cambia la
     * versión del paquete, lo vuelve a bajar.
     */
    public record PaqueteApp(
            UUID id,
            UUID creadorId,
            /** El nombre del creador: WhatsApp lo enseña como "publicado por". */
            String creador,
            String nombre,
            boolean animado,
            int version,
            String icono,
            List<StickerApp> stickers
    ) {}

    public record StickerApp(UUID id, List<String> emojis, String imagen) {}

    // --- El panel ------------------------------------------------------------

    public record PanelStickers(
            /** apagado | admins | elegidos | todos */
            String alcance,
            /** Las cuentas elegidas una por una; solo cuentan con "elegidos". */
            List<CuentaElegida> elegidas,
            List<PaqueteAdmin> paquetes
    ) {}

    public record CuentaElegida(UUID id, String proveedor, String email, boolean esAdmin, Instant vistoEn) {}

    public record PaqueteAdmin(
            UUID id,
            UUID creadorId,
            /** Null si el creador ya no existe (no debería: se borra con él). */
            String creador,
            /** El creador está oculto: entonces la app no enseña el paquete. */
            boolean creadorActivo,
            String nombre,
            boolean publicado,
            boolean animado,
            int version,
            boolean conIcono,
            Integer iconoPeso,
            List<StickerAdmin> stickers,
            /** Lo que le falta para poder publicarse. Vacío si está listo. */
            List<String> falta,
            Instant actualizadoEn
    ) {}

    public record StickerAdmin(UUID id, String emojis, boolean animado, int peso) {}

    public record CrearPaquete(UUID creadorId, String nombre) {}

    /** Solo cambia lo que llega. */
    public record CambiarPaquete(String nombre, UUID creadorId, Boolean publicado) {}

    /**
     * Una imagen en base64, con o sin el "data:image/webp;base64," de
     * delante. Los emojis solo cuentan para un sticker.
     */
    public record SubirImagen(String datos, String emojis) {}

    public record CambiarSticker(String emojis) {}

    public record Orden(List<UUID> ids) {}

    /** La respuesta a cada cambio: el paquete como queda y qué decirle a quien lo hizo. */
    public record Hecho(PaqueteAdmin paquete, String mensaje) {}
}
