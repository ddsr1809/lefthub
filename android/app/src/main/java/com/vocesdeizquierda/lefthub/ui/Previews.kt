package com.vocesdeizquierda.lefthub.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import java.time.Instant
import com.vocesdeizquierda.lefthub.EstadoApp
import com.vocesdeizquierda.lefthub.data.Canal
import com.vocesdeizquierda.lefthub.data.Conexion
import com.vocesdeizquierda.lefthub.data.Creador
import com.vocesdeizquierda.lefthub.data.EstadoYouTube
import com.vocesdeizquierda.lefthub.data.Etiqueta
import com.vocesdeizquierda.lefthub.data.PermisoYouTube
import com.vocesdeizquierda.lefthub.data.SuscripcionesYouTube
import com.vocesdeizquierda.lefthub.data.Perfil
import com.vocesdeizquierda.lefthub.data.Productora
import com.vocesdeizquierda.lefthub.data.Publicacion
import com.vocesdeizquierda.lefthub.data.VideosDeCanal


// Previews.
//
// Sirven para dos cosas distintas:
//
//  1. Ver cómo queda la pantalla sin compilar ni instalar nada. Basta con
//     abrir este archivo y pulsar "Split" arriba a la derecha del editor.
//
//  2. Comprobar accesibilidad de verdad. @VistaPreviaAccesible renderiza la
//     misma pantalla con el texto al 100% y al 200%, que es el escenario que
//     exige WCAG 1.4.4 y el que más suele romperse. Si algo se corta o se
//     desborda, aquí se ve al instante en lugar de descubrirlo cuando un
//     usuario mayor ya no puede leer un botón.
//
// Los datos de abajo son inventados: las previews no tocan Firebase ni red.

// -----------------------------------------------------------------------------
// Anotaciones múltiples
// -----------------------------------------------------------------------------

