import { jwtClaimsSchema, type JwtClaims } from '@/features/auth/schemas/auth.schema'

/**
 * Lee los claims del access token.
 *
 * El backend no expone un `GET /me` en auth-service: el perfil se resuelve con
 * los claims del JWT (`sub`, `email`, `username`), que son los mismos que
 * propagan los servicios entre sí vía `X-User-Id`.
 */
export function decodeJwtClaims(token: string | null | undefined): JwtClaims | null {
  if (!token) return null

  const parts = token.split('.')
  if (parts.length < 2) return null

  try {
    const base64 = parts[1].replace(/-/g, '+').replace(/_/g, '/')
    const padded = base64.padEnd(Math.ceil(base64.length / 4) * 4, '=')
    const payload: unknown = JSON.parse(atob(padded))
    const parsed = jwtClaimsSchema.safeParse(payload)
    return parsed.success ? parsed.data : null
  } catch {
    return null
  }
}

/** Id numérico del usuario (`sub` del JWT), tal como lo usa el backend. */
export function subjectOf(claims: JwtClaims | null): number | null {
  if (!claims?.sub) return null
  const subject = Number(claims.sub)
  return Number.isFinite(subject) ? subject : null
}
