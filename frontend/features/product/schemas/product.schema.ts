import { z } from 'zod'
import { pageSchema } from '@/lib/schemas/page.schema'

/**
 * Fecha/hora tal como las serializa el backend:
 * `LocalDateTime` sin zona ("2026-01-01T12:00:00") o `Instant` con Z
 * ("2026-01-01T15:00:00Z"). Se aceptan las dos formas.
 */
export const dateTimeSchema = z
  .string()
  .regex(
    /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:?\d{2})?$/,
    'fecha inválida',
  )

/** Moneda: el backend manda BigDecimal como número. */
export const moneySchema = z.number().min(0)

/**
 * Contrato de `ProductDto` del product-service.
 *
 * Ojo con los nullables reales del backend: `title`, `imageUrl`, `quantity` y
 * `categoryId` pueden venir en null. El frontend anterior los tipaba como
 * requeridos y los pisaba con `?? 'Sin título'`, lo que tapaba datos faltantes.
 *
 * `tag`, `color` y `oldPrice` NO vienen del backend: son enriquecimientos
 * exclusivos del modo mock, opcionales para que el modo api simplemente no los
 * pinte.
 */
export const productSchema = z.object({
  productId: z.number().int(),
  sku: z.string(),
  title: z.string().nullable(),
  imageUrl: z.string().nullable(),
  price: moneySchema.nullable(),
  quantity: z.number().int().nullable(),
  categoryId: z.number().int().nullable(),
  /** Sólo modo mock. */
  tag: z.string().optional(),
  /** Sólo modo mock. */
  color: z.string().optional(),
  /** Sólo modo mock. */
  oldPrice: moneySchema.optional(),
})

export type Product = z.infer<typeof productSchema>

export const categorySchema = z.object({
  categoryId: z.number().int(),
  name: z.string(),
  description: z.string().nullable(),
})

export type Category = z.infer<typeof categorySchema>

export const productPageSchema = pageSchema(productSchema)
export const categoryPageSchema = pageSchema(categorySchema)

/**
 * Ordenamientos que el backend acepta en `sort`.
 *
 * Formato Spring Data: `propiedad` (ascendente) o `propiedad,asc|desc`. No se
 * usa el prefijo `-` de descendente porque Spring Data lo trata como parte del
 * nombre de la propiedad y responde 500 (`Sort expression '-id: ASC' must only
 * contain property references…`).
 *
 * Spring Data resuelve el sort por nombre de propiedad de la entidad: una
 * propiedad inválida responde 500, no 400. Por eso la lista está acotada acá y
 * no se expone libre al usuario.
 *
 * Ojo con la coma: `URLSearchParams` la encodea como `%2C` y el servlet la
 * decodifica antes de repartir el parámetro, así que llega igual a Spring.
 */
export const PRODUCT_SORTS = [
  { value: 'id,desc', label: 'Más recientes' },
  { value: 'sku', label: 'SKU (A–Z)' },
  { value: 'title', label: 'Nombre (A–Z)' },
  { value: 'price', label: 'Precio (menor a mayor)' },
  { value: 'price,desc', label: 'Precio (mayor a menor)' },
] as const

export const productSortSchema = z.enum([
  'id,desc',
  'sku',
  'sku,desc',
  'title',
  'title,desc',
  'price',
  'price,desc',
  'quantity',
  'quantity,desc',
])

export type ProductSort = z.infer<typeof productSortSchema>

/** Parámetros de búsqueda del catálogo (`GET /api/products`). */
export const catalogQuerySchema = z.object({
  page: z.number().int().min(0).default(0),
  size: z.number().int().min(1).max(100).default(8),
  sort: productSortSchema.default('id,desc'),
  /** Búsqueda por texto en el título (implementada en el backend). */
  q: z.string().trim().min(1).optional(),
  categoryId: z.number().int().optional(),
})

export type CatalogQuery = z.infer<typeof catalogQuerySchema>

export const DEFAULT_CATALOG_QUERY: CatalogQuery = catalogQuerySchema.parse({})
