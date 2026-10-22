'use client'

import { useCallback, useState } from 'react'
import { ApiError } from '@/lib/http/errors'
import { rememberPendingOrder } from '@/features/payment/lib/pending-order'
import { orderApi } from '@/features/order/services/order.repo'
import { paymentApi } from '@/features/payment/services/payment.repo'
import type { CartLine } from '@/features/order/utils/cart'

type StartCheckoutResult = { ok: true } | { ok: false; reason: 'error'; message: string }

/**
 * Flujo de compra: pedido → pago → redirección al checkout.
 *
 * El pedido se crea primero (con los precios vigentes en el servidor: si
 * cambiaron, el backend responde 400 y el usuario lo ve), y recién después el
 * pago, que devuelve la URL donde Mercado Pago cobra. El estado final lo
 * resuelve el webhook, no este flujo: por eso se guarda la orden pendiente en
 * sessionStorage como red de seguridad para la vuelta del checkout.
 */
export function useStartCheckout() {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const startCheckout = useCallback(
    async (lines: CartLine[], payerEmail: string): Promise<StartCheckoutResult> => {
      if (lines.length === 0) return { ok: false, reason: 'error', message: 'El carrito está vacío.' }
      if (busy) return { ok: false, reason: 'error', message: 'Hay una compra en curso.' }

      setBusy(true)
      setError(null)

      try {
        const order = await orderApi.createOrder({
          items: lines.map((line) => ({ productId: line.productId, quantity: line.quantity })),
        })

        const payment = await paymentApi.createPayment({
          orderId: order.id,
          payerEmail,
        })

        // Reserva por si Mercado Pago vuelve sin external_reference.
        rememberPendingOrder(order.id)

        if (!payment.checkoutUrl) {
          throw new ApiError({
            status: 0,
            message: 'El proveedor de pagos no devolvió la URL de pago. Intentá de nuevo en unos minutos.',
          })
        }

        window.location.href = payment.checkoutUrl
        return { ok: true }
      } catch (failure) {
        const message =
          failure instanceof ApiError
            ? failure.message
            : 'No pudimos iniciar la compra. Intentá de nuevo.'
        setError(message)
        setBusy(false)
        return { ok: false, reason: 'error', message }
      }
    },
    [busy],
  )

  return { busy, error, startCheckout }
}