/** Modo claro y oscuro de un tirón. */
@Preview(name = "Claro", showBackground = true, backgroundColor = 0xFFFAFAF8)
@Preview(
    name = "Oscuro",
    showBackground = true,
    backgroundColor = 0xFF16181C,
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
annotation class VistaPreviaTemas

/** El mismo diseño con la letra del sistema al 100% y al 200%. */
@Preview(name = "Letra normal", showBackground = true, backgroundColor = 0xFF16181C,
    uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Letra al 200%", showBackground = true, backgroundColor = 0xFF16181C,
    uiMode = Configuration.UI_MODE_NIGHT_YES, fontScale = 2.0f, heightDp = 900)
annotation class VistaPreviaAccesible

// -----------------------------------------------------------------------------
// Datos de ejemplo
// -----------------------------------------------------------------------------

private val juan = Creador(
    id = "juan",
    name = "Juan Pérez",
    category = "comida",
    bio = "Recetas caseras sin complicaciones. Un video nuevo cada martes.",
    photoUrl = null,
    platforms = mapOf(
        "youtube" to Conexion(url = "https://www.youtube.com/channel/UC123", channelId = "UC123"),
        "tiktok" to Conexion(url = "https://www.tiktok.com/@juanperez", handle = "juanperez"),
        "instagram" to Conexion(url = "https://www.instagram.com/juanperez")
    ),
    // Tres canales de YouTube: el suyo, el de clips y uno que es de la productora.
    canales = listOf(
        Canal(id = "c1", plataforma = "youtube", url = "https://www.youtube.com/channel/UC123",
            channelId = "UC123", creadorId = "juan"),
        Canal(id = "c2", plataforma = "youtube", nombre = "Clips",
            url = "https://www.youtube.com/channel/UC124", channelId = "UC124", creadorId = "juan"),
        Canal(id = "c3", plataforma = "youtube", nombre = "Cocina con Juan",
            url = "https://www.youtube.com/channel/UC125", channelId = "UC125",
            creadorId = "juan", productoraId = "estudio"),
        Canal(id = "c4", plataforma = "tiktok", url = "https://www.tiktok.com/@juanperez",
            handle = "juanperez", creadorId = "juan"),
        Canal(id = "c5", plataforma = "instagram", url = "https://www.instagram.com/juanperez",
            creadorId = "juan"),
        // El canal oficial de la productora, en el que también aparece él.
        Canal(id = "c9", plataforma = "youtube", nombre = "Oficial",
            url = "https://www.youtube.com/channel/UC900", channelId = "UC900",
            productoraId = "estudio", tambien = listOf("juan", "ana"))
    ),
    productoras = listOf("estudio")
)

private val estudio = Productora(
    id = "estudio",
    nombre = "Estudio X",
    descripcion = "La casa de varios creadores de cocina y cine.",
    canales = listOf(
        Canal(id = "c9", plataforma = "youtube", nombre = "Oficial",
            url = "https://www.youtube.com/channel/UC900", channelId = "UC900",
            productoraId = "estudio", tambien = listOf("juan", "ana")),
        Canal(id = "c3", plataforma = "youtube", nombre = "Cocina con Juan",
            url = "https://www.youtube.com/channel/UC125", channelId = "UC125",
            creadorId = "juan", productoraId = "estudio")
    ),
    creadores = listOf("juan", "ana"),
    enDirectorio = true
)

/** La misma productora tal como llega en el listado de creadores. */
private val estudioEnElDirectorio = Creador(
    id = "estudio", name = "Estudio X", category = "cine",
    bio = estudio.descripcion, canales = estudio.canales, esProductora = true
)

private val ana = Creador(
    id = "ana",
    name = "Ana Ruiz",
    category = "cine",
    bio = "Reseñas de cine clásico.",
    platforms = mapOf("youtube" to Conexion(url = "https://www.youtube.com/channel/UC456"))
)

private val luis = Creador(
    id = "luis",
    name = "Luis Mendoza",
    category = "noticias",
    platforms = mapOf(
        "youtube" to Conexion(url = "https://www.youtube.com/channel/UC789"),
        "twitch" to Conexion(url = "https://www.twitch.tv/luismendoza")
    )
)

/** Alguien que solo tiene redes: una cuenta de X y otra de Instagram, sin YouTube. */
private val rosa = Creador(
    id = "rosa",
    name = "Rosa Luna",
    category = "politica",
    bio = "Columnista. Escribe todos los días.",
    canales = listOf(
        Canal(id = "r1", plataforma = "x", url = "https://x.com/rosaluna", creadorId = "rosa"),
        Canal(id = "r2", plataforma = "instagram", url = "https://www.instagram.com/rosaluna",
            creadorId = "rosa")
    )
)

private val creadores = listOf(juan, ana, luis)

private val publicaciones = listOf(
    Publicacion(
        id = "v1",
        videoId = "v1",
        creatorId = "juan",
        creatorName = "Juan Pérez",
        title = "Pan de muerto casero: la receta de mi abuela paso a paso",
        thumbnailUrl = null,
        url = "https://www.youtube.com/watch?v=v1",
        publishedAt = Instant.ofEpochMilli(
            System.currentTimeMillis() - 45 * 60 * 1000L
        )
    ),
    Publicacion(
        id = "v2",
        videoId = "v2",
        creatorId = "ana",
        creatorName = "Ana Ruiz",
        title = "Por qué Casablanca sigue funcionando 80 años después",
        url = "https://vimeo.com/respaldo",
        status = "moved",
        overrideUrl = "https://vimeo.com/respaldo",
        overridePlatform = "web",
        publishedAt = Instant.ofEpochMilli(
            System.currentTimeMillis() - 5 * 60 * 60 * 1000L
        )
    )
)

private val perfilConFavoritos = Perfil(favoritos = listOf("juan", "ana"), tema = "oscuro")

/** Envoltorio para que cada preview salga con el tema real de la app. */
@Composable
private fun Marco(
    tema: String = "oscuro",
    escala: EscalaTexto = EscalaTexto.NORMAL,
    contenido: @Composable () -> Unit
) {
    TemaRelay(preferencia = tema, escala = escala) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxSize()
        ) { contenido() }
    }
}

// -----------------------------------------------------------------------------
// Componentes sueltos
// -----------------------------------------------------------------------------

@VistaPreviaTemas
@Composable
private fun PreviaBotones() {
    Marco {
        Column(Modifier.padding(Espacio.md)) {
            BotonGrande(
                titulo = "Ver el video",
                subtitulo = "Se abre en YouTube",
                onClick = {}
            )
            BotonGrande(
                titulo = "Ver videos cortos",
                subtitulo = "Se abre la app de TikTok",
                variante = VarianteBoton.SECUNDARIO,
                onClick = {}
            )
            BotonGrande(
                titulo = "Borrar mi cuenta",
                variante = VarianteBoton.PELIGRO,
                onClick = {}
            )
            BotonGrande(
                titulo = "Guardar con Google",
                habilitado = false,
                onClick = {}
            )
        }
    }
}

