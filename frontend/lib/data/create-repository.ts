import { env } from '@/lib/http/env'
import type { ZodType } from 'zod'

/**
 * Selector de implementación: mock o backend real.
 *
 * Cada feature expone una interfaz (`ProductApi`, `AuthApi`, ...) con dos
 * implementaciones: `*.api.ts` (axios + zod) y `*.mock.ts` (datos en memoria).
 * El factory elige en módulo según `NEXT_PUBLIC_DATA_MODE`, así el resto de la
 * app nunca pregunta de dónde vienen los datos.
 */
export function createRepository<T>(implementations: { api: T; mock: T }): T {
  return env.NEXT_PUBLIC_DATA_MODE === 'mock' ? implementations.mock : implementations.api
}

export type { ZodType }
