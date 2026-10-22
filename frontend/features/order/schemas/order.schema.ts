import { z } from 'zod'
import { dateTimeSchema, moneySchema } from '@/features/product/schemas/product.schema'

/**
 * Contratos del order-service.
 *
 * `total` es opcional en el request porque el servidor lo recalcula a partir de
 * los precios vigentes; si el cliente lo envía y no coincide, responde 400.
 * Se envía igualmente para que la UI pueda mostrarle al usuario el importe que
 * está por pagar.
 */

export const orderItemRequestSchema = z.object({
  productId: z.number().int().positive(),
  quantity: z.number().int().min(1, 'La cantidad debe ser al menos 1'),
})

export type OrderItemRequest = z.infer<typeof orderItemRequestSchema>

export const createOrderRequestSchema = z
  .object({
    items: z.array(orderItemRequestSchema).min(1, 'El pedido necesita al menos un producto'),
    total: moneySchema.optional(),
  })
  .refine(
    (request) => new Set(request.items.map((item) => item.productId)).size === request.items.length,
    { message: 'Hay productos repetidos en el pedido', path: ['items'] },
  )

export type CreateOrderRequest = z.infer<typeof createOrderRequestSchema>

export const orderStatusSchema = z.enum([
  'PENDING',
  'PAID',
  'SHIPPED',
  'DELIVERED',
  'CANCELLED',
])

export type OrderStatus = z.infer<typeof orderStatusSchema>

export const ORDER_STATUS_LABEL: Record<OrderStatus, string> = {
  PENDING: 'Pendiente de pago',
  PAID: 'Pagado',
  SHIPPED: 'Enviado',
  DELIVERED: 'Entregado',
  CANCELLED: 'Cancelado',
}

export const orderItemSchema = z.object({
  productId: z.number().int().nullable(),
  productTitle: z.string().nullable(),
  sku: z.string().nullable(),
  quantity: z.number().int().nullable(),
  unitPrice: moneySchema.nullable(),
  subtotal: moneySchema.nullable(),
})

export type OrderItem = z.infer<typeof orderItemSchema>

export const orderSchema = z.object({
  id: z.number().int(),
  orderNumber: z.string().nullable(),
  customerId: z.number().int().nullable(),
  status: orderStatusSchema,
  total: moneySchema.nullable(),
  refundRequired: z.boolean(),
  cancelReason: z.string().nullable(),
  items: z.array(orderItemSchema),
  createdAt: dateTimeSchema,
  updatedAt: dateTimeSchema,
})

export type Order = z.infer<typeof orderSchema>
