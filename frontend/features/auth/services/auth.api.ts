import { apiClient } from '@/lib/http/client'
import { decode } from '@/lib/http/decode'
import { ApiError } from '@/lib/http/errors'
import { clearSession, writeSession, type Session } from '@/lib/http/token-storage'
import {
  accountResponseSchema,
  tokenResponseSchema,
  userSchema,
  type LoginRequest,
  type RegisterRequest,
  type TokenResponse,
} from '@/features/auth/schemas/auth.schema'
import type { AuthApi } from '@/features/auth/services/auth.repo'

function toSession(tokens: TokenResponse): Session {
  return {
    accessToken: tokens.accessToken,
    refreshToken: tokens.refreshToken,
    // expiresIn viene en segundos; el claim `exp` del JWT dice lo mismo.
    expiresAt: Math.floor(Date.now() / 1000) + tokens.expiresIn,
  }
}

export const authApiImpl: AuthApi = {
  async register(request: RegisterRequest) {
    const response = await apiClient.post<unknown>('/api/auth/register', request)
    return decode('/api/auth/register', response.data, accountResponseSchema)
  },

  async login(request: LoginRequest) {
    const response = await apiClient.post<unknown>('/api/auth/login', request)
    const session = toSession(decode('/api/auth/login', response.data, tokenResponseSchema))
    writeSession(session)
    return session
  },

  async refresh(refreshToken: string) {
    try {
      const response = await apiClient.post<unknown>('/api/auth/refresh', { refreshToken })
      const session = toSession(decode('/api/auth/refresh', response.data, tokenResponseSchema))
      // El refresh token rota en cada uso: hay que persistir SIEMPRE el nuevo.
      writeSession(session)
      return session
    } catch (error) {
      const expired =
        error instanceof ApiError && [400, 401, 404].includes(error.status)
      if (expired) {
        clearSession()
        return null
      }
      throw error
    }
  },

  async logout(refreshToken: string) {
    try {
      // Best effort: si el backend no está, igual se limpia la sesión local.
      await apiClient.post('/api/auth/logout', { refreshToken })
    } catch {
      // sin acciones: el cierre local es lo que importa
    }
    clearSession()
  },

  async getProfile() {
    try {
      const response = await apiClient.get<unknown>('/api/users/me')
      return decode('/api/users/me', response.data, userSchema)
    } catch (error) {
      // 404 = el perfil todavía no se creó (el consumer de Kafka es
      // asíncrono); 401 = sin sesión. En ambos casos se usa el claim del JWT.
      if (error instanceof ApiError && [401, 404].includes(error.status)) return null
      throw error
    }
  },
}
