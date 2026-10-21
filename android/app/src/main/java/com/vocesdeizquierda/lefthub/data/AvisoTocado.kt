package com.vocesdeizquierda.lefthub.data

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * El aviso que se tocó y todavía no se ha abierto. Lo pone MainActivity al
 * recibir el toque y lo recoge la navegación en cuanto la app está lista:
 * puede llegar antes de que exista la pantalla, al arrancar en frío.
 */
object AvisoTocado {
    val pendiente = MutableStateFlow<VideoDeAviso?>(null)

    /** Se tocó el aviso de un mensaje del equipo: hay que abrir el chat. */
    val chat = MutableStateFlow(false)

    /**
     * A un administrador: se tocó el aviso de que una cuenta escribió. Lleva
     * el id de esa cuenta, para abrir su conversación.
     */
    val chatEquipo = MutableStateFlow<String?>(null)

    /**
     * Un aviso del equipo que abre una ficha: la ruta de la navegación
     * ("creador/<id>", "productora/<id>" o "canal/<id>"). Igual que el de
     * arriba, puede llegar antes de que exista la pantalla.
     */
    val ficha = MutableStateFlow<String?>(null)
}
