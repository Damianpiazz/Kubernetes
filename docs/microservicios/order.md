# Microservicio: `orderService`

## 1. Responsabilidad

Gestiona los pedidos de la plataforma de e-commerce: creación con sus ítems (producto, cantidad, precio unitario histórico y subtotal), cálculo del total en servidor, máquina de estados, consulta y cancelación con reglas de negocio (reembolso si ya se pagó). Es la única fuente de verdad sobre pedidos.

El precio, título y SKU de cada ítem se congelan al momento de la compra consultando `product-service`; el pedido es inmutable aunque el producto cambie o se elimine después.

El estado `PAID` lo fija este servicio **asincrónicamente**, consumiendo el evento `PAYMENT_APPROVED` de `payment-service`.

No le pertenece: autenticación/autorización (el Gateway valida el JWT e inyecta el header `X-User-Id`), catálogo de productos (`product-service`), usuarios/perfiles, procesamiento de pagos/reembolsos (solo emite el evento `REFUND_REQUESTED`) ni la decisión de si un pago fue aprobado (eso lo determina `payment-service`).

---

## 2. Endpoints

Todas las rutas son relativas a la raíz del servicio (ej. `http://orders:8080`).

| Método | Ruta | Descripción | Request | Response | Auth |
|---|---|---|---|---|---|
| `GET` | `/actuator/health` | Health check para probes de Kubernetes | — | `200` `{ status }` | No |
| `POST` | `/api/orders` | Crea un pedido con sus ítems | `CreateOrderRequest` | `201` OrderResponse + `Location: /api/orders/{id}` | Sí (Gateway) |
| `GET` | `/api/orders/{orderId}` | Obtiene un pedido por su ID (solo si es del cliente) | — | `200` OrderResponse | Sí (Gateway) |
| `GET` | `/api/orders` | Lista los pedidos del cliente autenticado (`page`, `size`, `sort`) | — | `200` Page\<OrderResponse\> | Sí (Gateway) |
| `POST` | `/api/orders/{orderId}/status` | Cambia el estado según la máquina de estados | `UpdateOrderStatusRequest` | `200` OrderResponse | Sí (Gateway) |
| `POST` | `/api/orders/{orderId}/cancel` | Cancela un pedido (`PENDING`/`PAID`); si era `PAID` requiere reembolso | `CancelOrderRequest` | `200` OrderResponse | Sí (Gateway) |

**DTOs:**

```json
// CreateOrderRequest (el cliente NO envía precios ni títulos; el total es opcional y se valida)
{ "items": [ { "productId": 42, "quantity": 2 }, { "productId": 7, "quantity": 1 } ],
  "total": 2049.48 }

// UpdateOrderStatusRequest ("CANCELLED" no está permitido acá; usar /cancel)
{ "status": "PAID" }

// CancelOrderRequest
{ "reason": "Me arrepentí de la compra" }

// OrderItemResponse (unitPrice y subtotal calculados por el servidor)
{ "productId": 42, "productTitle": "Notebook Lenovo", "sku": "NB-LEN-001",
  "quantity": 2, "unitPrice": 999.99, "subtotal": 1999.98 }

// OrderResponse
{ "id": 7, "orderNumber": "0b7e6f4c-...", "customerId": 1, "status": "PENDING",
  "total": 2049.48, "refundRequired": false, "cancelReason": null,
  "items": [ /* OrderItemResponse[] */ ],
  "createdAt": "2026-10-11T21:00:00Z", "updatedAt": "2026-10-11T21:00:00Z" }
```

> **Nota de auth:** el servicio no tiene dependencia de Spring Security ni valida JWT (decisión de diseño: el Gateway lo hace). El Gateway valida el token e inyecta el header **`X-User-Id`** (claim `sub` de la cuenta en `auth-service`); el servicio lo usa como `customerId` y para verificar ownership. Si el header falta o no es numérico → `401` (la petición no pasó por el Gateway). Es el **primer client HTTP entre servicios del repo**: al crear un pedido consulta `GET /api/products/{id}` de `product-service` para obtener el precio vigente.

---

## 3. Errores

Formato de respuesta de error: **ProblemDetail (RFC 7807)**, devuelto por `GlobalExceptionHandler`:

```json
{
  "type": "about:blank",
  "title": "Pedido no encontrado",
  "status": 404,
  "detail": "Pedido no encontrado con id 42"
}
```

