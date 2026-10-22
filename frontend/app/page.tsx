'use client'

import { useEffect, useMemo, useState } from 'react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  ApiError,
  createOrder,
  createPayment,
  hasToken,
  login,
  rememberPendingOrder,
  register,
  sessionEmail,
} from '@/lib/api'
import { apiFetch, productImageSrc, type CategoryDto, type Page, type ProductDto } from '@/lib/api'
import {
  ArrowRight,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  Heart,
  Menu,
  Minus,
  Plus,
  Search,
  ShoppingBag,
  Sparkles,
  UserRound,
  X,
} from 'lucide-react'

type Product = {
  id: number
  name: string
  category: string
  price: number
  oldPrice?: number
  image: string
  tag?: string
  color: string
}

const mockProducts: Product[] = [
  { id: 1, name: 'Buzo Essential Cream', category: 'Indumentaria', price: 38990, oldPrice: 45990, image: 'https://images.unsplash.com/photo-1551488831-00ddcb6c6bd3?auto=format&fit=crop&w=900&q=85', tag: 'Oferta', color: 'Crema' },
  { id: 2, name: 'Zapatillas Nova White', category: 'Calzado', price: 72990, image: 'https://images.unsplash.com/photo-1542291026-7eec264c27ff?auto=format&fit=crop&w=900&q=85', tag: 'Nuevo', color: 'Blanco' },
  { id: 3, name: 'Vaso térmico Aura', category: 'Hogar', price: 18990, image: 'https://images.unsplash.com/photo-1514228742587-6b1558fcca3d?auto=format&fit=crop&w=900&q=85', color: 'Verde salvia' },
  { id: 4, name: 'Lámpara Nido', category: 'Hogar', price: 24990, oldPrice: 29990, image: 'https://images.unsplash.com/photo-1507473885765-e6ed057f782c?auto=format&fit=crop&w=900&q=85', tag: 'Oferta', color: 'Natural' },
  { id: 5, name: 'Camisa Lino Relax', category: 'Indumentaria', price: 42990, image: 'https://images.unsplash.com/photo-1603252110481-7ba873bf42ab?auto=format&fit=crop&w=900&q=85', color: 'Celeste' },
  { id: 6, name: 'Mochila Daily', category: 'Accesorios', price: 31990, image: 'https://images.unsplash.com/photo-1553062407-98eeb64c6a62?auto=format&fit=crop&w=900&q=85', color: 'Negro' },
  { id: 7, name: 'Cartera Mini Nube', category: 'Accesorios', price: 27990, image: 'https://images.unsplash.com/photo-1584917865442-de89df76afd3?auto=format&fit=crop&w=900&q=85', color: 'Chocolate' },
  { id: 8, name: 'Pantalón Wide Leg', category: 'Indumentaria', price: 51990, image: 'https://images.unsplash.com/photo-1506629905607-d9c297d2d5b7?auto=format&fit=crop&w=900&q=85', color: 'Denim' },
]

const CATEGORY_EMOJI: Record<string, string> = {
  Todo: '✦',
  Indumentaria: '◌',
  Calzado: '◒',
  Hogar: '⌂',
  Accesorios: '◈',
}

const money = (value: number) => `$${value.toLocaleString('es-AR')}`

