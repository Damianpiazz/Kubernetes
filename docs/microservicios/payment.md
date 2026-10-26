# Microservicio: `paymentService`

## 1. Responsabilidad

Gestiona el ciclo de vida de los cobros de la plataforma de e-commerce mediante **Mercado Pago Checkout Pro**: crea la preferencia de pago para un pedido, devuelve la URL de redirección al checkout alojado, recibe las notificaciones (webhooks), valida su autenticidad, consulta al proveedor para obtener el estado autoritativo y actualiza el pago. Es la única fuente de verdad sobre pagos.

Actúa como **adaptador de proveedor**: el resto del sistema no depende de los detalles particulares de Mercado Pago, solo de la interfaz `PaymentGateway`.

El estado del pedido no le pertenece: `order-service` lo transiciona consumiendo el evento `PAYMENT_APPROVED`.

No le pertenece: procesar el pago en sí (lo hace Mercado Pago en su checkout alojado), el catálogo de productos, usuarios/perfiles, el estado del pedido ni los reembolsos (*pendiente*).

---

## 2. Endpoints

Todas las rutas son relativas a la raíz del servicio (ej. `http://payments:8080`).

### 2.1 Pagos — `PaymentController`

| Método | Ruta | Descripción | Request | Response | Auth |
|---|---|---|---|---|---|
| `GET` | `/actuator/health` | Health check para probes de Kubernetes | — | `200` `{ status }` | No |
| `POST` | `/api/payments` | Crea el pago y la preferencia de Checkout Pro | `CreatePaymentRequest` | `201` PaymentResponse + `Location: /api/payments/{id}` | Sí (Gateway) |
| `GET` | `/api/payments` | Lista los pagos del cliente autenticado (`page`, `size`, `sort`) | — | `200` Page\<PaymentResponse\> | Sí (Gateway) |
| `GET` | `/api/payments/{paymentId}` | Obtiene un pago por su ID (solo si es del cliente) | — | `200` PaymentResponse | Sí (Gateway) |
| `GET` | `/api/payments/order/{orderId}` | Obtiene el pago de un pedido (solo si es del cliente) | — | `200` PaymentResponse | Sí (Gateway) |
| `GET` | `/api/payments/reference/{externalReference}` | Obtiene el pago por su `external_reference` (= `orderNumber`) | — | `200` PaymentResponse | Sí (Gateway) |

### 2.2 Webhooks — `WebhookController`

| Método | Ruta | Descripción | Request | Response | Auth |
|---|---|---|---|---|---|
| `POST` | `/api/webhooks/mercado-pago` | Notificación de Mercado Pago | `WebhookNotification` | `200` | **No** (firma `x-signature`) |

> **Nota de auth:** el servicio no tiene dependencia de Spring Security ni valida JWT (decisión de diseño: el Gateway lo hace). La identidad del cliente llega resuelta en el header **`X-User-Id`** que inyecta el Gateway; el servicio lo usa como `customerId` y para verificar ownership. Si el header falta o no es numérico → `401` (la petición no pasó por el Gateway).
>
> El webhook es **público**: lo invoca Mercado Pago directamente, no pasa por el Gateway. Su autenticidad se valida con el header `x-signature` (HMAC-SHA256, ver sección 6.3).

**DTOs:**

```json
// CreatePaymentRequest — el cliente solo dice QUÉ pedido pagar.
// El monto NO se envía: se toma de order-service (evita montos falsificados).
{ "orderId": 7, "payerEmail": "ana@mail.com" }

// PaymentResponse (checkoutUrl = init_point de Mercado Pago)
{ "id": 1, "orderId": 7, "orderNumber": "0b7e6f4c-...", "customerId": 1,
  "provider": "MERCADO_PAGO", "status": "PENDING",
  "amount": 2049.48, "currency": "ARS",
  "preferenceId": "152454022-90e74b33-...", "checkoutUrl": "https://www.mercadopago.com.ar/checkout/v1/redirect?pref_id=...",
  "paymentMethodId": null, "paymentType": null, "statusDetail": null, "paidAt": null,
  "createdAt": "2026-10-11T21:00:00Z", "updatedAt": "2026-10-11T21:00:00Z" }

// WebhookNotification (formato Webhooks de MP; el formato IPN legacy llega por query params)
{ "action": "payment.updated", "type": "payment", "id": 123456789,
  "data": { "id": "1234567890" } }
```

