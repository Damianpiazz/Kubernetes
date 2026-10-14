# Microservicio: `<nombre>`


## 1. Responsabilidad

<_Párrafo corto (2-4 líneas) que responda: qué hace este microservicio, qué problema resuelve y cuál es su límite de responsabilidad. Si algo NO le pertenece, indícalo aquí para evitar solapamientos con otros servicios._>

**Ejemplo:** *Gestiona el registro, autenticación y perfil de los usuarios de la plataforma. Es la única fuente de verdad sobre datos de usuarios; otros servicios lo consultan pero nunca almacenan copias de esta información.*

---

## 2. Endpoints

Todas las rutas son relativas a la raíz del servicio (ej. `http://users:8080`).

| Método | Ruta | Descripción | Request | Response | Auth |
|---|---|---|---|---|---|
| `GET` | `/actuator/health` | Health check para probes de Kubernetes | — | `200` `{ status }` | No |
| `GET` | `/api/users/{id}` | Obtiene un usuario por su ID | — | `200` UserDto | Sí |
| `POST` | `/api/users` | Crea un nuevo usuario | `CreateUserRequest` | `201` UserDto | No |
| `PUT` | `/api/users/{id}` | Actualiza un usuario | `UpdateUserRequest` | `200` UserDto | Sí |
| `DELETE` | `/api/users/{id}` | Elimina un usuario | — | `204` | Sí |

---

## 3. Errores

Formato de respuesta de error: **ProblemDetail (RFC 7807)**, emitido por `GlobalExceptionHandler` (mismo formato en todos los servicios):

```json
{
  "type": "about:blank",
  "title": "User not found",
  "status": 404,
  "detail": "User with id 42 not found"
}
```

| Código | Error | Cuándo ocurre | Ejemplo de mensaje |
|---|---|---|---|
| `400` | Bad Request | Validación de campos falla | `Email must be valid` |
| `401` | Unauthorized | Token ausente o inválido | `Missing Authorization header` |
| `403` | Forbidden | Usuario sin permiso sobre el recurso | `Cannot access resource of another user` |
| `404` | Not Found | Recurso inexistente | `User with id 42 not found` |
| `409` | Conflict | Regla de negocio violada (duplicado) | `Email already registered` |
| `422` | Unprocessable | Entidad válida pero no procesable | `Start date must be before end date` |
| `500` | Internal Error | Fallo inesperado | `Unexpected error` |
| `503` | Service Unavailable | Dependencia externa caída | `Auth service is not available` |

---

## 4. Modelo de datos (Entidad)

### 4.1 Entidades

#### `<User>` → tabla `users`

| Campo | Tipo | PK | Nullable | Unique | Descripción |
|---|---|---|---|---|---|
| `id` | `UUID` | ✅ | ❌ | ✅ | Identificador único |
| `email` | `varchar(255)` | ❌ | ❌ | ✅ | Correo del usuario |
| `password_hash` | `varchar(255)` | ❌ | ❌ | ❌ | Hash bcrypt (nunca en respuestas) |
| `full_name` | `varchar(120)` | ❌ | ❌ | ❌ | Nombre completo |
| `created_at` | `timestamptz` | ❌ | ❌ | ❌ | Fecha de creación (auditoría) |
| `updated_at` | `timestamptz` | ❌ | ❌ | ❌ | Fecha de modificación (auditoría) |

### 4.2 Relaciones

| Relación | Tipo | Descripción |
|---|---|---|
| `User` 1 → N `Order` | Uno-a-muchos | Un usuario puede tener muchos pedidos (reside en `orders`) |

### 4.3 Reglas de negocio

| Regla | Detalle |
|---|---|
| Unicidad | El `email` no puede repetirse |
| Borrado | Borrado lógico (`deleted_at`) o físico, indicar cuál |
| Invariantes | `created_at <= updated_at` siempre |

---

## 5. Configuración

### 5.1 Variables de entorno

| Variable | Descripción | Requerida | Default | Ejemplo |
|---|---|---|---|---|
| `SERVER_PORT` | Puerto HTTP del servicio | ❌ | `8080` | `8080` |
| `DB_URL` | JDBC URL de la base de datos | ✅ | — | `jdbc:postgresql://db:5432/users` |
| `DB_USERNAME` | Usuario de la base de datos | ✅ | — | `users` |
| `DB_PASSWORD` | Password de la base de datos | ✅ | — | *(secret)* |
| `JWT_SECRET` | Secreto para validar tokens | ✅ | — | *(secret)* |
| `LOG_LEVEL` | Nivel de log (`INFO`, `DEBUG`) | ❌ | `INFO` | `INFO` |

### 5.2 Spring profiles

| Profile | Uso |
|---|---|
| `local` | Desarrollo con `docker compose` |
| `test` | Tests automatizados (Testcontainers) |
| `k8s` | Despliegue en Kubernetes |

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
| `users` | Validar existencia y datos del usuario | HTTP/REST (`http://users:8080`) | Devolver `503` | ✅ Sí |
| `products` | Obtener datos del producto | HTTP/REST | Cachear y servir stale | ❌ No |

### 6.2 Dependencias externas

| Dependencia | Tipo | Para qué | Crítica | Comportamiento si cae |
|---|---|---|---|---|
| PostgreSQL | Base de datos | Persistencia principal | ✅ Sí | `503`, readiness en `DOWN` |
| Kafka | Mensajería | Eventos de dominio (vía outbox transaccional) | ❌ No | El outbox retiene eventos y el relay los reintenta al volver |
| API de pagos | Terceros | Procesar cobros | ✅ Sí | Reintentos con circuit breaker |

### 6.3 Eventos publicados/consumidos

| Evento | Dirección | Trigger | Payload |
|---|---|---|---|
| `user.created` | Publica | Alta de usuario | `{ id, email }` |
| `order.cancelled` | Consume | Cancelación de pedido | `{ orderId, userId }` |