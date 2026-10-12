# Microservicio: `userService`

## 1. Responsabilidad

Gestiona la consulta, modificación y eliminación del perfil de usuario de la plataforma de e-commerce. Es la única fuente de verdad sobre datos de perfil: otros servicios lo consultan o consumen sus eventos, pero nunca almacenan copias de esta información.

El perfil se crea a partir del evento `ACCOUNT_CREATED` publicado por `auth-service` (el `password` vive **solo** en `auth-service`).

No le pertenece: autenticación/autorización (el Gateway valida el JWT), credenciales/contraseñas (pertenecen a `auth-service`), catálogo de productos ni gestión de pedidos.

---

## 2. Endpoints

Todas las rutas son relativas a la raíz del servicio (ej. `http://users:8080`).

| Método | Ruta | Descripción | Request | Response | Auth |
|---|---|---|---|---|---|
| `GET` | `/actuator/health` | Health check para probes de Kubernetes | — | `200` `{ status }` | No |
| `GET` | `/api/users` | Lista usuarios paginada (`page`, `size`, `sort`) | — | `200` Page\<UserResponse\> | Sí (Gateway) |
| `GET` | `/api/users/{id}` | Obtiene un usuario por su ID | — | `200` UserResponse | Sí (Gateway) |
| `POST` | `/api/users` | Crea un nuevo usuario | `CreateUserRequest` | `201` UserResponse + `Location` | No |
| `PUT` | `/api/users/{id}` | Actualiza un usuario | `UpdateUserRequest` | `200` UserResponse | Sí (Gateway) |
| `DELETE` | `/api/users/{id}` | Elimina un usuario (borrado físico) | — | `204` | Sí (Gateway) |

**DTOs:**

```json
// CreateUserRequest (UserRequest)
{ "email": "ana@mail.com", "username": "ana" }

// UpdateUserRequest (UserUpdateRequest) — mismo shape que el de creación
{ "email": "ana@mail.com", "username": "ana" }

// UserResponse (no existe campo password en este servicio)
{ "id": 42, "email": "ana@mail.com", "username": "ana" }
```

> **Nota de auth:** el servicio no tiene dependencia de Spring Security ni valida JWT (decisión de diseño: el Gateway lo hace). Las rutas marcadas "Sí (Gateway)" están protegidas en la ruta del cluster, no dentro de este código.

---

## 3. Errores

Formato de respuesta de error: **ProblemDetail (RFC 7807)**, devuelto por `GlobalExceptionHandler`:

```json
{
  "type": "about:blank",
  "title": "Usuario no encontrado",
  "status": 404,
  "detail": "Usuario no encontrado con id 42"
}
```

| Código | Error | Cuándo ocurre | Ejemplo de mensaje |
|---|---|---|---|
| `400` | Bad Request | Validación de campos falla (va además la lista `errors`) | `email: debe ser un correo válido` |
| `404` | Not Found | Usuario inexistente | `Usuario no encontrado con id 42` |
| `409` | Conflict | Email o username duplicados | `El email 'ana@mail.com' ya está en uso` |
| `409` | Conflict | Carrera entre escrituras (`@version`) | `El recurso fue modificado por otra operación; reintente la petición` |
| `409` | Conflict | Violación de restricción única (carrera entre peticiones simultáneas) | `Violación de restricción única (posible carrera entre peticiones simultáneas)` |
| `500` | Internal Error | Fallo inesperado | `Unexpected error` |

`401`/`403` los emite el **Gateway** (token ausente/inválido), no este servicio.

---

## 4. Modelo de datos (Entidad)

### 4.1 Entidades

#### `UserEntity` → tabla `users`

| Campo | Tipo | PK | Nullable | Unique | Descripción |
|---|---|---|---|---|---|
| `id` | `bigint` (autoincrement) | ✅ | ❌ | ✅ | Identificador único |
| `email` | `varchar(150)` | ❌ | ❌ | ✅ | Correo del usuario |
| `username` | `varchar(30)` | ❌ | ❌ | ✅ | Nombre de usuario |
| `version` | `bigint` | ❌ | ❌ | ❌ | Control de concurrencia optimista (`@Version`) |

> No hay columna de password: las credenciales residen únicamente en `auth-service`.

#### `OutboxEvent` → tabla `outbox_events`

| Campo | Tipo | PK | Nullable | Unique | Descripción |
|---|---|---|---|---|---|
| `id` | `bigint` (autoincrement) | ✅ | ❌ | ✅ | Identificador del evento |
| `topic` | `varchar(50)` | ❌ | ❌ | ❌ | Topic de Kafka (`user-events`) |
| `aggregate_id` | `varchar(64)` | ❌ | ❌ | ❌ | Id del usuario (clave del mensaje) |
| `event_type` | `varchar(30)` | ❌ | ❌ | ❌ | `USER_CREATED` / `USER_UPDATED` / `USER_DELETED` |
| `payload` | `text` | ❌ | ❌ | ❌ | JSON del evento |
| `status` | `varchar(20)` | ❌ | ❌ | ❌ | `PENDING` → `SENT` / `FAILED` |
| `attempts` | `int` | ❌ | ❌ | ❌ | Intentos de publicación fallidos |
| `last_error` | `varchar(500)` | ❌ | ✅ | ❌ | Último error de publicación |
| `created_at` / `sent_at` | `timestamptz` | ❌ | `sent_at` nullable | ❌ | Auditoría |

### 4.2 Relaciones

| Relación | Tipo | Descripción |
|---|---|---|
| — | — | — |

No hay relaciones JPA en este servicio. `outbox_events` es una tabla técnica (el `aggregate_id` referencia al `id` del usuario solo como dato del mensaje, sin FK).

### 4.3 Reglas de negocio

