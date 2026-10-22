export const API_BASE = process.env.NEXT_PUBLIC_API_URL ?? 'http://localhost:8080'

const TOKEN_STORAGE_KEY = 'latiendita-token'
const EMAIL_STORAGE_KEY = 'latiendita-email'

/** Orden pendiente de pago: se guarda al crear el pago para poder retomarla
 * cuando Mercado Pago nos devuelve al back_url (el query param que manda MP es
 * `external_reference` = orderNumber, y la API solo consulta por id de orden). */
const PENDING_ORDER_KEY = 'latiendita-pending-order'

export function getToken(): string | null {
  if (typeof window === 'undefined') return null
  return window.localStorage.getItem(TOKEN_STORAGE_KEY)
}

export function setToken(token: string): void {
  window.localStorage.setItem(TOKEN_STORAGE_KEY, token)
}

export function setSessionEmail(email: string): void {
  window.localStorage.setItem(EMAIL_STORAGE_KEY, email)
}

export function clearToken(): void {
  window.localStorage.removeItem(TOKEN_STORAGE_KEY)
}

export function hasToken(): boolean {
  return getToken() != null
}

export function rememberPendingOrder(orderId: number): void {
  window.sessionStorage.setItem(PENDING_ORDER_KEY, String(orderId))
}

export function recallPendingOrder(): number | null {
  if (typeof window === 'undefined') return null
  const raw = window.sessionStorage.getItem(PENDING_ORDER_KEY)
  if (raw == null) return null
  const parsed = Number(raw)
  return Number.isFinite(parsed) ? parsed : null
}

export function clearPendingOrder(): void {
  window.sessionStorage.removeItem(PENDING_ORDER_KEY)
}

/**
 * Email del usuario logueado. Lo pide `CreatePaymentRequest.payerEmail`, que
 * Mercado Pago usa para el antifraude: lo leyemos del claim `email` del propio
 * JWT (lo emite auth-service) sin necesidad de un endpoint extra, con el valor
 * guardado al hacer login como respaldo.
 */
export function sessionEmail(): string | null {
  const token = getToken()
  if (token) {
    const parts = token.split('.')
    if (parts.length === 3) {
      try {
        const base64 = parts[1].replace(/-/g, '+').replace(/_/g, '/')
        const padded = base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), '=')
        const json = new TextDecoder().decode(
          Uint8Array.from(atob(padded), (char) => char.charCodeAt(0)),
        )
        const claim = (JSON.parse(json) as { email?: unknown }).email
        if (typeof claim === 'string' && claim.includes('@')) return claim
      } catch {
        // Token ilegible: cae al respaldo de abajo.
      }
    }
  }
  if (typeof window === 'undefined') return null
  return window.localStorage.getItem(EMAIL_STORAGE_KEY)
}

/** Error de API con el mensaje que devolvió el backend (RFC 7807 o no). */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly path: string,
    message: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

export async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const token = getToken()
  const headers = new Headers(init?.headers)
  headers.set('Accept', 'application/json')
  if (init?.body && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  if (token && !headers.has('Authorization')) {
    headers.set('Authorization', `Bearer ${token}`)
  }

  const response = await fetch(`${API_BASE}${path}`, { ...init, headers })
  if (!response.ok) {
    throw new ApiError(response.status, path, await readErrorMessage(response, path))
  }
  if (response.status === 204) return undefined as T
  return (await response.json()) as T
}

/** Los backends responden RFC 7807 (`detail`/`title`) o `{message}`. */
async function readErrorMessage(response: Response, path: string): Promise<string> {
  const fallback = `API ${response.status}: ${path}`
  try {
    const body = (await response.json()) as Record<string, unknown> | null
    if (!body || typeof body !== 'object') return fallback
    const detail = body.detail ?? body.message ?? body.title
    return typeof detail === 'string' && detail.length > 0 ? detail : fallback
  } catch {
    return fallback
  }
}

export type Page<T> = {
  content: T[]
}

export type ProductDto = {
  productId: number
  title: string
  imageUrl: string | null
  sku: string
  price: number
  quantity: number
  categoryId: number | null
}

export type CategoryDto = {
  categoryId: number
  name: string
  description: string | null
}

