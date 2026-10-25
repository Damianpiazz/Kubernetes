import { apiClient } from '@/lib/http/client'
import { buildQuery, decode } from '@/lib/http/decode'
import { env } from '@/lib/http/env'
import type { Page } from '@/lib/schemas/page.schema'
import {
  categoryPageSchema,
  productPageSchema,
  type CatalogQuery,
  type Category,
  type Product,
} from '@/features/product/schemas/product.schema'
import type { ProductApi } from '@/features/product/services/product.repo'

/** Tamaño con el que se piden las categorías: hay poquitas y se listan todas. */
const CATEGORIES_PAGE_SIZE = 100

export const productApiImpl: ProductApi = {
  async getProducts(query: CatalogQuery): Promise<Page<Product>> {
    const response = await apiClient.get<unknown>(
      `/api/products${buildQuery({
        page: query.page,
        size: query.size,
        sort: query.sort,
        q: query.q,
        categoryId: query.categoryId,
      })}`,
    )
    return decode('/api/products', response.data, productPageSchema)
  },

  async getCategories(): Promise<Category[]> {
    const response = await apiClient.get<unknown>(
      `/api/categories${buildQuery({ size: CATEGORIES_PAGE_SIZE })}`,
    )
    return decode('/api/categories', response.data, categoryPageSchema).content
  },

  resolveImage(product: Product): string | null {
    if (product.imageUrl?.startsWith('http')) return product.imageUrl
    if (product.productId == null) return null
    return `${env.NEXT_PUBLIC_API_URL}/api/products/${product.productId}/image`
  },
}
