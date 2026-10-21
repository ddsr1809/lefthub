package com.vocesdeizquierda.lefthub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vocesdeizquierda.lefthub.agregar.AgregarViewModel
import com.vocesdeizquierda.lefthub.agregar.Dueno
import com.vocesdeizquierda.lefthub.agregar.EstadoAgregar
import com.vocesdeizquierda.lefthub.data.Agregado
import com.vocesdeizquierda.lefthub.data.AltaDesdeApp
import com.vocesdeizquierda.lefthub.data.CanalEncontrado
import com.vocesdeizquierda.lefthub.data.Etiqueta
import com.vocesdeizquierda.lefthub.data.TipoDeAlta
import java.text.Collator
import java.util.Locale

/**
 * "Agregar al directorio": dar de alta un creador, un medio o un canal más
 * de alguien que ya está, desde el teléfono.
 *
 * Solo la ven las cuentas que el equipo autorizó en el panel. Lo agregado
 * sale en el acto para todos, sin aprobación; por eso se busca el canal en
 * YouTube antes de guardar, para que se vea que es el bueno.
 */
@Composable
fun AgregarPantalla(
    modelo: AgregarViewModel,
    duenos: List<Dueno>,
    etiquetas: List<Etiqueta>,
    /** Salió bien: la app vuelve a pedir el directorio. */
    onAgregado: () -> Unit,
    onVerFicha: (Agregado) -> Unit,
    onVolver: () -> Unit
) {
    val estado by modelo.estado.collectAsState()
    AgregarContenido(
        estado = estado,
        duenos = duenos,
        etiquetas = etiquetas,
        onElegir = modelo::elegir,
        onBuscar = modelo::buscar,
        onOlvidarCanal = modelo::olvidarCanal,
        onAgregar = { nombre, descripcion, dueno, marcadas ->
            modelo.agregar(nombre, descripcion, dueno, marcadas, onAgregado)
        },
        onVerFicha = onVerFicha,
        onVolver = onVolver
    )
}

