import { z } from 'zod'

/**
 * Contratos del auth-service.
 *
 * Los límites (email ≤ 150, username 3–30, password 8–100) replican las
 * anotaciones `@Size` del backend, así el formulario avisa antes de mandar y
 * el error del servidor queda sólo para los casos de negocio (email duplicado).
 */

export const emailSchema = z.email('Ingresá un correo válido').max(150, 'Máximo 150 caracteres')
export const passwordSchema = z
  .string('Ingresá la contraseña')
  .min(8, 'La contraseña debe tener al menos 8 caracteres')
  .max(100, 'Máximo 100 caracteres')
export const usernameSchema = z
  .string('Ingresá el usuario')
  .min(3, 'El usuario debe tener al menos 3 caracteres')
  .max(30, 'Máximo 30 caracteres')

export const loginRequestSchema = z.object({
  email: emailSchema,
  password: z.string().min(1, 'Ingresá la contraseña').max(100),
})

export type LoginRequest = z.infer<typeof loginRequestSchema>

export const registerRequestSchema = z.object({
  email: emailSchema,
  username: usernameSchema,
  password: passwordSchema,
})

export type RegisterRequest = z.infer<typeof registerRequestSchema>

/** `POST /api/auth/login` y `POST /api/auth/refresh`. */
export const tokenResponseSchema = z.object({
  accessToken: z.string().min(1),
  refreshToken: z.string().min(1),
  tokenType: z.string(),
  /** Segundos hasta que venza el access token (15 min). */
  expiresIn: z.number().int().positive(),
})

export type TokenResponse = z.infer<typeof tokenResponseSchema>

export const accountResponseSchema = z.object({
  id: z.number().int(),
  email: z.string(),
  username: z.string(),
})

export type Account = z.infer<typeof accountResponseSchema>

/** Claims que el auth-service pone en el JWT (`sub`, `email`, `username`). */
export const jwtClaimsSchema = z.object({
  sub: z.string(),
  email: z.string().optional(),
  username: z.string().optional(),
  iat: z.number().optional(),
  exp: z.number().optional(),
})

export type JwtClaims = z.infer<typeof jwtClaimsSchema>

/** Perfil del usuario en user-service (`GET /api/users/me`). */
export const userSchema = z.object({
  id: z.number().int(),
  email: z.string(),
  username: z.string(),
})

export type UserProfile = z.infer<typeof userSchema>
