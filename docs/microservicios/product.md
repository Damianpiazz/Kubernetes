# Microservicio: `productService`

## 1. Responsabilidad

Gestiona el catálogo de productos y sus categorías de la plataforma de e-commerce: altas, bajas, actualizaciones y consultas paginadas. Es la única fuente de verdad sobre productos y categorías.

No le pertenece: autenticación/autorización (el Gateway valida el JWT), usuarios/perfiles, pedidos ni pagos.

> **Decisión de diseño (por ahora):** no tiene outbox ni Kafka — solo CRUD. En el futuro puede publicar eventos `PRODUCT_*`/`CATEGORY_*` siguiendo el patrón outbox de `user-service`.

---

## 2. Endpoints

Todas las rutas son relativas a la raíz del servicio (ej. `http://products:8080`).

### 2.1 Productos — `ProductController`

| Método | Ruta | Descripción | Request | Response | Auth |
|---|---|---|---|---|---|
| `GET` | `/actuator/health` | Health check para probes de Kubernetes | — | `200` `{ status }` | No |
| `GET` | `/api/products` | Lista productos paginada (`page`, `size`, `sort`) | — | `200` Page\<ProductDto\> | Sí (Gateway) |
| `GET` | `/api/products/{productId}` | Obtiene un producto por su ID | — | `200` ProductDto | Sí (Gateway) |
| `GET` | `/api/products/{productId}/image` | Sirve la imagen del producto (proxy al storage de blobs con `Cache-Control`; `302` si `image_url` es una URL externa) | — | `200` image bytes / `302` / `404` | Sí (Gateway) |
| `POST` | `/api/products` | Crea un producto | `ProductDto` | `201` ProductDto + `Location` | Sí (Gateway) |
| `PUT` | `/api/products` | Actualiza un producto (ID en el body) | `ProductDto` (con `productId`) | `200` ProductDto | Sí (Gateway) |
| `PUT` | `/api/products/{productId}` | Actualiza un producto (ID en la ruta) | `ProductDto` | `200` ProductDto | Sí (Gateway) |
| `DELETE` | `/api/products/{productId}` | Elimina un producto (borrado físico) | — | `204` | Sí (Gateway) |

### 2.2 Categorías — `CategoryController`

| Método | Ruta | Descripción | Params / Request | Response | Auth |
|---|---|---|---|---|---|
| `GET` | `/api/categories` | Lista categorías paginada | `page`, `size`, `sort` | `200` Page\<CategoryDto\> | Sí (Gateway) |
| `GET` | `/api/categories/paging` | Paginación simple | `page=0`, `size=10` | `200` Page\<CategoryDto\> | Sí (Gateway) |
| `GET` | `/api/categories/paging-and-sorting` | Paginación + ordenamiento | `pageNo=0`, `pageSize=10`, `sortBy=categoryId` | `200` Page\<CategoryDto\> | Sí (Gateway) |
| `GET` | `/api/categories/{categoryId}` | Obtiene una categoría por su ID | — | `200` CategoryDto | Sí (Gateway) |
| `POST` | `/api/categories` | Crea una categoría | `CategoryDto` | `201` CategoryDto + `Location` | Sí (Gateway) |
| `PUT` | `/api/categories` | Actualiza una categoría (ID en el body) | `CategoryDto` (con `categoryId`) | `200` CategoryDto | Sí (Gateway) |
| `PUT` | `/api/categories/{categoryId}` | Actualiza una categoría (ID en la ruta) | `CategoryDto` | `200` CategoryDto | Sí (Gateway) |
| `DELETE` | `/api/categories/{categoryId}` | Elimina una categoría (borrado físico) | — | `204` | Sí (Gateway) |

> `sortBy=categoryId` se traduce internamente a la propiedad `id` de la entidad (el resto de propiedades: `name`, `description`).

**DTOs:**

```json
// ProductDto (entrada y salida; el ID solo se usa en PUT con body)
{
  "productId": 42,
  "title": "Notebook Lenovo",
  "imageUrl": "https://cdn.tienda.com/notebook.png",
  "sku": "NB-LEN-001",
  "price": 999.99,
  "quantity": 5,
  "categoryId": 7
}

// CategoryDto (entrada y salida; el ID solo se usa en PUT con body)
{ "categoryId": 7, "name": "Electrónica", "description": "Notebooks, tablets y más" }
```

