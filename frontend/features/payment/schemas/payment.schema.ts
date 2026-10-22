import { z } from 'zod'
import { dateTimeSchema, moneySchema } from '@/features/product/schemas/product.schema'

/**
 * Contratos del payment-service.
 *
 * El flujo de checkout es: `POST /api/payments` crea el pago en PENDING junto
 * con la preferencia de Mercado Pago, y devuelve `checkoutUrl` (la página de
 * pago de MP). Al volver, MP redirige a las back_urls con
 * `?external_reference=<orderNumber>`, y se consulta el pago por ese número.
 *
 * Campos que sólo se completan después del webhook (hasta entonces vienen en
 * null): `paymentMethodId`, `paymentType`, `statusDetail` y `paidAt`.
 */

export const createPaymentRequestSchema = z.object({
  orderId: z.number().int().positive(),
  payerEmail: z.email('Ingresá un correo válido').max(150),
})

export type CreatePaymentRequest = z.infer<typeof createPaymentRequestSchema>

export const paymentStatusSchema = z.enum([
  'PENDING',
  'REJECTED',
  'CANCELLED',
  'EXPIRED',
  'APPROVED',
])

export type PaymentStatus = z.infer<typeof paymentStatusSchema>

export const PAYMENT_STATUS_LABEL: Record<PaymentStatus, string> = {
  PENDING: 'Pendiente',
  REJECTED: 'Rechazado',
  CANCELLED: 'Cancelado',
  EXPIRED: 'Expirado',
  APPROVED: 'Aprobado',
}

/** Pago registrado en el backend. */
export const paymentSchema = z.object({
  id: z.number().int(),
  orderId: z.number().int(),
  /** Es lo que MP manda en `external_reference`; puede tardar en poblarse. */
  orderNumber: z.string().nullable(),
  customerId: z.number().int().nullable(),
  provider: z.string(),
  status: paymentStatusSchema,
  amount: moneySchema.nullable(),
  currency: z.string(),
  preferenceId: z.string().nullable(),
  /** URL de pago de Mercado Pago. En null si el proveedor no la generó. */
  checkoutUrl: z.string().nullable(),
  paymentMethodId: z.string().nullable(),
  paymentType: z.string().nullable(),
  statusDetail: z.string().nullable(),
  paidAt: dateTimeSchema.nullable(),
  createdAt: dateTimeSchema,
  updatedAt: dateTimeSchema,
})

export type Payment = z.infer<typeof paymentSchema>