| Código | Error | Cuándo ocurre | Ejemplo de mensaje |
|---|---|---|---|
| `400` | Bad Request | Validación de campos falla (va además la lista `errors`) | `items: no debe estar vacío` |
| `400` | Bad Request | El pedido no tiene ítems o el mismo `productId` aparece repetido | `No puede repetir el mismo productId en los ítems del pedido` |
| `400` | Bad Request | El producto consultado no tiene precio asignado | `El producto 42 no tiene precio asignado` |
| `400` | Bad Request | El `total` enviado por el cliente no coincide con el calculado en el servidor | `El total enviado (1.00) no coincide con el total calculado en el servidor (2049.48)` |
| `400` | Bad Request | Intento de cancelar vía `/status` en vez de `/cancel` | `Para cancelar un pedido use POST /api/orders/{orderId}/cancel` |
| `401` | Unauthorized | Falta o es inválido el header `X-User-Id` (no pasó por el Gateway) | `Falta o es inválido el header 'X-User-Id'; la petición debe pasar por el Gateway` |
| `403` | Forbidden | El pedido existe pero pertenece a otro cliente | `No tiene permiso sobre el pedido 7` |
| `404` | Not Found | Pedido inexistente | `Pedido no encontrado con id 7` |
| `404` | Not Found | Producto inexistente en `product-service` | `Producto no encontrado con id 42` |
| `409` | Conflict | Transición de estado inválida (ej. cancelar un `SHIPPED`, entregar un `PENDING`) | `No se puede pasar de PENDING a DELIVERED` |
| `409` | Conflict | Carrera entre escrituras (`@version`) | `El recurso fue modificado por otra operación; reintente la petición` |
| `409` | Conflict | Violación de restricción única (carrera entre peticiones simultáneas) | `Violación de restricción única (posible carrera entre peticiones simultáneas)` |
| `503` | Service Unavailable | `product-service` caído o con error al crear el pedido (tras agotar reintentos o con el circuito abierto) | `El servicio de productos no está disponible` |

---

## 4. Modelo de datos (Entidad)

### 4.1 Entidades

#### `OrderEntity` → tabla `orders`

| Campo | Tipo | PK | Nullable | Unique | Descripción |
|---|---|---|---|---|---|
| `id` | `bigint` (autoincrement) | ✅ | ❌ | ✅ | Identificador único |
| `order_number` | `varchar(36)` | ❌ | ❌ | ✅ | UUID visible al cliente (evita enumeración) |
| `customer_id` | `bigint` | ❌ | ❌ | ❌ | Cliente = header `X-User-Id` (id de cuenta en `auth-service`) |
| `status` | `varchar(20)` enum | ❌ | ❌ | ❌ | `PENDING` / `PAID` / `SHIPPED` / `DELIVERED` / `CANCELLED` |
| `total` | `numeric(12,2)` | ❌ | ❌ | ❌ | Calculado en servidor: Σ subtotals |
| `cancel_reason` | `varchar(500)` | ❌ | ✅ | ❌ | Motivo informado al cancelar |
| `refund_required` | `boolean` | ❌ | ❌ | ❌ | `true` si se canceló un pedido ya pagado |
| `created_at` / `updated_at` | `timestamptz` | ❌ | ❌ | ❌ | Auditoría |
| `version` | `bigint` | ❌ | ❌ | ❌ | Control de concurrencia optimista (`@Version`) |

#### `OrderItemEntity` → tabla `order_items`

| Campo | Tipo | PK | Nullable | Unique | Descripción |
|---|---|---|---|---|---|
| `id` | `bigint` (autoincrement) | ✅ | ❌ | ✅ | Identificador del ítem |
| `order_id` | `bigint` (FK → `orders.id`) | ❌ | ❌ | ❌ | Pedido dueño (`ON DELETE CASCADE`) |
| `product_id` | `bigint` | ❌ | ❌ | ❌ | Referencia lógica a `product-service` (sin FK cross-service) |
| `product_title` | `varchar(255)` | ❌ | ✅ | ❌ | Snapshot del título al comprar (inmutable) |
| `sku` | `varchar(100)` | ❌ | ✅ | ❌ | Snapshot del SKU al comprar (inmutable) |
| `quantity` | `int` | ❌ | ❌ | ❌ | Unidades compradas (`@Min(1)`) |
| `unit_price` | `numeric(12,2)` | ❌ | ❌ | ❌ | Precio unitario histórico congelado al crear el pedido |
| `subtotal` | `numeric(12,2)` | ❌ | ❌ | ❌ | `quantity × unit_price`, calculado en servidor |