> **Nota de auth:** el servicio no tiene dependencia de Spring Security ni valida JWT (decisión de diseño: el Gateway lo hace). Las rutas marcadas "Sí (Gateway)" están protegidas en la ruta del cluster, no dentro de este código.

---

## 3. Errores

Formato de respuesta de error: **ProblemDetail (RFC 7807)**, devuelto por `GlobalExceptionHandler`:

```json
{
  "type": "about:blank",
  "title": "Producto no encontrado",
  "status": 404,
  "detail": "Producto no encontrado con id 42"
}
```

| Código | Error | Cuándo ocurre | Ejemplo de mensaje |
|---|---|---|---|
| `400` | Bad Request | Validación de campos falla (va además la lista `errors`) | `sku: no debe estar en blanco` |
| `404` | Not Found | Producto inexistente | `Producto no encontrado con id 42` |
| `404` | Not Found | Categoría inexistente referenciada por un producto | `Categoría no encontrada con id 7` |
| `409` | Conflict | SKU duplicado | `El SKU 'NB-LEN-001' ya está en uso` |
| `409` | Conflict | Categoría duplicada | `La categoría 'Electrónica' ya existe` |
| `409` | Conflict | Carrera entre escrituras (`@version`) | `El recurso fue modificado por otra operación; reintente la petición` |
| `409` | Conflict | Violación de restricción única (carrera entre peticiones simultáneas) | `Violación de restricción única (posible carrera entre peticiones simultáneas)` |

`401`/`403` los emite el **Gateway** (token ausente/inválido), no este servicio.

---

## 4. Modelo de datos (Entidad)

### 4.1 Entidades

#### `ProductEntity` → tabla `products`

| Campo | Tipo | PK | Nullable | Unique | Descripción |
|---|---|---|---|---|---|
| `id` | `bigint` (autoincrement) | ✅ | ❌ | ✅ | Identificador único (inmutable) |
| `title` | `varchar(255)` | ❌ | ✅ | ❌ | Título descriptivo del producto |
| `image_url` | `varchar(500)` | ❌ | ✅ | ❌ | Clave del blob en el storage de imágenes (`productos/<uuid>.<ext>`) o URL externa; servida por `GET /{id}/image` |
| `sku` | `varchar(100)` | ❌ | ❌ | ✅ | Código único de inventario |
| `price` | `numeric(12,2)` | ❌ | ✅ | ❌ | Precio unitario |
| `quantity` | `int` | ❌ | ✅ | ❌ | Unidades disponibles |
| `category_id` | `bigint` | ❌ | ✅ | ❌ | FK lógica hacia `categories.id` |
| `version` | `bigint` | ❌ | ❌ | ❌ | Control de concurrencia optimista (`@Version`) |

#### `CategoryEntity` → tabla `categories`

| Campo | Tipo | PK | Nullable | Unique | Descripción |
|---|---|---|---|---|---|
| `id` | `bigint` (autoincrement) | ✅ | ❌ | ✅ | Identificador único |
| `name` | `varchar(100)` | ❌ | ❌ | ✅ | Nombre de la categoría (único) |
| `description` | `varchar(500)` | ❌ | ✅ | ❌ | Descripción opcional |
| `version` | `bigint` | ❌ | ❌ | ❌ | Control de concurrencia optimista (`@Version`) |

### 4.2 Relaciones

| Relación | Tipo | Descripción |
|---|---|---|
| `products.category_id` → `categories.id` | FK lógica (sin constraint JPA) | Columna `category_id` almacena el ID de la categoría; el servicio valida la existencia de la categoría en create/update (patrón desacoplado, misma BD) |

No hay relaciones JPA (`@ManyToOne`) en este servicio: se mantiene el desacoplamiento por identificador.

### 4.3 Reglas de negocio