### 2.3 Flujo Checkout Pro

```
Frontend ──POST /api/payments──▶ payment-service ──PreferenceClient──▶ Mercado Pago
                                        │                                    │
                                        │◀── preferenceId + initPoint ───────┘
   ◀── { checkoutUrl } ────────────────┘
   │
   └──▶ redirect ──▶ checkout alojado de MP ──▶ usuario paga
                                                            │
   Mercado Pago ──POST /api/webhooks/mercado-pago────────────┘
        │
        ▼
   payment-service valida firma x-signature
        └──▶ PaymentClient.get(id)  → estado autoritativo
                └──▶ update payment + outbox ──▶ Kafka `payment-events`
                                                    └──▶ order-service → PENDING→PAID
```

### 2.4 Qué se envía a la preferencia (calidad de integración)

`MercadoPagoAdapter.createPreference()` arma la preferencia con:

| Campo | Valor | Origen |
|---|---|---|
| `items[].id` | `productId` | `order-service` (conciliación contra nuestro catálogo) |
| `items[].title` | `productTitle` | `order-service` |
| `items[].description` | `productTitle` | `order-service` no expone descripciones largas |
| `items[].quantity` / `unit_price` | ítems del pedido | `order-service` |
| `items[].currency_id` | `currency` del request | Moneda del pedido; vacío ⇒ omitido y MP usa la moneda de la cuenta |
| `payer.email` | `payerEmail` del request | Frontend, del `email` del JWT |
| `external_reference` | `orderNumber` | Único eslabón entre Mercado Pago y nuestro pago |
| `back_urls.*` / `notification_url` | Configuración por entorno | `MERCADO_PAGO_*_URL` |
| `statement_descriptor` | `MERCADO_PAGO_STATEMENT_DESCRIPTOR` | Resumen de la tarjeta del titular |
| `auto_return` | `approved` | Condicional: sólo si `back_urls.success` está configurado (MP devuelve 400 sin ella) |

Pendiente de sumar (mejoran el puntaje del *checklist de calidad*): `payer.last_name`
(ya que `CreatePaymentRequest` sólo recibe el email) y `items.category_id` (no
hay categoría en `order-service`). La numeración que manda Mercado Pago para
`items[].id` usa `productId`; con `sku` sería más estable, pero `order-service`
no lo expone en su `OrderItemDto`.

---

## 3. Errores

Formato de respuesta de error: **ProblemDetail (RFC 7807)**, devuelto por `GlobalExceptionHandler`:

```json
{
  "type": "about:blank",
  "title": "Pago no encontrado",
  "status": 404,
  "detail": "Pago no encontrado con id 42"
}
```

| Código | Error | Cuándo ocurre | Ejemplo de mensaje |
|---|---|---|---|
| `400` | Bad Request | Validación de campos falla (va además la lista `errors`) | `payerEmail: debe ser un correo válido` |
| `400` | Bad Request | El pedido no está pendiente de pago (`CANCELLED`, `SHIPPED`, `DELIVERED`) | `El pedido 7 no está pendiente de pago (estado actual: SHIPPED)` |
| `400` | Bad Request | El monto informado por el proveedor no coincide con el registrado | `El monto informado por el proveedor (1.00) no coincide con el monto registrado (2049.48)` |
| `401` | Unauthorized | Falta o es inválido el header `X-User-Id` (no pasó por el Gateway) | `Falta o es inválido el header 'X-User-Id'; la petición debe pasar por el Gateway` |
| `403` | Forbidden | El pago existe pero pertenece a otro cliente | `No tiene permiso sobre el pago 1` |
| `404` | Not Found | Pago inexistente | `Pago no encontrado con id 1` |
| `404` | Not Found | Pedido inexistente en `order-service` | `Pedido no encontrado con id 7` |
| `409` | Conflict | El pedido ya tiene un pago aprobado | `El pedido 7 ya tiene un pago aprobado` |
| `409` | Conflict | Carrera entre escrituras (`@version`) | `El recurso fue modificado por otra operación; reintente la petición` |
| `409` | Conflict | Violación de restricción única (carrera entre peticiones simultáneas) | `Violación de restricción única (posible carrera entre peticiones simultáneas)` |
| `500` | Internal Error | Fallo inesperado | `Unexpected error` |
| `503` | Service Unavailable | `order-service` caído al crear el pago (tras agotar reintentos o con el circuito abierto) | `El servicio de pedidos no está disponible` |
| `503` | Service Unavailable | Mercado Pago caído o con error | `El proveedor de pagos respondió 500` |
| `503` | Service Unavailable | `MERCADO_PAGO_ACCESS_TOKEN` no configurado | `MERCADO_PAGO_ACCESS_TOKEN no está configurado` |

