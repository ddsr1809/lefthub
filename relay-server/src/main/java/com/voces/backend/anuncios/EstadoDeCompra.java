package com.voces.backend.anuncios;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * Lo que importa de lo que contesta Google Play cuando se le pregunta por una
 * compra (purchases.products.get). Lógica pura, sin red: de aquí sale la
 * decisión de quitar o no los anuncios, así que se prueba aparte.
 *
 * @param reconocida ya se le dijo a Google que la compra quedó entregada
 * @param orden      el número de pedido del recibo, si lo trae
 */
record EstadoDeCompra(Pago pago, boolean reconocida, String orden, Instant compradoEn) {

    enum Pago { PAGADO, CANCELADO, PENDIENTE }

    static EstadoDeCompra de(JsonNode compra) {
        // Una compra de verdad trae al menos la fecha o el número de pedido.
        // Con una respuesta vacía o que no se entiende no se da nada por
        // pagado.
        if (compra == null || !compra.isObject()
                || !(compra.hasNonNull("purchaseTimeMillis") || compra.hasNonNull("orderId"))) {
            return new EstadoDeCompra(Pago.CANCELADO, false, null, null);
        }

        // purchaseState: 0 pagada, 1 cancelada (o devuelta), 2 pendiente.
        // Si el campo no viene se toma por cero, que es como las API de
        // Google escriben a veces los valores por defecto. Cualquier otro
        // valor, conocido o no, no es una compra pagada.
        JsonNode estado = compra.path("purchaseState");
        Pago pago = switch (estado.isMissingNode() ? 0 : estado.asInt(-1)) {
            case 0 -> Pago.PAGADO;
            case 2 -> Pago.PENDIENTE;
            default -> Pago.CANCELADO;
        };

        // acknowledgementState: 0 sin reconocer, 1 reconocida.
        boolean reconocida = compra.path("acknowledgementState").asInt(0) == 1;

        String orden = compra.path("orderId").asText("");

        // Viene como texto ("1728345600000") o como número, según el día.
        long milis = compra.path("purchaseTimeMillis").asLong(0);

        return new EstadoDeCompra(pago, reconocida,
                orden.isBlank() ? null : orden,
                milis > 0 ? Instant.ofEpochMilli(milis) : null);
    }
}