#### `OutboxEvent` → tabla `outbox_events`

| Campo | Tipo | PK | Nullable | Unique | Descripción |
|---|---|---|---|---|---|
| `id` | `bigint` (autoincrement) | ✅ | ❌ | ✅ | Identificador del evento |
| `topic` | `varchar(50)` | ❌ | ❌ | ❌ | Topic de Kafka (`order-events`) |
| `aggregate_id` | `varchar(64)` | ❌ | ❌ | ❌ | Id del pedido (clave del mensaje) |
| `event_type` | `varchar(30)` | ❌ | ❌ | ❌ | `ORDER_CREATED` / `ORDER_STATUS_CHANGED` / `ORDER_CANCELLED` / `REFUND_REQUESTED` |
| `payload` | `text` | ❌ | ❌ | ❌ | JSON del evento |
| `status` | `varchar(20)` | ❌ | ❌ | ❌ | `PENDING` → `SENT` / `FAILED` |
| `attempts` / `last_error` | `int` / `varchar(500)` | ❌ | `last_error` nullable | ❌ | Auditoría de publicación |
| `created_at` / `sent_at` | `timestamptz` | ❌ | `sent_at` nullable | ❌ | Auditoría |

### 4.2 Relaciones

| Relación | Tipo | Descripción |
|---|---|---|
| `Order` 1 → N `OrderItem` | Uno-a-muchos | `order_items.order_id` referencia a `orders.id`; `cascade = ALL` + `orphanRemoval`; los ítems se cargan con `@EntityGraph` (sin N+1) |

### 4.3 Reglas de negocio

| Regla | Detalle |
|---|---|
| Precio histórico | Al crear, cada ítem consulta `product-service` (`GET /api/products/{id}`) y congela `unit_price`, `product_title` y `sku`; el pedido no cambia aunque el producto cambie de precio o se elimine |
| Ítems | Mínimo 1 ítem; `quantity ≥ 1`; `productId` repetido en el mismo pedido → `400` |
| Total | Siempre `Σ (unit_price × quantity)` calculado en servidor; si el cliente envía `total` y no coincide → `400 TotalMismatchException`; el valor persistido es siempre el calculado |
| Identidad | `customer_id` = header `X-User-Id`; sin header → `401` |
| Ownership | `GET /{id}`, `/{id}/status` y `/{id}/cancel` verifican que el pedido pertenezca al cliente; si no → `403`. `GET /` solo lista pedidos del cliente |
| Máquina de estados | `PENDING → PAID → SHIPPED → DELIVERED`; `PENDING`/`PAID → CANCELLED`; cualquier otra transición → `409` |
| Cancelación | Solo desde `PENDING` o `PAID`; si era `PAID` ⇒ `refund_required = true` + evento `REFUND_REQUESTED`; `CANCELLED` solo se logra vía `/cancel`, nunca vía `/status` |
| Concurrencia | `@Version` → conflicto simultáneo responde `409` |
| Esquema | `ddl-auto=update` (JPA crea/ajusta el esquema; no hay Flyway) |
| Invariantes | El total es siempre la suma de los subtotals; los ítems jamás se editan después de creado el pedido |

---

## 5. Configuración

### 5.1 Variables de entorno

