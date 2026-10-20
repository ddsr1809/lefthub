package com.vocesdeizquierda.lefthub.data

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * El aviso que se tocó y todavía no se ha abierto. Lo pone MainActivity al
 * recibir el toque y lo recoge la navegación en cuanto la app está lista:
 * puede llegar antes de que exista la pantalla, al arrancar en frío.
 */
object AvisoTocado {
    val pendiente = MutableStateFlow<VideoDeAviso?>(null)

    /** Se tocó el aviso de que el equipo quiere hablar: hay que abrir el chat. */
    val chat = MutableStateFlow(false)
}
