package com.vocesdeizquierda.lefthub.stickers

import androidx.core.content.FileProvider

/**
 * El FileProvider que entrega los stickers sueltos a otras apps. Es una
 * subclase y no FileProvider tal cual para que no choque con el de alguna
 * biblioteca: el manifiesto los distingue por el nombre de la clase.
 * Qué carpeta comparte: res/xml/archivos_compartidos.xml.
 */
class ArchivosDeStickers : FileProvider()
