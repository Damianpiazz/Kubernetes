# Mercado Pago: exposición pública y URLs

Guía de cómo exponer el stack para que **Mercado Pago** pueda alcanzar el
webhook y las *back_urls*, tanto en Docker Compose como en Kubernetes.

> El detalle funcional del servicio (preferencias, webhook firmado, esquema de
> tablas) está en [docs/microservicios/payment.md](../microservicios/payment.md).
> Este documento sólo cubre la **exposición de red y las URLs por entorno**.

## 1. Puertos a exponer

Mercado Pago toca **dos** partes distintas del stack, así que hacen falta
**dos puertos públicos**. Ninguno es el `8085` (debug directo del
`payment-service`):

| Puerto | Servicio | Ruta | Variable | Para qué |
|---|---|---|---|---|
| **8080** | `gateway` | `/api/webhooks/mercado-pago` | `MERCADO_PAGO_NOTIFICATION_URL` | Recibe las notificaciones (webhook) |
| **3000** | `frontend` | `/checkout/success` · `/failure` · `/pending` | `MERCADO_PAGO_BACK_URL_*` | Redirige al usuario tras el pago |

```
Mercado Pago ──POST webhook──▶ :8080 (gateway) ──▶ payment-service
Usuario      ◀─redirect back_url── :3000 (frontend)
```

- El **webhook va por el `gateway` (8080)**, no directo al `payment-service`.
  El gateway enruta `/api/webhooks/**` hacia `payment-service` como ruta
  **pública** (sin JWT; la autenticidad se valida con la firma `x-signature`).
- Las **back_urls son rutas del frontend** (Next.js, puerto 3000). Si no se
  expone, el usuario paga pero **no puede volver** a la tienda.

> ⚠️ El `payment-service` también publica el **8085** en Compose
> (`compose.override.yaml`), pero es sólo para debugging. La `notification_url`
> **debe** apuntar al **8080** (gateway), nunca al 8085.

## 2. Docker Compose (local)

Las cuatro URLs tienen defaults `localhost` **sólo para que el stack arranque
sin configurar nada**: Mercado Pago rechaza `localhost`/`127.0.0.1` tanto en
`notification_url` como en las `back_urls`. Para probar el flujo de verdad
hace falta un **túnel público**:

```bash
docker compose up -d
ngrok http 8080   # gateway  → https://<tunel-gw>/api/webhooks/mercado-pago
ngrok http 3000   # frontend → https://<tunel-fe>/checkout/success|failure|pending
```

Luego en `.env`:

```dotenv
MERCADO_PAGO_NOTIFICATION_URL=https://<tunel-gw>/api/webhooks/mercado-pago
MERCADO_PAGO_BACK_URL_SUCCESS=https://<tunel-fe>/checkout/success
MERCADO_PAGO_BACK_URL_FAILURE=https://<tunel-fe>/checkout/failure
MERCADO_PAGO_BACK_URL_PENDING=https://<tunel-fe>/checkout/pending
```

y `docker compose up -d payment-service` para recrear el contenedor con los
nuevos valores. Un único túnel sólo llega a un puerto: con un dominio propio y
un reverse proxy (o con `--host-header=rewrite` de ngrok) se resuelve en uno
solo.

### 2.1 Alternativa: VS Code (Remote Tunnels / Codespaces)

En lugar de ngrok/cloudflared se puede exponer el stack con el port forwarding
de VS Code, **pero sólo si te conectas por Remote Tunnels o Codespaces**. El
panel **Ports** deja pasar cada puerto a *Public* y genera una URL
`https://<...>.app.github.dev` (HTTPS, alcanzable por Mercado Pago):

```bash
code tunnel   # en la máquina donde corre docker compose
```

Luego en el panel **Ports** de VS Code, exponer `8080` (gateway) y `3000`
(frontend) como *Public*, y usar esas URLs en `.env`:

```dotenv
MERCADO_PAGO_NOTIFICATION_URL=https://<tunel-gw>.app.github.dev/api/webhooks/mercado-pago
MERCADO_PAGO_BACK_URL_SUCCESS=https://<tunel-fe>.app.github.dev/checkout/success
MERCADO_PAGO_BACK_URL_FAILURE=https://<tunel-fe>.app.github.dev/checkout/failure
MERCADO_PAGO_BACK_URL_PENDING=https://<tunel-fe>.app.github.dev/checkout/pending
```

> **No sirve con Remote-SSH, WSL ni Dev Containers**: ahí el *Forward a Port*
> sólo expone el puerto en el `localhost` local y Mercado Pago no puede llegar.
> En esos casos usa ngrok/cloudflared.

| Método | URL pública HTTPS | Varios puertos | URL estable |
|---|---|---|---|
| ngrok / cloudflared | ✅ | ✅ (2 túneles o `--host-header=rewrite`) | ✅ (pago) / ❌ (free) |
| VS Code Tunnels / Codespaces | ✅ | ✅ (uno por puerto) | ❌ (cambia al reiniciar el tunnel) |
| SSH / WSL / Dev Containers | ❌ (sólo localhost) | — | — |

