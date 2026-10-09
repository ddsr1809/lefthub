package com.vocesdeizquierda.lefthub.data

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.vocesdeizquierda.lefthub.BuildConfig
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * La compra que quita los anuncios, en Google Play.
 *
 * Es un solo producto de pago único. Quién hace qué:
 *
 *   · Este archivo habla con Google Play: pregunta el precio, abre la pantalla
 *     de pago (que es de Google, no nuestra) y recoge el comprobante.
 *   · El servidor recibe ese comprobante, le pregunta a Google si es de
 *     verdad y, si lo es, apunta en la cuenta que ya no ve anuncios y le
 *     confirma a Google que la compra quedó entregada.
 *
 * Aquí no se decide nunca que alguien ya pagó: eso solo lo dice el servidor,
 * en el perfil. Así una app modificada no puede quitarse los anuncios sola, y
 * la compra acompaña a la cuenta y no al teléfono.
 *
 * La compra es de la cuenta de Google Play del teléfono. Por eso se puede
 * recuperar: al reinstalar la app o al cambiar de teléfono, Google Play la
 * devuelve en `pagadas()` y se vuelve a registrar sin pagar otra vez.
 */
class ComprasRepo {

    /** Lo que pasó al comprar, en términos que la interfaz entiende. */
    sealed interface Novedad {
        /** Pagada. Falta registrarla en el servidor con este comprobante. */
        data class Pagada(val producto: String, val token: String) : Novedad

        /** Pago en efectivo en una tienda, por ejemplo: Google todavía no tiene el dinero. */
        data object Pendiente : Novedad

        /** Google Play dice que esta cuenta ya lo había comprado. */
        data object YaLaTenia : Novedad

        data object Cancelada : Novedad
        data class Fallo(val mensaje: String) : Novedad
    }

    // Un canal y no un estado, igual que con los permisos de YouTube: una
    // compra es un suceso que se atiende una vez, no algo que se pinta.
    private val _novedades = Channel<Novedad>(Channel.BUFFERED)
    val novedades: Flow<Novedad> = _novedades.receiveAsFlow()

    private var cliente: BillingClient? = null
    private var producto: ProductDetails? = null

    // Una sola conversación con Google Play a la vez.
    private val turno = Mutex()

    /**
     * Conecta con Google Play y devuelve el precio ya escrito con su moneda
     * ("$29.00"), o null si aquí no se puede comprar: no hay Google Play, no
     * hay conexión o el producto no está dado de alta.
     */
    suspend fun precio(contexto: Context): String? = turno.withLock {
        val google = conectado(contexto) ?: return null
        val detalles = producto ?: consultarProducto(google)?.also { producto = it }
        detalles?.oneTimePurchaseOfferDetails?.formattedPrice
    }

    /**
     * Las compras ya pagadas de la cuenta de Google Play de este teléfono.
     * Sirve para recuperar la compra tras reinstalar o cambiar de teléfono, y
     * para registrar la que se pagó y no llegó al servidor por un corte.
     *
     * Sin contexto solo contesta si ya se había conectado antes.
     */
    suspend fun pagadas(contexto: Context? = null): List<Novedad.Pagada> = turno.withLock {
        val google = conectado(contexto) ?: return emptyList()
        consultarCompras(google).mapNotNull { it.comoPagada() }
    }

    /**
     * Abre la pantalla de pago de Google Play. Devuelve false si no se pudo
     * abrir; lo que pase después llega por `novedades`.
     */
    fun comprar(actividad: Activity): Boolean {
        val google = cliente?.takeIf { it.isReady } ?: return false
        val detalles = producto ?: return false

        val linea = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(detalles)
        // Un producto puede tener varias ofertas; se compra la que Google
        // Play devolvió para esta persona.
        detalles.oneTimePurchaseOfferDetails?.offerToken
            ?.takeIf { it.isNotBlank() }
            ?.let { linea.setOfferToken(it) }

        val parametros = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(linea.build()))
            .build()

