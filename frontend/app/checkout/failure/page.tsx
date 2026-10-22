import CheckoutResult from '@/components/checkout-result'

export const metadata = { title: 'Pago rechazado · latiendita' }

export default function FailurePage() {
  return <CheckoutResult outcome="failure" />
}
