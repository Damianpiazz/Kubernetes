import { apiClient } from '@/lib/http/client'
import { decode } from '@/lib/http/decode'
import { paymentSchema, type CreatePaymentRequest, type Payment } from '@/features/payment/schemas/payment.schema'
import type { PaymentApi } from '@/features/payment/services/payment.repo'

export const paymentApiImpl: PaymentApi = {
  async createPayment(request: CreatePaymentRequest): Promise<Payment> {
    const response = await apiClient.post<unknown>('/api/payments', request)
    return decode('/api/payments', response.data, paymentSchema)
  },

  async getPaymentByOrderId(orderId: number): Promise<Payment> {
    const response = await apiClient.get<unknown>(`/api/payments/order/${orderId}`)
    return decode(`/api/payments/order/${orderId}`, response.data, paymentSchema)
  },

  async getPaymentByReference(externalReference: string): Promise<Payment> {
    const response = await apiClient.get<unknown>(
      `/api/payments/reference/${encodeURIComponent(externalReference)}`,
    )
    return decode('/api/payments/reference', response.data, paymentSchema)
  },
}
