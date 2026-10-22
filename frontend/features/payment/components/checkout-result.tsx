'use client'

import { Suspense, useState } from 'react'
import { useSearchParams } from 'next/navigation'
import { ArrowRight, CheckCircle2, Clock3, Loader2, XCircle } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { ApiError } from '@/lib/http/errors'
import { money } from '@/lib/format'
import { recallPendingOrder } from '@/features/payment/lib/pending-order'
import {
  MAX_POLL_ATTEMPTS,
  usePaymentResult,
} from '@/features/payment/hooks/use-payment-result'
import { useIsAuthenticated } from '@/features/auth/hooks/use-auth'

export type CheckoutOutcome = 'success' | 'failure' | 'pending'

const OUTCOME = {
  success: {
    fallbackTitle: 'Pago aprobado',
    fallbackCopy: 'Tu compra se acreditó correctamente.',
    Icon: CheckCircle2,
    tone: '#4f7a4a',
  },
  failure: {
    fallbackTitle: 'Pago no acreditado',
    fallbackCopy: 'El pago fue rechazado. No se realizó ningún cobro.',
    Icon: XCircle,
    tone: '#9a4b45',
  },
  pending: {
    fallbackTitle: 'Pago en proceso',
    fallbackCopy: 'Estamos esperando la confirmación del medio de pago.',
    Icon: Clock3,
    tone: '#9a7b3f',
  },
} as const

/**
 * Página de retorno del checkout (back_urls de la preferencia de Mercado Pago).
 *
 * MP redirige con `?external_reference=<orderNumber>` (el `payment_id` que
 * también manda es el SUYO y no sirve para consultar). Con eso se busca el pago;
 * si no viniera, se usa la orden recordada en sessionStorage. El estado que se
 * muestra SIEMPRE es el de la API: el query param es sólo una pista, porque el
 * webhook puede llegar después del redirect.
 */
export function CheckoutResult({ outcome }: { outcome: CheckoutOutcome }) {
  return (
    <Suspense fallback={<BusyScreen label="Cargando…" />}>
      <CheckoutResultInner outcome={outcome} />
    </Suspense>
  )
}

function CheckoutResultInner({ outcome }: { outcome: CheckoutOutcome }) {
  const searchParams = useSearchParams()
  const config = OUTCOME[outcome]
  const authenticated = useIsAuthenticated()

  const reference = searchParams.get('external_reference')
  // Se lee una sola vez al montar: el flujo lo borra apenas aprueba el pago.
  const [pendingOrderId] = useState(() => recallPendingOrder())

  const result = usePaymentResult(
    authenticated ? reference : null,
    authenticated ? pendingOrderId : null,
  )

  const payment = result.data ?? null

  if (!authenticated) {
    return (
      <ResultShell title={config.fallbackTitle} tone={config.tone} Icon={config.Icon}>
        <p className="checkout-copy">Ingresá a tu cuenta para ver el estado de tu compra.</p>
        <div className="checkout-actions">
          <Button
            size="lg"
            onClick={() => {
              window.location.href = '/'
            }}
          >
            Volver a la tienda <ArrowRight data-icon="inline-end" />
          </Button>
        </div>
      </ResultShell>
    )
  }

  if (result.isPending) return <BusyScreen label="Consultando tu pago…" />

  if (result.isError || payment == null) {
    const failure = result.error
    const message =
      failure instanceof ApiError && failure.status === 404
        ? 'Todavía no registramos el pago de esta compra. Esperá unos segundos y volvé a cargar.'
        : failure instanceof ApiError && failure.status === 401
          ? 'Tu sesión expiró. Ingresá de nuevo para ver el estado de tu compra.'
          : reference == null && pendingOrderId == null
            ? 'No encontramos la orden asociada a este pago. Si acabás de comprar, volvé al inicio y revisá tu cuenta.'
            : 'No pudimos consultar el estado del pago. Intentá de nuevo en unos minutos.'

    return (
      <ResultShell title={config.fallbackTitle} tone={config.tone} Icon={config.Icon}>
        <p className="checkout-copy">{message}</p>
        <div className="checkout-actions">
          <Button size="lg" onClick={() => void result.refetch()}>
            Reintentar
          </Button>
          <Button
            variant="ghost"
            size="lg"
            onClick={() => {
              window.location.href = '/'
            }}
          >
            Volver a la tienda <ArrowRight data-icon="inline-end" />
          </Button>
        </div>
      </ResultShell>
    )
  }

  const status = payment.status
  const approved = status === 'APPROVED'
  const pending = status === 'PENDING'
  const title = approved
    ? 'Pago aprobado'
    : pending
      ? 'Pago en proceso'
      : status === 'REJECTED'
        ? 'Pago rechazado'
        : status === 'CANCELLED'
          ? 'Pago cancelado'
          : 'Pago expirado'
  const tone = approved ? '#4f7a4a' : pending ? '#9a7b3f' : '#9a4b45'

  return (
    <ResultShell title={title} tone={tone} Icon={approved ? CheckCircle2 : pending ? Clock3 : XCircle}>
      <p className="checkout-copy">
        {approved
          ? 'Tu compra se acreditó correctamente. Te mandamos el detalle a tu email.'
          : pending
            ? 'Estamos esperando la confirmación del medio de pago. Esta página se actualiza sola.'
            : 'El pago no se pudo acreditar. No se realizó ningún cobro.'}
      </p>

      <dl className="checkout-meta">
        <div>
          <dt>Orden</dt>
          <dd>{payment.orderNumber ?? `#${payment.orderId}`}</dd>
        </div>
        <div>
          <dt>Importe</dt>
          <dd>
            {money(payment.amount ?? 0)} {payment.currency}
          </dd>
        </div>
        <div>
          <dt>Estado</dt>
          <dd>
            {payment.status}
            {payment.statusDetail ? ` · ${payment.statusDetail}` : ''}
          </dd>
        </div>
        {payment.paymentMethodId && (
          <div>
            <dt>Medio de pago</dt>
            <dd>{payment.paymentMethodId}</dd>
          </div>
        )}
      </dl>

      {pending && !result.timedOut && (
        <p className="checkout-hint">
          <Loader2 className="spin" data-icon="inline-start" />
          Esperando confirmación… ({Math.min(result.attempts, MAX_POLL_ATTEMPTS)}/{MAX_POLL_ATTEMPTS})
        </p>
      )}
      {pending && result.timedOut && (
        <p className="checkout-hint">
          Seguimos esperando la confirmación. Te avisamos apenas se acredite: podés cerrar esta
          página.
        </p>
      )}

      <div className="checkout-actions">
        <Button
          size="lg"
          onClick={() => {
            window.location.href = '/'
          }}
        >
          Volver a la tienda <ArrowRight data-icon="inline-end" />
        </Button>
      </div>
    </ResultShell>
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

function ResultShell({
  title,
  tone,
  Icon,
  children,
}: {
  title: string
  tone: string
  Icon: typeof CheckCircle2
  children: React.ReactNode
}) {
  return (
    <main className="utility-overlay checkout-surface">
      <section className="utility-panel">
        <div className="utility-header">
          <div>
            <p className="eyebrow">latiendita.</p>
            <h2>{title}</h2>
          </div>
        </div>
        <div className="checkout-result">
          <span className="checkout-badge" style={{ color: tone, borderColor: tone }}>
            <Icon data-icon="inline-start" />
          </span>
          {children}
        </div>
      </section>
    </main>
  )
}