| Variable | Descripción | Requerida | Default | Ejemplo |
|---|---|---|---|---|
| `SERVER_PORT` | Puerto HTTP del servicio | ❌ | `8080` | `8080` |
| `SPRING_DATASOURCE_URL` | JDBC URL de la base de datos | ✅ | — | `jdbc:postgresql://db:5432/orders` |
| `SPRING_DATASOURCE_USERNAME` | Usuario de la base de datos | ✅ | — | `orders` |
| `SPRING_DATASOURCE_PASSWORD` | Password de la base de datos | ✅ | — | *(secret)* |
| `KAFKA_BOOTSTRAP_SERVERS` | Brokers de Kafka para el outbox y el consumer | ❌ | `localhost:9092` | `kafka:9092` |
| `OUTBOX_RELAY_POLL_INTERVAL_MS` | Intervalo del relay (ms) | ❌ | `1000` | `1000` |
| `OUTBOX_RELAY_BATCH_SIZE` | Lote del relay por ciclo | ❌ | `50` | `50` |
| `OUTBOX_RELAY_MAX_ATTEMPTS` | Intentos máximos de publicación por evento | ❌ | `10` | `10` |
| `PRODUCT_SERVICE_BASE_URL` | Base URL de `product-service` para resolver precios | ❌ | `http://localhost:8080` | `http://products:8080` |
| `HTTP_CLIENT_CONNECT_TIMEOUT_MS` | Timeout de conexión del client HTTP a `product-service` | ❌ | `2000` | `2000` |
| `HTTP_CLIENT_READ_TIMEOUT_MS` | Timeout de lectura del client HTTP a `product-service` | ❌ | `3000` | `3000` |
| `RESILIENCE_MAX_ATTEMPTS` | Intentos totales por llamada (reintento incluido) | ❌ | `3` | `3` |
| `RESILIENCE_WAIT_DURATION` | Espera base entre reintentos (backoff exponencial) | ❌ | `200ms` | `200ms` |
| `RESILIENCE_WAIT_DURATION_MULTIPLIER` | Multiplicador del backoff exponencial | ❌ | `2.0` | `2.0` |
| `RESILIENCE_FAILURE_RATE_THRESHOLD` | Tasa de fallos (%) que abre el circuit breaker | ❌ | `50` | `50` |
| `RESILIENCE_SLIDING_WINDOW_SIZE` | Ventana deslizante del circuit breaker | ❌ | `10` | `10` |
| `RESILIENCE_MINIMUM_NUMBER_OF_CALLS` | Mínimo de llamadas antes de evaluar la tasa de fallos | ❌ | `5` | `5` |
| `RESILIENCE_WAIT_DURATION_IN_OPEN_STATE` | Tiempo en estado abierto antes de half-open | ❌ | `10s` | `10s` |
| `EUREKA_URL` | Registry de Eureka para registrarse | ❌ | `http://localhost:8761/eureka` | `http://eureka:8761/eureka` |
| `EUREKA_ENABLED` | Habilita el cliente Eureka (`false` para correr sin registry) | ❌ | `true` | `true` |
| `LOGGING_LEVEL_ROOT` | Nivel de log | ❌ | `INFO` | `INFO` |

### 5.2 Spring profiles

| Profile | Uso |
|---|---|
| `test` | Tests automatizados (Testcontainers PostgreSQL + EmbeddedKafka; `ProductClient` mockeado con `@MockitoBean`; relay congelado con `poll-interval-ms=600000`) |
| `local` | Desarrollo con `docker compose` *(pendiente)* |
| `k8s` | Despliegue en Kubernetes *(pendiente)* |

> No existe `application-{profile}.properties`: la config de test se inyecta por anotaciones (`@TestPropertySource`, `@EmbeddedKafka`, `@MockitoBean`).

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
| `product-service` | Resolver precio/título/SKU vigentes al crear un pedido | HTTP/REST (`GET /api/products/{id}`) | Reintenta 3 veces (backoff exponencial) y abre un circuit breaker tras el 50% de fallos; si se agota → `503` al crear el pedido (las consultas de pedidos existentes siguen funcionando) | ✅ Sí (solo al crear) |
| `payment-service` | Recibe su evento `PAYMENT_APPROVED` (no es una llamada sincrónica) | Kafka (`payment-events`) | El pedido permanece `PENDING` hasta que el consumer vuelva a procesar | ❌ No |

> La identidad del cliente no implica dependencia de `auth-service`: llega resuelta en el header `X-User-Id` que inyecta el Gateway.

### 6.2 Dependencias externas

| Dependencia | Tipo | Para qué | Crítica | Comportamiento si cae |
|---|---|---|---|---|
| PostgreSQL | Base de datos | Pedidos, ítems y outbox | ✅ Sí | `503`, readiness en `DOWN` |
| Kafka | Mensajería | Publicación de eventos de dominio | ❌ No | El outbox retiene eventos (`PENDING`) y el relay los reintentará al volver |

### 6.3 Eventos publicados/consumidos

Topic publicado: **`order-events`** (Kafka), clave = `id` del pedido. Publicados mediante **outbox transaccional** (escritura en la misma transacción que el cambio; relay con `FOR UPDATE SKIP LOCKED`, reintenta `PENDING` y `FAILED` hasta `outbox.relay.max-attempts`, default `10` — la misma property alimenta el límite de la query y el log del relay).

