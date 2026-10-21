package com.vocesdeizquierda.lefthub.data

/**
 * Las vistas de un video en palabras, como se dicen: "950 vistas",
 * "12 mil vistas", "1.2 millones de vistas". Un número como 1,234,567 hay
 * que leerlo cifra por cifra; "1.2 millones" se entiende de un vistazo.
 *
 * Por debajo de diez mil y de diez millones lleva un decimal ("1.5 mil"),
 * porque ahí la diferencia se nota; por encima, el número redondo basta. Se
 * redondea siempre hacia abajo: nunca se dice que un video tiene más vistas
 * de las que tiene.
 *
 * Lógica pura, sin nada de Android, para poder probarla sola.
 */
object Cifras {

    fun vistas(n: Long): String = when {
        n <= 0L -> "Sin vistas todavía"
        n == 1L -> "1 vista"
        n < 1_000L -> "$n vistas"
        n < 1_000_000L -> "${abreviar(n, 1_000L)} mil vistas"
        n < 2_000_000L && n / 100_000L == 10L -> "1 millón de vistas"
        else -> "${abreviar(n, 1_000_000L)} millones de vistas"
    }

    /** 1500 entre 1000 → "1.5"; 12_300 → "12"; 2000 → "2". */
    private fun abreviar(n: Long, unidad: Long): String {
        val enteros = n / unidad
        if (enteros >= 10L) return enteros.toString()
        val decima = (n % unidad) * 10L / unidad
        return if (decima == 0L) enteros.toString() else "$enteros.$decima"
    }
}
