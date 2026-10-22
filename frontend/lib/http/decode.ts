import type { ZodType } from 'zod'
import { ContractError } from '@/lib/http/errors'

/**
 * Valida en runtime la respuesta contra el schema de la feature.
 *
 * Es el reemplazo del `as T` que tenía `lib/api.ts`: si el backend cambia un
 * campo, esto falla acá y con el camino exacto del problema, en vez de dejar
 * que un `undefined` llegue a un render.
 */
export function decode<T>(endpoint: string, data: unknown, schema: ZodType<T>): T {
  const result = schema.safeParse(data)

  if (!result.success) {
    const detail = result.error.issues
      .slice(0, 5)
      .map((issue) => `${issue.path.join('.') || '(raíz)'}: ${issue.message}`)
      .join(' | ')

    if (process.env.NODE_ENV !== 'production') {
      console.error(`[contrato] ${endpoint} no cumple el schema → ${detail}`, data)
    }

    throw new ContractError(endpoint, detail)
  }

  return result.data
}

/** Serializa query params omitiendo los `undefined` (el backend los ignora). */
export function buildQuery(params: Record<string, string | number | undefined>): string {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === '') continue
    search.set(key, String(value))
  }
  const query = search.toString()
  return query ? `?${query}` : ''
}
