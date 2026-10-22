import { apiClient } from '@/lib/http/client'
import { decode } from '@/lib/http/decode'
import { orderSchema, type CreateOrderRequest, type Order } from '@/features/order/schemas/order.schema'
import type { OrderApi } from '@/features/order/services/order.repo'

/**
 * Nota sobre `total`: no se envía. El servidor lo recalcula con los precios
 * vigentes de cada producto y responde 400 si el que manda el cliente no
 * coincide, así que enviarlo sólo puede fallar.
 */
export const orderApiImpl: OrderApi = {
  async createOrder(request: CreateOrderRequest): Promise<Order> {
    const response = await apiClient.post<unknown>('/api/orders', {
      items: request.items,
    })
    return decode('/api/orders', response.data, orderSchema)
  },

  async getOrder(orderId: number): Promise<Order> {
    const response = await apiClient.get<unknown>(`/api/orders/${orderId}`)
    return decode(`/api/orders/${orderId}`, response.data, orderSchema)
  },
}
