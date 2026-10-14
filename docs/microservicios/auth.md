# Microservicio: `authService`

## 1. Responsabilidad

Gestiona el registro, la autenticación y la emisión de tokens (access + refresh) de la plataforma de e-commerce. Es la única fuente de verdad sobre **credenciales**: almacena el hash BCrypt de la contraseña y el estado de los refresh tokens.

Publica el evento `ACCOUNT_CREATED` para que `user-service` cree el perfil de usuario; no guarda ni expone datos de perfil más allá de `email`/`username`.

No le pertenece: autorización sobre recursos (el Gateway valida el JWT), gestión del perfil de usuario (`user-service`), catálogo de productos ni gestión de pedidos.

---

## 2. Endpoints

Todas las rutas son relativas a la raíz del servicio (ej. `http://auth:8080`).

| Método | Ruta | Descripción | Request | Response | Auth |
|---|---|---|---|---|---|
| `GET` | `/actuator/health` | Health check para probes de Kubernetes | — | `200` `{ status }` | No |
| `POST` | `/api/auth/register` | Crea una cuenta y publica `ACCOUNT_CREATED` | `RegisterRequest` | `201` AccountResponse + `Location: /api/users/{id}` | No |
| `POST` | `/api/auth/login` | Autentica y emite access + refresh token | `LoginRequest` | `200` TokenResponse | No |
| `POST` | `/api/auth/refresh` | Rota el refresh token y emite nuevos tokens | `{ "refreshToken": "..." }` | `200` TokenResponse | No |
| `POST` | `/api/auth/logout` | Revoca el refresh token | `{ "refreshToken": "..." }` | `204` | No |

**DTOs:**

```json
// RegisterRequest
{ "email": "ana@mail.com", "username": "ana", "password": "supersecret1" }

// LoginRequest
{ "email": "ana@mail.com", "password": "supersecret1" }

// AccountResponse (el password nunca se expone)
{ "id": 1, "email": "ana@mail.com", "username": "ana" }

// TokenResponse (expiresIn = segundos del access token)
{ "accessToken": "eyJhbGciOiJIUzI1NiJ9...", "refreshToken": "hB7q...", "tokenType": "Bearer", "expiresIn": 900 }
```

> **Nota de auth:** el Gateway es la primera barrera, y además este servicio actúa como **resource server** (Spring Security `oauth2ResourceServer` con el mismo secreto HS256 que emite los tokens): `/api/auth/**` y `/actuator/**` son públicos; cualquier otra ruta exige un header `Authorization: Bearer <jwt>` válido. Es defensa en profundidad: ninguna ruta queda expuesta aunque el Gateway no esté delante.

---

## 3. Errores

Formato de respuesta de error: **ProblemDetail (RFC 7807)**, devuelto por `GlobalExceptionHandler`:

```json
{
  "type": "about:blank",
  "title": "No autorizado",
  "status": 401,
  "detail": "Credenciales inválidas"
}
```

| Código | Error | Cuándo ocurre | Ejemplo de mensaje |
|---|---|---|---|
| `400` | Bad Request | Validación de campos falla (va además la lista `errors`) | `password: debe tener entre 8 y 100 caracteres` |
| `401` | Unauthorized | Email inexistente o password incorrecto | `Credenciales inválidas` |
| `401` | Unauthorized | Refresh token desconocido, caducado o reusado | `Refresh token inválido o caducado` |
| `401` | Unauthorized | Excepción de Spring Security | `No autorizado` |
| `401` | Unauthorized | Token ausente o inválido en una ruta protegida (emite el resource server del servicio; body vacío + header `WWW-Authenticate`) | — |
| `409` | Conflict | Email o username duplicados | `El email 'ana@mail.com' ya está en uso` |
| `409` | Conflict | Carrera entre escrituras (`@version`) | `El recurso fue modificado por otra operación; reintente la petición` |
| `409` | Conflict | Violación de restricción única (carrera entre peticiones simultáneas) | `Violación de restricción única (posible carrera entre peticiones simultáneas)` |
| `500` | Internal Error | Fallo inesperado | `Unexpected error` |

`403` y los `401` de rutas protegidas fuera de `/api/auth/**` los emite el **Gateway**, no este servicio.

---

## 4. Modelo de datos (Entidad)

### 4.1 Entidades

#### `AccountEntity` → tabla `accounts`

| Campo | Tipo | PK | Nullable | Unique | Descripción |
|---|---|---|---|---|---|
| `id` | `bigint` (autoincrement) | ✅ | ❌ | ✅ | Identificador único |
| `email` | `varchar(150)` | ❌ | ❌ | ✅ | Correo de la cuenta |
| `username` | `varchar(30)` | ❌ | ❌ | ✅ | Nombre de usuario |
| `password` | `varchar(100)` | ❌ | ❌ | ❌ | Hash BCrypt (nunca en respuestas) |
| `version` | `bigint` | ❌ | ❌ | ❌ | Control de concurrencia optimista (`@Version`) |

