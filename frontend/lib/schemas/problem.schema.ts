import { z } from 'zod'

/**
 * Shape del error del backend.
 *
 * Todos los servicios devuelven Problem Details (RFC 7807) con más campos de
 * contexto (`timestamp`, `path`, `correlationId`). La validación es laxa a
 * propósito: estos campos son diagnóstico, no contrato.
 *
 * Excepción importante: el gateway devuelve 401 con body VACÍO cuando el JWT
 * falta o falla, así que esto sólo aplica si hay body.
 */
export const problemDetailsSchema = z.object({
  type: z.string().optional(),
  title: z.string().optional(),
  status: z.number().int().optional(),
  detail: z.string().optional(),
  instance: z.string().optional(),
  /** Errores de validación, cada uno como "campo: mensaje". */
  errors: z.array(z.string()).optional(),
  timestamp: z.string().optional(),
  path: z.string().optional(),
  correlationId: z.string().optional(),
})

export type ProblemDetails = z.infer<typeof problemDetailsSchema>

/**
 * Convierte `["email: debe ser un correo válido", "username: muy corto"]` en
 * `{ email: 'debe ser un correo válido', username: 'muy corto' }` para poder
 * mostrar el error debajo de cada campo del formulario.
 */
export function parseFieldErrors(errors: string[] | undefined): Record<string, string> {
  if (!errors?.length) return {}

  const fieldErrors: Record<string, string> = {}
  for (const entry of errors) {
    const separator = entry.indexOf(':')
    if (separator === -1) {
      fieldErrors._ = entry
      continue
    }
    const field = entry.slice(0, separator).trim()
    const message = entry.slice(separator + 1).trim()
    if (field) fieldErrors[field] = message
  }
  return fieldErrors
}