> El webhook **siempre** responde `200` aunque el procesamiento falle, para que Mercado Pago no entre en un bucle de reintentos. El error queda registrado en la tabla `payment_events` (columna `last_error`).

---

## 4. Modelo de datos (Entidad)

### 4.1 Entidades

#### `PaymentEntity` → tabla `payments`

| Campo | Tipo | PK | Nullable | Unique | Descripción |
|---|---|---|---|---|---|
| `id` | `bigint` (autoincrement) | ✅ | ❌ | ✅ | Identificador interno |
| `order_id` | `bigint` | ❌ | ❌ | ❌ | Pedido en `order-service` (FK lógica, sin constraint cross-service) |
| `customer_id` | `bigint` | ❌ | ❌ | ❌ | Cliente = header `X-User-Id` (id de cuenta en `auth-service`) |
| `provider` | `varchar(20)` enum | ❌ | ❌ | ❌ | `MERCADO_PAGO` (hoy); preparado para otros proveedores |
| `status` | `varchar(20)` enum | ❌ | ❌ | ❌ | Ver 4.3 |
| `amount` | `numeric(12,2)` | ❌ | ❌ | ❌ | Total cobrado, congelado desde `order.total` |
| `currency` | `varchar(3)` | ❌ | ❌ | ❌ | `ARS` |
| `preference_id` | `varchar(64)` | ❌ | ✅ | ❌ | Id de la preferencia en Mercado Pago |
| `checkout_url` | `varchar(500)` | ❌ | ✅ | ❌ | `init_point` (URL del checkout alojado) |
| `external_reference` | `varchar(36)` | ❌ | ✅ | ❌ | `orderNumber`; clave de idempotencia del lado de Mercado Pago y **vía de localización en el primer webhook** (ver 4.3) |
| `idempotency_key` | `varchar(64)` | ❌ | ❌ | ✅ | Anti doble cobro; hoy = `orderNumber` |
| `provider_payment_id` | `varchar(64)` | ❌ | ✅ | ✅ | Único por `(provider, provider_payment_id)`; llega con el webhook |
| `payment_method_id` | `varchar(40)` | ❌ | ✅ | ❌ | `visa`, `master`, `pix`... |
| `payment_type` | `varchar(30)` | ❌ | ✅ | ❌ | `credit_card`, `debit_card`, `bank_transfer`... |
| `status_detail` | `varchar(100)` | ❌ | ✅ | ❌ | `status_detail` de Mercado Pago (ej. `cc_rejected_insufficient_amount`) |
| `paid_at` | `timestamptz` | ❌ | ✅ | ❌ | Seteado al aprobarse |
| `expires_at` | `timestamptz` | ❌ | ✅ | ❌ | Expiración de la preferencia (*pendiente*) |
| `created_at` / `updated_at` | `timestamptz` | ❌ | ❌ | ❌ | Auditoría |
| `version` | `bigint` | ❌ | ❌ | ❌ | Control de concurrencia optimista (`@Version`) |

#### `PaymentEventEntity` → tabla `payment_events` (inbox)

Complemento del outbox. Registra **cada** notificación recibida: es lo que aporta deduplicación auditable.

La deduplicación tiene **semántica de reintento**: como el webhook siempre responde `200`, Mercado Pago no vuelve a enviar lo que ya recibió. Por eso un duplicado cuyo intento anterior quedó en `FAILED` o `RECEIVED` (a medias) se vuelve a procesar, y solo se omiten los que ya terminaron en `PROCESSED` o `IGNORED`.

