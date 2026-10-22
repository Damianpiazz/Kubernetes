import { createRepository } from '@/lib/data/create-repository'
import type { Page } from '@/lib/schemas/page.schema'
import { productApiImpl } from '@/features/product/services/product.api'
import { productMockImpl } from '@/features/product/services/product.mock'
import type {
  CatalogQuery,
  Category,
  Product,
} from '@/features/product/schemas/product.schema'

/**
 * Contrato del catálogo.
 *
 * `resolveImage` vive en la interfaz (y no en un helper suelto) porque cambia
 * según el modo: en mock son las fotos de ejemplo; en api es el proxy del
 * backend `/api/products/{id}/image`, que sirve el blob de Azure Storage sin
 * exponer el storage al navegador.
 */
export interface ProductApi {
  getProducts(query: CatalogQuery): Promise<Page<Product>>
  getCategories(): Promise<Category[]>
  resolveImage(product: Product): string | null
}

export const productApi: ProductApi = createRepository({
  api: productApiImpl,
  mock: productMockImpl,
})