@Composable
fun AgregarContenido(
    estado: EstadoAgregar,
    duenos: List<Dueno>,
    etiquetas: List<Etiqueta>,
    onElegir: (TipoDeAlta?) -> Unit,
    onBuscar: (String) -> Unit,
    onOlvidarCanal: () -> Unit,
    onAgregar: (String, String, Dueno?, List<String>) -> Unit,
    onVerFicha: (Agregado) -> Unit,
    onVolver: () -> Unit
) {
    val esquema = MaterialTheme.colorScheme
    val hecho = estado.hecho

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.navigationBars))
            .verticalScroll(rememberScrollState())
            .padding(Espacio.md)
    ) {
        TextButton(
            // Dentro de un formulario, "Volver" regresa a elegir qué agregar.
            onClick = { if (estado.tipo != null && hecho == null && !estado.noDisponible) onElegir(null) else onVolver() },
            contentPadding = PaddingValues(horizontal = 0.dp),
            modifier = Modifier.heightIn(min = Tactil.minimo)
        ) {
            Text("‹ Volver", style = MaterialTheme.typography.labelLarge)
        }
        Text(
            when (estado.tipo) {
                null -> "Agregar al directorio"
                TipoDeAlta.CREADOR -> "Agregar un creador"
                TipoDeAlta.MEDIO -> "Agregar un medio"
                TipoDeAlta.CANAL -> "Agregar un canal"
            },
            style = MaterialTheme.typography.headlineMedium,
            color = esquema.onBackground,
            modifier = Modifier.semantics { heading() }
        )

        when {
            estado.noDisponible -> {
                Text(
                    estado.error ?: "Tu cuenta ya no puede agregar al directorio.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = esquema.onSurfaceVariant,
                    modifier = Modifier
                        .padding(top = Espacio.md, bottom = Espacio.lg)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                )
                BotonGrande(titulo = "Volver", variante = VarianteBoton.SECUNDARIO, onClick = onVolver)
            }
            hecho != null -> Listo(hecho, onVerFicha = { onVerFicha(hecho) }, onOtro = { onElegir(null) })
            estado.tipo == null -> Menu(estado, onElegir)
            // Cada tipo empieza con el formulario limpio.
            else -> key(estado.tipo) {
                Formulario(estado, estado.tipo, duenos, etiquetas, onBuscar, onOlvidarCanal, onAgregar)
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Qué agregar
// -----------------------------------------------------------------------------

@Composable
private fun ColumnScope.Menu(estado: EstadoAgregar, onElegir: (TipoDeAlta) -> Unit) {
    val esquema = MaterialTheme.colorScheme
    val sinCupo = !estado.cargando && estado.historial.quedanHoy <= 0

    Text(
        "Lo que agregues sale en el directorio en el acto, para todos, sin que " +
            "nadie lo revise antes. El equipo ve después quién agregó qué.",
        style = MaterialTheme.typography.bodyLarge,
        color = esquema.onSurfaceVariant,
        modifier = Modifier.padding(top = Espacio.xs, bottom = Espacio.lg)
    )
    BotonGrande(
        titulo = "Un creador",
        subtitulo = "Una persona con su canal de YouTube",
        variante = VarianteBoton.SECUNDARIO,
        habilitado = !sinCupo,
        onClick = { onElegir(TipoDeAlta.CREADOR) }
    )
    BotonGrande(
        titulo = "Un medio",
        subtitulo = "Un periódico, una televisora o un grupo, con o sin canal",
        variante = VarianteBoton.SECUNDARIO,
        habilitado = !sinCupo,
        onClick = { onElegir(TipoDeAlta.MEDIO) }
    )
    BotonGrande(
        titulo = "Otro canal de alguien que ya está",
        subtitulo = "Un segundo canal de un creador o de un medio del directorio",
        variante = VarianteBoton.SECUNDARIO,
        habilitado = !sinCupo,
        onClick = { onElegir(TipoDeAlta.CANAL) }
    )
    if (sinCupo) {
        Text(
            "Ya agregaste todo lo que se puede hoy. Mañana puedes seguir; si es urgente, pídelo al equipo.",
            style = MaterialTheme.typography.bodyLarge,
            color = esquema.onSurfaceVariant
        )
    }
    estado.error?.let {
        Text(it, style = MaterialTheme.typography.bodyLarge, color = esquema.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }

    HorizontalDivider(color = esquema.outline, modifier = Modifier.padding(top = Espacio.lg))
    Text(
        "Lo que agregaste",
        style = MaterialTheme.typography.headlineMedium,
        color = esquema.onBackground,
        modifier = Modifier
            .padding(top = Espacio.lg, bottom = Espacio.md)
            .semantics { heading() }
    )
    when {
        estado.cargando -> Text("Cargando…", style = MaterialTheme.typography.bodyLarge, color = esquema.onSurfaceVariant)
        estado.historial.altas.isEmpty() -> Text(
            "Todavía no has agregado nada.",
            style = MaterialTheme.typography.bodyLarge,
            color = esquema.onSurfaceVariant
        )
        else -> estado.historial.altas.forEach { FilaAlta(it) }
    }
}

@Composable
private fun FilaAlta(a: AltaDesdeApp) {
    val esquema = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = Espacio.md)
            .semantics(mergeDescendants = true) {}
    ) {
        Text(a.nombre, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
            color = esquema.onBackground, maxLines = 2)
        val que = when (a.tipo) {
            TipoDeAlta.CREADOR -> "Creador"
            TipoDeAlta.MEDIO -> "Medio"
            TipoDeAlta.CANAL -> "Canal"
        }
        val cuando = tiempoRelativo(a.creadoEn)
        Text(if (cuando.isEmpty()) que else "$que · $cuando",
            style = MaterialTheme.typography.bodySmall, color = esquema.onSurfaceVariant)
    }
}

// -----------------------------------------------------------------------------
// Formulario
// -----------------------------------------------------------------------------

@Composable
private fun ColumnScope.Formulario(
    estado: EstadoAgregar,
    tipo: TipoDeAlta,
    duenos: List<Dueno>,
    etiquetas: List<Etiqueta>,
    onBuscar: (String) -> Unit,
    onOlvidarCanal: () -> Unit,
    onAgregar: (String, String, Dueno?, List<String>) -> Unit
) {
    val esquema = MaterialTheme.colorScheme
    var busqueda by rememberSaveable { mutableStateOf("") }
    var nombre by rememberSaveable { mutableStateOf("") }
    var descripcion by rememberSaveable { mutableStateOf("") }
    var dueno by remember { mutableStateOf<Dueno?>(null) }
    var marcadas by remember { mutableStateOf(setOf<String>()) }

    val encontrado = estado.encontrado
    // El nombre de YouTube, de entrada; se puede cambiar.
    LaunchedEffect(encontrado) {
        val titulo = encontrado?.takeIf { it.libre }?.titulo
        if (titulo != null && nombre.isBlank()) nombre = titulo.take(AgregarViewModel.MAX_NOMBRE)
    }

    // --- Para el canal: de quién es -------------------------------------------
    if (tipo == TipoDeAlta.CANAL) {
        Paso("¿De quién es el canal?")
        ElegirDueno(duenos, dueno, onElegir = { dueno = it })
    }

    // --- El canal de YouTube ---------------------------------------------------
    Paso(if (tipo == TipoDeAlta.MEDIO) "Su canal de YouTube (si tiene)" else "Su canal de YouTube")
    if (encontrado == null) {
        OutlinedTextField(
            value = busqueda,
            onValueChange = { busqueda = it.take(300) },
            label = { Text("Enlace del canal o @nombre") },
            supportingText = { Text("Sirve el enlace del canal, su @nombre o su ID que empieza por UC.") },
            textStyle = MaterialTheme.typography.bodyLarge,
            singleLine = true,
            enabled = !estado.buscando,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onBuscar(busqueda) }),
            modifier = Modifier.fillMaxWidth()
        )
        estado.errorBusqueda?.let {
            Text(it, style = MaterialTheme.typography.bodyLarge, color = esquema.error,
                modifier = Modifier
                    .padding(top = Espacio.xs)
                    .semantics { liveRegion = LiveRegionMode.Polite })
        }
        BotonGrande(
            titulo = if (estado.buscando) "Buscando…" else "Buscar en YouTube",
            variante = VarianteBoton.SECUNDARIO,
            habilitado = busqueda.isNotBlank() && !estado.buscando,
            onClick = { onBuscar(busqueda) },
            modifier = Modifier.padding(top = Espacio.sm)
        )
    } else {
        TarjetaCanal(encontrado)
        BotonGrande(
            titulo = if (encontrado.libre) "No es este, buscar otro" else "Buscar otro",
            variante = VarianteBoton.SECUNDARIO,
            onClick = onOlvidarCanal,
            modifier = Modifier.padding(top = Espacio.sm)
        )
    }

    // --- Nombre y descripción --------------------------------------------------
    if (tipo != TipoDeAlta.CANAL) {
        Paso("Cómo se llama")
        OutlinedTextField(
            value = nombre,
            onValueChange = { nombre = it.take(AgregarViewModel.MAX_NOMBRE) },
            label = { Text(if (tipo == TipoDeAlta.CREADOR) "Nombre del creador" else "Nombre del medio") },
            textStyle = MaterialTheme.typography.bodyLarge,
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = descripcion,
            onValueChange = { descripcion = it.take(AgregarViewModel.MAX_DESCRIPCION) },
            label = { Text("De qué trata (no es obligatorio)") },
            textStyle = MaterialTheme.typography.bodyLarge,
            minLines = 2,
            maxLines = 5,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Espacio.sm)
        )
    }

    // --- Temas -----------------------------------------------------------------
    if (etiquetas.isNotEmpty()) {
        Paso("Temas (no es obligatorio)")
        ElegirEtiquetas(etiquetas, marcadas, onCambiar = { marcadas = it })
    }

    // --- Agregar ---------------------------------------------------------------
    val canalListo = encontrado?.libre == true
    val listo = when (tipo) {
        TipoDeAlta.CREADOR -> canalListo && nombre.trim().length >= 2
        // Un medio puede entrar sin canal, pero no con uno que ya es de otro.
        TipoDeAlta.MEDIO -> nombre.trim().length >= 2 && (encontrado == null || canalListo)
        TipoDeAlta.CANAL -> canalListo && dueno != null
    }

    estado.error?.let {
        Text(it, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = esquema.error,
            modifier = Modifier
                .padding(top = Espacio.lg)
                .semantics { liveRegion = LiveRegionMode.Polite })
    }
    Text(
        "Saldrá en el directorio en cuanto lo agregues, para todos.",
        style = MaterialTheme.typography.bodySmall,
        color = esquema.onSurfaceVariant,
        modifier = Modifier.padding(top = Espacio.lg, bottom = Espacio.sm)
    )
    BotonGrande(
        titulo = if (estado.enviando) "Agregando…" else "Agregar al directorio",
        habilitado = listo && !estado.enviando,
        onClick = { onAgregar(nombre, descripcion, dueno, marcadas.toList()) }
    )
}

