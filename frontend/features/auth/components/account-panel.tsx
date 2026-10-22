'use client'

import { useState, type FormEvent } from 'react'
import { ArrowRight, LogOut, UserRound, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { ApiError } from '@/lib/http/errors'
import { useAuth } from '@/features/auth/hooks/use-auth'
import {
  loginRequestSchema,
  registerRequestSchema,
} from '@/features/auth/schemas/auth.schema'

type View = 'welcome' | 'login' | 'signup'

const EMPTY_VALUES = { email: '', username: '', password: '' }

type AccountPanelProps = {
  open: boolean
  onClose: () => void
  /** Vista con la que abre el panel (el checkout pide 'login'). */
  initialView?: View
}

/**
 * Panel de cuenta: bienvenida, ingreso y registro.
 *
 * La validación ocurre en dos capas: Zod en el cliente (para no mandar un
 * request inválido) y el backend (que puede rechazar con 409 por email o
 * usuario duplicado). Los errores por campo vienen del `errors[]` del Problem
 * Details y se muestran debajo de cada input.
 */
export function AccountPanel({ open, onClose, initialView = 'welcome' }: AccountPanelProps) {
  const { isAuthenticated, email, username, busy, login, register, logout } = useAuth()

  const [view, setView] = useState<View>(initialView)
  const [values, setValues] = useState(EMPTY_VALUES)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState<string | null>(null)

  if (!open) return null

  const switchView = (next: View) => {
    setView(next)
    setFieldErrors({})
    setFormError(null)
  }

  const update = (field: keyof typeof EMPTY_VALUES) => (event: React.ChangeEvent<HTMLInputElement>) => {
    setValues((current) => ({ ...current, [field]: event.target.value }))
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setFieldErrors({})
    setFormError(null)

    if (view === 'signup') {
      const parsed = registerRequestSchema.safeParse(values)
      if (!parsed.success) {
        setFieldErrors(
          Object.fromEntries(parsed.error.issues.map((issue) => [String(issue.path[0]), issue.message])),
        )
        return
      }
      try {
        await register(parsed.data)
        onClose()
      } catch (error) {
        reportError(error)
      }
      return
    }

    const parsed = loginRequestSchema.safeParse(values)
    if (!parsed.success) {
      setFieldErrors(
        Object.fromEntries(parsed.error.issues.map((issue) => [String(issue.path[0]), issue.message])),
      )
      return
    }
    try {
      await login(parsed.data)
      onClose()
    } catch (error) {
      reportError(error)
    }
  }

  function reportError(error: unknown) {
    if (error instanceof ApiError && Object.keys(error.fieldErrors).length > 0) {
      setFieldErrors(error.fieldErrors)
      return
    }
    setFormError(
      error instanceof ApiError ? error.message : 'No pudimos procesar la operación. Probá de nuevo.',
    )
  }

  async function handleLogout() {
    setFormError(null)
    try {
      await logout()
      setValues(EMPTY_VALUES)
      setView('welcome')
    } catch (error) {
      reportError(error)
    }
  }

  const title =
    view === 'welcome' ? 'Mi cuenta' : view === 'login' ? 'Ingresar' : 'Crear cuenta'

  return (
    <div className="utility-overlay" onClick={onClose}>
      <section
        className="utility-panel account-panel"
        onClick={(event) => event.stopPropagation()}
      >
        <div className="utility-header">
          <div>
            <p className="eyebrow">tu espacio personal</p>
            <h2>{title}</h2>
          </div>
          <Button variant="ghost" size="icon" onClick={onClose} aria-label="Cerrar cuenta">
            <X />
          </Button>
        </div>

        {isAuthenticated ? (
          <div className="account-content">
            <div className="account-icon">
              <UserRound />
            </div>
            <h3>{username ?? '¡Hola!'}</h3>
            <p>
              Ingresaste como <strong>{email}</strong>. Tu carrito y tus pedidos quedan asociados
              a esta cuenta.
            </p>
            {formError && <p className="checkout-notice">{formError}</p>}
            <Button size="lg" variant="outline" onClick={() => void handleLogout()} disabled={busy}>
              <LogOut data-icon="inline-start" /> {busy ? 'Saliendo…' : 'Cerrar sesión'}
            </Button>
          </div>
        ) : view === 'welcome' ? (
          <div className="account-content">
            <div className="account-icon">
              <UserRound />
            </div>
            <h3>Todo en un solo lugar</h3>
            <p>Guardá tus favoritos y seguí tus pedidos desde tu cuenta.</p>
            <Button size="lg" onClick={() => switchView('login')}>
              Ingresar <ArrowRight data-icon="inline-end" />
            </Button>
            <button className="account-link" onClick={() => switchView('signup')}>
              Crear una cuenta
            </button>
          </div>
        ) : (
          <form className="account-form" onSubmit={(event) => void handleSubmit(event)} noValidate>
            {view === 'signup' && (
              <Field
                label="Nombre de usuario"
                name="username"
                value={values.username}
                onChange={update('username')}
                error={fieldErrors.username}
                autoComplete="username"
                minLength={3}
                maxLength={30}
                placeholder="Tu nombre"
              />
            )}
            <Field
              label="Email"
              name="email"
              type="email"
              value={values.email}
              onChange={update('email')}
              error={fieldErrors.email}
              autoComplete="email"
              placeholder="tu@email.com"
            />
            <Field
              label="Contraseña"
              name="password"
              type="password"
              value={values.password}
              onChange={update('password')}
              error={fieldErrors.password}
              autoComplete={view === 'signup' ? 'new-password' : 'current-password'}
              minLength={view === 'signup' ? 8 : undefined}
              placeholder={view === 'signup' ? 'Mínimo 8 caracteres' : 'Tu contraseña'}
            />

            {formError && <p className="checkout-notice">{formError}</p>}

            <Button size="lg" type="submit" disabled={busy}>
              {busy ? 'Un momento…' : view === 'login' ? 'Ingresar' : 'Crear cuenta'}{' '}
              <ArrowRight data-icon="inline-end" />
            </Button>

            <button
              className="account-link"
              type="button"
              onClick={() => switchView(view === 'login' ? 'signup' : 'login')}
            >
              {view === 'login' ? 'Crear una cuenta' : 'Ya tengo una cuenta'}
            </button>
          </form>
        )}
      </section>
    </div>
  )
}

type FieldProps = {
  label: string
  name: string
  value: string
  onChange: (event: React.ChangeEvent<HTMLInputElement>) => void
  error?: string
} & Omit<React.InputHTMLAttributes<HTMLInputElement>, 'name' | 'value' | 'onChange'>

function Field({ label, name, error, ...inputProps }: FieldProps) {
  return (
    <label htmlFor={`account-${name}`}>
      {label}
      <input
        id={`account-${name}`}
        name={name}
        aria-invalid={error ? true : undefined}
        {...inputProps}
      />
      {error && <span className="field-error">{error}</span>}
    </label>
  )
}
