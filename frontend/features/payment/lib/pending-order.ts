/**
 * Orden pendiente de pago.
 *
 * Se guarda al crear el pago para poder retomarla cuando Mercado Pago vuelve
 * a las back_urls sin el `external_reference` (pasa si el usuario navega
 * raro o el navegador limpia la query string). Vive en sessionStorage porque
 * sólo sirve para esa vuelta del checkout.
 */
const PENDING_ORDER_KEY = 'latiendita-pending-order'

export function rememberPendingOrder(orderId: number): void {
  if (typeof window === 'undefined') return
  window.sessionStorage.setItem(PENDING_ORDER_KEY, String(orderId))
}

export function recallPendingOrder(): number | null {
  if (typeof window === 'undefined') return null
  const raw = window.sessionStorage.getItem(PENDING_ORDER_KEY)
  if (!raw) return null
  const orderId = Number(raw)
  return Number.isFinite(orderId) ? orderId : null
}

export function clearPendingOrder(): void {
  if (typeof window === 'undefined') return
  window.sessionStorage.removeItem(PENDING_ORDER_KEY)
}
