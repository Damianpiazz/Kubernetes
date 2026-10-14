# Microservicio: `gateway`

## 1. Responsabilidad

Es la **única puerta de entrada** a la plataforma: enruta cada petición al microservicio correspondiente, valida el JWT en el borde, limita la tasa de peticiones y agrega la observabilidad transversal (correlación, access log). También propaga la identidad (`X-User-Id`) hacia los servicios de destino.

No le pertenece: autorización por recurso (cada servicio valida además el JWT como defensa en profundidad), lógica de negocio, persistencia ni emisión de tokens (`auth-service`).

**Modelo de ejecución:** Spring Cloud Gateway **Server WebMVC** (servlet, sobre Tomcat), consistente con el resto de los microservicios (todos WebMVC). Los filtros transversales son `OncePerRequestFilter` estándar de servlet, no `GlobalFilter` reactivo.

---

## 2. Rutas y endpoints

Todas las rutas son relativas a la raíz del Gateway (ej. `http://gateway:8080`).

| Ruta recibida | Destino (`spring.application.name`) | Auth | Notas |
|---|---|---|---|
| `/api/auth/**` | `authService` | No (pública) | Login/registro/refresh |
| `/api/users/**` | `userService` | Sí (JWT) | — |
| `/api/products/**`, `/api/categories/**` | `productService` | Sí (JWT) | Una sola ruta con dos patterns |
| `/api/orders/**` | `orderService` | Sí (JWT) | — |
| `/api/payments/**` | `paymentService` | Sí (JWT) | — |
| `/api/webhooks/**` | `paymentService` | No (pública) | Webhook de MP: firma HMAC propia |
| `/actuator/health/**`, `/actuator/prometheus`, `/actuator/info` | Gateway local | No (pública) | El resto de `/actuator/**` (ej. `metrics`) exige JWT |

El destino por defecto es `lb://<serviceId>` (load balancing vía Eureka); se puede sobreescribir por variable (`AUTH_SERVICE_BASE_URL`, etc.) con una URL directa. **En k8s no hay Eureka**: el ConfigMap (`k8s/base/gateway/configmap.yaml`) fija `EUREKA_ENABLED=false` y define todas las `*_SERVICE_BASE_URL` apuntando a los Services del clúster (`http://auth-service`, etc.).

| Método | Ruta | Descripción | Auth |
|---|---|---|---|
| `GET` | `/actuator/health` | Health check para probes de Kubernetes | No |
| `GET` | `/actuator/prometheus` | Métricas para Prometheus | No |

### 2.1 Headers transversales

| Header | Dirección | Descripción |
|---|---|---|
| `X-Correlation-Id` | Entrada y salida | Se propaga si viene; si no, se genera (UUID). Siempre se reenvía al microservicio y se devuelve en la respuesta |
| `X-User-Id` | Salida (Gateway → servicio) | `sub` del JWT. **Cualquier `X-User-Id` del cliente se descarta** (anti-spoofing) |
| `X-RateLimit-Limit` / `X-RateLimit-Remaining` | Salida | Cuota y restante del rate limit aplicado |
| `Retry-After` | Salida (`429`) | Segundos hasta la siguiente cuota |

---

## 3. Errores

Formato **ProblemDetail (RFC 7807)**, emitido por `GatewayErrorController` (mismo formato que el resto de los servicios):

```json
{
  "type": "about:blank",
  "title": "Servicio no disponible",
  "status": 503,
  "detail": "El microservicio de destino no está disponible"
}
```

| Código | Error | Cuándo ocurre | Ejemplo de detalle |
|---|---|---|---|
| `401` | Unauthorized | Token ausente, inválido o vencido en ruta protegida (lo emite Spring Security, body vacío + `WWW-Authenticate`) | — |
| `413` | Payload Too Large | `Content-Length` supera `GATEWAY_MAX_REQUEST_SIZE` | `El cuerpo de la petición supera el máximo permitido de 1048576 bytes` |
| `429` | Too Many Requests | Rate limit excedido (ver `Retry-After`) | `Límite de peticiones excedido; reintente más tarde` |
| `404` | Not Found | Ruta sin registrar | `No existe ninguna ruta registrada para este recurso` |
| `502`/`503` | Bad Gateway / Service Unavailable | Microservicio de destino caído o sin instancias disponibles (en `lb://`, sin registros en Eureka) | `El microservicio de destino no está disponible` |
| `504` | Gateway Timeout | El destino no respondió dentro de `GATEWAY_READ_TIMEOUT_MS` | `El microservicio de destino no respondió a tiempo` |

