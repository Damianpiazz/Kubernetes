import { AxiosError } from 'axios'
import { parseFieldErrors, problemDetailsSchema } from '@/lib/schemas/problem.schema'

/**
 * Error normalizado de cualquier llamada al backend.
 *
 * Sea cual sea el origen (400 de validación, 401 del gateway, 429 del rate
 * limit, 5xx del servicio caído o incluso un body que no cumple el contrato),
 * la UI recibe siempre un ApiError con el mismo aspecto.
 */
export class ApiError extends Error {
  readonly status: number
  readonly title: string
  readonly fieldErrors: Record<string, string>
  /** Segundos a esperar antes de reintentar, si el servidor lo indicó (429/503). */
  readonly retryAfterSeconds: number | null

  constructor(params: {
    status: number
    message: string
    title?: string
    fieldErrors?: Record<string, string>
    retryAfterSeconds?: number | null
    cause?: unknown
  }) {
    super(params.message, { cause: params.cause })
    this.name = 'ApiError'
    this.status = params.status
    this.title = params.title ?? ''
    this.fieldErrors = params.fieldErrors ?? {}
    this.retryAfterSeconds = params.retryAfterSeconds ?? null
  }

  /** ¿Es un error del lado del cliente (4xx) que no tiene sentido reintentar? */
  get isClientError(): boolean {
    return this.status >= 400 && this.status < 500
  }
}

/** La respuesta no cumplió el contrato Zod: bug de integración, no de red. */
export class ContractError extends Error {
  readonly endpoint: string

  constructor(endpoint: string, detail: string, cause?: unknown) {
    super(`Respuesta inesperada de ${endpoint}: ${detail}`, { cause })
    this.name = 'ContractError'
    this.endpoint = endpoint
  }
}

const MESSAGES: Record<number, string> = {
  400: 'Revisá los datos ingresados.',
  401: 'Tu sesión expiró. Ingresá de nuevo.',
  403: 'No tenés permiso para hacer esto.',
  404: 'No encontramos lo que estabas buscando.',
  409: 'Ya existe un recurso con esos datos.',
  429: 'Demasiadas peticiones. Esperá unos segundos y volvé a intentar.',
  500: 'Algo salió mal de nuestro lado. Intentá de nuevo en unos minutos.',
  502: 'El servicio no está disponible. Intentá de nuevo en unos minutos.',
  503: 'El servicio no está disponible. Intentá de nuevo en unos minutos.',
  504: 'El servicio tardó demasiado en responder. Intentá de nuevo.',
}

function messageFor(status: number): string {
  return MESSAGES[status] ?? 'No pudimos completar la operación.'
}

function readRetryAfter(error: AxiosError): number | null {
  const header = error.response?.headers?.['retry-after']
  if (typeof header === 'string') {
    const seconds = Number(header)
    if (Number.isFinite(seconds)) return seconds
    const date = Date.parse(header)
    if (!Number.isNaN(date)) return Math.max(0, Math.round((date - Date.now()) / 1000))
  }
  return null
}

/**
 * Traduce un AxiosError al ApiError de la aplicación.
 *
 * Cubre dos particularidades del backend:
 * - el gateway devuelve 401 con body VACÍO (no es Problem Details), y
 * - el rate limit responde 429 con `Retry-After`.
 */
export function toApiError(error: unknown): ApiError {
  if (error instanceof ApiError) return error

  if (error instanceof AxiosError) {
    const status = error.response?.status ?? 0
    const data: unknown = error.response?.data
    const endpoint = `${error.config?.method?.toUpperCase() ?? ''} ${error.config?.url ?? ''}`.trim()

    // 401 sin body = el gateway rechazó el token (o no lo hay). No hay detalle.
    if (status === 401 && (data == null || (typeof data === 'string' && data.trim() === ''))) {
      return new ApiError({ status, message: MESSAGES[401], title: 'No autorizado', cause: error })
    }

    const problem = problemDetailsSchema.safeParse(data)
    if (problem.success) {
      const { title, detail, errors } = problem.data
      return new ApiError({
        status,
        message: detail || messageFor(status),
        title: title || `Error ${status}`,
        fieldErrors: parseFieldErrors(errors),
        retryAfterSeconds: readRetryAfter(error),
        cause: error,
      })
    }

    // Body que no es Problem Details: red caída, HTML de un proxy, etc.
    return new ApiError({
      status,
      message: messageFor(status),
      title: status === 0 ? 'Sin conexión' : `Error ${status}`,
      retryAfterSeconds: readRetryAfter(error),
      cause: error,
    })
  }

  if (error instanceof Error) {
    return new ApiError({ status: 0, message: messageFor(0), title: 'Sin conexión', cause: error })
  }

  return new ApiError({ status: 0, message: messageFor(0), title: 'Sin conexión' })
}