| Evento | Dirección | Trigger | Payload |
|---|---|---|---|
| `ORDER_CREATED` | Publica | Alta de pedido | `{ orderId, orderNumber, customerId, total, items: [{ productId, quantity, unitPrice, subtotal }], occurredAt }` |
| `ORDER_STATUS_CHANGED` | Publica | Pago / envío / entrega | `{ orderId, orderNumber, customerId, from, to, occurredAt }` |
| `ORDER_CANCELLED` | Publica | Cancelación de pedido | `{ orderId, orderNumber, customerId, reason, refundRequired, occurredAt }` |
| `REFUND_REQUESTED` | Publica | Cancelación de un pedido `PAID` | `{ orderId, orderNumber, customerId, amount, occurredAt }` |

Topic consumido: **`payment-events`** (Kafka), group-id `order-service`, `auto-offset-reset=earliest`.

| Evento | Dirección | Trigger | Acción |
|---|---|---|---|
| `PAYMENT_APPROVED` | Consume | `payment-service` confirma el cobro | Si el pedido está `PENDING` → pasa a `PAID` y actualiza `updated_at`. Si ya no es `PENDING`, se ignora (idempotente). Ante un pedido inexistente se re-lanza la excepción para que Kafka reintente |
| `PAYMENT_CREATED` / `PAYMENT_REJECTED` / `PAYMENT_CANCELLED` | Consume | — | Ignorados (el consumer discrimina por el campo `type` del payload) |

> `PAYMENT_APPROVED` es la **única** vía para que un pedido pase a `PAID`; `POST /api/orders/{id}/status` con `PAID` sigue funcionando pero no es el flujo esperado.
>
> Consumidores naturales futuros: `product-service` (descontar/restaurar stock con `ORDER_*`, requiere outbox propio) y `payment-service` (`REFUND_REQUESTED`, pendiente de incorporarse).

---

## 7. Tests

| Archivo | Tipo | Cantidad | Cubre |
|---|---|---|---|
| `OrderServiceTest` | Unit (Mockito) | 13 | create con total calculado desde precios del servidor, total del cliente coincide/no coincide (400), producto duplicado, items vacíos, 404, 403 con pedido de otro cliente, transición válida PENDING→PAID, transición inválida (409), CANCELLED vía `/status` rechazado (400), cancel de `PENDING` sin reembolso, cancel de `PAID` con `REFUND_REQUESTED`, cancel de `SHIPPED` (409) |
| `ProductClientTest` | Unit (MockRestServiceServer) | 5 | 200 → DTO, 404 → `ProductNotFoundException` sin reintento, 5xx reintenta 3 veces y agota → 503, reintento recuperado (500 → 200), circuito abierto → 503 sin llamada HTTP |
| `PaymentApprovedConsumerTest` | Unit (Mockito) | 5 | `PENDING`→`PAID` al recibir `PAYMENT_APPROVED`, idempotencia si el pedido ya no es `PENDING`, ignorado de `PAYMENT_CREATED`/`PAYMENT_REJECTED`/`PAYMENT_CANCELLED`, JSON malformado descartado sin lanzar, pedido inexistente re-lanza para que Kafka reintente |
| `OrderApiIntegrationTest` | Integración (Testcontainers PostgreSQL) | 9 | 201 + `Location` + total e ítems calculados, 400 con total que no coincide, 401 sin `X-User-Id`, 404, 403 con pedido ajeno, paginación solo del cliente, cancel, 409 en transición inválida, atomicidad del outbox en la misma transacción |
| `OrderMessagingIntegrationTest` | Integración (Testcontainers PostgreSQL + EmbeddedKafka) | 2 | outbox → relay → Kafka end-to-end (clave = orderId, payload con `unitPrice`/`subtotal`), reintento de `FAILED` con tope de `outbox.relay.max-attempts` (3 en el test) |
| `OrderServiceApplicationTests` | Context (Testcontainers PostgreSQL) | 1 | Arranque de la aplicación |

> Kafka se testea con un broker **embebido en la JVM** (`@EmbeddedKafka`, sin imágenes adicionales) y la base de datos con PostgreSQL vía Testcontainers. `ProductClient` se mockea con `@MockitoBean` (no hay un `product-service` real corriendo en los tests). El relay automático se congela (`poll-interval-ms=600000`) y se invoca manualmente para que las aserciones sean determinísticas.

```bash
./mvnw test
```

`TestOrderServiceApplication` no es un test: es un launcher (`main`) que arranca la app con `TestcontainersConfiguration` para desarrollo local.
