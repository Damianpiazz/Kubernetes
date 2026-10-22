'use client'

import { Loader2 } from 'lucide-react'
import { useCategories } from '@/features/product/hooks/use-catalog'

const CATEGORY_EMOJI: Record<string, string> = {
  Todo: '✦',
  Indumentaria: '◌',
  Calzado: '◒',
  Hogar: '⌂',
  Accesorios: '◈',
}

type CategoryStripProps = {
  /** null = "Todo". */
  activeCategoryId: number | null
  onSelect: (categoryId: number | null) => void
}

export function CategoryStrip({ activeCategoryId, onSelect }: CategoryStripProps) {
  const categories = useCategories()

  return (
    <section id="categorias" className="category-strip">
      <div className="category-heading">
        <p>Explorá por categoría</p>
        <span>Encontrá lo que buscás</span>
      </div>

      <nav className="category-nav" aria-label="Categorías de productos">
        <button
          className={activeCategoryId === null ? 'category-pill active' : 'category-pill'}
          aria-pressed={activeCategoryId === null}
          onClick={() => onSelect(null)}
        >
          <span aria-hidden="true">{CATEGORY_EMOJI.Todo}</span>
          Todo
        </button>

        {categories.data?.map((category) => (
          <button
            key={category.categoryId}
            className={
              activeCategoryId === category.categoryId ? 'category-pill active' : 'category-pill'
            }
            aria-pressed={activeCategoryId === category.categoryId}
            onClick={() => onSelect(category.categoryId)}
          >
            <span aria-hidden="true">{CATEGORY_EMOJI[category.name] ?? '◈'}</span>
            {category.name}
          </button>
        ))}

        {categories.isPending && (
          <span className="category-loading">
            <Loader2 className="spin" data-icon="inline-start" /> Cargando categorías…
          </span>
        )}
      </nav>
    </section>
  )
}