| Campo | Tipo | PK | Nullable | Unique | Descripción |
|---|---|---|---|---|---|
| `id` | `bigint` (autoincrement) | ✅ | ❌ | ✅ | Identificador del evento |
| `provider` | `varchar(20)` enum | ❌ | ❌ | ❌ | `MERCADO_PAGO` |
| `notification_id` | `varchar(128)` | ❌ | ❌ | ✅ | Único por `(provider, notification_id)` = **dedup** |
| `resource_type` | `varchar(20)` | ❌ | ❌ | ❌ | `payment`, `merchant_order`... |
| `resource_id` | `varchar(64)` | ❌ | ❌ | ❌ | `data.id` de Mercado Pago |
| `action` | `varchar(50)` | ❌ | ✅ | ❌ | `payment.updated` |
| `signature_valid` | `boolean` | ❌ | ❌ | ❌ | Resultado de validar `x-signature` |
| `status` | `varchar(20)` enum | ❌ | ❌ | ❌ | `RECEIVED` → `PROCESSED` / `IGNORED` / `FAILED` |
| `attempts` / `last_error` | `int` / `varchar(500)` | ❌ | `last_error` nullable | ❌ | Auditoría de reintento |
| `created_at` / `processed_at` | `timestamptz` | ❌ | `processed_at` nullable | ❌ | Auditoría |

#### `OutboxEvent` → tabla `outbox_events`

Idéntico al de `order-service`. Topic: **`payment-events`**.

### 4.2 Relaciones

| Relación | Tipo | Descripción |
|---|---|---|
| — | — | — |

No hay relaciones JPA en este servicio. `order_id` referencia al `id` del pedido en `order-service` y `outbox_events` / `payment_events` son tablas técnicas, sin FK.

### 4.3 Máquina de estados — `PaymentStatus`

| Estado | Rank | Descripción |
|---|---|---|
| `PENDING` | 0 | Preferencia creada, esperando el pago en el checkout |
| `REJECTED` | 1 | Mercado Pago rechazó el cobro |
| `CANCELLED` | 1 | Pago cancelado |
| `EXPIRED` | 1 | La preferencia o el pago expiraron |
| `APPROVED` | 2 | Cobro confirmado; **terminal** en esta versión |

Mapeo desde Mercado Pago (`MercadoPagoStatusMapper`):

| Status de Mercado Pago | Status propio |
|---|---|
| `approved` | `APPROVED` |
| `pending`, `authorized`, `in_process`, `in_mediation` | `PENDING` |
| `rejected` | `REJECTED` |
| `cancelled` | `CANCELLED` |
| `expired` | `EXPIRED` |

Reglas:

| Regla | Detalle |
|---|---|
| Transiciones | `PENDING` → cualquier estado; `REJECTED`/`CANCELLED`/`EXPIRED` → solo `APPROVED`; `APPROVED` es terminal |
| Localización del pago | El webhook trae el id del pago en Mercado Pago, que **aún no está registrado** en el primer aviso. Se busca por `(provider, provider_payment_id)` y, si no existe, por `external_reference` (= `orderNumber`); recién ahí se guarda el `provider_payment_id` para las notificaciones siguientes |
| Fuera de orden | Una notificación solo aplica si `canTransitionTo()` lo permite. Si llega `pending` después de `approved`, se ignora |
| Doble cobro | Un solo pago activo (`PENDING`/`APPROVED`) por `order_id`; si ya existe → `409` |
| Monto | `amount` se congela desde `order.total` al crear; se valida contra el `transaction_amount` devuelto por el proveedor |
| Identidad | `customer_id` = header `X-User-Id`; sin header → `401` |
| Ownership | `GET /{id}`, `/order/{orderId}` verifican que el pago pertenezca al cliente; si no → `403` |
| Webhook | Autenticado por firma HMAC-SHA256; si es inválida se registra y se ignora |
| Concurrencia | `@Version` → conflicto simultáneo responde `409` |
| Esquema | `ddl-auto=update` (JPA crea/ajusta el esquema; no hay Flyway) |

---

## 5. Configuración

### 5.1 Variables de entorno

