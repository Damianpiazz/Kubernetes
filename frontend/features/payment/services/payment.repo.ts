import { createRepository } from '@/lib/data/create-repository'
import { paymentApiImpl } from '@/features/payment/services/payment.api'
import { paymentMockImpl } from '@/features/payment/services/payment.mock'
import type { CreatePaymentRequest, Payment } from '@/features/payment/schemas/payment.schema'

/**
 * Contrato de pagos.
 *
 * `getPaymentByOrderId` es el que usa el sondeo del checkout (el webhook de MP
 * es quien pasa el pago a APPROVED, y eso dispara el evento que marca el pedido
 * como PAID en order-service). `getPaymentByReference` es el de las páginas de
 * retorno: MP redirige con `?external_reference=<orderNumber>`.
 */
export interface PaymentApi {
  createPayment(request: CreatePaymentRequest): Promise<Payment>
  getPaymentByOrderId(orderId: number): Promise<Payment>
  getPaymentByReference(externalReference: string): Promise<Payment>
}

export const paymentApi: PaymentApi = createRepository({
  api: paymentApiImpl,
  mock: paymentMockImpl,
})
