'use client'

import { Suspense, useCallback, useEffect, useState } from 'react'
import { useSearchParams } from 'next/navigation'
import { ArrowRight, CheckCircle2, Clock3, Loader2, XCircle } from 'lucide-react'
import { Button } from '@/components/ui/button'
import {
  ApiError,
  clearPendingOrder,
  getPayment,
  getPaymentByReference,
  hasToken,
  recallPendingOrder,
  type PaymentDto,
} from '@/lib/api'

/** Cuánto espera la página a que el webhook pase el pago a un estado final:
 * ~75 s (30 intentos x 2,5 s). Mercado Pago reintenta el webhook cada 15 min,
 * así que después de ese tope se le avisa al usuario que consulte más tarde. */
const MAX_ATTEMPTS = 30
const POLL_INTERVAL_MS = 2500

type Phase = 'resolving' | 'ready' | 'error'

const money = (value: number) => `$${value.toLocaleString('es-AR')}`

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

export type CheckoutOutcome = keyof typeof OUTCOME

/**
 * Página de retorno del checkout (back_urls de la preferencia de Mercado Pago).
 *
 * Mercado Pago redirige con `?external_reference=<orderNumber>` (más
 * `payment_id`, que es el SUYO y no sirve para consultar). Con eso se busca el
 * pago en la API; si no viniera, se usa la orden recordada en sessionStorage.
 * El estado que se muestra SIEMPRE es el de la API: el query param es sólo una
 * pista, porque el webhook puede llegar después del redirect.
 */
export default function CheckoutResult({ outcome }: { outcome: CheckoutOutcome }) {
  return (
    <Suspense fallback={<BusyScreen label="Cargando…" />}>
      <CheckoutResultInner outcome={outcome} />
    </Suspense>
  )
}

function CheckoutResultInner({ outcome }: { outcome: CheckoutOutcome }) {
  const searchParams = useSearchParams()
  const config = OUTCOME[outcome]

  const [phase, setPhase] = useState<Phase>('resolving')
  const [payment, setPayment] = useState<PaymentDto | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [attempts, setAttempts] = useState(0)
  const [timedOut, setTimedOut] = useState(false)

  const authenticated = hasToken()

  const resolvePayment = useCallback(async () => {
    const reference = searchParams.get('external_reference')
    const pendingOrderId = recallPendingOrder()
    const attemptsOrder: Array<[string, () => Promise<PaymentDto>]> = []
    if (reference) {
      attemptsOrder.push(['reference', () => getPaymentByReference(reference)])
    }
    if (pendingOrderId != null) {
      attemptsOrder.push(['order', () => getPayment(pendingOrderId)])
    }

    if (attemptsOrder.length === 0) {
      setError(
        'No encontramos la orden asociada a este pago. Si acabás de comprar, volvé al inicio y revisá tu cuenta.',
      )
      setPhase('error')
      return
    }

    let lastFailure: unknown = null
    for (const [, fetchPayment] of attemptsOrder) {
      try {
        const dto = await fetchPayment()
        setPayment(dto)
        setPhase('ready')
        return
      } catch (failure) {
        lastFailure = failure
      }
    }

    setError(
      lastFailure instanceof ApiError && lastFailure.status === 404
        ? 'Todavía no registramos el pago de esta compra. Esperá unos segundos y volvé a cargar.'
        : lastFailure instanceof ApiError && lastFailure.status === 401
          ? 'Tu sesión expiró. Ingresá de nuevo para ver el estado de tu compra.'
          : 'No pudimos consultar el estado del pago. Intentá de nuevo en unos minutos.',
    )
    setPhase('error')
  }, [searchParams])

  useEffect(() => {
    if (!authenticated) {
      setPhase('error')
      setError('Ingresá a tu cuenta para ver el estado de tu compra.')
      return
    }
    void resolvePayment()
  }, [authenticated, resolvePayment])

  // Sondeo mientras el pago siga PENDING (el webhook es quien lo resuelve).
  useEffect(() => {
    if (phase !== 'ready' || payment == null || payment.status !== 'PENDING') return
    if (attempts >= MAX_ATTEMPTS) {
      setTimedOut(true)
      return
    }
    const timer = setTimeout(() => {
      void (async () => {
        try {
          const next = await getPayment(payment.orderId)
          setPayment(next)
        } catch {
          // Reintenta en la próxima vuelta: un fallo transitorio no corta el sondeo.
        }
        setAttempts((current) => current + 1)
      })()
    }, POLL_INTERVAL_MS)
    return () => clearTimeout(timer)
  }, [phase, payment, attempts])

  const status = payment?.status ?? null

  useEffect(() => {
    if (status === 'APPROVED') clearPendingOrder()
  }, [status])

  if (phase === 'resolving') return <BusyScreen label="Consultando tu pago…" />

  if (phase === 'error') {
    return (
      <ResultShell title={config.fallbackTitle} tone={config.tone} Icon={config.Icon}>
        <p className="checkout-copy">{error}</p>
        <div className="checkout-actions">
          <Button size="lg" onClick={() => { window.location.href = '/' }}>
            Volver a la tienda <ArrowRight data-icon="inline-end" />
          </Button>
        </div>
      </ResultShell>
    )
  }

  if (payment == null) return <BusyScreen label="Consultando tu pago…" />

  const approved = status === 'APPROVED'
  const pending = status === 'PENDING'
  const title = approved ? 'Pago aprobado'
    : pending ? 'Pago en proceso'
      : status === 'REJECTED' ? 'Pago rechazado'
        : status === 'CANCELLED' ? 'Pago cancelado'
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
          <dd>{payment.orderNumber}</dd>
        </div>
        <div>
          <dt>Importe</dt>
          <dd>{money(payment.amount)} {payment.currency}</dd>
        </div>
        <div>
          <dt>Estado</dt>
          <dd>{payment.status}{payment.statusDetail ? ` · ${payment.statusDetail}` : ''}</dd>
        </div>
        {payment.paymentMethodId && (
          <div>
            <dt>Medio de pago</dt>
            <dd>{payment.paymentMethodId}</dd>
          </div>
        )}
      </dl>

      {pending && !timedOut && (
        <p className="checkout-hint">
          <Loader2 className="spin" data-icon="inline-start" />
          Esperando confirmación… ({attempts}/{MAX_ATTEMPTS})
        </p>
      )}
      {pending && timedOut && (
        <p className="checkout-hint">
          Seguimos esperando la confirmación. Te avisamos apenas se acredite: podés cerrar esta
          página.
        </p>
      )}

      <div className="checkout-actions">
        <Button size="lg" onClick={() => { window.location.href = '/' }}>
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
