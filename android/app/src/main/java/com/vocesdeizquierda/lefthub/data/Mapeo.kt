package com.vocesdeizquierda.lefthub.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

// Del JSON del servidor a los modelos de la app.
//
// El servidor usa nombres en español; los modelos conservan los suyos para no
// tocar la interfaz. La traducción vive aquí, en un solo sitio y sin nada de
// Android ni de red, para poder probarla con una respuesta real del servidor.
//
// Todo campo que el servidor agregó después se lee con opt…: una app nueva
// tiene que seguir funcionando contra un servidor que todavía no lo manda.

internal fun creadorDe(json: JSONObject): Creador {
    val conexiones = mutableMapOf<String, Conexion>()
    json.optJSONArray("conexiones")?.let { arr ->
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            conexiones[c.getString("plataforma")] = Conexion(
                url = c.optString("url", ""),
                handle = c.optStringONull("handle"),
                channelId = c.optStringONull("channelId")
            )
        }
    }

    return Creador(
        id = json.getString("id"),
        name = json.optString("nombre", ""),
        category = json.optString("categoria", "otros"),
        bio = json.optStringONull("bio"),
        photoUrl = json.optStringONull("fotoUrl"),
        platforms = conexiones,
        active = true,
        canales = json.optJSONArray("canales")?.mapJson { canalDe(it) } ?: emptyList(),
        productoras = json.optJSONArray("productoras").mapJsonStrings()
    )
}

internal fun canalDe(json: JSONObject) = Canal(
    id = json.optString("id", ""),
    plataforma = json.optString("plataforma", "web"),
    nombre = json.optStringONull("nombre"),
    url = json.optString("url", ""),
    handle = json.optStringONull("handle"),
    channelId = json.optStringONull("channelId"),
    creadorId = json.optStringONull("creadorId"),
    productoraId = json.optStringONull("productoraId")
)

internal fun productoraDe(json: JSONObject) = Productora(
    id = json.getString("id"),
    nombre = json.optString("nombre", ""),
    descripcion = json.optStringONull("descripcion"),
    logoUrl = json.optStringONull("logoUrl"),
    canales = json.optJSONArray("canales")?.mapJson { canalDe(it) } ?: emptyList(),
    creadores = json.optJSONArray("creadores").mapJsonStrings()
)

internal fun publicacionDe(json: JSONObject): Publicacion {
    val videoId = json.optString("videoId", "")
    return Publicacion(
        id = videoId,
        videoId = videoId,
        // Vacío en el canal propio de una productora, que no tiene creador.
        creatorId = json.optStringONull("creadorId").orEmpty(),
        creatorName = json.optStringONull("creadorNombre"),
        platform = json.optString("plataforma", "youtube"),
        title = json.optString("titulo", "Video nuevo"),
        thumbnailUrl = json.optStringONull("miniaturaUrl"),
        url = json.optStringONull("url"),
        publishedAt = json.optStringONull("publicadoEn")?.let {
            runCatching { Instant.parse(it) }.getOrNull()
        },
        status = json.optString("estado", "ok"),
        overrideUrl = json.optStringONull("destinoUrl"),
        overridePlatform = json.optStringONull("destinoPlataforma"),
        esEnVivo = json.optBoolean("enVivo", false),
        tipo = json.optString("tipo", "video"),
        productoraId = json.optStringONull("productoraId"),
        productoraNombre = json.optStringONull("productoraNombre")
    )
}

internal fun perfilDe(json: JSONObject) = Perfil(
    favoritos = json.optJSONArray("favoritos").mapJsonStrings(),
    escalaTexto = json.optString("escalaTexto", "normal"),
    tema = json.optString("tema", "sistema"),
    avisos = json.optBoolean("avisos", true),
    productoras = json.optJSONArray("productoras").mapJsonStrings()
)

internal fun suscripcionesDe(json: JSONObject) = SuscripcionesYouTube(
    suscritos = json.optJSONArray("suscritos").mapJsonStrings().toSet(),
    noSuscritos = json.optJSONArray("noSuscritos").mapJsonStrings().toSet(),
    verificadoEn = json.optStringONull("verificadoEn")?.let {
        runCatching { Instant.parse(it) }.getOrNull()
    },
    canalesSuscritos = json.optJSONArray("canalesSuscritos").mapJsonStrings().toSet(),
    canalesNoSuscritos = json.optJSONArray("canalesNoSuscritos").mapJsonStrings().toSet()
)

// --- Utilidades de JSON ------------------------------------------------------
// org.json devuelve la cadena "null" en vez de null cuando el campo viene nulo,
// que es una fuente clásica de textos con "null" impreso en la pantalla.

internal fun JSONObject.optStringONull(clave: String): String? =
    if (isNull(clave)) null else optString(clave).takeIf { it.isNotBlank() }

internal fun <T> JSONArray.mapJson(transformar: (JSONObject) -> T): List<T> =
    (0 until length()).map { transformar(getJSONObject(it)) }

internal fun JSONArray?.mapJsonStrings(): List<String> =
    this?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList()
