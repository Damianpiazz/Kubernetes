import axios, { AxiosError, type AxiosInstance, type InternalAxiosRequestConfig } from 'axios'
import { env } from '@/lib/http/env'
import { toApiError } from '@/lib/http/errors'
import { notifySessionExpired, tryRefreshSession } from '@/lib/http/session'
import { readSession } from '@/lib/http/token-storage'

type RetriableConfig = InternalAxiosRequestConfig & { _retried?: boolean }

/**
 * Cliente HTTP único de la aplicación.
 *
 * - `timeout` = 10 s, igual al read-timeout del gateway: si esperamos más, el
 *   gateway ya respondió 504 y la petición extra sólo genera ruido.
 * - El único header custom que el CORS del gateway acepta además de
 *   `Authorization` y `Content-Type` es `X-Correlation-Id`, y sirve para
 *   seguir una request de punta a punta en los logs del backend.
 */
export const apiClient: AxiosInstance = axios.create({
  baseURL: env.NEXT_PUBLIC_API_URL,
  timeout: 10_000,
  headers: { Accept: 'application/json' },
})

apiClient.interceptors.request.use((config) => {
  const session = readSession()
  if (session?.accessToken) {
    config.headers.set('Authorization', `Bearer ${session.accessToken}`)
  }
  config.headers.set('X-Correlation-Id', crypto.randomUUID())
  return config
})

apiClient.interceptors.response.use(
  (response) => response,
  async (error: unknown) => {
    if (!(error instanceof AxiosError)) throw error

    const config = error.config as RetriableConfig | undefined
    const status = error.response?.status
    const url = config?.url ?? ''

    // 401 en una llamada protegida => intentamos refresh una sola vez.
    // Los endpoints de auth se excluyen: ahí el 401 significa credenciales
    // inválidas, no token vencido, y reintentar sólo empeora el rate limit.
    const isAuthCall = url.startsWith('/api/auth/')
    if (status === 401 && config && !config._retried && !isAuthCall) {
      config._retried = true
      if (await tryRefreshSession()) {
        return apiClient.request(config)
      }
      notifySessionExpired()
    }

    throw toApiError(error)
  },
)