@VistaPreviaAccesible
@Composable
private fun PreviaFilasDelDirectorio() {
    Marco {
        Column(Modifier.padding(Espacio.md)) {
            FilaCreador(juan, siguiendo = true, onAbrir = {}, onSeguir = {})
            FilaCreador(ana, siguiendo = false, onAbrir = {}, onSeguir = {})
            FilaCreador(luis, siguiendo = false, onAbrir = {}, onSeguir = {})
        }
    }
}

// -----------------------------------------------------------------------------
// Pantallas completas
// -----------------------------------------------------------------------------

@VistaPreviaTemas
@Composable
private fun PreviaNovedades() {
    Marco {
        NovedadesPantalla(
            publicaciones = publicaciones,
            hayFavoritos = true,
            cuantosFavoritos = 2,
            onIrAlDirectorio = {},
            onReportar = {},
            // El primero ya se abrió: se ven las dos tarjetas, la nueva y la vista.
            abiertos = setOf("v1"),
            // Y arriba, la explicación de la tele, como la primera vez.
            ayudaTele = true
        )
    }
}

/** Con los videos cortos encendidos: aparecen los dos apartados. */
@VistaPreviaTemas
@Composable
private fun PreviaNovedadesConCortos() {
    Marco {
        NovedadesPantalla(
            publicaciones = publicaciones,
            hayFavoritos = true,
            cuantosFavoritos = 2,
            onIrAlDirectorio = {},
            onReportar = {},
            cortos = publicaciones.take(1).map { it.copy(tipo = "short") },
            verCortos = true
        )
    }
}

/** El caso vacío importa tanto como el lleno: es la primera pantalla que ve alguien. */
@VistaPreviaTemas
@Composable
private fun PreviaNovedadesVacio() {
    Marco {
        NovedadesPantalla(
            publicaciones = emptyList(),
            hayFavoritos = false,
            cuantosFavoritos = 0,
            onIrAlDirectorio = {},
            onReportar = {}
        )
    }
}

@VistaPreviaAccesible
@Composable
private fun PreviaDirectorio() {
    Marco {
        DirectorioPantalla(
            creadores = creadores + estudioEnElDirectorio,
            favoritos = listOf("juan"),
            onSeguir = {},
            onAbrirCreador = {},
            // Con YouTube conectado: sale la leyenda arriba, Juan con su
            // etiqueta verde y Ana sin suscripción.
            youtube = EstadoYouTube(
                PermisoYouTube.CONCEDIDO,
                SuscripcionesYouTube(suscritos = setOf("juan"), noSuscritos = setOf("ana"))
            ),
            productoras = listOf(estudio),
            productorasSeguidas = listOf("estudio"),
            // Dos etiquetas encendidas: salen como filtro encima de la lista.
            etiquetas = listOf(
                Etiqueta("e1", "Cocina", creadores = setOf("juan")),
                Etiqueta("e2", "Cine", creadores = setOf("ana"), productoras = setOf("estudio"))
            )
        )
    }
}

@VistaPreviaAccesible
@Composable
private fun PreviaPerfilDeCreador() {
    Marco {
        CreadorPantalla(
            creador = juan,
            siguiendo = false,
            onSeguir = {},
            onVolver = {},
            productoras = listOf(estudio),
            // El mini feed: sus últimos videos, arriba de sus canales.
            videos = VideosDeCanal("juan", lista = publicaciones)
        )
    }
}

/** Un creador sin canal de YouTube: se dice, y se enseñan sus redes. */
@VistaPreviaTemas
@Composable
private fun PreviaPerfilSoloConRedes() {
    Marco {
        CreadorPantalla(
            creador = rosa,
            siguiendo = false,
            onSeguir = {},
            onVolver = {}
        )
    }
}

@VistaPreviaAccesible
@Composable
private fun PreviaFichaDeCanal() {
    Marco {
        CanalPantalla(
            canal = estudio.canales.first(),
            creadores = creadores,
            productoras = listOf(estudio),
            videos = VideosDeCanal(canalId = "c9", lista = publicaciones),
            onVolver = {}
        )
    }
}

