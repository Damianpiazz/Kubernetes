import { QueryClient } from '@tanstack/react-query'
import { ApiError } from '@/lib/http/errors'

/**
 * Defaults del cache.
 *
 * - No se reintenta un 4xx: son errores del cliente (404, 409, validación) y
 *   volver a pegar sólo ensucia el rate limit del gateway (10 req/min en auth).
 * - `staleTime` de 30 s: el catálogo no cambia a cada rato y evita un refetch
 *   en cada navegación.
 */
export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: (failureCount, error) => {
          if (error instanceof ApiError && error.isClientError) return false
          return failureCount < 1
        },
        staleTime: 30_000,
        refetchOnWindowFocus: false,
      },
      mutations: {
        retry: false,
      },
    },
  })
}
