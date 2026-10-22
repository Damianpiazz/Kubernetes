import { createRepository } from '@/lib/data/create-repository'
import { authApiImpl } from '@/features/auth/services/auth.api'
import { authMockImpl } from '@/features/auth/services/auth.mock'
import type {
  Account,
  LoginRequest,
  RegisterRequest,
  UserProfile,
} from '@/features/auth/schemas/auth.schema'
import type { Session } from '@/lib/http/token-storage'

/**
 * Contrato de autenticación.
 *
 * `login` y `refresh` devuelven la sesión ya persistida (access + refresh +
 * vencimiento), y no el TokenResponse crudo: así ninguna implementación se
 * olvida de guardar el refresh token, que rota en cada uso.
 */
export interface AuthApi {
  register(request: RegisterRequest): Promise<Account>
  login(request: LoginRequest): Promise<Session>
  /** Devuelve null si el refresh ya no es válido. */
  refresh(refreshToken: string): Promise<Session | null>
  logout(refreshToken: string): Promise<void>
  /** Perfil del usuario en user-service; null si todavía no existe. */
  getProfile(): Promise<UserProfile | null>
}

export const authApi: AuthApi = createRepository({
  api: authApiImpl,
  mock: authMockImpl,
})
