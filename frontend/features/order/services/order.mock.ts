import { ApiError } from '@/lib/http/errors'
import { db } from '@/lib/mock/db'
import { slowDelay } from '@/lib/mock/delay'
import { createOrderRequestSchema, type CreateOrderRequest, type Order } from '@/features/order/schemas/order.schema'
import type { OrderApi } from '@/features/order/services/order.repo'

let orderCounter = 0

/** Pedidos en memoria: replica lo que hace order-service con los precios vigentes. */
export const orderMockImpl: OrderApi = {
  async createOrder(request: CreateOrderRequest): Promise<Order> {
    await slowDelay()

    const parsed = createOrderRequestSchema.parse(request)
    const items = parsed.items.map((item) => {
      const product = db.products.find((candidate) => candidate.productId === item.productId)
      if (!product) {
        throw new ApiError({ status: 404, message: `El producto ${item.productId} no existe.` })
      }
      const unitPrice = product.price
      return {
        productId: product.productId,
        productTitle: product.title,
        sku: product.sku,
        quantity: item.quantity,
        unitPrice,
        subtotal: unitPrice * item.quantity,
      }
    })

    const total = items.reduce((sum, item) => sum + (item.subtotal ?? 0), 0)
    const now = new Date().toISOString()

    return db.createOrder({
      orderNumber: `LT-${String(++orderCounter).padStart(6, '0')}`,
      customerId: 1,
      status: 'PENDING',
      total,
      refundRequired: false,
      cancelReason: null,
      items,
      createdAt: now,
      updatedAt: now,
    })
  },

  async getOrder(orderId: number): Promise<Order> {
    await slowDelay()

    const order = db.findOrder(orderId)
    if (!order) {
      throw new ApiError({ status: 404, message: 'No encontramos el pedido.' })
    }
    return order
  },
}
