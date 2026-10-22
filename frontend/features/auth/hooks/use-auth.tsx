'use client'

import {
  createContext,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { registerRefreshHandler, subscribeSessionExpired } from '@/lib/http/session'
import { readSession } from '@/lib/http/token-storage'
import { authApi } from '@/features/auth/services/auth.repo'
import { decodeJwtClaims } from '@/features/auth/utils/jwt'
import type {
  LoginRequest,
  RegisterRequest,
  UserProfile,
} from '@/features/auth/schemas/auth.schema'

type AuthContextValue = {
  isAuthenticated: boolean
  email: string | null
  username: string | null
  /** True mientras corre un login, registro o logout. */
  busy: boolean
  login: (request: LoginRequest) => Promise<void>
  register: (request: RegisterRequest) => Promise<void>
  logout: () => Promise<void>
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient()
  // Se lee después del mount: durante el SSR no hay localStorage, y leerlo en
  // el primer render produciría un mismatch de hidratación.
  const [accessToken, setAccessToken] = useState<string | null>(null)

  useEffect(() => {
    setAccessToken(readSession()?.accessToken ?? null)
  }, [])

  // El cliente HTTP no puede importar esta feature (la feature lo usa), así
  // que le registra acá el handler que sabe refrescar la sesión.
  useEffect(() => {
    registerRefreshHandler(async () => {
      const current = readSession()
      if (!current) return false

      const next = await authApi.refresh(current.refreshToken)
      if (!next) return false

      setAccessToken(next.accessToken)
      return true
    })
  }, [])

  useEffect(
    () =>
      subscribeSessionExpired(() => {
        setAccessToken(null)
        // Los datos del usuario no sirven para otra cuenta: se descarta todo.
        queryClient.clear()
      }),
    [queryClient],
  )

  const claims = useMemo(() => decodeJwtClaims(accessToken), [accessToken])

  const profileQuery = useQuery<UserProfile | null>({
    queryKey: ['me'],
    queryFn: () => authApi.getProfile(),
    enabled: accessToken !== null,
    staleTime: 5 * 60_000,
    retry: false,
  })

  const loginMutation = useMutation({
    mutationFn: (request: LoginRequest) => authApi.login(request),
    onSuccess: (session) => {
      setAccessToken(session.accessToken)
      void queryClient.invalidateQueries({ queryKey: ['me'] })
    },
  })

  const registerMutation = useMutation({
    mutationFn: async (request: RegisterRequest) => {
      await authApi.register(request)
      // El backend no loguea al registrar; se hace acá para no dejar al
      // usuario en la vista de login después de crear la cuenta.
      return authApi.login({ email: request.email, password: request.password })
    },
    onSuccess: (session) => {
      setAccessToken(session.accessToken)
      void queryClient.invalidateQueries({ queryKey: ['me'] })
    },
  })

  const logoutMutation = useMutation({
    mutationFn: async () => {
      const current = readSession()
      await authApi.logout(current?.refreshToken ?? '')
      setAccessToken(null)
      queryClient.clear()
    },
  })

  const value = useMemo<AuthContextValue>(
    () => ({
      isAuthenticated: accessToken !== null,
      email: claims?.email ?? profileQuery.data?.email ?? null,
      username: profileQuery.data?.username ?? claims?.username ?? null,
      busy: loginMutation.isPending || registerMutation.isPending || logoutMutation.isPending,
      login: (request) => loginMutation.mutateAsync(request).then(() => undefined),
      register: (request) => registerMutation.mutateAsync(request).then(() => undefined),
      logout: () => logoutMutation.mutateAsync().then(() => undefined),
    }),
    [
      accessToken,
      claims,
      profileQuery.data,
      loginMutation,
      registerMutation,
      logoutMutation,
    ],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext)
  if (!context) throw new Error('useAuth debe usarse dentro de <AuthProvider>')
  return context
}

/** Sólo hace falta saber si hay sesión; evita re-renderizar por el email. */
export function useIsAuthenticated(): boolean {
  return useAuth().isAuthenticated
}
