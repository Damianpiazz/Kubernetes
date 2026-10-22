/** TTL del access token simulado, igual al del backend (15 minutos). */
export const MOCK_TOKEN_TTL_SECONDS = 15 * 60

function toBase64Url(text: string): string {
  const bytes = new TextEncoder().encode(text)
  let binary = ''
  bytes.forEach((byte) => {
    binary += String.fromCharCode(byte)
  })
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

/**
 * JWT de mentira pero decodificable.
 *
 * El modo mock debe poder recorrer el flujo completo de sesión (claims,
 * vencimiento, refresh), así que el token se construye con el mismo shape que
 * el del backend: `sub`, `email`, `username`, `iat` y `exp`. La firma no
 * verifica nada, sólo existe para que el token tenga tres partes.
 */
export function createFakeJwt(account: {
  id: number
  email: string
  username: string
}): string {
  const now = Math.floor(Date.now() / 1000)

  const header = toBase64Url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }))
  const payload = toBase64Url(
    JSON.stringify({
      sub: String(account.id),
      email: account.email,
      username: account.username,
      iat: now,
      exp: now + MOCK_TOKEN_TTL_SECONDS,
    }),
  )

  return `${header}.${payload}.mock-signature`
}