// ---------------------------------------------------------------------------
// Auth
// ---------------------------------------------------------------------------

export type TokenResponse = {
  accessToken: string
  refreshToken: string
  tokenType: string
  expiresIn: number
}

export type RegisterRequest = {
  email: string
  username: string
  password: string
}

/** Guarda el JWT en localStorage. Todas las llamadas siguientes lo adjuntan. */
export async function login(email: string, password: string): Promise<void> {
  const tokens = await apiFetch<TokenResponse>('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email, password }),
  })
  setToken(tokens.accessToken)
  setSessionEmail(email)
}

export async function register(request: RegisterRequest): Promise<void> {
  await apiFetch<unknown>('/api/auth/register', {
    method: 'POST',
    body: JSON.stringify(request),
  })
  await login(request.email, request.password)
}

// ---------------------------------------------------------------------------
// Órdenes
// ---------------------------------------------------------------------------

export type CreateOrderItem = {
  productId: number
  quantity: number
}

export type OrderItemDto = {
  productId: number
  productTitle: string
  sku: string
  quantity: number
  unitPrice: number
  subtotal: number
}

export type OrderStatus = 'PENDING' | 'PAID' | 'SHIPPED' | 'DELIVERED' | 'CANCELLED'

export type OrderDto = {
  id: number
  orderNumber: string
  customerId: number
  status: OrderStatus
  total: number
  refundRequired: boolean
  cancelReason: string | null
  items: OrderItemDto[]
  createdAt: string
  updatedAt: string
}

/**
 * Crea la orden. El `total` va **sin enviar**: order-service lo recalcula con
 * los precios vigentes de product-service y devuelve `TotalMismatchException`
 * si no coincide con el del cliente (precio desactualizado en el carrito).
 */
export async function createOrder(items: CreateOrderItem[]): Promise<OrderDto> {
  return apiFetch<OrderDto>('/api/orders', {
    method: 'POST',
    body: JSON.stringify({ items }),
  })
}

export function getOrder(orderId: number): Promise<OrderDto> {
  return apiFetch<OrderDto>(`/api/orders/${orderId}`)
}

// ---------------------------------------------------------------------------
// Pagos
// ---------------------------------------------------------------------------

export type PaymentStatus = 'PENDING' | 'REJECTED' | 'CANCELLED' | 'EXPIRED' | 'APPROVED'

export type PaymentDto = {
  id: number
  orderId: number
  orderNumber: string
  customerId: number
  provider: string
  status: PaymentStatus
  amount: number
  currency: string
  preferenceId: string | null
  checkoutUrl: string | null
  paymentMethodId: string | null
  paymentType: string | null
  statusDetail: string | null
  paidAt: string | null
  createdAt: string
  updatedAt: string
}

/**
 * Crea el pago y devuelve la preferencia de Checkout Pro. `checkoutUrl` es el
 * `init_point`: hay que redirigir el navegador ahí para pagar.
 */
export function createPayment(orderId: number, payerEmail: string): Promise<PaymentDto> {
  return apiFetch<PaymentDto>('/api/payments', {
    method: 'POST',
    body: JSON.stringify({ orderId, payerEmail }),
  })
}

export function getPayment(orderId: number): Promise<PaymentDto> {
  return apiFetch<PaymentDto>(`/api/payments/order/${orderId}`)
}

/**
 * Busca el pago por el `external_reference` que Mercado Pago devuelve en las
 * back_urls (?external_reference=<orderNumber>). Es la vía principal para que
 * la página de retorno sepa de qué orden se trata.
 */
export function getPaymentByReference(orderNumber: string): Promise<PaymentDto> {
  return apiFetch<PaymentDto>(`/api/payments/reference/${encodeURIComponent(orderNumber)}`)
}

/**
 * URL de la imagen de un producto. Las claves de blob (p. ej.
 * "productos/REM-BAS-BLA.jpg") se resuelven contra el proxy del gateway
 * /api/products/{id}/image, que funciona igual en local y producción.
 */
export function productImageSrc(
  product: Pick<ProductDto, 'productId' | 'imageUrl'>,
): string | null {
  if (!product.imageUrl) return null
  if (/^https?:\/\//.test(product.imageUrl)) return product.imageUrl
  return `${API_BASE}/api/products/${product.productId}/image`
}
