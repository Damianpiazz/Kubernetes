import { ApiError } from '@/lib/http/errors'
import { slowDelay } from '@/lib/mock/delay'
import { db, type MockAccount } from '@/lib/mock/db'
import { clearSession, readSession, writeSession, type Session } from '@/lib/http/token-storage'
import { createFakeJwt, MOCK_TOKEN_TTL_SECONDS } from '@/features/auth/utils/fake-jwt'
import { decodeJwtClaims } from '@/features/auth/utils/jwt'
import type {
  LoginRequest,
  RegisterRequest,
  UserProfile,
} from '@/features/auth/schemas/auth.schema'
import type { AuthApi } from '@/features/auth/services/auth.repo'

/**
 * Refresh tokens "válidos".
 *
 * Se simula la rotación real del backend: cada refresh invalida el token que
 * lo originó y emite otro nuevo. Si el cliente reusara un token ya rotado, el
 * backend revocaría la familia entera, y este Set permite detectar exactamente
 * ese caso en modo mock.
 */
const validRefreshTokens = new Set<string>()

let refreshCounter = 0

function issueSession(account: MockAccount): Session {
  const refreshToken = `mock-refresh-${account.id}-${++refreshCounter}`
  validRefreshTokens.add(refreshToken)

  return {
    accessToken: createFakeJwt(account),
    refreshToken,
    expiresAt: Math.floor(Date.now() / 1000) + MOCK_TOKEN_TTL_SECONDS,
  }
}

function invalidCredentials(): ApiError {
  return new ApiError({
    status: 401,
    title: 'No autorizado',
    message: 'El correo o la contraseña no son correctos.',
    fieldErrors: {},
  })
}

export const authMockImpl: AuthApi = {
  async register(request: RegisterRequest) {
    await slowDelay()

    if (db.findAccountByEmail(request.email)) {
      throw new ApiError({
        status: 409,
        title: 'Conflicto',
        message: 'Ya existe una cuenta con ese correo.',
        fieldErrors: { email: 'Ya existe una cuenta con ese correo.' },
      })
    }
    if (db.accounts.some((account) => account.username === request.username)) {
      throw new ApiError({
        status: 409,
        title: 'Conflicto',
        message: 'Ese nombre de usuario ya está en uso.',
        fieldErrors: { username: 'Ese nombre de usuario ya está en uso.' },
      })
    }

    const account = db.createAccount(request.email, request.username, request.password)
    return { id: account.id, email: account.email, username: account.username }
  },

  async login(request: LoginRequest) {
    await slowDelay()

    const account = db.findAccountByEmail(request.email)
    if (!account || account.password !== request.password) {
      throw invalidCredentials()
    }

    const session = issueSession(account)
    writeSession(session)
    return session
  },

  async refresh(refreshToken: string) {
    await slowDelay()

    // Token ya rotado o desconocido: el backend respondería 401 y revocaría
    // la familia. Acá se limpia la sesión para que la UI pida loguearse.
    if (!validRefreshTokens.has(refreshToken)) {
      validRefreshTokens.clear()
      clearSession()
      return null
    }

    validRefreshTokens.delete(refreshToken)

    const claims = decodeJwtClaims(readSession()?.accessToken)
    const account = db.accounts.find((candidate) => candidate.id === Number(claims?.sub))
    if (!account) {
      clearSession()
      return null
    }

    const session = issueSession(account)
    writeSession(session)
    return session
  },

  async logout(refreshToken: string) {
    validRefreshTokens.delete(refreshToken)
    clearSession()
  },

  async getProfile(): Promise<UserProfile | null> {
    await slowDelay()

    const claims = decodeJwtClaims(readSession()?.accessToken)
    const account = db.accounts.find((candidate) => candidate.id === Number(claims?.sub))
    if (!account) return null

    return { id: account.id, email: account.email, username: account.username }
  },
}