---

## 4. Seguridad

| Capa | Detalle |
|---|---|
| Resource server | Spring Security `oauth2ResourceServer` con JWT **HS256**, mismo `JWT_SECRET`/`JWT_ISSUER` que emite `auth-service` |
| Rutas públicas | `/api/auth/**`, `/api/webhooks/**`, `/actuator/health/**`, `/actuator/prometheus`, `/actuator/info` (`SecurityConfig.PUBLIC_PATHS`); **todo lo demás** —incluido el resto de `/actuator/**`— exige `Authorization: Bearer <jwt>` |
| Propagación de identidad | `IdentityPropagationFilter` inyecta `X-User-Id` con el `sub` y **elimina siempre** el header enviado por el cliente |
| Rate limiting | `RateLimitFilter` (Resilience4j, in-memory): clave = `user:<sub>` si hay token, si no `ip:<ip>` |
| CORS | `CorsConfigurationSource` dentro de la cadena de seguridad: el preflight `OPTIONS` se contesta sin JWT; con `allow-credentials=true` los orígenes se listan explícitamente |
| Estado | Sin sesiones (`STATELESS`), CSRF deshabilitado (API sin cookies) |

> El rate limiting es **por instancia** (memoria): con N réplicas del Gateway el límite efectivo se multiplica por N. Deuda futura: contadores en Redis para un límite global.

### 4.1 Rate limit por ruta

| Ruta | Límite (default) | Config |
|---|---|---|
| Default | 60 peticiones/min por clave | `gateway.ratelimit.default-*` |
| `auth` | 10/min por clave (fuerza bruta de login) | `gateway.ratelimit.routes.auth.*` |
| Exentas | Webhooks y actuator | `gateway.ratelimit.excluded-paths` |

---

## 5. Configuración

### 5.1 Variables de entorno

| Variable | Descripción | Requerida | Default | Ejemplo |
|---|---|---|---|---|
| `SERVER_PORT` | Puerto HTTP del Gateway | ❌ | `8080` | `8080` |
| `EUREKA_URL` | Registry de Eureka | ❌ | `http://localhost:8761/eureka` | `http://eureka:8761/eureka` |
| `EUREKA_ENABLED` | Habilita el cliente Eureka (`false` = URLs directas) | ❌ | `true` | `true` |
| `AUTH_SERVICE_BASE_URL` | Override directo de la ruta `auth` | ❌ | `lb://authService` | `http://auth:8080` |
| `USER_SERVICE_BASE_URL` | Override directo de la ruta `users` | ❌ | `lb://userService` | `http://user:8080` |
| `PRODUCT_SERVICE_BASE_URL` | Override directo de la ruta `products` | ❌ | `lb://productService` | `http://product:8080` |
| `ORDER_SERVICE_BASE_URL` | Override directo de la ruta `orders` | ❌ | `lb://orderService` | `http://order:8080` |
| `PAYMENT_SERVICE_BASE_URL` | Override directo de `payments` y `webhooks` | ❌ | `lb://paymentService` | `http://payment:8080` |
| `JWT_SECRET` | Secreto HS256 para validar tokens, ≥ 32 caracteres | ✅ | — | *(secret)* |
| `JWT_ISSUER` | `iss` esperado en los tokens | ❌ | `auth-service` | `auth-service` |
| `GATEWAY_MAX_REQUEST_SIZE` | Tamaño máximo de `Content-Length` (bytes) | ❌ | `1048576` | `1048576` |
| `GATEWAY_CONNECT_TIMEOUT_MS` | Timeout de conexión hacia el destino (ms) | ❌ | `2000` | `2000` |
| `GATEWAY_READ_TIMEOUT_MS` | Timeout de respuesta hacia el destino (ms) | ❌ | `10000` | `10000` |
| `CORS_ALLOWED_ORIGINS` | Orígenes permitidos (coma) | ❌ | `http://localhost:5173` | `https://tienda.com` |
| `CORS_ALLOWED_METHODS` | Métodos HTTP permitidos (coma) | ❌ | `GET,POST,PUT,PATCH,DELETE,OPTIONS` | `GET,POST` |
| `CORS_ALLOWED_HEADERS` | Headers permitidos (coma) | ❌ | `Authorization,Content-Type,X-Correlation-Id` | `Authorization` |
| `LOGGING_LEVEL_ROOT` | Nivel de log | ❌ | `INFO` | `INFO` |