@VistaPreviaAccesible
@Composable
private fun PreviaFichaDeProductora() {
    Marco {
        ProductoraPantalla(
            productora = estudio,
            siguiendo = true,
            onSeguir = {},
            onVolver = {},
            creadores = creadores,
            favoritos = listOf("juan")
        )
    }
}

@VistaPreviaTemas
@Composable
private fun PreviaAjustes() {
    Marco {
        AjustesPantalla(
            estado = EstadoApp(
                listo = true,
                creadores = creadores,
                perfil = perfilConFavoritos,
                esAnonimo = true
            ),
            onGuardarPreferencia = { _, _ -> },
            onVincularGoogle = {},
            onCerrarSesion = {},
            onBorrarCuenta = {}
        )
    }
}

/** Ajustes cuando el equipo permite los videos cortos: aparece su sección. */
@Preview(name = "Ajustes con videos cortos", showBackground = true, heightDp = 1100)
@Composable
private fun PreviaAjustesConCortos() {
    Marco(tema = "claro") {
        AjustesPantalla(
            estado = EstadoApp(
                listo = true,
                perfil = perfilConFavoritos.copy(tema = "claro", cortosDisponibles = true),
                esAnonimo = true
            ),
            onGuardarPreferencia = { _, _ -> },
            onVincularGoogle = {},
            onCerrarSesion = {},
            onBorrarCuenta = {}
        )
    }
}

/** Cómo se ve Ajustes con la cuenta ya guardada. */
@Preview(name = "Ajustes con sesión", showBackground = true, heightDp = 900,
    uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviaAjustesConSesion() {
    Marco {
        AjustesPantalla(
            estado = EstadoApp(
                listo = true,
                perfil = perfilConFavoritos,
                esAnonimo = false,
                correo = "juan.perez@gmail.com"
            ),
            onGuardarPreferencia = { _, _ -> },
            onVincularGoogle = {},
            onCerrarSesion = {},
            onBorrarCuenta = {}
        )
    }
}

// -----------------------------------------------------------------------------
// Anuncios
// -----------------------------------------------------------------------------

/**
 * La tarjeta de un anuncio junto a la de un video, para comprobar que no se
 * confunden. El recuadro gris hace de anuncio: aquí no hay ninguno de verdad,
 * porque las previews no tocan la red.
 */
@VistaPreviaTemas
@VistaPreviaAccesible
@Composable
private fun PreviaTarjetaDeAnuncio() {
    Marco {
        Column(Modifier.padding(Espacio.md)) {
            TarjetaPublicacion(publicacion = publicaciones.first(), onAbrir = {}, onReportar = {})
            MarcoDeAnuncio(onQuitar = {}) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .background(MaterialTheme.colorScheme.outline)
                ) { Text("Aquí va el anuncio de Google") }
            }
        }
    }
}

/** Ajustes con los anuncios encendidos: comprar, o canjear un folio de regalo. */
@Preview(name = "Ajustes con anuncios", showBackground = true, heightDp = 1300)
@Composable
private fun PreviaAjustesConAnuncios() {
    Marco(tema = "claro") {
        AjustesPantalla(
            estado = EstadoApp(
                listo = true,
                perfil = perfilConFavoritos.copy(tema = "claro", anuncios = true, compraDisponible = true),
                esAnonimo = true,
                precioSinAnuncios = "\$29.00"
            ),
            onGuardarPreferencia = { _, _ -> },
            onVincularGoogle = {},
            onCerrarSesion = {},
            onBorrarCuenta = {}
        )
    }
}

/** Ajustes de quien ya quitó los anuncios, todavía como invitado. */
@Preview(name = "Ajustes sin anuncios", showBackground = true, heightDp = 1100,
    uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviaAjustesSinAnuncios() {
    Marco {
        AjustesPantalla(
            estado = EstadoApp(
                listo = true,
                perfil = perfilConFavoritos.copy(anuncios = true, sinAnuncios = true),
                esAnonimo = true
            ),
            onGuardarPreferencia = { _, _ -> },
            onVincularGoogle = {},
            onCerrarSesion = {},
            onBorrarCuenta = {}
        )
    }
}
