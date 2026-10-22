'use client'

import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { productApi } from '@/features/product/services/product.repo'
import type { CatalogQuery, Category, Product } from '@/features/product/schemas/product.schema'
import type { Page } from '@/lib/schemas/page.schema'

/**
 * Catálogo paginado.
 *
 * `keepPreviousData` evita que la grilla salte a vacío mientras se carga la
 * página siguiente: se muestra la anterior hasta que la nueva llegue.
 */
export function useCatalog(query: CatalogQuery): ReturnType<typeof useQuery<Page<Product>>> {
  return useQuery({
    queryKey: ['catalog', query],
    queryFn: () => productApi.getProducts(query),
    placeholderData: keepPreviousData,
  })
}

export function useCategories(): ReturnType<typeof useQuery<Category[]>> {
  return useQuery({
    queryKey: ['categories'],
    queryFn: () => productApi.getCategories(),
    staleTime: 5 * 60_000,
  })
}
