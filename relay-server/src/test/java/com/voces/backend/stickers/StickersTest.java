package com.voces.backend.stickers;

import com.voces.backend.directorio.AjustesService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Lo que WhatsApp exige a los stickers, y quién los ve. */
class StickersTest {

    /** Un WebP de verdad, con pérdida (VP8), de 512 x 512. Lo hizo Pillow. */
    private static final byte[] VP8 = Base64.getDecoder().decode(
            "UklGRiYCAABXRUJQVlA4IBoCAAAQOwCdASoAAgACPm02mUmkIyKhIAgAgA2JaW7hd2Ee3AfgB+AAABPYB77ZOQ99snIe+2TkRA8nIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPekAAP7/Wa///6aKMKY+5N///a0Bwar/Lpp47BsP2AmAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");

    /** Un WebP de verdad, sin pérdida (VP8L), de 512 x 512. Lo hizo Pillow. */
    private static final byte[] VP8L = Base64.getDecoder().decode(
            "UklGRqIDAABXRUJQVlA4TJYDAAAv/8F/EA8QMf/zHwzZtW3HbWMuScYnS0ApLA0qjaWwBH56wOLLHLx78KAhw4roPxxGkhRJefwDVvc+TWf7L/85vB1mC8lqP9wmjKv9dONGf4Qb3O0Xt8R9Df3S26+Z6Vczahwdtt9R5Gy/xU5q6u/J8tkNN/1n+GNtIdOfzdSf4Y9vbcz6oAVDd/098uvf3koS74puaq0U5Qg8sLeThCPwgLVTdCPwwObhJE06MPUOD4vomuO6qWbCE3cfSTTTNVE0E55oPopmJjxx83KSzIQnVi9ZspJdeDX3TbESXrj5OQlWwgurnyxYyS68muDmXwkv3BScJBc98NL3pmB27wVvZQqKdy94q1XD5NwL3mrXkJx7wVtVDdmd9Wjmm4biCzs48FYVkyvs4MDbVSRX2MGBV1VkV9jBgWcqiifs4MBbdUw8jrCFA3fXkRxhCwdu1ZEdYQsHrukoPHTYm7WHLRy4m5JTn+xKUnPYw4FflWSe5rSBE8eUFDrszXha0wZOnE3LiacxbeDEedMy8zSmDZw4h5aFpy3t4MS7irEOWdVM/bGpOfE0pS2cuG9q5v6oanJ/HGqW/jA1hYdOe7PuWPVMvbHpOfXGrif1xpueuTeqnjwaDj1Lb5iewkNfdswGw0owjYWN4NQXO0EaC28E81ioBHksHATLbcEIylC4IthNYWWYbgkbw+meYGdI9wRvDPM9SWXIjwQHw/JMxBjKv3/wP/c8/+B/77z/4f/uc/9x//3d8/77u/vt/7/Nzf+/3fj/73r7/28+/nMP4z93Mv5zP/HPTY3/3Nnwz/3FPzcZ/9zo+M/dxj+3HP/cdvxz6/HP7YevW4hftxG/biV+3U78uiW+7ip+3Vj8urnwdYN83WP8uk2+7jR+3Sxf9xu/bpmvuw5fN87XvfN19/G9Abz3gPcuxPdG8N4L3LsR3xvCe0947wrvfeG9M7z3Jr63B/cO8d4j3rvEe5947xTvveK9W7z3C/eO8d4z3rvGe9947xzvveO9e7z3D/cO8t5D3rvIex957yTvveS9m7j3k/eO8t5T3rvKe1957yzuveW9u7z3l/cO895j3ruMe5957zTvvea927z3G/eO895z3rvOe9957zzuvee9+1G8/6trhB0ofN+FOH0fVscIO1D4vht83w+87wjf94Tvu4L3feH7zvB9b/C+O3zfH7zvEN/3CO+7xPd9wvtO4X2v+L5beN+v/1veXgA=");