| Regla | Detalle |
|---|---|
| Unicidad | `sku` no puede repetirse (check previo + restricción única como red de seguridad); en update se ignora el propio ID (`existsBySkuAndIdNot`). Ídem con el nombre de categoría (`existsByName` / `existsByNameAndIdNot`) → `409` |
| FK de categoría | `categoryId` es opcional; si viene informado, la categoría debe existir → si no, `404` |
| Alta/edición | Dos variantes de `PUT` (ID en body o en ruta) con la misma lógica de negocio |
| Borrado | Borrado **físico** (por ahora no hay eventos de dominio) |
| Concurrencia | `@Version` → conflicto simultáneo responde `409` |
| Esquema | `ddl-auto=update` (JPA crea/ajusta el esquema; no hay Flyway) |

---

## 5. Configuración

### 5.1 Variables de entorno

| Variable | Descripción | Requerida | Default | Ejemplo |
|---|---|---|---|---|
| `SERVER_PORT` | Puerto HTTP del servicio | ❌ | `8080` | `8080` |
| `SPRING_DATASOURCE_URL` | JDBC URL de la base de datos | ✅ | — | `jdbc:postgresql://db:5432/products` |
| `SPRING_DATASOURCE_USERNAME` | Usuario de la base de datos | ✅ | — | `products` |
| `SPRING_DATASOURCE_PASSWORD` | Password de la base de datos | ✅ | — | *(secret)* |
| `EUREKA_URL` | Registry de Eureka para registrarse | ❌ | `http://localhost:8761/eureka` | `http://eureka:8761/eureka` |
| `EUREKA_ENABLED` | Habilita el cliente Eureka (`false` para correr sin registry) | ❌ | `true` | `true` |
| `APP_SEED_ENABLED` | Habilita el seed de datos al arrancar (solo desarrollo) | ❌ | `false` | `true` |
| `APP_SEED_LOCATION` | Directorio con los JSON de seed (vacío = classpath embebido en la imagen) | ❌ | *(vacío)* | `/datos/seed` |
| `AZURE_STORAGE_ENDPOINT` | Endpoint del storage de imágenes (Azure Blob Storage en prod / Azurite en local) | ❌ | `http://localhost:10000/devstoreaccount1` | `https://cuenta.blob.core.windows.net` |
| `AZURE_STORAGE_ACCOUNT` | Cuenta del storage de imágenes | ❌ | `devstoreaccount1` | `cuentatienda` |
| `AZURE_STORAGE_KEY` | Clave de la cuenta (secret). Vacío ⇒ storage deshabilitado (arranca igual, sin imágenes) | ❌ | *(vacío)* | *(secret)* |
| `AZURE_STORAGE_CONTAINER` | Container destino | ❌ | `productos` | `productos` |
| `LOGGING_LEVEL_ROOT` | Nivel de log | ❌ | `INFO` | `INFO` |

### 5.2 Spring profiles

| Profile | Uso |
|---|---|
| `test` | Tests automatizados (Testcontainers PostgreSQL; config por anotaciones) |
| `local` | Desarrollo con `docker compose` *(pendiente)* |
| `k8s` | Despliegue en Kubernetes: manifiestos en `k8s/apps/base/product-service` + environments. Sin perfil `application-k8s.properties`; la config se inyecta por ConfigMap y Secret |

> **Kubernetes y Eureka**: en k8s este servicio corre con `EUREKA_ENABLED=false` (ver `k8s/apps/base/product-service/configmap.yaml`). No hay registro en Eureka ni descubrimiento `lb://`: el tráfico entra por el Gateway, que apunta a URLs directas (`PRODUCT_SERVICE_BASE_URL=http://product-service`). `eureka-server` **no se despliega** en el clúster (solo aplica a desarrollo local).

> No existe `application-{profile}.properties`: la config de test se inyecta por anotaciones (`@Import(TestcontainersConfiguration.class)`).

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
| — | — | — | — | — |

No hace llamadas HTTP sincrónicas ni consume eventos de otros servicios (por ahora). La validación de `categoryId` es local (misma BD).

### 6.2 Dependencias externas