#### `RefreshTokenEntity` → tabla `refresh_tokens`

| Campo | Tipo | PK | Nullable | Unique | Descripción |
|---|---|---|---|---|---|
| `id` | `bigint` (autoincrement) | ✅ | ❌ | ✅ | Identificador del token |
| `token_hash` | `varchar(64)` | ❌ | ❌ | ✅ | SHA-256 (hex) del token crudo; el crudo nunca se persiste |
| `account_id` | `bigint` | ❌ | ❌ | ❌ | Cuenta dueña del token |
| `expires_at` | `timestamptz` | ❌ | ❌ | ❌ | Caducidad (`JWT_REFRESH_TTL_DAYS`) |
| `revoked` | `boolean` | ❌ | ❌ | ❌ | Revocado o ya canjeado |
| `replaced_by` | `bigint` | ❌ | ✅ | ❌ | Id del token que lo reemplazó; se setea en cada rotación |
| `created_at` | `timestamptz` | ❌ | ❌ | ❌ | Fecha de emisión (`@PrePersist`) |

#### `OutboxEvent` → tabla `outbox_events`

| Campo | Tipo | PK | Nullable | Unique | Descripción |
|---|---|---|---|---|---|
| `id` | `bigint` (autoincrement) | ✅ | ❌ | ✅ | Identificador del evento |
| `topic` | `varchar(50)` | ❌ | ❌ | ❌ | Topic de Kafka (`account-events`) |
| `aggregate_id` | `varchar(64)` | ❌ | ❌ | ❌ | Id de la cuenta (clave del mensaje) |
| `event_type` | `varchar(30)` | ❌ | ❌ | ❌ | `ACCOUNT_CREATED` |
| `payload` | `text` | ❌ | ❌ | ❌ | JSON del evento |
| `status` | `varchar(20)` | ❌ | ❌ | ❌ | `PENDING` → `SENT` / `FAILED` |
| `attempts` / `last_error` | `int` / `varchar(500)` | ❌ | `last_error` nullable | ❌ | Auditoría de publicación |
| `created_at` / `sent_at` | `timestamptz` | ❌ | `sent_at` nullable | ❌ | Auditoría |

### 4.2 Relaciones

| Relación | Tipo | Descripción |
|---|---|---|
| `Account` 1 → N `RefreshToken` | Uno-a-muchos | `refresh_tokens.account_id` referencia a `accounts.id` |

### 4.3 Reglas de negocio

| Regla | Detalle |
|---|---|
| Unicidad | `email` y `username` no pueden repetirse (check previo + restricción única como red de seguridad) |
| Password | Se hashea con BCrypt al registrarse; jamás se persiste ni se devuelve en claro |
| Access token | JWT HS256, `iss` = `JWT_ISSUER`, `sub` = id de la cuenta, claims `email` y `username`, TTL = `JWT_ACCESS_TTL_SECONDS` |
| Refresh token | 32 bytes aleatorios (Base64URL); solo se guarda su SHA-256; TTL = `JWT_REFRESH_TTL_DAYS` |
| Rotación | Cada `refresh` revoca el token usado, lo enlaza con el nuevo vía `replaced_by` y emite uno nuevo; **reusar un token ya canjeado revoca toda la familia** de la cuenta |
| Logout | Revoca el refresh token enviado (idempotente: si no existe, no falla) |
| Concurrencia | `@Version` → conflicto simultáneo responde `409` |
| Esquema | `ddl-auto=update` (JPA crea/ajusta el esquema; no hay Flyway) |
| Invariantes | El password nunca aparece en ninguna respuesta |

---

## 5. Configuración

### 5.1 Variables de entorno

| Variable | Descripción | Requerida | Default | Ejemplo |
|---|---|---|---|---|
| `SERVER_PORT` | Puerto HTTP del servicio | ❌ | `8080` | `8080` |
| `SPRING_DATASOURCE_URL` | JDBC URL de la base de datos | ✅ | — | `jdbc:postgresql://db:5432/auth` |
| `SPRING_DATASOURCE_USERNAME` | Usuario de la base de datos | ✅ | — | `auth` |
| `SPRING_DATASOURCE_PASSWORD` | Password de la base de datos | ✅ | — | *(secret)* |
| `JWT_SECRET` | Secreto HS256, **≥ 32 caracteres** (si no, la app no arranca) | ✅ | — | *(secret)* |
| `JWT_ISSUER` | `iss` del access token | ❌ | `auth-service` | `auth-service` |
| `JWT_ACCESS_TTL_SECONDS` | Vida del access token | ❌ | `900` | `900` |
| `JWT_REFRESH_TTL_DAYS` | Vida del refresh token | ❌ | `7` | `7` |
| `KAFKA_BOOTSTRAP_SERVERS` | Brokers de Kafka para el outbox | ❌ | `localhost:9092` | `kafka:9092` |
| `OUTBOX_RELAY_POLL_INTERVAL_MS` | Intervalo del relay (ms) | ❌ | `1000` | `1000` |
| `OUTBOX_RELAY_BATCH_SIZE` | Lote del relay por ciclo | ❌ | `50` | `50` |
| `OUTBOX_RELAY_MAX_ATTEMPTS` | Intentos máximos de publicación por evento | ❌ | `10` | `10` |
| `EUREKA_URL` | Registry de Eureka para registrarse | ❌ | `http://localhost:8761/eureka` | `http://eureka:8761/eureka` |
| `EUREKA_ENABLED` | Habilita el cliente Eureka (`false` para correr sin registry) | ❌ | `true` | `true` |
| `LOGGING_LEVEL_ROOT` | Nivel de log | ❌ | `INFO` | `INFO` |