    /**
     * Un WebP animado con solo las cabeceras: VP8X con la marca de animación
     * y un bloque ANMF por cuadro, con su duración. Es lo único que se lee.
     */
    static byte[] animado(int lado, int... duraciones) {
        ByteArrayOutputStream cuerpo = new ByteArrayOutputStream();
        escribir(cuerpo, "WEBP");
        escribir(cuerpo, "VP8X");
        le(cuerpo, 10, 4);
        cuerpo.write(0x12);          // transparencia y animación
        le(cuerpo, 0, 3);
        le(cuerpo, lado - 1, 3);
        le(cuerpo, lado - 1, 3);
        escribir(cuerpo, "ANIM");
        le(cuerpo, 6, 4);
        le(cuerpo, 0, 4);
        le(cuerpo, 0, 2);
        for (int d : duraciones) {
            escribir(cuerpo, "ANMF");
            le(cuerpo, 16, 4);
            le(cuerpo, 0, 3);
            le(cuerpo, 0, 3);
            le(cuerpo, lado - 1, 3);
            le(cuerpo, lado - 1, 3);
            le(cuerpo, d, 3);
            cuerpo.write(0);
        }
        byte[] c = cuerpo.toByteArray();
        ByteArrayOutputStream todo = new ByteArrayOutputStream();
        escribir(todo, "RIFF");
        le(todo, c.length, 4);
        todo.writeBytes(c);
        return todo.toByteArray();
    }

    /** Un PNG con solo la firma y el bloque IHDR. */
    static byte[] png(int ancho, int alto) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes(new byte[] { (byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 13 });
        escribir(o, "IHDR");
        be(o, ancho);
        be(o, alto);
        o.writeBytes(new byte[] { 8, 6, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 });
        return o.toByteArray();
    }

    @Test
    @DisplayName("Lee el tamaño de los tres tipos de WebP y del PNG")
    void leeCabeceras() {
        Imagen a = Imagen.leer(VP8);
        assertEquals(new Imagen(Imagen.WEBP, 512, 512, false, 1, 0, 0), a);
        Imagen b = Imagen.leer(VP8L);
        assertEquals(new Imagen(Imagen.WEBP, 512, 512, false, 1, 0, 0), b);
        Imagen c = Imagen.leer(animado(512, 100, 100, 40));
        assertEquals(new Imagen(Imagen.WEBP, 512, 512, true, 3, 240, 40), c);
        assertEquals(new Imagen(Imagen.PNG, 96, 96, false, 1, 0, 0), Imagen.leer(png(96, 96)));
    }

    @Test
    @DisplayName("Lo que no es WebP ni PNG, o llega cortado, no se lee")
    void noLee() {
        assertNull(Imagen.leer(null));
        assertNull(Imagen.leer(new byte[200]));
        assertNull(Imagen.leer("GIF89a esto no es un webp ni un png".getBytes(StandardCharsets.US_ASCII)));
        assertNull(Imagen.leer(Arrays.copyOf(VP8, 31)));
    }

    @Test
    @DisplayName("Un animado de un solo cuadro cuenta como fijo")
    void unCuadro() {
        assertFalse(Imagen.leer(animado(512, 100)).animado());
    }

    @Test
    @DisplayName("Sticker: WebP de 512 x 512; fijo hasta 100 KB, animado hasta 500 KB")
    void sticker() {
        assertFalse(Reglas.sticker(VP8).animado());
        assertTrue(Reglas.sticker(animado(512, 100, 100)).animado());

        assertThrows(ResponseStatusException.class, () -> Reglas.sticker(png(512, 512)), "PNG no");
        assertThrows(ResponseStatusException.class, () -> Reglas.sticker(animado(256, 100, 100)), "medida");
        // Lo mismo, con relleno hasta pasar del peso de uno fijo.
        byte[] pesado = Arrays.copyOf(VP8, Reglas.PESO_FIJO + 1);
        assertThrows(ResponseStatusException.class, () -> Reglas.sticker(pesado), "fijo pesado");
        byte[] animadoPesado = Arrays.copyOf(animado(512, 100, 100), Reglas.PESO_FIJO + 1);
        assertTrue(Reglas.sticker(animadoPesado).animado(), "animado puede pesar más de 100 KB");
    }

    @Test
    @DisplayName("Animación: hasta 10 segundos y ningún cuadro de menos de 8 ms")
    void duracion() {
        assertThrows(ResponseStatusException.class, () -> Reglas.sticker(animado(512, 6000, 6000)));
        assertThrows(ResponseStatusException.class, () -> Reglas.sticker(animado(512, 100, 5)));
        assertTrue(Reglas.sticker(animado(512, 5000, 5000)).animado());
    }

