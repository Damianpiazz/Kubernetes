# Variables de entorno

Inventario completo de las variables de entorno del proyecto, dónde se definen
y cómo se inyectan en cada entorno (Docker Compose local, Kubernetes y
frontend).

## Dónde se definen

| Archivo | Entorno | Uso |
|---|---|---|
| `.env.example` → `.env` | Docker Compose | Variables leídas por `compose.yaml`, `compose.override.yaml` y `compose.observability.yaml` |
| `k8s/apps/environments/local/secrets.env.example` → `secrets.env` | Kubernetes local (kind/k3d) | Secretos de la app (namespace `latiendita`), inyectados como Secret `latiendita-secrets` |
| `k8s/apps/environments/production/secrets.env.example` → `secrets.env` | Kubernetes producción | Ídem, con valores reales (gitignored) |
| `k8s/observability/environments/local/secrets.env.example` → `secrets.env` | Kubernetes local | Credenciales de Grafana (namespace `latiendita-observability`) |
| `k8s/observability/environments/production/secrets.env.example` → `secrets.env` | Kubernetes producción | Ídem, con valores reales (gitignored) |
| `frontend/.env.local.example` → `.env.local` | Frontend (Next.js) | Solo `pnpm dev` local; en Docker/k8s la URL de la API entra como build-ARG del `frontend/Dockerfile` |
| ConfigMaps en `k8s/apps/base/*/configmap.yaml` | Kubernetes (todos) | Valores no secretos por servicio (ya definidos en el repo; no requieren acción) |

Los archivos `secrets.env` reales y el `.env` son **gitignored**: nunca se
commitean. Generar valores con:

```bash
bash scripts/secrets.sh <target>            # Linux/macOS
.\scripts\secrets.ps1 -Target <target>      # Windows
```

Targets: `compose`, `local`, `observability-local`, `apps`, `observability`.

---

## 1. Docker Compose (`.env`)

Archivo de referencia: `.env.example`. Docker Compose lo lee desde la raíz.

### Obligatorias (Compose falla si faltan)

| Variable | Ejemplo | Descripción |
|---|---|---|
| `AUTH_DB_PASSWORD` | `changeme-auth` | Password del container y del servicio `auth-service` |
| `USERS_DB_PASSWORD` | `changeme-users` | Ídem para `users-db` / `user-service` |
| `PRODUCTS_DB_PASSWORD` | `changeme-products` | Ídem para `products-db` / `product-service` |
| `ORDERS_DB_PASSWORD` | `changeme-orders` | Ídem para `orders-db` / `order-service` |
| `PAYMENTS_DB_PASSWORD` | `changeme-payments` | Ídem para `payments-db` / `payment-service` |
| `JWT_SECRET` | (mín. 32 chars) | Clave de firma de JWT; usada por `auth-service` y `gateway` |

### Opcionales

| Variable | Default | Descripción |
|---|---|---|
| `MERCADO_PAGO_ACCESS_TOKEN` | vacío | Token de Mercado Pago; vacío ⇒ integración deshabilitada |
| `MERCADO_PAGO_WEBHOOK_SECRET` | vacío | Secreto de verificación de webhooks de Mercado Pago |
| `MERCADO_PAGO_NOTIFICATION_URL` | `http://localhost:8080/api/webhooks/mercado-pago` | URL a la que Mercado Pago notifica los cambios de pago |
| `MERCADO_PAGO_BACK_URL_SUCCESS` | `http://localhost:3000/checkout/success` | Redirección tras pagar |
| `MERCADO_PAGO_BACK_URL_FAILURE` | `http://localhost:3000/checkout/failure` | Redirección tras un pago rechazado |
| `MERCADO_PAGO_BACK_URL_PENDING` | `http://localhost:3000/checkout/pending` | Redirección tras un pago en proceso (p. ej. débito o transferencia) |
| `MERCADO_PAGO_STATEMENT_DESCRIPTOR` | `La Tiendita` | Texto del resumen de la tarjeta del titular (reduce desconocimientos/chargebacks) |
| `CORS_ALLOWED_ORIGINS` | `*` | Orígenes permitidos por el gateway (CORS) |
| `NEXT_PUBLIC_API_URL` | `http://localhost:8080` | URL del gateway vista desde el browser; se pasa como build-ARG al frontend (ver sección 3) |
| `NEXT_PUBLIC_DATA_MODE` | `api` | Modo de datos del frontend (`api` contra el backend, `mock` sin backend); build-ARG del frontend (ver sección 3) |
| `GRAFANA_ADMIN_USER` | `admin` | Usuario admin de Grafana (`compose.observability.yaml`) |
| `GRAFANA_ADMIN_PASSWORD` | `admin` | Password admin de Grafana (cambiar fuera de desarrollo) |
| `TRACING_SAMPLING_PROBABILITY` | `0.1` | Probabilidad de muestreo de traces (0.0–1.0) |
| `PGADMIN_PASSWORD` | `changeme` | Password de pgAdmin (profile `tools`, `compose.override.yaml`) |

