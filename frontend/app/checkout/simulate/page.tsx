'use client'

import { Suspense, useEffect, useState } from 'react'
import { useSearchParams } from 'next/navigation'
import { CheckCircle2, CreditCard, Loader2, XCircle } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { ApiError } from '@/lib/http/errors'
import { money } from '@/lib/format'
import { env, isMockMode } from '@/lib/http/env'
import {
  approveSimulatedPayment,
  rejectSimulatedPayment,
} from '@/features/payment/services/payment.mock'
import { paymentApi } from '@/features/payment/services/payment.repo'

/**
 * Pantalla de pago SIMULADA (sólo modo mock).
 *
 * Reemplaza a Mercado Pago Checkout Pro cuando `NEXT_PUBLIC_DATA_MODE=mock`:
 * muestra el resumen de la compra y, al aprobar, deja el pago en APPROVED y el
 * pedido en PAID — lo mismo que haría el webhook real — para después redirigir
 * a la misma página de resultado que usaría el backend.
 *
 * En modo api no debería llegar nadie acá: `checkoutUrl` apunta a MP.
 */
export default function SimulateCheckoutPage() {
  return (
    <Suspense fallback={<BusyScreen label="Cargando el pago…" />}>
      <SimulateCheckoutInner />
    </Suspense>
  )
}

function SimulateCheckoutInner() {
  const searchParams = useSearchParams()
  const orderId = Number(searchParams.get('orderId'))

  const [payment, setPayment] = useState<Awaited<ReturnType<typeof paymentApi.getPaymentByOrderId>> | null>(null)
  const [loading, setLoading] = useState<'approve' | 'reject' | 'load' | null>('load')
  const [error, setError] = useState<string | null>(null)

  // Se carga una vez al montar; si no existe la orden, se avisa.
  useEffect(() => {
    let cancelled = false

    void (async () => {
      if (!Number.isFinite(orderId) || orderId <= 0) {
        setError('Falta el identificador del pedido.')
        setLoading(null)
        return
      }
      try {
        const found = await paymentApi.getPaymentByOrderId(orderId)
        if (!cancelled) setPayment(found)
      } catch (failure) {
        if (!cancelled) {
          setError(
            failure instanceof ApiError ? failure.message : 'No pudimos cargar el pago simulado.',
          )
        }
      } finally {
        if (!cancelled) setLoading(null)
      }
    })()

    return () => {
      cancelled = true
    }
  }, [orderId])

  async function finish(action: 'approve' | 'reject') {
    setLoading(action)
    setError(null)
    try {
      const updated =
        action === 'approve'
          ? await approveSimulatedPayment(orderId)
          : await rejectSimulatedPayment(orderId)

      const reference = updated.orderNumber
      const destination =
        action === 'approve'
          ? `/checkout/success?external_reference=${encodeURIComponent(reference ?? '')}`
          : `/checkout/failure?external_reference=${encodeURIComponent(reference ?? '')}`

      window.location.href = destination
    } catch (failure) {
      setError(
        failure instanceof ApiError ? failure.message : 'No pudimos procesar el pago simulado.',
      )
      setLoading(null)
    }
  }

  if (!isMockMode) {
    return (
      <main className="utility-overlay checkout-surface">
        <section className="utility-panel">
          <div className="checkout-result">
            <span className="checkout-badge" style={{ color: '#9a7b3f', borderColor: '#9a7b3f' }}>
              <CreditCard data-icon="inline-start" />
            </span>
            <h2>Esta página es del modo mock</h2>
            <p className="checkout-copy">
              El frontend está compilado con <code>NEXT_PUBLIC_DATA_MODE=api</code>, así que el
              pago se realiza contra Mercado Pago. Si querés simularlo, volvé a compilar en modo
              mock.
            </p>
            <div className="checkout-actions">
              <Button
                size="lg"
                onClick={() => {
                  window.location.href = '/'
                }}
              >
                Volver a la tienda
              </Button>
            </div>
          </div>
        </section>
      </main>
    )
  }

  if (loading === 'load') return <BusyScreen label="Cargando el pago…" />

  if (error && payment == null) {
    return (
      <main className="utility-overlay checkout-surface">
        <section className="utility-panel">
          <div className="checkout-result">
            <h2>No pudimos abrir el pago</h2>
            <p className="checkout-copy">{error}</p>
            <div className="checkout-actions">
              <Button
                size="lg"
                onClick={() => {
                  window.location.href = '/'
                }}
              >
                Volver a la tienda
              </Button>
            </div>
          </div>
        </section>
      </main>
    )
  }

  return (
    <main className="utility-overlay checkout-surface">
      <section className="utility-panel">
        <div className="utility-header">
          <div>
            <p className="eyebrow">pago simulado · modo mock</p>
            <h2>Resumen de compra</h2>
          </div>
        </div>

        <div className="checkout-result">
          <span className="checkout-badge" style={{ color: '#596d51', borderColor: '#596d51' }}>
            <CreditCard data-icon="inline-start" />
          </span>

          <p className="checkout-copy">
            Esta pantalla reemplaza a Mercado Pago cuando el frontend corre en modo mock. Elegí si
            aprobás o rechazás el pago para ver cómo queda el pedido.
          </p>

          <dl className="checkout-meta">
            <div>
              <dt>Pedido</dt>
              <dd>#{orderId}</dd>
            </div>
            <div>
              <dt>Importe</dt>
              <dd>
                {money(payment?.amount ?? 0)} {payment?.currency ?? 'ARS'}
              </dd>
            </div>
            <div>
              <dt>Estado</dt>
              <dd>{payment?.status ?? '—'}</dd>
            </div>
          </dl>

          {error && <p className="checkout-notice">{error}</p>}

          <div className="checkout-actions">
            <Button
              size="lg"
              disabled={loading !== null}
              onClick={() => void finish('approve')}
            >
              {loading === 'approve' ? (
                <Loader2 className="spin" data-icon="inline-start" />
              ) : (
                <CheckCircle2 data-icon="inline-start" />
              )}
              Aprobar pago
            </Button>
            <Button
              size="lg"
              variant="outline"
              disabled={loading !== null}
              onClick={() => void finish('reject')}
            >
              {loading === 'reject' ? (
                <Loader2 className="spin" data-icon="inline-start" />
              ) : (
                <XCircle data-icon="inline-start" />
              )}
              Rechazar
            </Button>
          </div>

          <p className="checkout-hint">
            Modo activo: <strong>{env.NEXT_PUBLIC_DATA_MODE}</strong>
          </p>
        </div>
      </section>
    </main>
  )
}

function BusyScreen({ label }: { label: string }) {
  return (
    <main className="utility-overlay checkout-surface">
      <section className="utility-panel">
        <div className="empty-cart">
          <Loader2 className="spin" />
          <h3>{label}</h3>
          <p>Esto tarda sólo un segundo.</p>
        </div>
      </section>
    </main>
  )
}
