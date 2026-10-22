'use client'

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react'
import type { Product } from '@/features/product/schemas/product.schema'
import {
  readCart,
  toCartLine,
  writeCart,
  type CartLine,
} from '@/features/order/utils/cart'

type CartContextValue = {
  lines: CartLine[]
  /** Cantidad total de unidades (lo que muestra el botón del header). */
  count: number
  /** Subtotal a precio de catálogo. */
  total: number
  add: (product: Product, image: string | null) => void
  increment: (productId: number) => void
  decrement: (productId: number) => void
  remove: (productId: number) => void
  clear: () => void
}

const CartContext = createContext<CartContextValue | null>(null)

export function CartProvider({ children }: { children: ReactNode }) {
  // Arranca vacío: el carrito demo con productos precargados confundía al
  // probar el flujo real de compra.
  const [lines, setLines] = useState<CartLine[]>([])

  useEffect(() => {
    setLines(readCart())
  }, [])

  const persist = useCallback((next: CartLine[]) => {
    setLines(next)
    writeCart(next)
  }, [])

  const add = useCallback(
    (product: Product, image: string | null) => {
      const existing = lines.find((line) => line.productId === product.productId)
      if (existing) {
        persist(
          lines.map((line) =>
            line.productId === product.productId
              ? { ...line, quantity: line.quantity + 1 }
              : line,
          ),
        )
        return
      }
      persist([...lines, toCartLine(product, image)])
    },
    [lines, persist],
  )

  const increment = useCallback(
    (productId: number) => {
      persist(
        lines.map((line) =>
          line.productId === productId ? { ...line, quantity: line.quantity + 1 } : line,
        ),
      )
    },
    [lines, persist],
  )

  const decrement = useCallback(
    (productId: number) => {
      persist(
        lines.flatMap((line) => {
          if (line.productId !== productId) return [line]
          if (line.quantity <= 1) return []
          return [{ ...line, quantity: line.quantity - 1 }]
        }),
      )
    },
    [lines, persist],
  )

  const remove = useCallback(
    (productId: number) => {
      persist(lines.filter((line) => line.productId !== productId))
    },
    [lines, persist],
  )

  const clear = useCallback(() => persist([]), [persist])

  const value = useMemo<CartContextValue>(
    () => ({
      lines,
      count: lines.reduce((sum, line) => sum + line.quantity, 0),
      total: lines.reduce((sum, line) => sum + line.price * line.quantity, 0),
      add,
      increment,
      decrement,
      remove,
      clear,
    }),
    [lines, add, increment, decrement, remove, clear],
  )

  return <CartContext.Provider value={value}>{children}</CartContext.Provider>
}

export function useCart(): CartContextValue {
  const context = useContext(CartContext)
  if (!context) throw new Error('useCart debe usarse dentro de <CartProvider>')
  return context
}
