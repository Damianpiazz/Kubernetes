import type { Product } from '@/features/product/schemas/product.schema'

/**
 * Línea del carrito.
 *
 * Guarda un snapshot del producto (título, precio, foto) en el momento de
 * agregarlo, en vez de sólo el id: con catálogo paginado, un producto agregado
 * en la página 3 no está en el cache de la página 1, y el drawer tendría que
 * hacer N requests para mostrarlo. El backend recalcula los precios al crear el
 * pedido, así que el snapshot sólo afecta lo que se muestra.
 */
export type CartLine = {
  productId: number
  quantity: number
  title: string
  price: number
  image: string | null
  sku: string
}

export const CART_STORAGE_KEY = 'latiendita-cart'

export function toCartLine(product: Product, image: string | null): CartLine {
  return {
    productId: product.productId,
    quantity: 1,
    title: product.title?.trim() || product.sku,
    price: product.price ?? 0,
    image,
    sku: product.sku,
  }
}

export function readCart(): CartLine[] {
  if (typeof window === 'undefined') return []

  const raw = window.localStorage.getItem(CART_STORAGE_KEY)
  if (!raw) return []

  try {
    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    return parsed.filter(isCartLine)
  } catch {
    return []
  }
}

export function writeCart(lines: CartLine[]): void {
  if (typeof window === 'undefined') return
  window.localStorage.setItem(CART_STORAGE_KEY, JSON.stringify(lines))
}

function isCartLine(value: unknown): value is CartLine {
  if (typeof value !== 'object' || value === null) return false
  const line = value as CartLine
  return (
    typeof line.productId === 'number' &&
    typeof line.quantity === 'number' &&
    typeof line.title === 'string' &&
    typeof line.price === 'number'
  )
}