@Composable
private fun Paso(titulo: String) {
    Text(
        titulo,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier
            .padding(top = Espacio.lg, bottom = Espacio.sm)
            .semantics { heading() }
    )
}

@Composable
private fun TarjetaCanal(k: CanalEncontrado) {
    val esquema = MaterialTheme.colorScheme
    Surface(
        color = if (k.libre) esquema.surfaceVariant else esquema.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(Espacio.md)
                .semantics(mergeDescendants = true) {}
        ) {
            Avatar(url = k.fotoUrl, nombre = k.titulo ?: "Canal", tamano = 52.dp)
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = Espacio.md)
            ) {
                Text(k.titulo ?: "Canal sin nombre", style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold, color = esquema.onSurface, maxLines = 2)
                val detalle = listOfNotNull(
                    k.handle?.let { "@$it" },
                    k.suscriptores?.toLongOrNull()?.let { suscriptoresEnPalabras(it) }
                ).joinToString(" · ")
                if (detalle.isNotEmpty()) {
                    Text(detalle, style = MaterialTheme.typography.bodySmall, color = esquema.onSurfaceVariant)
                }
                if (!k.libre) {
                    Text(
                        "Ya está en el directorio: es de ${k.yaEsDe}.",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = esquema.onErrorContainer,
                        modifier = Modifier
                            .padding(top = Espacio.xs)
                            .semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
            }
        }
    }
}