### Pago con Mercado Pago en local (túnel)

Las cuatro URLs anteriores tienen defaults `localhost` **sólo para que el stack
arranque sin configurar nada**: el proveedor rechaza `localhost`/`127.0.0.1`
tanto en `notification_url` como en las `back_urls`. Para probar el flujo de
verdad hace falta un túnel público que repunte a los puertos del host:

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

> Puertos, alternativa con VS Code (Remote Tunnels) y la tabla de métodos de
> exposición están en [docs/mercado-pago/mercado-pago.md](../mercado-pago/mercado-pago.md).

**Webhook secret**: se genera en *Tus integraciones → Webhooks → Configurar
notificaciones → Guardar* de Mercado Pago y va en `MERCADO_PAGO_WEBHOOK_SECRET`.
Sin él, `WebhookSignatureValidator` descarta toda notificación y el pago queda
`PENDING` para siempre. Con credenciales de **prueba**, los pagos reales no
envían notificaciones: el webhook se ensaya desde *Tus integraciones* (usa
`notifications_history` para diagnosticar entregas fallidas).

> El checkout de Mercado Pago se abre en la misma pestaña y vuelve a las
> `back_urls` con `?external_reference=<orderNumber>`. Esa referencia se traduce
> al pago con `GET /api/payments/reference/{externalReference}`.

---

## 2. Kubernetes

### 2.1 Secretos de apps (`k8s/apps/environments/<env>/secrets.env`)

Claves idénticas en `local` y `production`; el secret `latiendita-secrets` se
genera desde el archivo con `scripts/deploy.*` (via `secretGenerator` de
Kustomize). En producción todos los valores `CHANGE-ME` deben reemplazarse
(el deploy falla con exit 6 si no).

| Variable | Consumidor (env del container) | Descripción |
|---|---|---|
| `JWT_SECRET` | `JWT_SECRET` en `auth-service` y `gateway` | Firma/verificación de JWT (mín. 32 chars) |
| `AUTH_DB_PASSWORD` | `SPRING_DATASOURCE_PASSWORD` en `auth-service` | Password de la DB externa `auth` |
| `USERS_DB_PASSWORD` | `SPRING_DATASOURCE_PASSWORD` en `user-service` | Password de la DB externa `users` |
| `PRODUCTS_DB_PASSWORD` | `SPRING_DATASOURCE_PASSWORD` en `product-service` | Password de la DB externa `products` |
| `ORDERS_DB_PASSWORD` | `SPRING_DATASOURCE_PASSWORD` en `order-service` | Password de la DB externa `orders` |
| `PAYMENTS_DB_PASSWORD` | `SPRING_DATASOURCE_PASSWORD` en `payment-service` | Password de la DB externa `payments` |
| `MERCADO_PAGO_ACCESS_TOKEN` | `MERCADO_PAGO_ACCESS_TOKEN` en `payment-service` | Vacío ⇒ feature deshabilitada en local; en producción el validador del perfil `k8s` impide el arranque |
| `MERCADO_PAGO_WEBHOOK_SECRET` | `MERCADO_PAGO_WEBHOOK_SECRET` en `payment-service` | Vacío ⇒ webhooks ignorados en local; en producción, obligatorio (mismo validador) |
| `AZURE_STORAGE_KEY` | `AZURE_STORAGE_KEY` en `product-service` | Clave de cuenta del storage de imágenes (Azurite local / Azure Blob Storage en prod) |

### 2.2 Secretos de observabilidad (`k8s/observability/environments/<env>/secrets.env`)

