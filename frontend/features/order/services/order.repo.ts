import { createRepository } from '@/lib/data/create-repository'
import { orderApiImpl } from '@/features/order/services/order.api'
import { orderMockImpl } from '@/features/order/services/order.mock'
import type { CreateOrderRequest, Order } from '@/features/order/schemas/order.schema'

/** Contrato de pedidos: crear uno nuevo y consultar su estado. */
export interface OrderApi {
  createOrder(request: CreateOrderRequest): Promise<Order>
  getOrder(orderId: number): Promise<Order>
}

export const orderApi: OrderApi = createRepository({
  api: orderApiImpl,
  mock: orderMockImpl,
})