| Dependencia | Tipo | Para qué | Crítica | Comportamiento si cae |
|---|---|---|---|---|
| PostgreSQL | Base de datos | Persistencia principal | ✅ Sí | `503`, readiness en `DOWN` |
| Azure Blob Storage (Azurite en local) | Storage de imágenes (blob) | Subir/borrar/leer imágenes de productos (`ImageStorageService`) | ❌ No | El servicio sigue `ready` (el storage **no** está en el readiness group); `GET /{id}/image` → `404` y los uploads fallan hasta que vuelva |

### 6.3 Eventos publicados/consumidos

Ninguno actualmente. El patrón outbox + Kafka de `user-service`/`auth-service` está pendiente de incorporarse.

---

## 7. Tests

| Archivo | Tipo | Cantidad | Cubre |
|---|---|---|---|
| `ProductServiceTest` | Unit (Mockito) | 8 | create, create con categoría válida, SKU duplicado (409), categoría desconocida (404), update, mismo SKU en update sin conflicto, 404, paginación |
| `CategoryServiceTest` | Unit (Mockito) | 5 | create, update, 404 en find/delete, traducción `sortBy=categoryId` → `id` |
| `ProductApiIntegrationTest` | Integración (Testcontainers PostgreSQL) | 11 | 201 + `Location`, create con categoría, 409 SKU duplicado, 400 validación, 404 (producto y categoría), paginación, PUT por ruta y por body, 409 SKU en update, delete |
| `CategoryApiIntegrationTest` | Integración (Testcontainers PostgreSQL) | 9 | 201 + `Location`, 400 validación, 404, get por ID, paginación (`/paging` y `/paging-and-sorting`), PUT por body y ruta, delete |
| `ProductServiceApplicationTests` | Context (Testcontainers PostgreSQL) | 1 | Arranque de la aplicación |

> La base de datos se testea con PostgreSQL vía Testcontainers (imagen `postgres:17-alpine`, misma que `compose.yaml`). Surefire corre con `-Duser.timezone=UTC` (la imagen oficial rechaza TZs locales como `America/Buenos_Aires`).

```bash
./mvnw test
```

`TestProductServiceApplication` no es un test: es un launcher (`main`) que arranca la app con `TestcontainersConfiguration` para desarrollo local.

---

## 8. Seeders de desarrollo

Carga inicial de datos desde JSON, pensada **solo para desarrollo local** (`compose.yaml` y el overlay de k8s local `k8s/apps/environments/local` definen `APP_SEED_ENABLED=true`; en producción la variable no existe y el runner ni se instancia gracias a `@ConditionalOnProperty`).

| Archivo | Contenido |
|---|---|
| `src/main/resources/seed/categories.json` | Categorías de ropa (Remeras, Pantalones, Buzos, Zapatillas, Accesorios) |
| `src/main/resources/seed/products.json` | Productos con `category` referenciado **por nombre** (no por ID) |
| `src/main/resources/seed/images/<SKU>.<ext>` | Foto de cada producto; se sube al storage como clave `productos/<SKU>.<ext>` |

Implementación: `seed/CatalogSeedRunner` (un `ApplicationRunner`).

- **Idempotente (insert-only)**: categorías con guard por `name`, productos con guard por `sku`; si la fila ya existe, se omite — nunca actualiza datos existentes.
- **Sin IDs hardcodeados**: cada producto resuelve su `categoryId` en runtime a partir del nombre de la categoría.
- **Errores aislados**: una fila inválida se loguea (`[seed] ...`) y no detiene el resto de la carga.
- **Orden garantizado**: primero categorías, luego productos (en un único runner).
- **Imágenes**: `seed/images/<SKU>.*` se suben al storage (Azurite/Azure) con clave determinística `productos/<SKU>.<ext>` —sondeando `jpg`, `jpeg`, `png`, `webp` y `gif`, y re-subiendo en cada arranque para reparar un volumen limpio— y se asignan como `imageUrl` solo si el producto no tiene uno (insert-only). Best-effort: si el storage está caído o deshabilitado (`app.storage.key` vacío) se loguea y el arranque continúa. El frontend las pide siempre por el proxy `GET /api/products/{id}/image`.
- **Ubicación externa**: `APP_SEED_LOCATION` apunta a un directorio en disco (p. ej. un ConfigMap montado en k8s) en lugar del classpath, permitiendo cambiar datos sin rebuild de la imagen.