| Regla | Detalle |
|---|---|
| Unicidad | `email` y `username` no pueden repetirse (check previo + restricción única como red de seguridad) |
| Alta | El perfil puede crearse vía `POST /api/users` o de forma idempotente al consumir `ACCOUNT_CREATED` |
| Borrado | Borrado **físico**; publica `USER_DELETED` en el outbox |
| Concurrencia | `@Version` → conflicto simultáneo responde `409` |
| Esquema | `ddl-auto=update` (JPA crea/ajusta el esquema; no hay Flyway) |
| Invariantes | Nunca se expone ni persiste información de credenciales |

---

## 5. Configuración

### 5.1 Variables de entorno

| Variable | Descripción | Requerida | Default | Ejemplo |
|---|---|---|---|---|
| `SERVER_PORT` | Puerto HTTP del servicio | ❌ | `8080` | `8080` |
| `SPRING_DATASOURCE_URL` | JDBC URL de la base de datos | ✅ | — | `jdbc:postgresql://db:5432/users` |
| `SPRING_DATASOURCE_USERNAME` | Usuario de la base de datos | ✅ | — | `users` |
| `SPRING_DATASOURCE_PASSWORD` | Password de la base de datos | ✅ | — | *(secret)* |
| `KAFKA_BOOTSTRAP_SERVERS` | Brokers de Kafka para el outbox | ❌ | `localhost:9092` | `kafka:9092` |
| `OUTBOX_RELAY_POLL_INTERVAL_MS` | Intervalo del relay (ms) | ❌ | `1000` | `1000` |
| `OUTBOX_RELAY_BATCH_SIZE` | Lote del relay por ciclo | ❌ | `50` | `50` |
| `OUTBOX_RELAY_MAX_ATTEMPTS` | Intentos máximos de publicación por evento | ❌ | `10` | `10` |
| `EUREKA_URL` | Registry de Eureka para registrarse | ❌ | `http://localhost:8761/eureka` | `http://eureka:8761/eureka` |
| `EUREKA_ENABLED` | Habilita el cliente Eureka (`false` para correr sin registry) | ❌ | `true` | `true` |
| `LOGGING_LEVEL_ROOT` | Nivel de log | ❌ | `INFO` | `INFO` |

### 5.2 Spring profiles

| Profile | Uso |
|---|---|
| `test` | Tests automatizados (Testcontainers PostgreSQL + EmbeddedKafka; propiedades vía `@TestPropertySource`; relay congelado con `poll-interval-ms=600000`) |
| `local` | Desarrollo con `docker compose` *(pendiente)* |
| `k8s` | Despliegue en Kubernetes *(pendiente)* |

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
| `auth-service` | Origen del perfil: consume su evento `ACCOUNT_CREATED` | Kafka (`account-events`) | El alta manual vía `POST /api/users` sigue funcionando | ❌ No |

No hace llamadas HTTP sincrónicas a otros servicios.

### 6.2 Dependencias externas

| Dependencia | Tipo | Para qué | Crítica | Comportamiento si cae |
|---|---|---|---|---|
| PostgreSQL | Base de datos | Persistencia principal | ✅ Sí | `503`, readiness en `DOWN` |
| Kafka | Mensajería | Publicación de eventos de dominio | ❌ No | El outbox retiene eventos (`PENDING`) y el relay los reintentará al volver |

### 6.3 Eventos publicados/consumidos

Topic publicado: **`user-events`** (Kafka), clave = `id` del usuario. Publicados mediante **outbox transaccional** (escritura en la misma transacción que el cambio; relay con `FOR UPDATE SKIP LOCKED`, reintenta `PENDING` y `FAILED` hasta `outbox.relay.max-attempts`, default `10` — la misma property alimenta el límite de la query y el log del relay).

| Evento | Dirección | Trigger | Payload |
|---|---|---|---|
| `USER_CREATED` | Publica | Alta de usuario | `{ id, email, username, occurredAt }` |
| `USER_UPDATED` | Publica | Modificación de usuario | `{ id, email, username, occurredAt }` |
| `USER_DELETED` | Publica | Baja de usuario | `{ id, occurredAt }` |
| `ACCOUNT_CREATED` | Consume | Registro en `auth-service` | `{ id, email, username, occurredAt }` |

El consumidor (`AccountEventsConsumer`, topic `account-events`) es **idempotente**: si ya existe un perfil con ese `email`, no hace nada. Ante un error se re-lanza la excepción para que Kafka reintente.

---

## 7. Tests

| Archivo | Tipo | Cantidad | Cubre |
|---|---|---|---|
| `UserServiceTest` | Unit (Mockito) | 5 | create + evento en outbox, duplicados (email/username), 404, update |
| `UserApiIntegrationTest` | Integración (Testcontainers PostgreSQL) | 7 | 201 + `Location`, 409 duplicado, 400 validación, 404, paginación, delete, atomicidad del outbox |
| `UserMessagingIntegrationTest` | Integración (Testcontainers PostgreSQL + EmbeddedKafka) | 3 | outbox → relay → Kafka end-to-end (clave y payload), reintento de `FAILED` con tope de `outbox.relay.max-attempts` (3 en el test), consumer de `ACCOUNT_CREATED` idempotente |
| `UserServiceApplicationTests` | Context (Testcontainers PostgreSQL) | 1 | Arranque de la aplicación |

> Kafka se testea con un broker **embebido en la JVM** (`@EmbeddedKafka`, sin imágenes adicionales) y la base de datos con PostgreSQL vía Testcontainers. El relay automático se congela (`poll-interval-ms=600000`) y se invoca manualmente para que las aserciones sean determinísticas.

```bash
./mvnw test
```

`TestUserServiceApplication` no es un test: es un launcher (`main`) que arranca la app con `TestcontainersConfiguration` para desarrollo local.
