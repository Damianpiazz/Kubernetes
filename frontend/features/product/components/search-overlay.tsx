'use client'

import { useEffect, useState, type FormEvent } from 'react'
import { Search, X } from 'lucide-react'
import { Button } from '@/components/ui/button'

type SearchOverlayProps = {
  open: boolean
  onClose: () => void
  /** Búsqueda vigente, para abrir el campo con el valor actual. */
  value: string
  /** Aplica la búsqueda y cierra. */
  onSearch: (term: string) => void
}

const SUGGESTIONS = ['remeras', 'zapatillas', 'accesorios', 'buzos']

/**
 * Búsqueda del catálogo.
 *
 * A diferencia de la versión anterior (cuyo input no hacía nada), acá el término
 * se aplica al query del catálogo: se pagina de nuevo y el backend filtra por
 * título (`q`).
 */
export function SearchOverlay({ open, onClose, value, onSearch }: SearchOverlayProps) {
  const [term, setTerm] = useState(value)

  useEffect(() => {
    if (open) setTerm(value)
  }, [open, value])

  useEffect(() => {
    if (!open) return
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [open, onClose])

  if (!open) return null

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    onSearch(term.trim())
  }

  return (
    <div className="utility-overlay" onClick={onClose}>
      <section
        className="utility-panel search-panel"
        onClick={(event) => event.stopPropagation()}
      >
        <div className="utility-header">
          <div>
            <p className="eyebrow">encontrá tu próximo favorito</p>
            <h2>¿Qué estás buscando?</h2>
          </div>
          <Button variant="ghost" size="icon" onClick={onClose} aria-label="Cerrar búsqueda">
            <X />
          </Button>
        </div>

        <form onSubmit={submit}>
          <label className="search-field">
            <Search />
            <input
              autoFocus
              value={term}
              onChange={(event) => setTerm(event.target.value)}
              placeholder="Buscar productos por nombre o SKU…"
              aria-label="Buscar productos"
            />
            <span>Enter</span>
          </label>
        </form>

        <div className="search-suggestions">
          <p>Podés probar con</p>
          {SUGGESTIONS.map((suggestion) => (
            <button key={suggestion} type="button" onClick={() => onSearch(suggestion)}>
              {suggestion}
            </button>
          ))}
        </div>
      </section>
    </div>
  )
}