| Variable | Descripción | Requerida | Default | Ejemplo |
|---|---|---|---|---|
| `SERVER_PORT` | Puerto HTTP del servicio | ❌ | `8080` | `8080` |
| `SPRING_DATASOURCE_URL` | JDBC URL de la base de datos | ✅ | — | `jdbc:postgresql://db:5432/payments` |
| `SPRING_DATASOURCE_USERNAME` | Usuario de la base de datos | ✅ | — | `payments` |
| `SPRING_DATASOURCE_PASSWORD` | Password de la base de datos | ✅ | — | *(secret)* |
| `KAFKA_BOOTSTRAP_SERVERS` | Brokers de Kafka para el outbox | ❌ | `localhost:9092` | `kafka:9092` |
| `OUTBOX_RELAY_POLL_INTERVAL_MS` | Intervalo del relay (ms) | ❌ | `1000` | `1000` |
| `OUTBOX_RELAY_BATCH_SIZE` | Lote del relay por ciclo | ❌ | `50` | `50` |
| `OUTBOX_RELAY_MAX_ATTEMPTS` | Intentos máximos de publicación por evento | ❌ | `10` | `10` |
| `ORDER_SERVICE_BASE_URL` | Base URL de `order-service` para validar el pedido | ❌ | `http://localhost:8080` | `http://order-service` |
| `HTTP_CLIENT_CONNECT_TIMEOUT_MS` | Timeout de conexión del client HTTP a `order-service` | ❌ | `2000` | `2000` |
| `HTTP_CLIENT_READ_TIMEOUT_MS` | Timeout de lectura del client HTTP a `order-service` | ❌ | `3000` | `3000` |
| `RESILIENCE_MAX_ATTEMPTS` | Intentos totales por llamada (reintento incluido) | ❌ | `3` | `3` |
| `RESILIENCE_WAIT_DURATION` | Espera base entre reintentos (backoff exponencial) | ❌ | `200ms` | `200ms` |
| `RESILIENCE_WAIT_DURATION_MULTIPLIER` | Multiplicador del backoff exponencial | ❌ | `2.0` | `2.0` |
| `RESILIENCE_FAILURE_RATE_THRESHOLD` | Tasa de fallos (%) que abre el circuit breaker | ❌ | `50` | `50` |
| `RESILIENCE_SLIDING_WINDOW_SIZE` | Ventana deslizante del circuit breaker | ❌ | `10` | `10` |
| `RESILIENCE_MINIMUM_NUMBER_OF_CALLS` | Mínimo de llamadas antes de evaluar la tasa de fallos | ❌ | `5` | `5` |
| `RESILIENCE_WAIT_DURATION_IN_OPEN_STATE` | Tiempo en estado abierto antes de half-open | ❌ | `10s` | `10s` |
| `PAYMENT_PROVIDER` | Proveedor activo | ❌ | `MERCADO_PAGO` | `MERCADO_PAGO` |
| `PAYMENT_DEFAULT_CURRENCY` | Moneda por defecto | ❌ | `ARS` | `ARS` |
| `MERCADO_PAGO_ACCESS_TOKEN` | Access Token de Mercado Pago | ✅ en producción | *(vacío → 503)* | `TEST-xxxxxxxx` |
| `MERCADO_PAGO_WEBHOOK_SECRET` | Secreto para validar `x-signature` | ✅ | *(vacío → webhook ignorado)* | *(secret)* |
| `MERCADO_PAGO_NOTIFICATION_URL` | URL pública del webhook | ✅ en producción | `http://localhost:8080/api/webhooks/mercado-pago` | `https://api.tienda.com/api/webhooks/mercado-pago` |
| `MERCADO_PAGO_BACK_URL_SUCCESS` | `back_urls.success` | ❌ | `http://localhost:3000/checkout/success` | `https://tienda.com/checkout/success` |
| `MERCADO_PAGO_BACK_URL_FAILURE` | `back_urls.failure` | ❌ | `http://localhost:3000/checkout/failure` | `https://tienda.com/checkout/failure` |
| `MERCADO_PAGO_BACK_URL_PENDING` | `back_urls.pending` | ❌ | `http://localhost:3000/checkout/pending` | `https://tienda.com/checkout/pending` |
| `MERCADO_PAGO_STATEMENT_DESCRIPTOR` | `statement_descriptor` de la preferencia | ❌ | `La Tiendita` | `La Tiendita` |
| `EUREKA_URL` | Registry de Eureka para registrarse | ❌ | `http://localhost:8761/eureka` | `http://eureka:8761/eureka` |
| `EUREKA_ENABLED` | Habilita el cliente Eureka (`false` para correr sin registry) | ❌ | `true` | `true` |
| `LOGGING_LEVEL_ROOT` | Nivel de log | ❌ | `INFO` | `INFO` |