> En k8s `JWT_SECRET` debe inyectarse como Secret y debe ser **el mismo** que usa `auth-service` para emitir; si no coincide, todos los tokens se rechazan con `401`.

### 5.2 Spring profiles

| Profile | Uso |
|---|---|
| `test` | Tests automatizados (Eureka deshabilitado, rutas hacia un stub HTTP del JDK, `jwt.secret` de prueba) |
| `local` | Desarrollo con `docker compose` *(pendiente)* |
| `k8s` | Despliegue en Kubernetes: manifiestos en `k8s/base/gateway` + overlays. Sin perfil `application-k8s.properties`; la config se inyecta por ConfigMap (`EUREKA_ENABLED=false`, `*_SERVICE_BASE_URL` directas) y Secret |

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
| `authService` | Enrutar `/api/auth/**` | HTTP/REST (`lb://authService`) | `502`/`503` ProblemDetail | ✅ Sí |
| `userService` | Enrutar `/api/users/**` | HTTP/REST (`lb://userService`) | `502`/`503` | ✅ Sí |
| `productService` | Enrutar `/api/products/**` y `/api/categories/**` | HTTP/REST (`lb://productService`) | `502`/`503` | ✅ Sí |
| `orderService` | Enrutar `/api/orders/**` | HTTP/REST (`lb://orderService`) | `502`/`503` | ✅ Sí |
| `paymentService` | Enrutar `/api/payments/**` y `/api/webhooks/**` | HTTP/REST (`lb://paymentService`) | `502`/`503` | ✅ Sí |

### 6.2 Dependencias externas

| Dependencia | Tipo | Para qué | Crítica | Comportamiento si cae |
|---|---|---|---|---|
| Eureka | Descubrimiento | Resolver `lb://<serviceId>` a instancias | ⚠️ Parcial | `EUREKA_ENABLED=false` + `*_SERVICE_BASE_URL` directas |
| Prometheus | Scraping | Métricas vía `/actuator/prometheus` | ❌ No | Sin métricas, el tráfico sigue |

No usa base de datos ni Kafka: no persiste nada.

---

## 7. Tests

| Archivo | Tipo | Cantidad | Cubre |
|---|---|---|---|
| `CorrelationIdFilterTest` | Unit (Mock servlet) | 3 | Generación, propagación y reenvío downstream del correlation id |
| `IdentityPropagationFilterTest` | Unit (Mock servlet + SecurityContext) | 3 | Inyección de `X-User-Id`, descarte del spoofeado, sin principal no setea |
| `RateLimitFilterTest` | Unit (Resilience4j real) | 5 | Límite default, `429` + `Retry-After`, rutas exentas, limiter por ruta, clave por IP |
| `RequestSizeLimitFilterTest` | Unit (Mock servlet) | 2 | Paso dentro del límite y `413` al superarlo |
| `GatewayRoutingIntegrationTest` | Integración (stub HTTP del JDK) | 7 | `401` sin token, enrutamiento + `X-User-Id`, sanitización del spoof, ruta pública auth, correlación end-to-end, preflight CORS y origen rechazado |
| `GatewayApplicationTests` | Context | 1 | Arranque de la aplicación |

```bash
./mvnw test
```