    @Test
    @DisplayName("Ícono: PNG o WebP de 96 x 96, fijo, hasta 50 KB")
    void icono() {
        assertEquals(Imagen.PNG, Reglas.icono(png(96, 96)).tipo());
        assertThrows(ResponseStatusException.class, () -> Reglas.icono(png(512, 512)));
        assertThrows(ResponseStatusException.class, () -> Reglas.icono(animado(96, 100, 100)));
        assertThrows(ResponseStatusException.class, () -> Reglas.icono(Arrays.copyOf(png(96, 96), Reglas.PESO_ICONO + 1)));
    }

    @Test
    @DisplayName("Emojis: de 1 a 3; sin ninguno, uno por defecto; palabras no")
    void emojis() {
        assertEquals(Reglas.EMOJI_POR_DEFECTO, Reglas.emojis("  "));
        assertEquals(Reglas.EMOJI_POR_DEFECTO, Reglas.emojis(null));
        assertEquals("😂 👍", Reglas.emojis(" 😂,  👍 "));
        assertEquals("👨‍👩‍👧", Reglas.emojis("👨‍👩‍👧"), "una familia es un solo emoji");
        assertThrows(ResponseStatusException.class, () -> Reglas.emojis("😂 👍 🎉 ❤️"));
        assertThrows(ResponseStatusException.class, () -> Reglas.emojis("hola"));
        assertEquals(List.of("😂", "👍"), Reglas.listaDeEmojis("😂 👍"));
    }

    @Test
    @DisplayName("Para publicar: al menos 3 stickers y el ícono")
    void loQueFalta() {
        assertEquals(2, Reglas.loQueFalta(0, false).size());
        assertEquals(List.of("Falta 1 sticker: WhatsApp pide al menos 3."), Reglas.loQueFalta(2, true));
        assertTrue(Reglas.loQueFalta(3, true).isEmpty());
    }

    @Test
    @DisplayName("El nombre se limpia, se recorta y no puede ir vacío")
    void nombre() {
        assertEquals("Rosa Luna", Reglas.nombre("  Rosa   Luna "));
        assertEquals(128, Reglas.nombre("a".repeat(300)).length());
        assertThrows(ResponseStatusException.class, () -> Reglas.nombre(" "));
    }

    @Test
    @DisplayName("La imagen del panel llega en base64, con o sin el data: de delante")
    void base64() {
        String b64 = Base64.getEncoder().encodeToString(VP8);
        assertArrayEquals(VP8, StickersService.base64(b64, Reglas.PESO_FIJO));
        assertArrayEquals(VP8, StickersService.base64("data:image/webp;base64," + b64, Reglas.PESO_FIJO));
        assertThrows(ResponseStatusException.class, () -> StickersService.base64(b64, 100), "más del tope");
        assertThrows(ResponseStatusException.class, () -> StickersService.base64("@@@", 100));
        assertThrows(ResponseStatusException.class, () -> StickersService.base64(" ", 100));
    }

    @Test
    @DisplayName("Empiezan apagados: ni los administradores los ven hasta que se encienden")
    void alcance() {
        assertFalse(AjustesService.permite(AjustesService.RECOMENDAR_APAGADO, true, true));
        assertTrue(AjustesService.permite(AjustesService.RECOMENDAR_ADMINS, true, false));
        assertFalse(AjustesService.permite(AjustesService.RECOMENDAR_ADMINS, false, true));
        assertTrue(AjustesService.permite(AjustesService.RECOMENDAR_ELEGIDOS, false, true));
        assertFalse(AjustesService.permite(AjustesService.RECOMENDAR_ELEGIDOS, false, false));
        assertTrue(AjustesService.permite(AjustesService.RECOMENDAR_TODOS, false, false));
    }

    private static void escribir(ByteArrayOutputStream o, String s) {
        o.writeBytes(s.getBytes(StandardCharsets.US_ASCII));
    }

    private static void le(ByteArrayOutputStream o, int v, int bytes) {
        for (int i = 0; i < bytes; i++) o.write((v >>> (8 * i)) & 0xff);
    }

    private static void be(ByteArrayOutputStream o, int v) {
        for (int i = 3; i >= 0; i--) o.write((v >>> (8 * i)) & 0xff);
    }
}
