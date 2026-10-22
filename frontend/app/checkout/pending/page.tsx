import CheckoutResult from '@/components/checkout-result'

export const metadata = { title: 'Pago en proceso · latiendita' }

export default function PendingPage() {
  return <CheckoutResult outcome="pending" />
}
