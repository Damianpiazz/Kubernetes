import { z } from 'zod'

/**
 * Página de Spring Data (`PagedModel`/`Page`).
 *
 * Spring serializa la página con todos estos campos; el frontend sólo consumía
 * `content`, pero `totalElements` y `totalPages` son necesarios para una
 * paginación real.
 */
export function pageSchema<T extends z.ZodType>(item: T) {
  return z.object({
    content: z.array(item),
    totalElements: z.number().int(),
    totalPages: z.number().int(),
    number: z.number().int(),
    size: z.number().int(),
    numberOfElements: z.number().int(),
    first: z.boolean(),
    last: z.boolean(),
    empty: z.boolean(),
  })
}

export type Page<T> = {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
  size: number
  numberOfElements: number
  first: boolean
  last: boolean
  empty: boolean
}

/** Crea una página vacía, útil para los estados iniciales de TanStack Query. */
export function emptyPage<T>(size: number): Page<T> {
  return {
    content: [],
    totalElements: 0,
    totalPages: 0,
    number: 0,
    size,
    numberOfElements: 0,
    first: true,
    last: true,
    empty: true,
  }
}