export default function Page() {
  const [activeCategory, setActiveCategory] = useState('Todo')
  const [cart, setCart] = useState<Record<number, number>>({ 1: 1, 3: 1 })
  const [cartOpen, setCartOpen] = useState(false)
  const [searchOpen, setSearchOpen] = useState(false)
  const [accountOpen, setAccountOpen] = useState(false)
  const [accountView, setAccountView] = useState<'welcome' | 'login' | 'signup'>('welcome')
  const [menuOpen, setMenuOpen] = useState(false)
  const [liked, setLiked] = useState<number[]>([])
  const [page, setPage] = useState(0)
  const [catalog, setCatalog] = useState<Product[]>(mockProducts)
  const [authBusy, setAuthBusy] = useState(false)
  const [authError, setAuthError] = useState<string | null>(null)
  const [checkoutBusy, setCheckoutBusy] = useState(false)
  const [checkoutError, setCheckoutError] = useState<string | null>(null)
  // Si el usuario intentó comprar sin sesión, al loguearse retomamos el checkout.
  const [resumeCheckout, setResumeCheckout] = useState(false)

  useEffect(() => {
    let cancelled = false

    async function loadCatalog() {
      try {
        const [productsPage, categoriesPage] = await Promise.all([
          apiFetch<Page<ProductDto>>('/api/products?size=50'),
          apiFetch<Page<CategoryDto>>('/api/categories?size=50'),
        ])
        if (cancelled) return
        const categoryNames = new Map(
          categoriesPage.content.map((category) => [category.categoryId, category.name]),
        )
        const loaded = productsPage.content.map((product) => ({
          id: product.productId,
          name: product.title,
          category:
            (product.categoryId != null ? categoryNames.get(product.categoryId) : undefined) ??
            'Sin categoría',
          price: Number(product.price),
          image: productImageSrc(product) ?? mockProducts[0].image,
          color: '',
        }))
        if (loaded.length > 0) setCatalog(loaded)
      } catch (error) {
        console.warn('Catálogo no disponible, se usan datos de ejemplo:', error)
      }
    }

    void loadCatalog()
    return () => {
      cancelled = true
    }
  }, [])

  const categoryNames = useMemo(() => {
    const names = new Set(catalog.map((product) => product.category))
    return ['Todo', ...Array.from(names).sort((a, b) => a.localeCompare(b))]

  }, [catalog])

  const filteredProducts = useMemo(
    () =>
      activeCategory === 'Todo'
        ? catalog
        : catalog.filter((product) => product.category === activeCategory),
    [activeCategory, catalog],
  )
  const cartItems = catalog.filter((product) => cart[product.id])
  const cartCount = Object.values(cart).reduce((sum, quantity) => sum + quantity, 0)
  const cartTotal = cartItems.reduce((sum, product) => sum + product.price * cart[product.id], 0)

  const addToCart = (id: number) => setCart((current) => ({ ...current, [id]: (current[id] || 0) + 1 }))
  const removeFromCart = (id: number) => setCart((current) => {
    const next = { ...current }
    if (next[id] > 1) next[id] -= 1
    else delete next[id]
    return next
  })

  const errorText = (error: unknown) =>
    error instanceof ApiError ? error.message : 'No pudimos procesar la operación. Probá de nuevo.'

  /** Submit del panel de cuenta: login o registro y, si venía de comprar, sigue el checkout. */
  async function handleAccountSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    const email = String(form.get('email') ?? '').trim()
    const password = String(form.get('password') ?? '')
    const username = String(form.get('username') ?? '').trim()

    setAuthBusy(true)
    setAuthError(null)
    try {
      if (accountView === 'signup') await register({ email, username, password })
      else await login(email, password)
      setAccountOpen(false)
      setAccountView('welcome')
      if (resumeCheckout) {
        setResumeCheckout(false)
        void handleCheckout()
      }
    } catch (error) {
      setAuthError(errorText(error))
    } finally {
      setAuthBusy(false)
    }
  }

  /**
   * Flujo de compra: orden → preferencia de Mercado Pago → redirección al
   * Checkout Pro. El estado final lo resuelve el webhook contra payment-service.
   */
  async function handleCheckout() {
    if (cartItems.length === 0 || checkoutBusy) return

    const payerEmail = sessionEmail()
    if (!hasToken() || !payerEmail) {
      setCheckoutError(null)
      setResumeCheckout(true)
      setAccountView('login')
      setAccountOpen(true)
      return
    }

    setCheckoutBusy(true)
    setCheckoutError(null)
    try {
      const order = await createOrder(
        cartItems.map((product) => ({ productId: product.id, quantity: cart[product.id] })),
      )
      const payment = await createPayment(order.id, payerEmail)
      // Lo guardamos por si Mercado Pago no devuelve el external_reference.
      rememberPendingOrder(order.id)
      if (!payment.checkoutUrl) {
        throw new Error('Mercado Pago no devolvió la URL de pago.')
      }
      window.location.href = payment.checkoutUrl
    } catch (error) {
      setCheckoutError(errorText(error))
      setCheckoutBusy(false)
    }
  }

  return (
    <main className="store-shell">
      <header className="site-header">
        <Button variant="ghost" size="icon" className="mobile-menu" onClick={() => setMenuOpen(!menuOpen)} aria-label="Abrir menú"><Menu /></Button>
        <a className="brand" href="#top">latiendita<span>.</span></a>
        <nav className={menuOpen ? 'main-nav open' : 'main-nav'}>
          <a href="#catalogo">Comprar</a><a href="#categorias">Categorías</a><a href="#nosotros">Nosotros</a>
        </nav>
        <div className="header-actions">
          <Button variant="ghost" size="icon" aria-label="Buscar" onClick={() => setSearchOpen(true)}><Search /></Button>
          <Button variant="ghost" size="icon" aria-label="Mi cuenta" onClick={() => setAccountOpen(true)}><UserRound /></Button>
          <Button variant="outline" className="cart-button" onClick={() => setCartOpen(true)}><ShoppingBag data-icon="inline-start" /> Carrito <span>{cartCount}</span></Button>
        </div>
      </header>

      <section id="top" className="hero">
        <div className="hero-copy"><h1>Elegí lo que<br /><em>te hace bien.</em></h1><p>Objetos simples, lindos y funcionales para acompañar tus días.</p><Button size="lg" onClick={() => document.getElementById('catalogo')?.scrollIntoView({ behavior: 'smooth' })}>Ver productos <ArrowRight data-icon="inline-end" /></Button></div>
        <div className="hero-art"><div className="hero-note">hecho para<br /><strong>disfrutar</strong></div><img src="https://images.unsplash.com/photo-1490481651871-ab68de25d43d?auto=format&fit=crop&w=1200&q=85" alt="Persona usando prendas de la nueva colección" /></div>
      </section>

      <section id="categorias" className="category-strip"><div className="category-heading"><p>Explorá por categoría</p><span>Encontrá lo que buscás</span></div><nav className="category-nav" aria-label="Categorías de productos">{categoryNames.map((name) => <button key={name} className={activeCategory === name ? 'category-pill active' : 'category-pill'} onClick={() => { setActiveCategory(name); setPage(0) }} aria-pressed={activeCategory === name}><span aria-hidden="true">{CATEGORY_EMOJI[name] ?? '◈'}</span>{name}</button>)}</nav></section>

      <section id="catalogo" className="catalog-section"><div className="section-heading"><div><p className="eyebrow">selección de la semana</p><h2>Lo más elegido</h2></div><Button variant="ghost" className="desktop-only">Ver todo <ArrowRight data-icon="inline-end" /></Button></div><div className="product-grid">{filteredProducts.map((product) => <article className="product-card" key={product.id}><div className="product-image"><img src={product.image} alt={product.name} loading="lazy" />{product.tag && <Badge className="product-tag" variant={product.tag === 'Oferta' ? 'destructive' : 'secondary'}>{product.tag}</Badge>}<button className="like-button" aria-label={`Agregar ${product.name} a favoritos`} onClick={() => setLiked((current) => current.includes(product.id) ? current.filter((id) => id !== product.id) : [...current, product.id])}><Heart fill={liked.includes(product.id) ? 'currentColor' : 'none'} /></button><button className="quick-add" onClick={() => { addToCart(product.id); setCartOpen(true) }}><Plus data-icon="inline-start" /> Agregar</button></div><div className="product-info"><div><p className="product-category">{product.category}</p><h3>{product.name}</h3></div><div className="price-row"><strong>{money(product.price)}</strong>{product.oldPrice && <del>{money(product.oldPrice)}</del>}</div></div></article>)}</div><div className="pagination"><span>0{page + 1} / 02</span><div><Button variant="outline" size="icon" disabled={page === 0} onClick={() => setPage(0)}><ChevronLeft /></Button><Button variant="outline" size="icon" disabled={page === 1} onClick={() => setPage(1)}><ChevronRight /></Button></div></div></section>

      <section id="nosotros" className="value-banner"><div><p className="eyebrow">una tienda con intención</p><h2>Menos ruido.<br /><em>Más de vos.</em></h2></div><p>Creemos que comprar también puede ser una forma de elegir mejor. Por eso curamos productos que duran, funcionan y te dan ganas de usarlos todos los días.</p></section>

      <footer><div className="footer-brand"><a className="brand" href="#top">latiendita<span>.</span></a><p>cosas lindas para todos los días.</p></div><div><p className="footer-label">Ayuda</p><a href="#top">Preguntas frecuentes</a><a href="#top">Envíos y cambios</a></div><div><p className="footer-label">Seguinos</p><a href="#top">Instagram</a><a href="#top">Pinterest</a></div></footer>

      {searchOpen && <div className="utility-overlay" onClick={() => setSearchOpen(false)}><section className="utility-panel search-panel" onClick={(event) => event.stopPropagation()}><div className="utility-header"><div><p className="eyebrow">encontrá tu próximo favorito</p><h2>¿Qué estás buscando?</h2></div><Button variant="ghost" size="icon" onClick={() => setSearchOpen(false)} aria-label="Cerrar búsqueda"><X /></Button></div><label className="search-field"><Search /><input autoFocus placeholder="Buscar productos, categorías..." aria-label="Buscar productos" /><span>⌘ K</span></label><div className="search-suggestions"><p>Podés probar con</p><button onClick={() => setSearchOpen(false)}>indumentaria</button><button onClick={() => setSearchOpen(false)}>hogar</button><button onClick={() => setSearchOpen(false)}>accesorios</button></div></section></div>}

      {accountOpen && <div className="utility-overlay" onClick={() => setAccountOpen(false)}><section className="utility-panel account-panel" onClick={(event) => event.stopPropagation()}><div className="utility-header"><div><p className="eyebrow">tu espacio personal</p><h2>{accountView === 'welcome' ? 'Mi cuenta' : accountView === 'login' ? 'Ingresar' : 'Crear cuenta'}</h2></div><Button variant="ghost" size="icon" onClick={() => setAccountOpen(false)} aria-label="Cerrar cuenta"><X /></Button></div>{accountView === 'welcome' ? <div className="account-content"><div className="account-icon"><UserRound /></div><h3>Todo en un solo lugar</h3><p>Guardá tus favoritos y seguí tus pedidos desde tu cuenta.</p><Button size="lg" onClick={() => setAccountView('login')}>Ingresar <ArrowRight data-icon="inline-end" /></Button><button className="account-link" onClick={() => setAccountView('signup')}>Crear una cuenta</button></div> : <form className="account-form" onSubmit={handleAccountSubmit}>{accountView === 'signup' && <label>Nombre<input name="username" required minLength={3} maxLength={30} placeholder="Tu nombre" autoComplete="username" /></label>}<label>Email<input name="email" required type="email" placeholder="tu@email.com" autoComplete="email" /></label><label>Contraseña<input name="password" required type="password" minLength={accountView === 'signup' ? 8 : undefined} placeholder="Mínimo 8 caracteres" autoComplete={accountView === 'signup' ? 'new-password' : 'current-password'} /></label>{accountView === 'login' && <button className="forgot-link" type="button">¿Olvidaste tu contraseña?</button>}{authError && <p className="checkout-notice">{authError}</p>}<Button size="lg" type="submit" disabled={authBusy}>{authBusy ? 'Un momento…' : accountView === 'login' ? 'Ingresar' : 'Crear cuenta'} <ArrowRight data-icon="inline-end" /></Button><button className="account-link" type="button" onClick={() => { setAuthError(null); setAccountView(accountView === 'login' ? 'signup' : 'login') }}>{accountView === 'login' ? '¿No tenés cuenta? Crear una' : 'Ya tengo una cuenta'}</button></form>}</section></div>}

      {cartOpen && <div className="cart-overlay" onClick={() => setCartOpen(false)}><aside className="cart-drawer" onClick={(event) => event.stopPropagation()}><div className="drawer-header"><div><p className="eyebrow">tu selección</p><h2>Carrito <span>({cartCount})</span></h2></div><Button variant="ghost" size="icon" onClick={() => setCartOpen(false)} aria-label="Cerrar carrito"><X /></Button></div>{cartItems.length ? <><div className="cart-items">{cartItems.map((product) => <div className="cart-item" key={product.id}><img src={product.image} alt="" /><div className="cart-item-copy"><h3>{product.name}</h3><p>{money(product.price)}</p><div className="quantity"><button onClick={() => removeFromCart(product.id)} aria-label="Quitar una unidad"><Minus /></button><span>{cart[product.id]}</span><button onClick={() => addToCart(product.id)} aria-label="Agregar una unidad"><Plus /></button></div></div><strong>{money(product.price * cart[product.id])}</strong></div>)}</div><div className="cart-summary"><div><span>Subtotal</span><strong>{money(cartTotal)}</strong></div><p>Envío calculado al finalizar la compra.</p>{checkoutError && <p className="checkout-notice">{checkoutError}</p>}<Button size="lg" className="checkout-button" disabled={checkoutBusy} onClick={() => { void handleCheckout() }}>{checkoutBusy ? 'Te llevamos al pago…' : 'Finalizar compra'} <ArrowRight data-icon="inline-end" /></Button></div></> : <div className="empty-cart"><ShoppingBag /><h3>Tu carrito está vacío</h3><p>Sumá algo lindo para empezar.</p></div>}</aside></div>}
    </main>
  )
}

const _ChevronDown = ChevronDown
