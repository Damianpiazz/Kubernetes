# Microservicio: `eurekaServer`

## 1. Responsabilidad

Es el **registry de descubrimiento de servicios** (Netflix Eureka): mantiene el registro de instancias de los microservicios y permite al Gateway (y a cualquier servicio) resolver `lb://<serviceId>` a una URL real con load balancing.

No le pertenece: enrutamiento (Gateway), lógica de negocio, persistencia ni autenticación. No expone API propia más allá de su UI/endpoint de Eureka y el health check.

---

## 2. Endpoints

| Método | Ruta | Descripción | Auth |
|---|---|---|---|
| `GET` | `/actuator/health` | Health check para probes de Kubernetes | No |
| `GET` | `/eureka/apps` | API de registro/descubrimiento de Eureka (para clientes) | No* |
| `GET` | `/` | UI de Eureka (instances registradas) | No* |

\* En producción debe ir detrás de red privada/ingress con restricciones: el registry no debe ser expuesto públicamente.

---

## 3. Errores

No tiene manejo de errores propio (no hay `GlobalExceptionHandler`): los clientes manejan el caso "registry inaccesible" reintentando el registro/fetch.

| Código | Error | Cuándo ocurre |
|---|---|---|
| `503` | Service Unavailable | El registry está en self-preservation o sin capacidad |

---

## 4. Comportamiento

| Aspecto | Detalle |
|---|---|
| Auto-registro | Deshabilitado (`register-with-eureka=false`): el registry no se registra a sí mismo |
| Fetch de registro | Deshabilitado (`fetch-registry=false`): no consume su propio registry |
| Self-preservation | Deshabilitado en local (`enable-self-preservation=false`): sin peers, la autorreplicación no aplica |
| Clientes | Los 5 servicios + Gateway se registran con su `spring.application.name` (`authService`, `userService`, `productService`, `orderService`, `paymentService`, `gateway`) y `prefer-ip-address=true` |
| Heartbeat | Los clientes renuevan el lease cada 30s (default de Eureka); una instancia se da de baja tras ~90s sin heartbeat |

### 4.1 Orden de arranque

```bash
# 1. Registry
services/eureka-server  →  8761
# 2. Microservicios (se registran)
auth-service, user-service, product-service, order-service, payment-service
# 3. Gateway (resuelve lb:// contra Eureka)
services/gateway        →  8080
```

Sin Eureka, todo puede correr igualmente: el Gateway con `EUREKA_ENABLED=false` y las `*_SERVICE_BASE_URL` apuntando a URLs directas (los servicios con `EUREKA_ENABLED=false` no se registran).

---

## 5. Configuración

### 5.1 Variables de entorno

| Variable | Descripción | Requerida | Default | Ejemplo |
|---|---|---|---|---|
| `SERVER_PORT` | Puerto HTTP del registry | ❌ | `8761` | `8761` |
| `LOGGING_LEVEL_ROOT` | Nivel de log | ❌ | `INFO` | `INFO` |

### 5.2 Spring profiles

| Profile | Uso |
|---|---|
| `test` | `contextLoads` (arranque del registry) |
| `local` | Desarrollo con `docker compose` *(pendiente)* |
| `k8s` | **No se despliega en Kubernetes**: en k8s no hay registro/descubrimiento — todos los servicios corren con `EUREKA_ENABLED=false` y el Gateway enruta con URLs directas (`*_SERVICE_BASE_URL`, ver `k8s/apps/base/*/configmap.yaml`) |

### 5.3 Recursos Kubernetes (orientativo)

| Recurso | Request | Limit | Notas |
|---|---|---|---|
| CPU | `250m` | `1000m` | — |
| Memoria | `512Mi` | `768Mi` | JVM con `-XX:MaxRAMPercentage=75`; Eureka cachea el registry en memoria |

---

## 6. Dependencias

### 6.1 Dependencias internas (otros microservicios)

| Servicio | Relación | Protocolo |
|---|---|---|
| `authService`, `userService`, `productService`, `orderService`, `paymentService`, `gateway` | Se **registran** como clientes | HTTP (Eureka REST) |

El registry no llama a ningún servicio (solo health check opcional de las instancias).

### 6.2 Dependencias externas

| Dependencia | Tipo | Para qué | Crítica | Comportamiento si cae |
|---|---|---|---|---|
| — | — | No tiene dependencias externas | — | — |

Si el registry cae: los clientes conservan su caché de registro (el Gateway sigue enrutando) y los servicios siguen atendiendo; lo que no funciona es descubrir instancias nuevas ni darse de baja. Por eso, si se llegara a desplegar en k8s, convendría un `replicas: 2` con peer discovery. **Hoy no aplica**: el despliegue en k8s no incluye este servicio (descubrimiento vía DNS de los Services del clúster).

---

## 7. Tests

| Archivo | Tipo | Cantidad | Cubre |
|---|---|---|---|
| `EurekaServerApplicationTests` | Context | 1 | Arranque del registry |

```bash
./mvnw test
```
