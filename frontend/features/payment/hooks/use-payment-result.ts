'use client'

import { useEffect, useRef } from 'react'
import { useQuery } from '@tanstack/react-query'
import { ApiError } from '@/lib/http/errors'
import { clearPendingOrder } from '@/features/payment/lib/pending-order'
import { paymentApi } from '@/features/payment/services/payment.repo'
import type { Payment } from '@/features/payment/schemas/payment.schema'

/** ~75 s de sondeo (30 intentos x 2,5 s). */
export const MAX_POLL_ATTEMPTS = 30
const POLL_INTERVAL_MS = 2500

type PaymentResultQuery = {
  data: Payment | undefined
  isPending: boolean
  isError: boolean
  error: unknown
  refetch: () => unknown
  /** Se agotó el sondeo: el pago sigue PENDING y hay que volver más tarde. */
  timedOut: boolean
  attempts: number
}

/**
 * Estado del pago en la página de retorno.
 *
 * El sondeo se hace con `refetchInterval` en vez de un `setTimeout` a mano:
 * React Query cancela, reintentan y cachea el resultado, y el intervalo se
 * apaga solo cuando el pago sale de PENDING o se agotan los intentos.
 *
 * Mercado Pago reintenta el webhook cada 15 minutos, así que pasado el tope se
 * le avisa al usuario que consulte más tarde en vez de sondear para siempre.
 */
export function usePaymentResult(
  externalReference: string | null,
  pendingOrderId: number | null,
): PaymentResultQuery {
  const attempts = useRef(0)

  const query = useQuery<Payment>({
    queryKey: ['payment-result', externalReference ?? `order-${pendingOrderId}`],
    enabled: externalReference != null || pendingOrderId != null,
    retry: false,
    queryFn: async () => {
      attempts.current += 1

      if (externalReference) {
        try {
          return await paymentApi.getPaymentByReference(externalReference)
        } catch (error) {
          // El webhook puede no haber poblado todavía el orderNumber que usa
          // MP como external_reference: se cae a buscar por la orden recordada.
          const notFound = error instanceof ApiError && error.status === 404
          if (!notFound || pendingOrderId == null) throw error
        }
      }

      if (pendingOrderId != null) {
        return paymentApi.getPaymentByOrderId(pendingOrderId)
      }

      throw new ApiError({ status: 404, message: 'No encontramos la orden de esta compra.' })
    },
    refetchInterval: (current) =>
      current.state.data?.status === 'PENDING' && attempts.current < MAX_POLL_ATTEMPTS
        ? POLL_INTERVAL_MS
        : false,
  })

  useEffect(() => {
    if (query.data?.status === 'APPROVED') clearPendingOrder()
  }, [query.data?.status])

  return {
    data: query.data,
    isPending: query.isPending,
    isError: query.isError,
    error: query.error,
    refetch: query.refetch,
    timedOut: query.data?.status === 'PENDING' && attempts.current >= MAX_POLL_ATTEMPTS,
    attempts: attempts.current,
  }
}
