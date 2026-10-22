'use client'

import { useState, type ReactNode } from 'react'
import { QueryClientProvider } from '@tanstack/react-query'
import { createQueryClient } from '@/lib/query/query-client'
import { isMockMode } from '@/lib/http/env'
import { AuthProvider } from '@/features/auth/hooks/use-auth'
import { CartProvider } from '@/features/order/hooks/use-cart'

/**
 * Providers de la aplicación.
 *
 * El QueryClient se crea una sola vez por montaje (con `useState` y no en el
 * cuerpo del componente, que crearía uno nuevo en cada render y perdería el
 * cache).
 *
 * La insignia de modo mock es deliberada: sirve para no confundir datos de
 * ejemplo con datos reales cuando se cambia entre un entorno y otro.
 */
export function Providers({ children }: { children: ReactNode }) {
  const [queryClient] = useState(createQueryClient)

  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <CartProvider>
          {children}
          {isMockMode && (
            <span className="data-mode-badge" title="Datos de ejemplo, sin backend">
              modo mock
            </span>
          )}
        </CartProvider>
      </AuthProvider>
    </QueryClientProvider>
  )
}