> En k8s `JWT_SECRET` debe inyectarse como Secret; el default es solo para desarrollo.

### 5.2 Spring profiles

| Profile | Uso |
|---|---|
| `test` | Tests automatizados (Testcontainers PostgreSQL + EmbeddedKafka; propiedades vía `@TestPropertySource`: `jwt.secret` de prueba, relay congelado con `poll-interval-ms=600000` y `max-attempts=3`) |
| `local` | Desarrollo con `docker compose` *(pendiente)* |
| `k8s` | Despliegue en Kubernetes: manifiestos en `k8s/base/auth-service` + overlays. Sin perfil `application-k8s.properties`; la config se inyecta por ConfigMap y Secret |

> **Kubernetes y Eureka**: en k8s este servicio corre con `EUREKA_ENABLED=false` (ver `k8s/base/auth-service/configmap.yaml`). No hay registro en Eureka ni descubrimiento `lb://`: el tráfico entra por el Gateway, que apunta a URLs directas (`AUTH_SERVICE_BASE_URL=http://auth-service`). `eureka-server` **no se despliega** en el clúster (solo aplica a desarrollo local).

> No existe `application-{profile}.properties`: la config de test se inyecta por anotaciones (`@TestPropertySource`, `@EmbeddedKafka`).

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
| `user-service` | Recibe su evento `ACCOUNT_CREATED` (no es una llamada sincrónica) | Kafka (`account-events`) | El outbox retiene el evento hasta que vuelva | ❌ No |

No hace llamadas HTTP sincrónicas a otros servicios.

### 6.2 Dependencias externas

| Dependencia | Tipo | Para qué | Crítica | Comportamiento si cae |
|---|---|---|---|---|
| PostgreSQL | Base de datos | Cuentas, refresh tokens y outbox | ✅ Sí | `503`, readiness en `DOWN` |
| Kafka | Mensajería | Publicación de eventos de dominio | ❌ No | El outbox retiene eventos (`PENDING`) y el relay los reintentará al volver |

### 6.3 Eventos publicados/consumidos

Topic publicado: **`account-events`** (Kafka), clave = `id` de la cuenta. Publicados mediante **outbox transaccional** (escritura en la misma transacción que el cambio; relay con `FOR UPDATE SKIP LOCKED`, reintenta `PENDING` y `FAILED` hasta `outbox.relay.max-attempts`, default `10` — la misma property alimenta el límite de la query y el log del relay).

| Evento | Dirección | Trigger | Payload |
|---|---|---|---|
| `ACCOUNT_CREATED` | Publica | Registro de una cuenta | `{ id, email, username, occurredAt }` |
| — | Consume | — | — |

---

## 7. Tests

| Archivo | Tipo | Cantidad | Cubre |
|---|---|---|---|
| `AuthServiceTest` | Unit (Mockito) | 7 | register con hash + evento, duplicado por email, login con tokens, password incorrecto, cuenta inexistente, refresh inválido, enlace del token reemplazado en refresh |
| `AuthApiIntegrationTest` | Integración (Testcontainers PostgreSQL) | 11 | 201 sin password + outbox, 409 duplicado, JWT decodificable, 401 con password malo, rotación de refresh con `replaced_by`, reuso de refresh → 401, logout 204, 400 validación, ruta protegida sin token → 401, ruta protegida con token válido → pasa la seguridad |
| `AuthMessagingIntegrationTest` | Integración (Testcontainers PostgreSQL + EmbeddedKafka) | 2 | outbox → relay → Kafka end-to-end (clave y payload sin password), reintento de `FAILED` con tope de `outbox.relay.max-attempts` (3 en el test) |
| `AuthServiceApplicationTests` | Context (Testcontainers PostgreSQL) | 1 | Arranque de la aplicación |

> Kafka se testea con un broker **embebido en la JVM** (`@EmbeddedKafka`, sin imágenes adicionales) y la base de datos con PostgreSQL vía Testcontainers (imagen `postgres:17-alpine`, misma que `compose.yaml`). El relay automático se congela (`poll-interval-ms=600000`) y se invoca manualmente para que las aserciones sean determinísticas.

```bash
./mvnw test
```
