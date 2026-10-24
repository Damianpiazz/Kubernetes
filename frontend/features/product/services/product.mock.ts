import { delay } from '@/lib/mock/delay'
import { db, type MockProduct } from '@/lib/mock/db'
import type { Page } from '@/lib/schemas/page.schema'
import type {
  CatalogQuery,
  Category,
  Product,
  ProductSort,
} from '@/features/product/schemas/product.schema'
import type { ProductApi } from '@/features/product/services/product.repo'

/** Implementación en memoria del catálogo: filtra, ordena y pagina igual que el backend. */
export const productMockImpl: ProductApi = {
  async getProducts(query: CatalogQuery): Promise<Page<Product>> {
    await delay()

    let items: MockProduct[] = [...db.products]

    if (query.categoryId != null) {
      items = items.filter((product) => product.categoryId === query.categoryId)
    }

    if (query.q) {
      const needle = query.q.toLocaleLowerCase('es')
      items = items.filter((product) =>
        [product.title, product.sku].some((field) =>
          field?.toLocaleLowerCase('es').includes(needle),
        ),
      )
    }

    const sorted = applySort(items, query.sort)
    const totalElements = sorted.length
    const totalPages = Math.ceil(totalElements / query.size)
    const start = query.page * query.size
    const content = sorted.slice(start, start + query.size)

    return {
      content,
      totalElements,
      totalPages,
      number: query.page,
      size: query.size,
      numberOfElements: content.length,
      first: query.page === 0,
      last: query.page >= totalPages - 1,
      empty: content.length === 0,
    }
  },

  async getCategories(): Promise<Category[]> {
    await delay()
    return [...db.categories]
  },

  resolveImage(product: Product): string | null {
    return product.imageUrl
  },
}

/**
 * Reproduce el `sort` de Spring Data: `propiedad` (ascendente por default) o
 * `propiedad,asc|desc`. El prefijo `-` no existe en el formato del backend.
 */
function applySort(items: MockProduct[], sort: ProductSort): MockProduct[] {
  const [property, direction] = sort.split(',')
  const descending = (direction ?? 'asc').toLowerCase() === 'desc'
  const key = property as keyof MockProduct

  const sorted = [...items].sort((left, right) => {
    const a = left[key]
    const b = right[key]
    if (typeof a === 'number' && typeof b === 'number') return a - b
    return String(a ?? '').localeCompare(String(b ?? ''), 'es')
  })

  return descending ? sorted.reverse() : sorted
}