Inyectados vía `envFrom` al Deployment de Grafana (secretRef `grafana-admin`).

| Variable | Descripción |
|---|---|
| `GF_SECURITY_ADMIN_USER` | Usuario admin de Grafana (default `admin`) |
| `GF_SECURITY_ADMIN_PASSWORD` | Password admin de Grafana |

### 2.3 ConfigMaps (no secretos, ya definidos en `k8s/apps/base/*/configmap.yaml`)

No requieren configuración manual, pero es el valor por defecto en clúster:

| Variable | Valor típico | Servicios |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://<db>:5432/<schema>` | auth, user, product, order, payment |
| `SPRING_DATASOURCE_USERNAME` | nombre de la DB | auth, user, product, order, payment |
| `KAFKA_BOOTSTRAP_SERVERS` | `kafka:9092` | auth, user, order, payment |
| `EUREKA_ENABLED` | `"false"` (en k8s) | todos |
| `OTLP_TRACING_ENDPOINT` | `http://alloy.latiendita-observability.svc.cluster.local:4318/v1/traces` | todos |
| `TRACING_SAMPLING_PROBABILITY` | `"0.1"` | todos |
| `PRODUCT_SERVICE_BASE_URL` | `http://product-service` | order, gateway |
| `ORDER_SERVICE_BASE_URL` | `http://order-service` | payment, gateway |
| `AUTH_SERVICE_BASE_URL` | `http://auth-service` | gateway |
| `USER_SERVICE_BASE_URL` | `http://user-service` | gateway |
| `PAYMENT_SERVICE_BASE_URL` | `http://payment-service` | gateway |
| `CORS_ALLOWED_ORIGINS` | `*` local / dominio exacto en producción | gateway |
| `AZURE_STORAGE_ENDPOINT` | `http://azurite:10000/devstoreaccount1` local / `https://<cuenta>.blob.core.windows.net` prod | product-service |
| `AZURE_STORAGE_ACCOUNT` | `devstoreaccount1` local / cuenta real en prod | product-service |
| `AZURE_STORAGE_CONTAINER` | `productos` | product-service |

### 2.4 Variables de los StatefulSets (fijas en los manifiestos)

- **PostgreSQL** (`k8s/data/base/postgres/*`): `PGDATA`, `POSTGRES_DB`,
  `POSTGRES_USER`, `POSTGRES_PASSWORD` (via secretKeyRef).
- **Kafka** (`k8s/data/base/kafka/statefulset.yaml`): `CLUSTER_ID`,
  `KAFKA_NODE_ID`, `KAFKA_HEAP_OPTS`, `KAFKA_PROCESS_ROLES`,
  `KAFKA_CONTROLLER_QUORUM_VOTERS`, `KAFKA_INITIAL_BROKER_REGISTRATION_TIMEOUT_MS`,
  `KAFKA_LISTENERS`, `KAFKA_ADVERTISED_LISTENERS`, `KAFKA_LOG_DIRS`,
  `KAFKA_CONTROLLER_LISTENER_NAMES`, `KAFKA_LISTENER_SECURITY_PROTOCOL_MAP`,
  `KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR`,
  `KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR`,
  `KAFKA_TRANSACTION_STATE_LOG_MIN_ISR`, `KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS`.
- **Azurite** (`k8s/data/base/azurite/statefulset.yaml`): sin env — todo por
  args fijos (`--blobHost/--queueHost/--tableHost 0.0.0.0`,
  `--location /data`); PVC `1Gi` por `volumeClaimTemplates`.
- **Grafana** (`k8s/observability/base/grafana/deployment.yaml`):
  `GF_USERS_ALLOW_SIGN_UP=false`, `GF_ANALYTICS_REPORTING_ENABLED=false`,
  `GF_ANALYTICS_CHECK_FOR_UPDATES=false`.

### 2.5 URLs de Mercado Pago en Kubernetes

Las cuatro URLs (`MERCADO_PAGO_NOTIFICATION_URL` y `MERCADO_PAGO_BACK_URL_*`) son
ConfigMap (no secretos): en `base` sólo hay placeholders `CHANGE-ME` y el valor
real se pisa en cada overlay. Ver dónde, cómo y por qué Compose y Kubernetes
difieren en [docs/mercado-pago/mercado-pago.md](../mercado-pago/mercado-pago.md).