internal fun suscriptoresEnPalabras(n: Long): String = when {
    n >= 1_000_000 -> {
        val millones = String.format(Locale("es", "MX"), "%.1f", n / 1_000_000.0).removeSuffix(".0")
        if (millones == "1") "1 millón de suscriptores" else "$millones millones de suscriptores"
    }
    n >= 1_000 -> "${n / 1_000} mil suscriptores"
    n == 1L -> "1 suscriptor"
    else -> "$n suscriptores"
}

/** Buscar al dueño entre los creadores y medios del directorio. */
@Composable
private fun ElegirDueno(duenos: List<Dueno>, elegido: Dueno?, onElegir: (Dueno?) -> Unit) {
    val esquema = MaterialTheme.colorScheme

    if (elegido != null) {
        Text(
            (if (elegido.esMedio) "Medio: " else "Creador: ") + elegido.nombre,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = esquema.onBackground
        )
        BotonGrande(titulo = "Elegir a otro", variante = VarianteBoton.SECUNDARIO,
            onClick = { onElegir(null) }, modifier = Modifier.padding(top = Espacio.sm))
    } else {
        BuscarDueno(duenos, onElegir)
    }
}

@Composable
private fun BuscarDueno(duenos: List<Dueno>, onElegir: (Dueno?) -> Unit) {
    val esquema = MaterialTheme.colorScheme
    var filtro by rememberSaveable { mutableStateOf("") }

    OutlinedTextField(
        value = filtro,
        onValueChange = { filtro = it.take(60) },
        label = { Text("Escribe su nombre") },
        textStyle = MaterialTheme.typography.bodyLarge,
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        modifier = Modifier.fillMaxWidth()
    )
    val coincidencias = remember(filtro, duenos) { filtrarDuenos(duenos, filtro) }
    if (filtro.isNotBlank() && coincidencias.isEmpty()) {
        Text("No hay nadie con ese nombre en el directorio. Agrégalo primero como creador o como medio.",
            style = MaterialTheme.typography.bodyLarge, color = esquema.onSurfaceVariant,
            modifier = Modifier.padding(top = Espacio.sm))
    }
    Column(Modifier.padding(top = Espacio.sm)) {
        coincidencias.forEach { d ->
            BotonGrande(
                titulo = d.nombre,
                subtitulo = if (d.esMedio) "Medio" else "Creador",
                variante = VarianteBoton.SECUNDARIO,
                onClick = { onElegir(d) }
            )
        }
    }
}

