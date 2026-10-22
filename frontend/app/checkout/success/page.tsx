import CheckoutResult from '@/components/checkout-result'

export const metadata = { title: 'Pago aprobado · latiendita' }

export default function SuccessPage() {
  return <CheckoutResult outcome="success" />
}