        val resultado = google.launchBillingFlow(actividad, parametros)
        if (resultado.responseCode != BillingResponseCode.OK) {
            Log.w(TAG, "No se abrió la pantalla de pago: ${resultado.responseCode} ${resultado.debugMessage}")
        }
        return resultado.responseCode == BillingResponseCode.OK
    }

    /** Suelta la conexión con Google Play. La llama el ViewModel al morir. */
    fun cerrar() {
        runCatching { cliente?.endConnection() }
        cliente = null
        producto = null
    }

    // -------------------------------------------------------------------------

    /** Lo que Google Play cuenta cuando se cierra su pantalla de pago. */
    private fun alActualizar(resultado: BillingResult, compras: List<Purchase>?) {
        when (resultado.responseCode) {
            BillingResponseCode.OK -> compras.orEmpty().forEach { compra ->
                when (compra.purchaseState) {
                    Purchase.PurchaseState.PURCHASED ->
                        compra.comoPagada()?.let { _novedades.trySend(it) }
                    Purchase.PurchaseState.PENDING ->
                        _novedades.trySend(Novedad.Pendiente)
                    else -> Unit
                }
            }

            BillingResponseCode.USER_CANCELED -> _novedades.trySend(Novedad.Cancelada)

            BillingResponseCode.ITEM_ALREADY_OWNED -> _novedades.trySend(Novedad.YaLaTenia)

            else -> {
                Log.w(TAG, "Compra sin completar: ${resultado.responseCode} ${resultado.debugMessage}")
                _novedades.trySend(Novedad.Fallo(
                    "No se pudo completar la compra. Si Google Play llegó a cobrarte, " +
                        "los anuncios se quitan solos al volver a abrir la app."
                ))
            }
        }
    }

    private fun Purchase.comoPagada(): Novedad.Pagada? =
        if (purchaseState == Purchase.PurchaseState.PURCHASED && PRODUCTO in products) {
            Novedad.Pagada(PRODUCTO, purchaseToken)
        } else null

    private suspend fun conectado(contexto: Context?): BillingClient? {
        val google = cliente ?: BillingClient.newBuilder(contexto?.applicationContext ?: return null)
            .setListener { resultado, compras -> alActualizar(resultado, compras) }
            // Obligatorio desde la versión 7 de la biblioteca, aunque el pago
            // pendiente sea raro: sin esto no se construye el cliente.
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
            )
            // Si Google Play se desconecta, la biblioteca vuelve a conectar sola.
            .enableAutoServiceReconnection()
            .build()
            .also { cliente = it }

        if (google.isReady) return google
        return if (conectar(google)) google else null
    }

    private suspend fun conectar(google: BillingClient): Boolean =
        withTimeoutOrNull(ESPERA_MS) {
            suspendCancellableCoroutine { continuacion ->
                // Con la reconexión automática este aviso puede llegar más de
                // una vez; la corrutina solo se puede reanudar la primera.
                val contestado = AtomicBoolean(false)

                google.startConnection(object : BillingClientStateListener {
                    override fun onBillingSetupFinished(resultado: BillingResult) {
                        if (contestado.getAndSet(true)) return
                        val bien = resultado.responseCode == BillingResponseCode.OK
                        if (!bien) Log.w(TAG, "Google Play no disponible: ${resultado.responseCode} ${resultado.debugMessage}")
                        if (continuacion.isActive) continuacion.resume(bien)
                    }

                    override fun onBillingServiceDisconnected() {
                        // Nada: la biblioteca reconecta sola.
                    }
                })
            }
        } ?: false

    private suspend fun consultarProducto(google: BillingClient): ProductDetails? =
        withTimeoutOrNull(ESPERA_MS) {
            suspendCancellableCoroutine { continuacion ->
                val parametros = QueryProductDetailsParams.newBuilder()
                    .setProductList(listOf(
                        QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(PRODUCTO)
                            .setProductType(BillingClient.ProductType.INAPP)
                            .build()
                    ))
                    .build()

                google.queryProductDetailsAsync(parametros) { resultado, respuesta ->
                    val detalles = if (resultado.responseCode == BillingResponseCode.OK) {
                        respuesta.productDetailsList.firstOrNull { it.productId == PRODUCTO }
                    } else null

                    if (detalles == null) {
                        // Lo más común: el producto no existe todavía en Play
                        // Console, o la app instalada no viene de Google Play.
                        Log.w(TAG, "Sin producto $PRODUCTO: ${resultado.responseCode} ${resultado.debugMessage}")
                    }
                    if (continuacion.isActive) continuacion.resume(detalles)
                }
            }
        }

    private suspend fun consultarCompras(google: BillingClient): List<Purchase> =
        withTimeoutOrNull(ESPERA_MS) {
            suspendCancellableCoroutine { continuacion ->
                val parametros = QueryPurchasesParams.newBuilder()
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build()

                google.queryPurchasesAsync(parametros) { resultado, compras ->
                    val lista = if (resultado.responseCode == BillingResponseCode.OK) compras else emptyList()
                    if (continuacion.isActive) continuacion.resume(lista)
                }
            }
        } ?: emptyList()

    private companion object {
        const val TAG = "ComprasRepo"

        /** El producto de pago único, como está en Play Console y en el servidor. */
        val PRODUCTO: String = BuildConfig.PRODUCTO_SIN_ANUNCIOS

        // Google Play contesta en décimas de segundo. Si no contesta en este
        // tiempo es que no va a contestar, y no hay que dejar la app esperando.
        const val ESPERA_MS = 15_000L
    }
}
