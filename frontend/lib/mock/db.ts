import type { Category, Product } from '@/features/product/schemas/product.schema'
import type { Order } from '@/features/order/schemas/order.schema'
import type { Payment } from '@/features/payment/schemas/payment.schema'

/**
 * Base de datos en memoria del modo mock.
 *
 * Sólo vive en el tablero del navegador y se reinicia al refrescar. Los
 * productos son los mismos que la tienda diseñada usaba como ejemplo, así el
 * modo mock se ve igual que siempre; las cuentas, pedidos y pagos arrancan
 * vacíos y se van creando a medida que se usan.
 */

const unsplash = (id: string) => `https://images.unsplash.com/${id}?auto=format&fit=crop&w=900&q=85`

export type MockProduct = Product & { price: number; quantity: number; categoryId: number }

export type MockAccount = {
  id: number
  email: string
  username: string
  password: string
}

export type MockOrder = Order
export type MockPayment = Payment

let nextProductId = 100
let nextAccountId = 1
let nextOrderId = 1
let nextPaymentId = 1

export const db = {
  categories: [
    { categoryId: 1, name: 'Indumentaria', description: 'Ropa de uso diario' },
    { categoryId: 2, name: 'Calzado', description: 'Zapatillas y calzado urbano' },
    { categoryId: 3, name: 'Hogar', description: 'Objetos para tu casa' },
    { categoryId: 4, name: 'Accesorios', description: 'Complementos y detalles' },
  ] satisfies Category[],

  products: [
    {
      productId: 1,
      sku: 'BUZ-ESS-CRE',
      title: 'Buzo Essential Cream',
      imageUrl: unsplash('photo-1551488831-00ddcb6c6bd3'),
      price: 38990,
      quantity: 12,
      categoryId: 1,
      tag: 'Oferta',
      color: 'Crema',
      oldPrice: 45990,
    },
    {
      productId: 2,
      sku: 'ZAP-NOV-WHI',
      title: 'Zapatillas Nova White',
      imageUrl: unsplash('photo-1542291026-7eec264c27ff'),
      price: 72990,
      quantity: 8,
      categoryId: 2,
      tag: 'Nuevo',
      color: 'Blanco',
    },
    {
      productId: 3,
      sku: 'VAS-AUR-TER',
      title: 'Vaso térmico Aura',
      imageUrl: unsplash('photo-1514228742587-6b1558fcca3d'),
      price: 18990,
      quantity: 25,
      categoryId: 3,
      color: 'Verde salvia',
    },
    {
      productId: 4,
      sku: 'LAM-NID-NAT',
      title: 'Lámpara Nido',
      imageUrl: unsplash('photo-1507473885765-e6ed057f782c'),
      price: 24990,
      quantity: 6,
      categoryId: 3,
      tag: 'Oferta',
      color: 'Natural',
      oldPrice: 29990,
    },
    {
      productId: 5,
      sku: 'CAM-LIN-CEL',
      title: 'Camisa Lino Relax',
      imageUrl: unsplash('photo-1603252110481-7ba873bf42ab'),
      price: 42990,
      quantity: 14,
      categoryId: 1,
      color: 'Celeste',
    },
    {
      productId: 6,
      sku: 'MOC-DAI-NEG',
      title: 'Mochila Daily',
      imageUrl: unsplash('photo-1553062407-98eeb64c6a62'),
      price: 31990,
      quantity: 10,
      categoryId: 4,
      color: 'Negro',
    },
    {
      productId: 7,
      sku: 'CAR-MIN-CHO',
      title: 'Cartera Mini Nube',
      imageUrl: unsplash('photo-1584917865442-de89df76afd3'),
      price: 27990,
      quantity: 9,
      categoryId: 4,
      color: 'Chocolate',
    },
    {
      productId: 8,
      sku: 'PAN-WID-DEN',
      title: 'Pantalón Wide Leg',
      imageUrl: unsplash('photo-1506629905607-d9c297d2d5b7'),
      price: 51990,
      quantity: 11,
      categoryId: 1,
      color: 'Denim',
    },
  ] satisfies MockProduct[],

  accounts: [] as MockAccount[],
  orders: [] as MockOrder[],
  payments: [] as MockPayment[],

  /** Cuenta de demostración para probar el login sin registrarse. */
  seedAccount(): MockAccount {
    const demo: MockAccount = {
      id: nextAccountId++,
      email: 'demo@latiendita.dev',
      username: 'demo',
      password: 'demo1234',
    }
    this.accounts.push(demo)
    return demo
  },

  createAccount(email: string, username: string, password: string): MockAccount {
    const account: MockAccount = { id: nextAccountId++, email, username, password }
    this.accounts.push(account)
    return account
  },

  findAccountByEmail(email: string): MockAccount | undefined {
    return this.accounts.find((account) => account.email === email)
  },

  createOrder(order: Omit<MockOrder, 'id'>): MockOrder {
    const created: MockOrder = { ...order, id: nextOrderId++ }
    this.orders.push(created)
    return created
  },

  findOrder(orderId: number): MockOrder | undefined {
    return this.orders.find((order) => order.id === orderId)
  },

  createPayment(payment: Omit<MockPayment, 'id'>): MockPayment {
    const created: MockPayment = { ...payment, id: nextPaymentId++ }
    this.payments.push(created)
    return created
  },

  findPaymentByOrderId(orderId: number): MockPayment | undefined {
    return this.payments.find((payment) => payment.orderId === orderId)
  },

  findPaymentByReference(reference: string): MockPayment | undefined {
    return this.payments.find((payment) => payment.orderNumber === reference)
  },
}

// La demo se crea al importar el módulo para que el login de prueba siempre
// funcione aunque no haya interactuado antes con el modo mock.
if (db.accounts.length === 0) {
  db.seedAccount()
}
