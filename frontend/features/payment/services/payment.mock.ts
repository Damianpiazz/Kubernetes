import { ApiError } from '@/lib/http/errors'
import { slowDelay } from '@/lib/mock/delay'
import { db } from '@/lib/mock/db'
import type {
  CreatePaymentRequest,
  Payment,
} from '@/features/payment/schemas/payment.schema'
import type { PaymentApi } from '@/features/payment/services/payment.repo'

const CURRENCY = 'ARS'

/**
 * Pagos en memoria.
 *
 * En vez de redirigir a Mercado Pago, `checkoutUrl` apunta a
 * `/checkout/simulate`, una página local que emula la pantalla de pago: al
 * aprobarla, el pago pasa a APPROVED y el pedido a PAID, y recién ahí se
 * redirige al resultado. Así se recorre el flujo completo (incluido el sondeo
 * de estado) sin backend ni túnel público.
 */
export const paymentMockImpl: PaymentApi = {
  async createPayment(request: CreatePaymentRequest): Promise<Payment> {
    await slowDelay()

    const order = db.findOrder(request.orderId)
    if (!order) {
      throw new ApiError({ status: 404, message: 'No encontramos el pedido a pagar.' })
    }

    const existing = db.findPaymentByOrderId(request.orderId)
    if (existing) return existing

    const now = new Date().toISOString()

    return db.createPayment({
      orderId: order.id,
      orderNumber: order.orderNumber,
      customerId: order.customerId,
      provider: 'MERCADO_PAGO',
      status: 'PENDING',
      amount: order.total ?? 0,
      currency: CURRENCY,
      preferenceId: `mock-pref-${order.id}`,
      checkoutUrl: `/checkout/simulate?orderId=${order.id}`,
      paymentMethodId: null,
      paymentType: null,
      statusDetail: null,
      paidAt: null,
      createdAt: now,
      updatedAt: now,
    })
  },

  async getPaymentByOrderId(orderId: number): Promise<Payment> {
    await slowDelay()

    const payment = db.findPaymentByOrderId(orderId)
    if (!payment) {
      throw new ApiError({ status: 404, message: 'Todavía no registramos el pago de esta compra.' })
    }
    return payment
  },

  async getPaymentByReference(externalReference: string): Promise<Payment> {
    await slowDelay()

    const payment = db.findPaymentByReference(externalReference)
    if (!payment) {
      throw new ApiError({ status: 404, message: 'Todavía no registramos el pago de esta compra.' })
    }
    return payment
  },
}

/**
 * Aprueba el pago simulado y deja el pedido en PAID.
 *
 * Es el equivalente local del webhook de Mercado Pago: en el backend real, el
 * webhook es quien resuelve el pago y publica el evento que actualiza el pedido.
 */
export async function approveSimulatedPayment(orderId: number): Promise<Payment> {
  await slowDelay()

  const payment = db.findPaymentByOrderId(orderId)
  if (!payment) {
    throw new ApiError({ status: 404, message: 'No encontramos el pago a aprobar.' })
  }

  const now = new Date().toISOString()
  payment.status = 'APPROVED'
  payment.paymentMethodId = 'mock'
  payment.paymentType = 'account_money'
  payment.statusDetail = 'approved (pago simulado)'
  payment.paidAt = now
  payment.updatedAt = now

  const order = db.findOrder(orderId)
  if (order && order.status === 'PENDING') {
    order.status = 'PAID'
    order.updatedAt = now
  }

  return payment
}

/**
 * Rechaza el pago simulado.
 *
 * El backend no expone esto (el estado lo decide Mercado Pago vía webhook):
 * existe sólo para poder recorrer también el camino del rechazo en modo mock.
 */
export async function rejectSimulatedPayment(orderId: number): Promise<Payment> {
  await slowDelay()

  const payment = db.findPaymentByOrderId(orderId)
  if (!payment) {
    throw new ApiError({ status: 404, message: 'No encontramos el pago a rechazar.' })
  }

  const now = new Date().toISOString()
  payment.status = 'REJECTED'
  payment.statusDetail = 'cc_rejected (pago simulado)'
  payment.updatedAt = now

  return payment
}
