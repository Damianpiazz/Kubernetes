export const API_BASE = process.env.NEXT_PUBLIC_API_URL ?? 'http://localhost:8080'

const TOKEN_STORAGE_KEY = 'latiendita-token'

export function getToken(): string | null {
  if (typeof window === 'undefined') return null
  return window.localStorage.getItem(TOKEN_STORAGE_KEY)
}

export async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const token = getToken()
  const headers = new Headers(init?.headers)
  headers.set('Accept', 'application/json')
  if (token && !headers.has('Authorization')) {
    headers.set('Authorization', `Bearer ${token}`)
  }

  const response = await fetch(`${API_BASE}${path}`, { ...init, headers })
  if (!response.ok) {
    throw new Error(`API ${response.status}: ${path}`)
  }
  return (await response.json()) as T
}

export type Page<T> = {
  content: T[]
}

export type ProductDto = {
  productId: number
  title: string
  imageUrl: string | null
  sku: string
  price: number
  quantity: number
  categoryId: number | null
}

export type CategoryDto = {
  categoryId: number
  name: string
  description: string | null
}
