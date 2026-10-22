import { clearSession } from '@/lib/http/token-storage'

/**
 * Puente entre el cliente HTTP (lib) y la feature de auth.
 *
 * El cliente no puede importar `features/auth` porque ese servicio usa el
 * cliente: la feature registra acá lo que necesita, y el cliente lo invoca.
 * Así se evita el ciclo de dependencias lib ⇄ features.
 */

export type RefreshHandler = () => Promise<boolean>

type Listener = () => void

let refreshHandler: RefreshHandler | null = null
const expiredListeners = new Set<Listener>()
let inflightRefresh: Promise<boolean> | null = null

export function registerRefreshHandler(handler: RefreshHandler): void {
  refreshHandler = handler
}

/** Se suscribe a la expiración definitiva de la sesión (refresh fallido). */
export function subscribeSessionExpired(listener: Listener): () => void {
  expiredListeners.add(listener)
  return () => {
    expiredListeners.delete(listener)
  }
}

/**
 * Ejecuta el refresh una sola vez aunque lleguen N peticiones en 401 juntas.
 * Si dos requests se disparan con el token vencido, sólo una refresca; la otra
 * espera el mismo resultado. Sin esto, la rotación de refresh tokens haría que
 * la segunda petición revocara la familia entera.
 */
export async function tryRefreshSession(): Promise<boolean> {
  if (!refreshHandler) return false
  if (inflightRefresh) return inflightRefresh

  inflightRefresh = refreshHandler()
    .catch(() => false)
    .finally(() => {
      inflightRefresh = null
    })

  return inflightRefresh
}

/** Limpia la sesión y avisa a la UI que hay que volver a loguearse. */
export function notifySessionExpired(): void {
  clearSession()
  expiredListeners.forEach((listener) => listener())
}