---

## 3. Frontend (Next.js)

Archivo de referencia: `frontend/.env.local.example`. Next.js inlinea las
variables `NEXT_PUBLIC_*` en el bundle del browser en tiempo de **build**, así
que no son variables runtime (un ConfigMap/env de k8s no las cambia): hay una
vía de inyección por forma de correr el frontend.

| Dónde se buildea | Valor de `NEXT_PUBLIC_API_URL` y `NEXT_PUBLIC_DATA_MODE` |
|---|---|
| `pnpm dev` local | `.env.local` (copiado de `.env.local.example`) |
| Docker Compose / `build-images` | build-ARGs del `frontend/Dockerfile`, con defaults `http://localhost:8080` y `api` (gateway publicado en el host) |
| Kubernetes local (kind) | los mismos defaults: el browser llega al gateway por `port-forward svc/gateway 8080:80` |
| Kubernetes producción | build en el pipeline con `--build-arg NEXT_PUBLIC_API_URL=https://<dominio>` (host único de UI y API, mismo origen) |

| Variable | Default | Descripción |
|---|---|---|
| `NEXT_PUBLIC_API_URL` | `http://localhost:8080` | URL base del gateway vista desde el browser. Producción: `https://<dominio>` (el mismo host que sirve la UI y `/api`) |
| `NEXT_PUBLIC_DATA_MODE` | `api` | `api` consume los microservicios a través del gateway; `mock` corre el frontend **sin backend**: los datos de ejemplo (usuarios, catálogo, pedidos y pagos) viven en el bundle y se guardan en `localStorage`. El pago simulado se aprueba/rechaza en `/checkout/simulate`. Sirve para demo, E2E sin Docker o revisar la UI cuando el stack está caído |

Modo de datos:

- La elección es **exclusiva**: en `api` no existe ningún fallback silencioso a
  datos mock; si el backend no responde, la UI muestra el error y ofrece
  reintentar.
- En `mock`, una insignia discreta ("modo mock") queda visible en pantalla para
  no confundir datos de ejemplo con datos reales.

---

## 4. Variables de los servicios Spring (tuning)

Todas tienen default en `application.properties`; solo se sobrescriben para
ajustar o cambiar de entorno.

### Comunes a todos los servicios

| Variable | Default | Descripción |
|---|---|---|
| `SERVER_PORT` | `8080` (`8761` en eureka-server) | Puerto HTTP |
| `EUREKA_URL` | `http://localhost:8761/eureka` | URL del registro de servicios |
| `EUREKA_ENABLED` | `true` | Habilita/deshabilita Eureka (en k8s: `false`) |
| `OTLP_TRACING_ENDPOINT` | `http://localhost:4318/v1/traces` | Endpoint OTLP para traces |
| `TRACING_SAMPLING_PROBABILITY` | `0.1` | Muestreo de traces |
| `LOGGING_LEVEL_ROOT` | `INFO` | Nivel de log raíz (gateway, eureka-server) |

### Conexión a datos (auth, user, product, order, payment)

| Variable | Default | Descripción |
|---|---|---|
| `SPRING_DATASOURCE_URL` | sin default (obligatoria) | JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | sin default (obligatoria) | Usuario DB |
| `SPRING_DATASOURCE_PASSWORD` | sin default (obligatoria) | Password DB |

### Kafka y Outbox (auth, user, order, payment)

| Variable | Default | Descripción |
|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Brokers Kafka |
| `OUTBOX_RELAY_POLL_INTERVAL_MS` | `1000` | Intervalo de polling del relay outbox |
| `OUTBOX_RELAY_BATCH_SIZE` | `50` | Tamaño de lote del relay outbox |
| `OUTBOX_RELAY_MAX_ATTEMPTS` | `10` | Reintentos máximos del relay outbox |

### Seguridad JWT (auth-service y gateway)

| Variable | Default | Descripción |
|---|---|---|
| `JWT_SECRET` | sin default (obligatoria) | Clave de firma (mín. 32 chars) |
| `JWT_ISSUER` | `auth-service` | Issuer del token |
| `JWT_ACCESS_TTL_SECONDS` | `900` | TTL del access token (auth-service) |
| `JWT_REFRESH_TTL_DAYS` | `7` | TTL del refresh token (auth-service) |

### Gateway