> Las `back_urls` vienen **de configuración**, nunca del request: el cliente solo envía `orderId`, lo que elimina el riesgo de open redirect / phishing.
>
> El access token se inicializa de forma **perezosa** en cada llamada (no en `@PostConstruct`): el servicio arranca y responde `/actuator/health` aunque no haya credenciales, y solo devuelve `503` cuando alguien intenta realmente usar el proveedor.
>
> **Mercado Pago rechaza `localhost` y `127.0.0.1`** tanto en `notification_url` como en las `back_urls`. Los defaults `localhost` de arriba sólo sirven para que el servicio arranque sin config; para probar el flujo de verdad hay que apuntar esas cuatro variables a un túnel público (`ngrok`/`cloudflared`) — ver *Pago con Mercado Pago en local* en `docs/environment/enviroment.md`.

### 5.2 Spring profiles

| Profile | Uso |
|---|---|
| `test` | Tests automatizados (Testcontainers PostgreSQL + EmbeddedKafka; `MercadoPagoAdapter` y `OrderClient` mockeados con `@MockitoBean`; relay congelado con `poll-interval-ms=600000`) |
| `local` | Desarrollo con `docker compose`: toda la config llega por variables del `.env` (ver `docs/environment/enviroment.md`). **Opcional**: sin `MERCADO_PAGO_ACCESS_TOKEN` el servicio arranca igual y sólo responde `503` al crear un pago |
| `k8s` | Despliegue en Kubernetes: manifiestos en `k8s/apps/base/payment-service` + environments. Sin perfil `application-k8s.properties`; la config se inyecta por ConfigMap y Secret |

> **Kubernetes y Eureka**: en k8s este servicio corre con `EUREKA_ENABLED=false` (ver `k8s/apps/base/payment-service/configmap.yaml`). No hay registro en Eureka ni descubrimiento `lb://`: el tráfico entra por el Gateway, que apunta a una URL directa (`PAYMENT_SERVICE_BASE_URL=http://payment-service`), y este servicio llama a `order-service` vía `ORDER_SERVICE_BASE_URL=http://order-service`. `eureka-server` **no se despliega** en el clúster (solo aplica a desarrollo local).

> No existe `application-{profile}.properties`: la config de test se inyecta por anotaciones.

### 5.3 Recursos Kubernetes (orientativo)

| Recurso | Request | Limit | Notas |
|---|---|---|---|
| CPU | `250m` | `1000m` | — |
| Memoria | `512Mi` | `768Mi` | JVM con `-XX:MaxRAMPercentage=75` |

---

## 6. Dependencias

### 6.1 Dependencias internas (otros microservicios)

| Servicio | Para qué | Protocolo | Fallback si cae | Crítica |
|---|---|---|---|---|
| `order-service` | Verificar que el pedido existe, es del cliente y está `PENDING`; obtener el `total` y los ítems | HTTP/REST (`GET /api/orders/{id}`) | Reintenta 3 veces (backoff exponencial) y abre un circuit breaker tras el 50% de fallos; si se agota → `503` al crear el pago (las consultas de pagos existentes siguen funcionando) | ✅ Sí (solo al crear) |

> La identidad del cliente no implica dependencia de `auth-service`: llega resuelta en el header `X-User-Id` que inyecta el Gateway y se reenvía a `order-service`.

### 6.2 Dependencias externas

| Dependencia | Tipo | Para qué | Crítica | Comportamiento si cae |
|---|---|---|---|---|
| PostgreSQL | Base de datos | Pagos, inbox de notificaciones y outbox | ✅ Sí | `503`, readiness en `DOWN` |
| Kafka | Mensajería | Publicación de eventos de dominio | ❌ No | El outbox retiene eventos (`PENDING`) y el relay los reintentará al volver |
| Mercado Pago API | Terceros | Crear preferencias y consultar pagos | ✅ Sí (solo al crear y al recibir webhooks) | `503` con circuit natural; el servicio sigue levantado |

### 6.3 Eventos publicados/consumidos

Topic publicado: **`payment-events`** (Kafka). **Clave del mensaje = `orderId`** (no `paymentId`): Kafka garantiza orden por partición, y así todos los eventos de un mismo pedido caen en la misma partición y `order-service` los procesa en orden. Publicados mediante **outbox transaccional** (misma patrón que `order-service`: escritura en la misma transacción, relay con `FOR UPDATE SKIP LOCKED`, reintenta `PENDING` y `FAILED` hasta `outbox.relay.max-attempts`).

