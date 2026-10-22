'use client'

import { useState } from 'react'
import { Heart, ImageOff, Plus } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { money } from '@/lib/format'
import { productApi } from '@/features/product/services/product.repo'
import type { Product } from '@/features/product/schemas/product.schema'

type ProductCardProps = {
  product: Product
  categoryName: string
  liked: boolean
  onToggleLike: (productId: number) => void
  onAdd: (product: Product, image: string | null) => void
}

export function ProductCard({
  product,
  categoryName,
  liked,
  onToggleLike,
  onAdd,
}: ProductCardProps) {
  const [imageFailed, setImageFailed] = useState(false)

  const title = product.title?.trim() || 'Sin título'
  const image = imageFailed ? null : productApi.resolveImage(product)
  const price = product.price ?? 0
  const soldOut = product.quantity === 0

  return (
    <article className="product-card">
      <div className="product-image">
        {image ? (
          <img
            src={image}
            alt={title}
            loading="lazy"
            onError={() => setImageFailed(true)}
          />
        ) : (
          // Producto sin foto, o la imagen no cargó: se muestra un placeholder
          // en vez de un ícono de imagen rota del navegador.
          <div className="product-image-empty">
            <ImageOff aria-hidden="true" />
            <span>Sin imagen</span>
          </div>
        )}

        {product.tag && (
          <Badge className="product-tag" variant={product.tag === 'Oferta' ? 'destructive' : 'secondary'}>
            {product.tag}
          </Badge>
        )}
        {soldOut && (
          <Badge className="product-tag" variant="secondary">
            Sin stock
          </Badge>
        )}

        <button
          className="like-button"
          aria-label={`Agregar ${title} a favoritos`}
          aria-pressed={liked}
          onClick={() => onToggleLike(product.productId)}
        >
          <Heart fill={liked ? 'currentColor' : 'none'} />
        </button>

        <button
          className="quick-add"
          disabled={soldOut}
          onClick={() => onAdd(product, image)}
        >
          <Plus data-icon="inline-start" /> {soldOut ? 'Sin stock' : 'Agregar'}
        </button>
      </div>

      <div className="product-info">
        <div>
          <p className="product-category">{categoryName}</p>
          <h3>{title}</h3>
          {product.color && <p className="product-color">{product.color}</p>}
        </div>
        <div className="price-row">
          <strong>{money(price)}</strong>
          {product.oldPrice && <del>{money(product.oldPrice)}</del>}
        </div>
      </div>
    </article>
  )
}
