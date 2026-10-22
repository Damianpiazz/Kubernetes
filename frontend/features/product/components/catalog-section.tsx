'use client'

import { AlertTriangle, ChevronLeft, ChevronRight, Loader2, PackageOpen } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { useCatalog, useCategories } from '@/features/product/hooks/use-catalog'
import { ProductCard } from '@/features/product/components/product-card'
import { ApiError } from '@/lib/http/errors'
import type { CatalogQuery, Product } from '@/features/product/schemas/product.schema'

type CatalogSectionProps = {
  query: CatalogQuery
  onQueryChange: (query: CatalogQuery) => void
  likedIds: number[]
  onToggleLike: (productId: number) => void
  onAdd: (product: Product, image: string | null) => void
}

export function CatalogSection({
  query,
  onQueryChange,
  likedIds,
  onToggleLike,
  onAdd,
}: CatalogSectionProps) {
  const catalog = useCatalog(query)
  const categories = useCategories()

  const categoryName = (categoryId: number | null): string => {
    if (categoryId == null) return 'Sin categoría'
    return categories.data?.find((category) => category.categoryId === categoryId)?.name ?? 'Sin categoría'
  }

  const page = catalog.data
  const totalPages = Math.max(page?.totalPages ?? 0, 1)

  return (
    <section id="catalogo" className="catalog-section">
      <div className="section-heading">
        <div>
          <p className="eyebrow">selección de la semana</p>
          <h2>Lo más elegido</h2>
        </div>
        {catalog.isFetching && !catalog.isPending && (
          <span className="catalog-refreshing">
            <Loader2 className="spin" data-icon="inline-start" /> Actualizando…
          </span>
        )}
      </div>

      {catalog.isPending && (
        <div className="product-grid" aria-busy="true">
          {Array.from({ length: query.size }).map((_, index) => (
            <div className="product-card" key={index}>
              <div className="product-image skeleton" />
              <div className="product-info">
                <div>
                  <p className="product-category">—</p>
                  <h3>Cargando…</h3>
                </div>
              </div>
            </div>
          ))}
        </div>
      )}

      {catalog.isError && (
        <div className="catalog-error" role="alert">
          <AlertTriangle />
          <h3>No pudimos cargar el catálogo</h3>
          <p>
            {catalog.error instanceof ApiError
              ? catalog.error.message
              : 'Revisá que el backend esté levantado e intentá de nuevo.'}
          </p>
          <Button onClick={() => void catalog.refetch()}>Reintentar</Button>
        </div>
      )}

      {catalog.isSuccess && page && page.content.length === 0 && (
        <div className="catalog-error">
          <PackageOpen />
          <h3>No encontramos productos</h3>
          <p>Probá con otra categoría u otra búsqueda.</p>
        </div>
      )}

      {catalog.isSuccess && page && page.content.length > 0 && (
        <>
          <div className="product-grid">
            {page.content.map((product) => (
              <ProductCard
                key={product.productId}
                product={product}
                categoryName={categoryName(product.categoryId)}
                liked={likedIds.includes(product.productId)}
                onToggleLike={onToggleLike}
                onAdd={onAdd}
              />
            ))}
          </div>

          <div className="pagination">
            <span>
              Página {page.number + 1} de {totalPages} · {page.totalElements} productos
            </span>
            <div>
              <Button
                variant="outline"
                size="icon"
                aria-label="Página anterior"
                disabled={page.first || catalog.isFetching}
                onClick={() => onQueryChange({ ...query, page: page.number - 1 })}
              >
                <ChevronLeft />
              </Button>
              <Button
                variant="outline"
                size="icon"
                aria-label="Página siguiente"
                disabled={page.last || catalog.isFetching}
                onClick={() => onQueryChange({ ...query, page: page.number + 1 })}
              >
                <ChevronRight />
              </Button>
            </div>
          </div>
        </>
      )}
    </section>
  )
}
