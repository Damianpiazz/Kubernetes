'use client'

import { useCallback, useEffect, useState } from 'react'
import { ArrowRight, Menu, Search, ShoppingBag, UserRound } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { AccountPanel } from '@/features/auth/components/account-panel'
import { useAuth } from '@/features/auth/hooks/use-auth'
import { useCart } from '@/features/order/hooks/use-cart'
import { CartDrawer } from '@/features/order/components/cart-drawer'
import { useStartCheckout } from '@/features/payment/hooks/use-checkout'
import { CatalogSection } from '@/features/product/components/catalog-section'
import { CategoryStrip } from '@/features/product/components/category-strip'
import { SearchOverlay } from '@/features/product/components/search-overlay'
import {
  DEFAULT_CATALOG_QUERY,
  type CatalogQuery,
  type Product,
} from '@/features/product/schemas/product.schema'

const LIKED_STORAGE_KEY = 'latiendita-liked'

/**
 * Home de la tienda.
 *
 * Esta página sólo compone: el catálogo, la búsqueda, la sesión y el carrito
 * viven en sus respectivas features. Los datos llegan por TanStack Query, así
 * que no hay `useEffect` de carga ni estados duplicados.
 */
export default function Page() {
  const cart = useCart()
  const { isAuthenticated, email } = useAuth()
  const checkout = useStartCheckout()

  const [query, setQuery] = useState<CatalogQuery>(DEFAULT_CATALOG_QUERY)
  const [liked, setLiked] = useState<number[]>([])
  const [cartOpen, setCartOpen] = useState(false)
  const [searchOpen, setSearchOpen] = useState(false)
  const [accountOpen, setAccountOpen] = useState(false)
  const [accountView, setAccountView] = useState<'welcome' | 'login' | 'signup'>('welcome')
  const [menuOpen, setMenuOpen] = useState(false)
  // Si el usuario intentó comprar sin sesión, al loguearse se retoma el pago.
  const [resumeCheckout, setResumeCheckout] = useState(false)

  useEffect(() => {
    try {
      const raw = window.localStorage.getItem(LIKED_STORAGE_KEY)
      const parsed: unknown = raw ? JSON.parse(raw) : []
      if (Array.isArray(parsed)) {
        setLiked(parsed.filter((id): id is number => typeof id === 'number'))
      }
    } catch {
      // Favoritos corruptos: se empieza de cero, no rompe la página.
    }
  }, [])

  const toggleLike = useCallback((productId: number) => {
    setLiked((current) => {
      const next = current.includes(productId)
        ? current.filter((id) => id !== productId)
        : [...current, productId]
      window.localStorage.setItem(LIKED_STORAGE_KEY, JSON.stringify(next))
      return next
    })
  }, [])

  const selectCategory = useCallback((categoryId: number | null) => {
    setQuery((current) => ({ ...current, categoryId: categoryId ?? undefined, page: 0 }))
  }, [])

  const search = useCallback((term: string) => {
    setQuery((current) => ({
      ...current,
      q: term ? term : undefined,
      page: 0,
    }))
    setSearchOpen(false)
  }, [])

  const handleCheckout = useCallback(async () => {
    if (!isAuthenticated || !email) {
      setResumeCheckout(true)
      setAccountView('login')
      setAccountOpen(true)
      return
    }

    const result = await checkout.startCheckout(cart.lines, email)
    if (result.ok) setCartOpen(false)
  }, [isAuthenticated, email, checkout, cart.lines])

  useEffect(() => {
    if (!resumeCheckout || !isAuthenticated || !email) return
    setResumeCheckout(false)
    void checkout.startCheckout(cart.lines, email)
    // El efecto sólo debe dispararse una vez: al cambiar `resumeCheckout` a
    // false se desengancha, aunque cambien el carrito o el correo.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [resumeCheckout, isAuthenticated, email])

  const addToCart = useCallback(
    (product: Product, image: string | null) => {
      cart.add(product, image)
      setCartOpen(true)
    },
    [cart],
  )

  return (
    <main className="store-shell">
      <header className="site-header">
        <Button
          variant="ghost"
          size="icon"
          className="mobile-menu"
          onClick={() => setMenuOpen(!menuOpen)}
          aria-label="Abrir menú"
        >
          <Menu />
        </Button>
        <a className="brand" href="#top">
          latiendita<span>.</span>
        </a>
        <nav className={menuOpen ? 'main-nav open' : 'main-nav'}>
          <a href="#catalogo">Comprar</a>
          <a href="#categorias">Categorías</a>
          <a href="#nosotros">Nosotros</a>
        </nav>
        <div className="header-actions">
          <Button
            variant="ghost"
            size="icon"
            aria-label="Buscar"
            onClick={() => setSearchOpen(true)}
          >
            <Search />
          </Button>
          <Button
            variant="ghost"
            size="icon"
            aria-label={isAuthenticated ? 'Mi cuenta (conectado)' : 'Mi cuenta'}
            onClick={() => {
              setAccountView('welcome')
              setAccountOpen(true)
            }}
          >
            <UserRound />
          </Button>
          <Button variant="outline" className="cart-button" onClick={() => setCartOpen(true)}>
            <ShoppingBag data-icon="inline-start" /> Carrito <span>{cart.count}</span>
          </Button>
        </div>
      </header>

      <section id="top" className="hero">
        <div className="hero-copy">
          <h1>
            Elegí lo que
            <br />
            <em>te hace bien.</em>
          </h1>
          <p>Objetos simples, lindos y funcionales para acompañar tus días.</p>
          <Button
            size="lg"
            onClick={() =>
              document.getElementById('catalogo')?.scrollIntoView({ behavior: 'smooth' })
            }
          >
            Ver productos <ArrowRight data-icon="inline-end" />
          </Button>
        </div>
        <div className="hero-art">
          <div className="hero-note">
            hecho para
            <br />
            <strong>disfrutar</strong>
          </div>
          <img
            src="https://images.unsplash.com/photo-1490481651871-ab68de25d43d?auto=format&fit=crop&w=1200&q=85"
            alt="Persona usando prendas de la nueva colección"
          />
        </div>
      </section>

      <CategoryStrip activeCategoryId={query.categoryId ?? null} onSelect={selectCategory} />

      <CatalogSection
        query={query}
        onQueryChange={setQuery}
        likedIds={liked}
        onToggleLike={toggleLike}
        onAdd={addToCart}
      />

      <section id="nosotros" className="value-banner">
        <div>
          <p className="eyebrow">una tienda con intención</p>
          <h2>
            Menos ruido.
            <br />
            <em>Más de vos.</em>
          </h2>
        </div>
        <p>
          Creemos que comprar también puede ser una forma de elegir mejor. Por eso curamos productos
          que duran, funcionan y te dan ganas de usarlos todos los días.
        </p>
      </section>

      <footer>
        <div className="footer-brand">
          <a className="brand" href="#top">
            latiendita<span>.</span>
          </a>
          <p>cosas lindas para todos los días.</p>
        </div>
        <div>
          <p className="footer-label">Ayuda</p>
          <a href="#top">Preguntas frecuentes</a>
          <a href="#top">Envíos y cambios</a>
        </div>
        <div>
          <p className="footer-label">Seguinos</p>
          <a href="#top">Instagram</a>
          <a href="#top">Pinterest</a>
        </div>
      </footer>

      <SearchOverlay
        open={searchOpen}
        onClose={() => setSearchOpen(false)}
        value={query.q ?? ''}
        onSearch={search}
      />

      {accountOpen && (
        <AccountPanel
          open={accountOpen}
          initialView={accountView}
          onClose={() => {
            setAccountOpen(false)
            setAccountView('welcome')
          }}
        />
      )}

      <CartDrawer
        open={cartOpen}
        onClose={() => setCartOpen(false)}
        busy={checkout.busy}
        error={checkout.error}
        onCheckout={handleCheckout}
      />
    </main>
  )
}
