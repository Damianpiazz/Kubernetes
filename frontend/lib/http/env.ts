import { z } from 'zod'

/**
 * Variables de entorno del frontend.
 *
 * Next.js sólo inyecta en el bundle las `NEXT_PUBLIC_*` que existen en tiempo
 * de BUILD, no de runtime. Se validan acá con Zod para que un valor mal escrito
 * truene temprano y con un mensaje claro, en vez de fallar una llamada en
 * runtime con un error críptico.
 */

const envSchema = z.object({
  /** URL base del gateway: es el único punto de entrada del backend. */
  NEXT_PUBLIC_API_URL: z.url(),
  /** 'mock' = datos en memoria sin backend; 'api' = backend real vía axios. */
  NEXT_PUBLIC_DATA_MODE: z.enum(['mock', 'api']),
})

const parsed = envSchema.safeParse({
  NEXT_PUBLIC_API_URL: process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8080',
  NEXT_PUBLIC_DATA_MODE: process.env.NEXT_PUBLIC_DATA_MODE || 'mock',
})

if (!parsed.success) {
  const detail = parsed.error.issues
    .map((issue) => `${issue.path.join('.')}: ${issue.message}`)
    .join(' | ')
  throw new Error(`Variables de entorno inválidas en el frontend → ${detail}`)
}

export const env = parsed.data

export type DataMode = (typeof env)['NEXT_PUBLIC_DATA_MODE']

/** ¿Estamos sirviendo datos de ejemplo (sin backend)? */
export const isMockMode: boolean = env.NEXT_PUBLIC_DATA_MODE === 'mock'