La URL de VS Code (como la de ngrok *free*) **cambia al reiniciar el tunnel**:
para un webhook de desarrollo es tolerable, pero si cambiás la URL volvé a
actualizarla en *Tus integraciones → Webhooks* de Mercado Pago.

### 2.2 Webhook secret

Se genera en *Tus integraciones → Webhooks → Configurar notificaciones →
Guardar* de Mercado Pago y va en `MERCADO_PAGO_WEBHOOK_SECRET`. Sin él,
`WebhookSignatureValidator` descarta toda notificación y el pago queda `PENDING`
para siempre. Con credenciales de **prueba**, los pagos reales no envían
notificaciones: el webhook se ensaya desde *Tus integraciones* (usa
`notifications_history` para diagnosticar entregas fallidas).

> El checkout de Mercado Pago se abre en la misma pestaña y vuelve a las
> `back_urls` con `?external_reference=<orderNumber>`. Esa referencia se
> traduce al pago con `GET /api/payments/reference/{externalReference}`.

## 3. Kubernetes

### 3.1 ¿Dónde se definen las URLs?

Las cuatro URLs (`MERCADO_PAGO_NOTIFICATION_URL` y `MERCADO_PAGO_BACK_URL_*`)
**no** se definen en `secrets.env`: son ConfigMap, no secretos. En `base`
sólo hay placeholders `CHANGE-ME` y el valor real se pisa en cada overlay con
`configMapGenerator`:

| Entorno | Archivo | Valor |
|---|---|---|
| local | `k8s/apps/environments/local/kustomization.yaml` | dominio del túnel (ngrok/cloudflared/VS Code) |
| producción | `k8s/apps/environments/production/kustomization.yaml` | dominio del Ingress (mismo host que el frontend) |

Siempre `https://` + dominio público, nunca `localhost`. `MERCADO_PAGO_ACCESS_TOKEN`
y `MERCADO_PAGO_WEBHOOK_SECRET` sí van en `secrets.env`.

#### Kubernetes local (overlay `local`)

No hay Ingress: se entra por `port-forward`, así que Mercado Pago no puede tocar
el clúster directamente. hace falta un túnel que repunte al port-forward del
gateway y del frontend:

```bash
kubectl port-forward svc/gateway 8080:80 -n latiendita
kubectl port-forward svc/frontend 3000:80 -n latiendita
ngrok http 8080        # → <tunel>.ngrok-free.app
```

Y en `k8s/apps/environments/local/kustomization.yaml`, reemplazar
`CHANGE-ME-tunel`:

```yaml
literals:
  - MERCADO_PAGO_NOTIFICATION_URL=https://<tunel>.ngrok-free.app/api/webhooks/mercado-pago
  - MERCADO_PAGO_BACK_URL_SUCCESS=https://<tunel>.ngrok-free.app/checkout/success
  - MERCADO_PAGO_BACK_URL_FAILURE=https://<tunel>.ngrok-free.app/checkout/failure
  - MERCADO_PAGO_BACK_URL_PENDING=https://<tunel>.ngrok-free.app/checkout/pending
```

#### Kubernetes producción (overlay `production`)

Reemplazar `CHANGE-ME.example.com` en
`k8s/apps/environments/production/kustomization.yaml` por el **dominio real del
Ingress** (el mismo host que el frontend):

```yaml
literals:
  - MERCADO_PAGO_NOTIFICATION_URL=https://tudominio.com/api/webhooks/mercado-pago
  - MERCADO_PAGO_BACK_URL_SUCCESS=https://tudominio.com/checkout/success
  - MERCADO_PAGO_BACK_URL_FAILURE=https://tudominio.com/checkout/failure
  - MERCADO_PAGO_BACK_URL_PENDING=https://tudominio.com/checkout/pending
```

La ruta del webhook debe estar expuesta a internet en el Ingress; el secreto de
firma va en `secrets.env` (`MERCADO_PAGO_WEBHOOK_SECRET`).

### 3.2 ¿Por qué Compose y Kubernetes se ven distintos?

**No es un error**: el conjunto de variables de Mercado Pago es **idéntico** en
ambos (las mismas 7). Lo que cambia es el mecanismo y los valores:

| | Docker Compose | Kubernetes |
|---|---|---|
| Variables secretas | `.env` plano (todo junto) | Secret `latiendita-secrets` (`ACCESS_TOKEN`, `WEBHOOK_SECRET`) |
| Variables no secretas | `.env` + defaults en `compose.yaml` | ConfigMap (`payment-service-config`) |
| Valores de las URLs | defaults `localhost` (un sólo entorno) | placeholders `CHANGE-ME` pisados por overlay (un dominio por entorno) |

Kubernetes separa ConfigMap vs Secret (cifrado en reposo, RBAC, rotación
independiente) y parametriza las URLs por entorno, porque cada despliegue tiene
su propio dominio. Compose sólo apunta a local, por eso un `.env` plano con
defaults alcanza.
