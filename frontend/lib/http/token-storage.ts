/**
 * Persistencia de la sesión (access + refresh token).
 *
 * El backend devuelve `expiresIn` en segundos (TTL de 15 min) y rota el refresh
 * token en cada uso, así que hay que guardar SIEMPRE el último refresh token
 * recibido: reusar uno ya rotado revoca toda la familia de tokens.
 */

export const TOKEN_STORAGE_KEY = 'latiendita-session'

export type Session = {
  accessToken: string
  refreshToken: string
  /** Epoch en segundos: cuándo vence el access token. */
  expiresAt: number
}

export function readSession(): Session | null {
  if (typeof window === 'undefined') return null

  const raw = window.localStorage.getItem(TOKEN_STORAGE_KEY)
  if (!raw) return null

  try {
    const parsed: unknown = JSON.parse(raw)
    if (
      typeof parsed !== 'object' ||
      parsed === null ||
      typeof (parsed as Session).accessToken !== 'string' ||
      typeof (parsed as Session).refreshToken !== 'string' ||
      typeof (parsed as Session).expiresAt !== 'number'
    ) {
      // Sesión corrupta: se limpia para no arrastrar un estado inválido.
      window.localStorage.removeItem(TOKEN_STORAGE_KEY)
      return null
    }
    return parsed as Session
  } catch {
    window.localStorage.removeItem(TOKEN_STORAGE_KEY)
    return null
  }
}

export function writeSession(session: Session): void {
  if (typeof window === 'undefined') return
  window.localStorage.setItem(TOKEN_STORAGE_KEY, JSON.stringify(session))
}

export function clearSession(): void {
  if (typeof window === 'undefined') return
  window.localStorage.removeItem(TOKEN_STORAGE_KEY)
}

export function hasSession(): boolean {
  return readSession() !== null
}