/** Los que empiezan por lo escrito primero, luego los que lo contienen. Como mucho seis. */
internal fun filtrarDuenos(duenos: List<Dueno>, filtro: String): List<Dueno> {
    val f = sinAcentos(filtro.trim())
    if (f.isEmpty()) return emptyList()
    val conNombre = duenos.map { it to sinAcentos(it.nombre) }
    val empiezan = conNombre.filter { (_, n) -> n.startsWith(f) || n.split(' ').any { it.startsWith(f) } }.map { it.first }
    val contienen = conNombre.filter { (d, n) -> d !in empiezan && n.contains(f) }.map { it.first }
    return (empiezan + contienen).take(6)
}

private fun sinAcentos(s: String): String =
    java.text.Normalizer.normalize(s.lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ElegirEtiquetas(etiquetas: List<Etiqueta>, marcadas: Set<String>, onCambiar: (Set<String>) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Espacio.sm)) {
        etiquetas.forEach { e ->
            val marcada = e.id in marcadas
            FilterChip(
                selected = marcada,
                onClick = { onCambiar(if (marcada) marcadas - e.id else marcadas + e.id) },
                label = { Text(e.nombre, style = MaterialTheme.typography.bodyLarge) },
                modifier = Modifier.heightIn(min = Tactil.minimo)
            )
        }
    }
}

// -----------------------------------------------------------------------------
// Listo
// -----------------------------------------------------------------------------

@Composable
private fun Listo(hecho: Agregado, onVerFicha: () -> Unit, onOtro: () -> Unit) {
    val esquema = MaterialTheme.colorScheme
    Text(
        hecho.mensaje,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.SemiBold,
        color = esquema.primary,
        modifier = Modifier
            .padding(top = Espacio.md)
            .semantics { liveRegion = LiveRegionMode.Polite }
    )
    hecho.aviso?.let {
        Text(it, style = MaterialTheme.typography.bodyLarge, color = esquema.onSurfaceVariant,
            modifier = Modifier.padding(top = Espacio.sm))
    }
    BotonGrande(
        titulo = when (hecho.tipo) {
            TipoDeAlta.CREADOR -> "Ver su ficha"
            TipoDeAlta.MEDIO -> "Ver el medio"
            TipoDeAlta.CANAL -> "Ver el canal"
        },
        onClick = onVerFicha,
        modifier = Modifier.padding(top = Espacio.lg)
    )
    BotonGrande(titulo = "Agregar otro", variante = VarianteBoton.SECUNDARIO, onClick = onOtro)
}

/** Los creadores y medios del directorio, por nombre, para elegir el dueño de un canal. */
internal fun duenosDe(
    creadores: List<com.vocesdeizquierda.lefthub.data.Creador>,
    productoras: List<com.vocesdeizquierda.lefthub.data.Productora>
): List<Dueno> {
    val porNombre = Collator.getInstance(Locale("es")).apply { strength = Collator.PRIMARY }
    // Un medio que sale en el directorio viene también en la lista de
    // creadores, como una fila más: ese se toma de las productoras.
    return (creadores.filter { !it.esProductora }.map { Dueno(it.id, it.name, esMedio = false, fotoUrl = it.photoUrl) } +
        productoras.map { Dueno(it.id, it.nombre, esMedio = true, fotoUrl = it.logoUrl) })
        .filter { it.id.isNotBlank() && it.nombre.isNotBlank() }
        .distinctBy { it.id }
        .sortedWith { a, b -> porNombre.compare(a.nombre, b.nombre) }
}