| Evento | Dirección | Trigger | Payload |
|---|---|---|---|
| `PAYMENT_CREATED` | Publica | Se creó la preferencia | `{ type, paymentId, orderId, orderNumber, customerId, amount, currency, occurredAt }` |
| `PAYMENT_APPROVED` | Publica | Webhook confirma el cobro | `{ type, paymentId, orderId, orderNumber, customerId, amount, currency, provider, providerPaymentId, occurredAt }` |
| `PAYMENT_REJECTED` | Publica | Pago rechazado | `{ type, paymentId, orderId, customerId, statusDetail, occurredAt }` |
| `PAYMENT_CANCELLED` | Publica | Pago cancelado | `{ type, paymentId, orderId, customerId, occurredAt }` |
| — | Consume | — | — |

> Cada payload incluye un campo `type`: el topic transporta varios tipos de evento y el consumidor debe discriminar antes de deserializar.

**Validación de la firma del webhook:** Mercado Pago envía el header `x-signature` con formato `ts=<epoch>,v1=<hex>`. Se calcula `HMAC_SHA256(MERCADO_PAGO_WEBHOOK_SECRET, "id:{data.id};request-id:{x-request-id};ts:{ts};")` y se compara con `v1` usando `MessageDigest.isEqual` (comparación constante en tiempo). Se soportan ambos formatos de notificación: **Webhooks** (body JSON) e **IPN legacy** (query params `?type=payment&data.id=123`).

---

## 7. Tests

| Archivo | Tipo | Cantidad | Cubre |
|---|---|---|---|
| `PaymentServiceTest` | Unit (Mockito) | 14 | create con preferencia + evento en outbox, pedido no `PENDING` → 400, pedido `PAID` → 409, pedido ajeno → 404, fallo del proveedor → 503, `applyFromProvider` aprobado y rechazado, notificación obsoleta ignorada, monto que no coincide → 400, recurso del proveedor desconocido → 404, ownership en `findById`/`findByOrderId` |
| `OrderClientTest` | Unit (MockRestServiceServer) | 5 | 200 → DTO, 404 → `OrderNotFoundException` sin reintento, 5xx reintenta 3 veces y agota → 503, reintento recuperado (500 → 200), circuito abierto → 503 sin llamada HTTP |
| `PaymentStatusTest` | Unit | 6 | Transiciones completas de la máquina de estados, `APPROVED` terminal, `rank()`, `isFinal()` |
| `MercadoPagoStatusMapperTest` | Unit | 7 | Mapeo de todos los status de Mercado Pago, default a `PENDING` y mayúsculas |
| `WebhookSignatureValidatorTest` | Unit | 9 | Firma válida, secreto incorrecto, `resourceId`/`ts`/`request-id` alterados, header ausente o malformado, secreto sin configurar, `resourceId` nulo |
| `WebhookServiceTest` | Unit | 7 | Procesado válido, firma inválida ignorada, duplicado omitido, tipo de recurso no soportado, fallback a query params, sin recurso, fallo registrado sin romper el `200` |
| `PaymentApiIntegrationTest` | Integración (Testcontainers PostgreSQL) | 17 | 201 + `Location` + `checkoutUrl`, 400 validación, 401 sin `X-User-Id`, 404, 403, 409, 503, paginación por cliente, atomicidad del outbox, webhook con firma válida aprueba, firma inválida ignora, fallo del proveedor aun así responde 200, duplicado registrado una sola vez |
| `PaymentMessagingIntegrationTest` | Integración (Testcontainers PostgreSQL + EmbeddedKafka) | 2 | outbox → relay → `PAYMENT_CREATED` en Kafka con clave = `orderId`; webhook → outbox → relay → `PAYMENT_APPROVED` en Kafka |
| `PaymentServiceApplicationTests` | Context (Testcontainers PostgreSQL) | 1 | Arranque de la aplicación |

> `MercadoPagoAdapter` y `OrderClient` se mockean con `@MockitoBean`: no se llama a Mercado Pago real ni a `order-service` real en los tests. El relay automático se congela (`poll-interval-ms=600000`) y se invoca manualmente para que las aserciones sean determinísticas.

```bash
./mvnw test
```