| Variable | Default | Descripción |
|---|---|---|
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000` | Orígenes CORS permitidos |
| `CORS_ALLOWED_METHODS` | `GET,POST,PUT,PATCH,DELETE,OPTIONS` | Métodos CORS |
| `CORS_ALLOWED_HEADERS` | `Authorization,Content-Type,X-Correlation-Id` | Headers CORS |
| `GATEWAY_CONNECT_TIMEOUT_MS` | `2000` | Timeout de conexión hacia backends |
| `GATEWAY_READ_TIMEOUT_MS` | `10000` | Timeout de lectura hacia backends |
| `GATEWAY_MAX_REQUEST_SIZE` | `1048576` | Tamaño máximo de request (bytes) |
| `AUTH_SERVICE_BASE_URL` | `lb://authService` | Override de ruta (sin Eureka) |
| `USER_SERVICE_BASE_URL` | `lb://userService` | Ídem |
| `PRODUCT_SERVICE_BASE_URL` | `lb://productService` | Ídem |
| `ORDER_SERVICE_BASE_URL` | `lb://orderService` | Ídem |
| `PAYMENT_SERVICE_BASE_URL` | `lb://paymentService` | Ídem |

### order-service / payment-service (clientes HTTP y Resilience4j)

| Variable | Default | Descripción |
|---|---|---|
| `PRODUCT_SERVICE_BASE_URL` | `http://localhost:8080` | URL base de product-service (order) |
| `ORDER_SERVICE_BASE_URL` | `http://localhost:8080` | URL base de order-service (payment) |
| `HTTP_CLIENT_CONNECT_TIMEOUT_MS` | `2000` | Timeout de conexión |
| `HTTP_CLIENT_READ_TIMEOUT_MS` | `3000` | Timeout de lectura |
| `RESILIENCE_MAX_ATTEMPTS` | `3` | Intentos del circuit breaker |
| `RESILIENCE_WAIT_DURATION` | `200ms` | Espera entre reintentos |
| `RESILIENCE_WAIT_DURATION_MULTIPLIER` | `2.0` | Backoff exponencial |
| `RESILIENCE_FAILURE_RATE_THRESHOLD` | `50` | Umbral de fallos (%) para abrir el circuito |
| `RESILIENCE_SLIDING_WINDOW_SIZE` | `10` | Ventana deslizante de llamadas |
| `RESILIENCE_MINIMUM_NUMBER_OF_CALLS` | `5` | Mínimo de llamadas antes de evaluar |
| `RESILIENCE_WAIT_DURATION_IN_OPEN_STATE` | `10s` | Tiempo en estado abierto |

### payment-service (Mercado Pago)

| Variable | Default | Descripción |
|---|---|---|
| `PAYMENT_PROVIDER` | `MERCADO_PAGO` | Proveedor de pagos |
| `PAYMENT_DEFAULT_CURRENCY` | `ARS` | Moneda por defecto |
| `MERCADO_PAGO_ACCESS_TOKEN` | vacío | Token de acceso |
| `MERCADO_PAGO_WEBHOOK_SECRET` | vacío | Secreto del webhook |
| `MERCADO_PAGO_NOTIFICATION_URL` | `http://localhost:8080/api/webhooks/mercado-pago` | URL pública de notificaciones |
| `MERCADO_PAGO_BACK_URL_SUCCESS` | `http://localhost:3000/checkout/success` | Redirección: éxito |
| `MERCADO_PAGO_BACK_URL_FAILURE` | `http://localhost:3000/checkout/failure` | Redirección: fallo |
| `MERCADO_PAGO_BACK_URL_PENDING` | `http://localhost:3000/checkout/pending` | Redirección: pendiente |
| `MERCADO_PAGO_STATEMENT_DESCRIPTOR` | `La Tiendita` | `statement_descriptor` de la preferencia (resumen de la tarjeta) |

---

## 5. Scripts (`scripts/*.sh` / `*.ps1`)

| Variable | Default | Descripción |
|---|---|---|
| `SECRETS_FILE` | `./secrets.env` | Archivo de secretos por defecto para `deploy.*` (flag `-s` lo sobrescribe) |
| `NO_COLOR` | sin valor | Cualquier valor desactiva colores en salida |
| `TERM` | — | `dumb` también desactiva colores |
