'use client'

import { ArrowRight, Minus, Plus, ShoppingBag, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { money } from '@/lib/format'
import { useCart } from '@/features/order/hooks/use-cart'

type CartDrawerProps = {
  open: boolean
  onClose: () => void
  /** Estado del flujo de compra (lo maneja la página para poder retomarlo). */
  busy: boolean
  error: string | null
  /** Arranca el checkout: si no hay sesión, la página abre el login. */
  onCheckout: () => void
}

export function CartDrawer({ open, onClose, busy, error, onCheckout }: CartDrawerProps) {
  const cart = useCart()

  if (!open) return null

  return (
    <div className="cart-overlay" onClick={onClose}>
      <aside className="cart-drawer" onClick={(event) => event.stopPropagation()}>
        <div className="drawer-header">
          <div>
            <p className="eyebrow">tu selección</p>
            <h2>
              Carrito <span>({cart.count})</span>
            </h2>
          </div>
          <Button variant="ghost" size="icon" onClick={onClose} aria-label="Cerrar carrito">
            <X />
          </Button>
        </div>

        {cart.lines.length === 0 ? (
          <div className="empty-cart">
            <ShoppingBag />
            <h3>Tu carrito está vacío</h3>
            <p>Sumá algo lindo para empezar.</p>
          </div>
        ) : (
          <>
            <div className="cart-items">
              {cart.lines.map((line) => (
                <div className="cart-item" key={line.productId}>
                  {line.image ? (
                    <img src={line.image} alt="" />
                  ) : (
                    <div className="cart-item-image-empty" aria-hidden="true">
                      <ShoppingBag />
                    </div>
                  )}
                  <div className="cart-item-copy">
                    <h3>{line.title}</h3>
                    <p>{money(line.price)}</p>
                    <div className="quantity">
                      <button
                        onClick={() => cart.decrement(line.productId)}
                        aria-label={`Quitar una unidad de ${line.title}`}
                      >
                        <Minus />
                      </button>
                      <span>{line.quantity}</span>
                      <button
                        onClick={() => cart.increment(line.productId)}
                        aria-label={`Agregar una unidad de ${line.title}`}
                      >
                        <Plus />
                      </button>
                    </div>
                  </div>
                  <strong>{money(line.price * line.quantity)}</strong>
                </div>
              ))}
            </div>

            <div className="cart-summary">
              <div>
                <span>Subtotal</span>
                <strong>{money(cart.total)}</strong>
              </div>
              <p>El importe final lo calcula la tienda al confirmar el pedido.</p>
              {error && <p className="checkout-notice">{error}</p>}
              <Button
                size="lg"
                className="checkout-button"
                disabled={busy}
                onClick={onCheckout}
              >
                {busy ? 'Te llevamos al pago…' : 'Finalizar compra'}{' '}
                <ArrowRight data-icon="inline-end" />
              </Button>
            </div>
          </>
        )}
      </aside>
    </div>
  )
}
