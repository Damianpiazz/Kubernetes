# Cómo está configurado el despliegue en Kubernetes

Este documento explica **cómo y por qué** está armado el scaffolding de Kubernetes
del repo: inventario de recursos, modelo de configuración, secretos, entornos,
observabilidad, decisiones de diseño y pendientes.

Para otras tareas:

| Querés… | Ir a |
|---|---|
| Operar el clúster (`kubectl`, logs, rollouts, debug) | [`comandos.md`](./comandos.md) |
| La estructura breve de `k8s/` y sus reglas | [`k8s/README.md`](../../k8s/README.md) |
| Usar los scripts (`.sh` / `.ps1`) y sus códigos de salida | [`scripts/README.md`](../../scripts/README.md) |
| Detalle de cada microservicio | [`docs/microservicios/`](../microservicios/) |

---

## 1. Qué se despliega

**Seis microservicios Spring Boot 4.1.1 (Java 17, Maven)**, todos con Actuator y
`/actuator/health/{readiness,liveness}`:

| Workload (Deployment) | Base de datos | Kafka | Rutas por el gateway | Notas |
|---|---|---|---|---|
| `auth-service` | `auth-db` | ✅ | `/api/auth/**` | Emite y valida el JWT |
| `user-service` | `users-db` | ✅ | `/api/users/**` | |
| `product-service` | `products-db` | ❌ | `/api/products/**`, `/api/categories/**` | Único servicio sin Kafka |
| `order-service` | `orders-db` | ✅ | `/api/orders/**` | Llama a `product-service` por HTTP |
| `payment-service` | `payments-db` | ✅ | `/api/payments/**`, `/api/webhooks/**` | Llama a `order-service` por HTTP; Mercado Pago |
| `gateway` | — | ❌ | Todo lo anterior | Spring Cloud Gateway: rutas, JWT, CORS, rate limit |

**Tier de datos** (solo entorno local): 5 × PostgreSQL 17 (`auth-db`, `users-db`,
`products-db`, `orders-db`, `payments-db`) + 1 × Kafka en modo KRaft *single-node*.

**Observabilidad**: Prometheus, Alertmanager, Loki, Tempo, Alloy y Grafana en su
propio namespace.

### Lo que **no** se despliega en Kubernetes

| Componente | Motivo |
|---|---|
| `eureka-server` | En k8s no hay descubrimiento Spring Cloud: todos los servicios corren con `EUREKA_ENABLED=false` y el gateway apunta a URLs directas (`http://auth-service`, etc.). Eureka solo aplica a desarrollo local con Docker Compose. |
| Frontend (SPA) | Vive **fuera del clúster**: Next.js en `frontend/`, desplegado en Vercel y hablando con el gateway por CORS (dominio exacto en producción, `*` en local). En `compose.yaml` solo hay una **red** llamada `frontend`, no un servicio. |
| `data/base` en producción | Las bases de datos y Kafka en producción son servicios gestionados (ver §9). |

---

## 2. Arquitectura

```text
                     local                         production
              ┌────────────────────┐        ┌──────────────────────────┐
 navegador ───┤ port-forward        │        │ DNS → Ingress            │
  :3000 ──────► svc/gateway 8080:80 │        │host api.CHANGE-ME → gw   │
              └─────────┬──────────┘        └────────────┬─────────────┘
                        ▼                                ▼
              ┌──────────────────────────────────────────────────────┐
              │  gateway (ClusterIP :80 → pod :8080)                 │
              │  rutas · JWT · CORS · rate limit · logs estructurados│
              └───┬──────────┬──────────┬──────────┬──────────┬──────┘
                  │          │          │          │          │
                  ▼          ▼          ▼          ▼          ▼
             auth-service user-service product  order     payment
                  │          │          service    │          │
                  │          │          │          │          │
                  ▼          ▼          ▼          ▼          ▼
              auth-db    users-db   products-db orders-db payments-db   (Postgres 17)
                  └──────────┴──────────┴────┬─────┴──────────┘
                                             ▼
                                    kafka (KRaft, 9092)
                                             ▲
   namespace latiendita ◄────────────────────┘
   namespace latiendita-observability
     ├── Prometheus ──► scrape por anotación prometheus.io/* de los Services
     ├── Alloy (DaemonSet) ──► logs vía API de k8s → Loki
     │                    ◄── OTLP de las apps (OTLP_TRACING_ENDPOINT) → Tempo
     ├── Loki · Tempo · Alertmanager
     └── Grafana (dashboard RED, enlaces logs ↔ trazas)
```

Dos namespaces a propósito:

| Namespace | Contenido | Por qué aparte |
|---|---|---|
| `latiendita` | apps + tier de datos | — |
| `latiendita-observability` | stack de observabilidad | `k8s/apps/base` fuerza `namespace: latiendita`; si estuvieran en el mismo kustomization, pisaría el namespace de la observabilidad |

---

## 3. Estructura del repo

```text
.
├── k8s/                              Manifiestos de Kubernetes (ver §4)
│   ├── apps/                         Stack 1: los 6 microservicios
│   │   ├── base/
│   │   └── environments/{local,production}
│   ├── data/                         Stack 2: Postgres + Kafka (solo local)
│   │   └── base/
│   └── observability/                Stack 3: stack de observabilidad
│       ├── base/
│       └── environments/{local,production}
│
├── config/observability/             Config de Prometheus/Loki/Tempo/Grafana/
│                                     Alloy/Alertmanager: FUENTE ÚNICA compartida
│                                     con Docker Compose (tiene su propio
│                                     kustomization.yaml que genera ConfigMaps)
│
├── scripts/                          4 pares .sh/.ps1 autocontenidos (§12)
├── docs/kubernetes/                  comandos.md (operación) + este archivo
├── services/<servicio>/              Código de los microservicios (Maven)
├── compose.yaml / compose.override.yaml
├── compose.observability.yaml        Alternativa Docker Compose
├── .env / .env.example               Variables para Compose (gitignored)
├── terraform/                        Vacío (solo .gitkeep): infra como código pendiente
├── .github/                          Vacío: sin workflows de CI/CD todavía
└── frontend/                         SPA Next.js: fuera del clúster, se despliega en Vercel
```

---

## 4. El modelo: `stacks` + `environments`

**Convención del repo: `<stack>/base` + `<stack>/environments/<entorno>`** — el
término "overlay" ya no se usa.

```text
k8s/apps/base                    manifiestos neutrales: sin entorno, sin credenciales
        ▲
        │ resources: ../../base
        │
k8s/apps/environments/local      base + data/base + secretGenerator (secrets.env)
k8s/apps/environments/production base + ingress + pdb + images + replicas + endpoints externos
```

Así se renderiza el resultado final:

```bash
kubectl kustomize k8s/apps/environments/local      # no toca el clúster
kubectl kustomize k8s/apps/environments/production
kubectl kustomize k8s/observability/environments/local
kubectl kustomize k8s/observability/environments/production
```

### Reglas que sostienen el modelo

1. **Nunca aplicar `base/` directamente.** Los Secretos (`latiendita-secrets`,
   `grafana-admin`) se generan en cada `environment`; aplicar solo la base deja los
   pods sin Secret y el rollout queda en `CreateContainerConfigError`.
2. **`namespace` se declara en cada `base/`** (apps y data → `latiendita`,
   observabilidad → `latiendita-observability`). Por eso los stacks se despliegan
   por separado: un `namespace:` a nivel de entorno pisaría el del otro stack.
3. **`data/` solo se instancia en `environments/local`.** En producción las bases de
   datos y Kafka son servicios gestionados y sus endpoints entran por
   `configMapGenerator` con `behavior: merge`.
4. **Las credenciales no se commitean**: van en un archivo `.env` gitignored con su
   `.example` como plantilla.

---

## 5. Stack `apps`

Cada servicio tiene tres archivos en `k8s/apps/base/<servicio>/`:

| Archivo | Qué define |
|---|---|
| `configmap.yaml` | Config no sensible (URLs, claves de feature flags) |
| `deployment.yaml` | Imagen, probes, recursos, seguridad, secretos consumidos |
| `service.yaml` | `ClusterIP` `80 → targetPort http (8080)` + anotaciones de Prometheus |

### Workload típico (idéntico patrón en los 6)

```yaml
replicas: 1                      # local; producción sube a 2 (§9)
revisionHistoryLimit: 3
strategy: RollingUpdate { maxSurge: 1, maxUnavailable: 0 }   # cero downtime
image: latiendita/<servicio>:dev   # imagePullPolicy: IfNotPresent (kind load)
```

**Probes** (todas sobre Actuator):

| Probe | Endpoint | Parámetros |
|---|---|---|
| `startupProbe` | `/actuator/health/readiness` | 5 s × 24 fallos → hasta 120 s para arrancar la JVM |
| `readinessProbe` | `/actuator/health/readiness` | 10 s × 3 |
| `livenessProbe` | `/actuator/health/liveness` | 15 s × 3 |

**Recursos**:

| Workload | Requests | Limits |
|---|---|---|
| los 6 servicios | `250m` / `256Mi` | `1` CPU / `512Mi` (gateway: `384Mi`) |
| `*-db` (Postgres) | `100m` / `128Mi` | `500m` / `384Mi` |
| `kafka` | `250m` / `512Mi` | `1` CPU / `1Gi` |
| observabilidad | según workload (100–250m / 128–512Mi) | ver manifiestos |

**Seguridad aplicada a los pods de aplicación**:

| Directiva | Valor |
|---|---|
| Usuario | `runAsNonRoot: true`, uid/gid `1001`, `fsGroup: 1001` |
| Seccomp | `RuntimeDefault` |
| Filesystem | `readOnlyRootFilesystem: true` + `emptyDir` montado en `/tmp` |
| Capabilities | `drop: [ALL]`, `allowPrivilegeEscalation: false` |
| Graceful shutdown | `server.shutdown=graceful` (30s/fase) + `preStop: sleep 5` + `terminationGracePeriodSeconds: 40` |
| Spread de réplicas | `topologySpreadConstraints` por `kubernetes.io/hostname` (`ScheduleAnyway`) para que el PDB valga contra caída de nodo |

**Labels** (usadas por Services, Prometheus, Alloy y por los `kubectl get pods -l`):

```yaml
app.kubernetes.io/name: <servicio>
app.kubernetes.io/part-of: la-tiendita
```

---

## 6. Stack `data` (solo local)

| Recurso | Detalle |
|---|---|
| 5 × `StatefulSet` Postgres | Imagen `postgres:17-alpine`; DB/usuario = nombre del servicio (`auth/auth`, `users/users`, …) |
| Service de cada base | **Headless** (`clusterIP: None`) → DNS estable `auth-db:5432` |
| PVC por base | `1Gi` (`volumeClaimTemplates`, `ReadWriteOnce`) |
| `StatefulSet` Kafka | `apache/kafka:3.9.0` en modo KRaft `broker,controller` (un solo nodo), `CLUSTER_ID` fijo, `KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://kafka:9092`, replicación `1` |
| Service Kafka | Headless, `9092`, `publishNotReadyAddresses: true` |
| PVC Kafka | `2Gi` |

La contraseña de cada Postgres **no está en el manifiesto**: sale de
`latiendita-secrets` vía `secretKeyRef` (misma clave que consume el servicio de
aplicación correspondiente, p. ej. `AUTH_DB_PASSWORD`), de modo que app y base
coinciden por construcción.

---

## 7. Configuración de las aplicaciones

### ConfigMap por servicio (`k8s/apps/base/<servicio>/configmap.yaml`)

| Clave | auth | user | product | order | payment | gateway |
|---|---|---|---|---|---|---|
| `SPRING_DATASOURCE_URL` (`jdbc:postgresql://<db>:5432/<db>`) | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| `SPRING_DATASOURCE_USERNAME` | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| `KAFKA_BOOTSTRAP_SERVERS=kafka:9092` | ✅ | ✅ | ❌ | ✅ | ✅ | — |
| `EUREKA_ENABLED=false` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `OTLP_TRACING_ENDPOINT` (FQDN hacia Alloy) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `TRACING_SAMPLING_PROBABILITY=0.1` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `*_BASE_URL` (llamadas entre servicios) | — | — | — | `PRODUCT_SERVICE_BASE_URL` | `ORDER_SERVICE_BASE_URL` | las 5 rutas |
| `CORS_ALLOWED_ORIGINS` | — | — | — | — | — | `*` en local; en producción el dominio exacto de Vercel |

Tres claves merecen explicación:

- **`CORS_ALLOWED_ORIGINS`** implementa la política local/producción: en local
  cualquier origen (`*` en el configmap base y en `compose.yaml`) y en
  producción solo el dominio exacto del frontend en Vercel (sin patrones ni
  previews). El default de la property (`http://localhost:3000`) es
  restrictivo a propósito: un deploy que olvide la variable falla con errores
  de CORS visibles en vez de quedar abierto.

- **`OTLP_TRACING_ENDPOINT`** apunta a
  `http://alloy.latiendita-observability.svc.cluster.local:4318/v1/traces` con
  nombre **FQDN** porque Alloy vive en otro namespace (la resolución corta
  `alloy:4318` solo funciona dentro de `latiendita-observability`).
- **`EUREKA_ENABLED=false`** es lo que hace innecesario `eureka-server` en k8s: el
  gateway enruta por nombre de Service de Kubernetes.

### Config que viene del propio `application.properties`

Cada servicio expone (por variable de entorno, con default):

```properties
management.endpoints.web.exposure.include=health,info,prometheus,metrics
management.endpoint.health.probes.enabled=true
management.metrics.tags.service=${spring.application.name}   # etiqueta del dashboard RED
management.tracing.sampling.probability=${TRACING_SAMPLING_PROBABILITY:0.1}
management.otlp.tracing.endpoint=${OTLP_TRACING_ENDPOINT:http://localhost:4318/v1/traces}
logging.structured.format.console=logstash                    # log JSON: lo parsea Alloy
```

> La etiqueta `service` de las métricas es **de la app**, no un `relabel` de
> Prometheus: si Prometheus la renombrara a `exported_service` se rompería el
> dashboard RED.

### Secretos consumidos (`secretKeyRef` → `latiendita-secrets`)

| Consumidor | Claves |
|---|---|
| `auth-service` | `AUTH_DB_PASSWORD`, `JWT_SECRET` |
| `gateway` | `JWT_SECRET` |
| `user-service` | `USERS_DB_PASSWORD` |
| `product-service` | `PRODUCTS_DB_PASSWORD` |
| `order-service` | `ORDERS_DB_PASSWORD` |
| `payment-service` | `PAYMENTS_DB_PASSWORD`, `MERCADO_PAGO_ACCESS_TOKEN`, `MERCADO_PAGO_WEBHOOK_SECRET` |
| cada `StatefulSet` de Postgres | la misma clave de contraseña que su servicio |

Grafana usa `envFrom: secretRef: grafana-admin` en lugar de `secretKeyRef` por
clave, **a propósito**: `deploy.sh` exige que toda clave referenciada exista en el
archivo de secrets, y estas credenciales las genera el propio `kustomization`.
Además, desde el cierre del patrón de producción el guard de `deploy.*` revisa
**todas** las claves del archivo `-s` (no solo las de `secretKeyRef`), así que un
`CHANGE-ME` en el `secrets.env` de observabilidad sale con **exit 6**.

---

## 8. Secretos

| Entorno | Cómo se generan | Archivo fuente |
|---|---|---|
| apps · local | `secretGenerator` de Kustomize → Secret `latiendita-secrets` | `k8s/apps/environments/local/secrets.env` (**gitignored**) |
| apps · production | `secretGenerator` de Kustomize → Secret `latiendita-secrets` | `k8s/apps/environments/production/secrets.env` (**gitignored**) |
| observabilidad · local | `secretGenerator` con literales de desarrollo (`admin`/`admin`) | `k8s/observability/environments/local/kustomization.yaml` |
| observabilidad · production | `secretGenerator` con `envs: [secrets.env]` → Secret `grafana-admin` | `k8s/observability/environments/production/secrets.env` (**gitignored**) |

Plantilla `k8s/apps/environments/local/secrets.env.example` (8 claves):

```dotenv
JWT_SECRET=dev-only-change-me-min-32-chars-0000
AUTH_DB_PASSWORD=changeme-auth
USERS_DB_PASSWORD=changeme-users
PRODUCTS_DB_PASSWORD=changeme-products
ORDERS_DB_PASSWORD=changeme-orders
PAYMENTS_DB_PASSWORD=changeme-payments
MERCADO_PAGO_ACCESS_TOKEN=          # opcional
MERCADO_PAGO_WEBHOOK_SECRET=        # opcional
```

- `scripts/kind-up.*` crea el `secrets.env` desde el `.example` **solo si no
  existe** (nunca pisa lo que ya hay).
- `scripts/secrets.*` genera los cuatro archivos de entorno que falten
  (`.env` de Compose + los tres `secrets.env`) desde su `.example`, rellenando
  `JWT_SECRET`, `*_DB_PASSWORD` y `*_ADMIN_PASSWORD` con valores aleatorios
  fuertes **distintos por archivo** y conservando el resto de claves y
  comentarios; nunca pisa lo existente (salvo `--force`) y con `--check` solo
  verifica faltantes y valores de plantilla (**exit 4**).
- `scripts/deploy.*` extrae del manifiesto las claves que aparecen en
  `secretKeyRef` y valida que existan en el archivo: si falta alguna → **exit 5**;
  si están definidas pero vacías (las de Mercado Pago) → solo aviso.
- Producción usa **el mismo patrón** con su propio archivo
  `k8s/apps/environments/production/secrets.env` (gitignored, plantilla
  `secrets.env.example` con `CHANGE-ME-*`; se crea con
  `scripts/secrets.sh --target production`). Si no existe, `kubectl kustomize`
  falla → **exit 3**; y si algún valor sigue siendo de plantilla
  (`CHANGE-ME`, `changeme`, `dev-only`) en un target `production` → **exit 6**.
- `generatorOptions.disableNameSuffixHash: true` en todos los generadores: los
  nombres quedan estables e inspeccionables (`kubectl get secret latiendita-secrets`),
  a cambio de que un cambio de contenido **no** reinicie los pods solo — hay que
  hacer `kubectl rollout restart`.

---

## 9. Entorno `local` vs `production`

| | `local` (kind / k3d) | `production` |
|---|---|---|
| Clúster | kind `latiendita-dev` (contexto `kind-latiendita-dev`; verificar con `kubectl config current-context` antes de aplicar) | cualquier clúster real |
| Imágenes | `latiendita/<servicio>:dev`, cargadas con `kind load` (`IfNotPresent`) | `images:` → `CHANGE-ME/latiendita/<servicio>:CHANGE-ME` (registry + tag inmutable) + patch `imagePullPolicy: Always` |
| Réplicas | 1 | 2 por servicio (`replicas:`) |
| Disponibilidad | — | 6 `PodDisruptionBudget` con `minAvailable: 1` + `topologySpreadConstraints` entre nodos |
| Límites de agregado | — | `ResourceQuota` + `LimitRange` (`quotas.yaml`, solo en este overlay) |
| Tier de datos | incluido (`../../data/base`) | **excluido** |
| Endpoints DB/Kafka | nombres de Service dentro del clúster (`auth-db:5432`, `kafka:9092`) | externos por `configMapGenerator` + `behavior: merge` (`CHANGE-ME...`) |
| Ingress | no hay (port-forward) | `ingress.yaml` → `gateway:80`, path `/api` en `api.CHANGE-ME.example.com` (solo API: la UI vive en Vercel) |
| CORS | `*` (cualquier origen, por merge del configmap base) | `https://CHANGE-ME.vercel.app` exacto (por merge; sin patrones ni previews) |
| Secretos de Grafana | `admin`/`admin` | `CHANGE-ME` |

### Antes de aplicar `production` hay que reemplazar todos los `CHANGE-ME`

- `k8s/apps/environments/production/kustomization.yaml`: `images` (registry y tag
  de las 6 imágenes) y los `configMapGenerator` (URL/user de las 5 bases, Kafka, CORS).
- `k8s/apps/environments/production/ingress.yaml`: `ingressClassName` y `host`.
- `k8s/observability/environments/production/secrets.env`: **crear** desde su
  `secrets.env.example` (gitignored) con la contraseña real de Grafana —
  `scripts/secrets.sh --target observability` la genera con valor fuerte.
  Sin él el render falla; con valores de plantilla el deploy sale con **exit 6**.
- `k8s/observability/environments/production/kustomization.yaml`: si el clúster no
  tiene StorageClass por defecto, descomentar el bloque comentado de `storageClassName`.
- `k8s/observability/environments/production/ingress.yaml`: `ingressClassName` y host.
- `k8s/apps/environments/production/secrets.env`: **crear** desde su
  `secrets.env.example` (el archivo real es gitignored) y rellenar los
  `CHANGE-ME-*` con valores generados — `scripts/secrets.sh --target apps`
  lo hace de una. Sin él el render falla (**exit 3**);
  con valores de plantilla el deploy falla (**exit 6**).

---

## 10. Red y puntos de entrada

| Concepto | Implementación |
|---|---|
| Service de app | `ClusterIP`, `port: 80 → targetPort: http (8080)` en los 6 servicios |
| Service de datos | Headless (`clusterIP: None`) para DNS estable de StatefulSets |
| DNS interna | `<svc>.<namespace>.svc.cluster.local` (FQDN usado para cruzar hacia Alloy) |
| Local | `kubectl port-forward svc/gateway 8080:80 -n latiendita` |
| Producción (apps) | Ingress → `gateway:80`, path `/` (Prefix), con `tls:` vía cert-manager |
| Producción (Grafana) | Ingress en **host propio** (`grafana.CHANGE-ME.example.com`) con `tls:` vía cert-manager, deliberadamente no bajo `/grafana` del host de las apps: evita el modo subpath de Grafana (`GF_SERVER_ROOT_URL` + `SERVE_FROM_SUB_PATH`), propenso a romper assets |
| CORS | Configurado en el gateway (`CORS_ALLOWED_ORIGINS`), no con NetworkPolicy |
| NetworkPolicy | Default-deny + matriz explícita en ambos stacks (ver §10.1). La CNI de kind no la aplica |
| TLS | cert-manager + Let's Encrypt (HTTP-01) en producción (ver §10.2) |
| HPA | **No hay** (ver §15) |

### 10.1 Aislamiento de red (NetworkPolicy)

Cada stack declara su default-deny con la matriz de tráfico real como
comentario (todo lo que se permite está enumerado punto por punto):

- **`k8s/apps/base/networkpolicy.yaml`** — aislamiento de **ingress** (aplica en
  local y producción): este-oeste (gateway → backends, order → product,
  payment → order), apps → tier de datos, apps → Alloy (OTLP cross-namespace),
  Prometheus → pods de apps (el `kubernetes_sd role=endpoints` scrapea pod IPs,
  no Services: sin esa regla el scrape cae entero).
- **`k8s/apps/environments/local/networkpolicy-egress.yaml`** — aislamiento de
  **egress**, solo local: acá todo es in-cluster (Postgres y Kafka son
  StatefulSets), así que se niega la salida y se abre DNS, datos y OTLP.
- **`k8s/observability/base/networkpolicy.yaml`** — ingress + egress del stack
  de observabilidad (idéntico en ambos entornos: Alloy → API del cluster,
  scrape cruzado, ruler → Alertmanager, Grafana → datasources).
- **`…/environments/production/networkpolicy-ingress.yaml`** (ambos stacks) —
  único origen externo permitido: el namespace del controlador del Ingress
  (`CHANGE-ME`, coherente con `ingressClassName`).

Advertencias: en **local** kindnet no enforcea NetworkPolicy (validación
estructural; el port-forward no se afecta: el kubelet inyecta ese tráfico fuera
del datapath del CNI). En **producción** la CNI del clúster debe enforcear
(Calico, Cilium, etc.). El egress deny de local **no** se replica en
producción: las DB/Kafka gestionadas tienen endpoints externos no declarables de
forma portable (FQDN lo soportan solo Cilium y similares).

### 10.2 TLS con cert-manager

En producción ambos Ingress llevan la anotación
`cert-manager.io/cluster-issuer: letsencrypt-prod` y un bloque `tls:`; los
Secretos (`la-tiendita-tls`, `grafana-tls`) los emite y renueva cert-manager.
El `ClusterIssuer` (HTTP-01, email y `ingressClassName` con `CHANGE-ME`) vive
en `k8s/cluster-scope/clusterissuer-letsencrypt.yaml`: es un recurso
cluster-scoped, se aplica una vez y queda fuera de los kustomize de los stacks:

```bash
# 1. Instalar cert-manager en el clúster (versión pinneada)
kubectl apply -f https://github.com/cert-manager/cert-manager/releases/download/v1.21.2/cert-manager.yaml
kubectl wait --for=condition=Available -n cert-manager deploy --all

# 2. Registrar el issuer (una sola vez)
kubectl apply -f k8s/cluster-scope/clusterissuer-letsencrypt.yaml

# 3. Verificar la emisión tras aplicar el environment de producción
kubectl get certificate -n latiendita
kubectl describe certificate la-tiendita-tls -n latiendita
```

Para testear sin consumir los límites de Let's Encrypt: duplicar el issuer
apuntando a `acme-staging-v02.api.letsencrypt.org/directory` y usar esa
anotación mientras se valida.

---

## 11. Observabilidad

Stack completo en `latiendita-observability`, desplegado **aparte** de las apps
(otro namespace, otro `kubectl apply -k`): así se puede reiniciar la observabilidad
sin tocar la aplicación y viceversa.

| Componente | Imagen | Rol | Persistencia |
|---|---|---|---|
| Prometheus | `prom/prometheus:v3.15.0` | Métricas + reglas de alerta | PVC `2Gi` |
| Alertmanager | `prom/alertmanager:v0.28.1` | Deduplicación/ruteo de alertas | — (config) |
| Loki | `grafana/loki:3.7.8` | Logs + ruler → Alertmanager | PVC `5Gi` |
| Tempo | `grafana/tempo:2.10.8` | Trazas | PVC `5Gi` |
| Alloy | `grafana/alloy:v1.20.1` (DaemonSet) | Recolecta logs (API de k8s) + recibe OTLP | `emptyDir` |
| Grafana | `grafana/grafana:13.2.3` | Dashboards y correlación | PVC `grafana-data` `1Gi` |

### Configuración compartida con Docker Compose

`config/observability/` es la **fuente única**: los mismos archivos que monta
Docker Compose se embeben como ConfigMaps para Kubernetes mediante
`config/observability/kustomization.yaml`, que `k8s/observability/base` incluye
como `resource` (Kustomize no permite referenciar archivos fuera de la raíz de un
kustomization, por eso existe ese archivo intermedio). No se duplica config.

| ConfigMap | Archivo de origen | Variante |
|---|---|---|
| `prometheus-config` | `prometheus/prometheus.k8s.yml` | **k8s**: `kubernetes_sd` (role `endpoints`) en vez de targets estáticos |
| `alloy-config` | `alloy/config.k8s.alloy` | **k8s**: logs vía API de Kubernetes (sin `docker.sock` ni hostPath) |
| `loki-config`, `tempo-config`, `alertmanager-config`, `prometheus-rules`, `loki-rules`, `grafana-*` | los mismos de Docker | idénticos en ambos entornos |

### Cadenas de datos

- **Métricas**: los Services declaran `prometheus.io/scrape: "true"` y
  `prometheus.io/path: /actuator/prometheus`; Prometheus los descubre por
  anotación, filtrando por namespace `latiendita`. Sin Prometheus Operator ni
  `ServiceMonitor`.
- **Logs**: Alloy (un pod por nodo) descubre los pods del namespace `latiendita`
  con `loki.source.kubernetes` (subrecurso `pods/log`, sin hostPath) y aplica el
  mismo pipeline que en Docker: extrae `level`, `application_name` y `traceId` del
  JSON y promueve `level`/`application_name` a labels de Loki.
- **Trazas**: las apps envían OTLP a Alloy (`:4318`) y Alloy reenvía a Tempo.
- **Correlación**: Grafana viene *provisionada* — datasources con
  `derivedFields` (log → TraceID), `tracesToLogs` (`service.name → application_name`)
  y `tracesToMetrics` (`service.name → service`), más el dashboard
  `latiendita.json`.
- **Alertas**: reglas de Prometheus (`Watchdog`, `TargetDown`, …) + ruler de Loki
  (tenant `fake`), ambas hacia `alertmanager:9093`.
- **Auto-observación**: job `latiendita-observability` en `prometheus.k8s.yml` +
  anotaciones `prometheus.io/*` en los Services de Loki/Tempo/Alloy/Grafana/
  Alertmanager → `TargetDown` también cubre al stack que dispara las alertas
  (antes solo se scrapeaba Prometheus a sí mismo y las apps).

### RBAC

Un `Role` + `RoleBinding` `observability-read` **en `latiendita`**
(`k8s/apps/base/observability-rbac.yaml`, se crea al aplicar apps): da a los
ServiceAccounts `prometheus` y `alloy` (de `latiendita-observability`)
lectura de `services`/`endpoints`/`pods` + `get` de `pods/log`. Sin permisos
cluster-wide: todo lo que leen vive en ese namespace (los filtros `namespaces`
estáticos de la config no consultan la API de `namespaces`).

### Cambiar la configuración

Los ConfigMaps se generan **sin hash**, así que aplicar no reinicia nada:

```powershell
kubectl apply -k k8s/observability/environments/local
kubectl rollout restart deploy/grafana deploy/alertmanager -n latiendita-observability
kubectl rollout restart sts/prometheus sts/loki sts/tempo -n latiendita-observability
kubectl rollout restart ds/alloy -n latiendita-observability
```

El ruler de Loki corre con `enable_api: false` a propósito: su directorio de
reglas es un ConfigMap readOnly. Reglas = código en
`config/observability/loki/rules/`.

---

## 12. Flujo de trabajo (cómo se usa día a día)

```text
1. scripts/install-tools   docker, kubectl, kind (+ git): instala lo que falte
2. scripts/kind-up         clúster kind → secrets → build+load de imágenes →
                           apply de k8s/apps/environments/local → espera de
                           StatefulSets y Deployments → verifica pods
3. scripts/deploy          re-despliegues puntuales (idempotente, valida secretos)
4. (aparte)                observabilidad: kubectl apply -k k8s/observability/environments/local
```

```powershell
.\scripts\install-tools.ps1 -Check
.\scripts\kind-up.ps1
.\scripts\deploy.ps1 -s k8s\apps\environments\local\secrets.env k8s\apps\environments\local
.\scripts\deploy.ps1 -s k8s\apps\environments\local\secrets.env k8s\observability\environments\local
```

```bash
bash scripts/install-tools.sh --check
bash scripts/kind-up.sh
bash scripts/deploy.sh -s k8s/apps/environments/local/secrets.env k8s/apps/environments/local
```

Verificación mínima (detalle y checklist en [`comandos.md`](./comandos.md)):

```powershell
kubectl get pods -n latiendita                           # 12 pods Running 1/1
kubectl get pods -n latiendita-observability             # 6 workloads 1/1
kubectl port-forward svc/gateway 8080:80 -n latiendita   # en otra terminal
curl http://localhost:8080/actuator/health/readiness     # status: UP
```

> Los scripts son **autocontenidos** (nada de dot-source ni librerías compartidas)
> y cada uno existe como par `.sh`/`.ps1` con las mismas opciones, pasos y códigos
> de salida. El detalle está en [`scripts/README.md`](../../scripts/README.md).

---

## 13. Verificar sin tocar el clúster

| Comando | Qué comprueba |
|---|---|
| `kubectl kustomize <environment>` (×4) | Que los 4 puntos de entrada renderizan sin errores (los de producción exigen su `secrets.env`, §8) |
| `kubectl diff -k <environment>` | Qué cambiaría en el clúster (exit 1 si hay diff) |
| `bash -n scripts/*.sh` | Sintaxis de los scripts bash |
| tokenizador `PSParser` de PowerShell sobre `scripts/*.ps1` | Sintaxis de los scripts PowerShell |
| `--help` / `-Help` y `install-tools --check` | Corrida real que no toca el clúster |
| `scripts/deploy.* --dry-run <environment>` | Render + validación de secretos + apply simulado (exit 3 si el clúster no responde) |

**Estado de la verificación al momento de escribir esto**: los 4 `.sh` pasan
`bash -n`, los 4 `.ps1` parsean con 0 errores (y en UTF-8 con BOM), los dos
environments locales renderizan con `kubectl kustomize` y los de producción
fallan sin su `secrets.env` (esperado: lo documentan §8 y §9). `deploy --dry-run`
produce los 32 recursos del environment local con la matriz de códigos
0/1/1/1/2 idéntica en ambos lenguajes, y el guard de valores de plantilla
devuelve **exit 6** (verificado en `.sh` y `.ps1` con el `secrets.env.example`
de producción, mismas claves detectadas). `build-images` construyó `gateway` en
bash y PowerShell con exit 0. **No se validó** un `kind-up` completo ni un
`apply` real: requiere `kind` instalado y un clúster respondiendo.

---

## 14. Decisiones de diseño

| Decisión | Por qué | Alternativa descartada |
|---|---|---|
| **Kustomize** (`kubectl apply -k`) | Sin herramienta extra: kubectl ya lo trae; los manifiestos se leen tal cual; overrides por capas | Helm (grafo de templates, releases que ocultan el render), CDK8s |
| **`base` + `environments`** por stack | Separa lo neutro de lo ambiental; el environment es el único punto de entrada y ahí viven los secretos | Un solo directorio con `sed`/plantillas |
| **3 stacks con sus propios namespaces** | Cada `base` declara su `namespace`; mezclarlos en un kustomization pisaría el de observabilidad | Namespace único |
| **`data/` solo en local** | En producción las DB/Kafka son gestionadas; el tier local no debe existir ahí | StatefulSets en producción |
| **`EUREKA_ENABLED=false` + URLs directas** | En k8s el DNS de Service ya resuelve `auth-service`; el descubrimiento de Spring Cloud sobra | Instalar `eureka-server` en el clúster |
| **Config compartida Docker/k8s en `config/observability/`** | Una sola fuente de verdad: `loki.yml`, `tempo.yml` y el dashboard son iguales en ambos entornos y no se desincronizan | Duplicar archivos por entorno |
| **Variantes `*.k8s.yml` / `config.k8s.alloy`** | Solo lo que *debe* cambiar: descubrimiento por API de k8s en vez de lista estática/`docker.sock` | Configs separadas y completas |
| **ConfigMaps sin hash** | Nombres estables e inspeccionables para inspeccionar y depurar | `disableNameSuffixHash: false` (auto-restart) |
| **Descubrimiento por anotaciones `prometheus.io/*`** | Cero dependencias: no hace falta Prometheus Operator ni CRDs | `ServiceMonitor` |
| **Alloy como DaemonSet con API de k8s** | Un contenedor por nodo, sin hostPath ni privilegios, mismos labels de Loki que en Docker | Fluent Bit/Fluentd con mounts del nodo |
| **Secretos por archivo gitignored + `secretGenerator`** | Simple y versionado por plantilla (`.example`); el deploy valida que existan las claves que piden los manifiestos | external-secrets/Vault (infra que hoy no existe) |
| **Imágenes `:dev` + `kind load` + `IfNotPresent`** | Iteración local sin registry | Push a registry en cada cambio |
| **1 réplica local / 2 + PDB en producción** | Ahorrar recursos en local; tolerar evacuaciones en producción | Igual en ambos |
| **Scripts espejo `.sh`/`.ps1` autocontenidos** | El repo se opera desde Windows (PowerShell) y desde Linux/macOS/CI (bash) con paridad de opciones y códigos de salida, sin dependencias entre archivos | Un solo lenguaje, o wrappers |
| **Observabilidad como stack separado** | Se aplica y reinicia independientemente de la aplicación | Todo junto |
| **NetworkPolicy: ingress en `base`, egress solo en `local`** | El ingress-es es igual en ambos entornos; el egress-deny solo es viable donde todo es in-cluster (en prod los endpoints gestionados no son declarables de forma portable) | Mismas políticas en ambos entornos (rompería prod) o default-deny solo en prod (sin aislamiento real en local) |
| **cert-manager + Let's Encrypt (HTTP-01)** | Renovación automática, sin secretos de TLS a mano, anotación estándar en el Ingress | Secretos TLS manuales (renovación manual), terminación solo en el LB (el repo queda mudo sobre TLS) |

---

## 15. Pendientes y huecos conocidos

| # | Pendiente | Impacto |
|---|---|---|
| 1 | Todos los `CHANGE-ME` de producción (registry/tag, ingress class/host, endpoints de DB/Kafka, CORS) | No se puede aplicar el environment de producción tal como está |
| 2 | **Sin CI/CD**: `.github/` está vacío | Builds, pruebas y deploys manuales |
| 3 | `terraform/` vacío (solo `.gitkeep`) | La infraestructura del clúster no está codificada |
| 4 | La SPA (Next.js en `frontend/`) vive **fuera del clúster por diseño** y se despliega en Vercel **manualmente** (sin pipeline) | Deploy manual del UI; el backend solo expone la API (`/api`) y el gateway acepta en producción solo el dominio exacto de Vercel |
| 5 | El clúster kind no trae `metrics-server` | `kubectl top` no funciona hasta instalarlo |
| 6 | Sin `HPA`; `PodDisruptionBudget` solo en producción | Escalado y presupuestos de interrupción manuales |
| 7 | Observabilidad sin `StorageClass` declarada (solo un bloque comentado en el environment de producción) | Si el clúster no tiene StorageClass por defecto, los PVC quedan `Pending` |
| 8 | NetworkPolicy declaradas pero **kind/k3d no las aplica** (kindnet); `ingressClassName` y el namespace del controlador siguen en `CHANGE-ME` | La matriz es estructural hasta instalar Calico/Cilium en local y definir el controlador en producción |
| 9 | Sin labels `pod-security.kubernetes.io/*` (`restricted` rompería Postgres en local) | Etiquetar solo el namespace en el overlay de producción, cuando haya clúster real |
| 10 | cert-manager es **prerequisito del clúster de producción** (§10.2) y los `CHANGE-ME` del ClusterIssuer (email, ingressClassName) siguen pendientes | Los Ingress de producción ya declaran `tls:`, pero sin cert-manager instalado el certificado nunca se emite |
| 11 | Pods de apps con el token del SA `default` montado (`automountServiceAccountToken` sin fijar) | Pospuesto: requiere probar con clúster arriba que nada use la API de k8s |
| 12 | Posiciones de Alloy en `emptyDir` | Pospuesto: persistirlas exige hostPath (regla "sin hostPath" del repo) o aceptar logs duplicados/faltantes al reiniciar el pod |
| 13 | `eureka-server` inexistente en k8s | Esperado (§1), pero las docs de los microservicios hablan de Compose: leerlas con ese contexto |

---

## 16. Referencias

- [`k8s/README.md`](../../k8s/README.md) — árbol de manifiestos, puntos de entrada y reglas.
- [`docs/kubernetes/comandos.md`](./comandos.md) — operación diaria con `kubectl`, observabilidad, checklist de salud.
- [`scripts/README.md`](../../scripts/README.md) — los 4 pares de scripts, opciones y códigos de salida.
- [`config/observability/`](../../config/observability) — configuración del stack de observabilidad (compartida con Compose).
- [`compose.yaml`](../../compose.yaml) / [`compose.observability.yaml`](../../compose.observability.yaml) — equivalente Docker Compose.
- [`docs/microservicios/`](../microservicios) — arquitectura y decisiones de cada servicio.
- [Kustomize](https://kubernetes.io/docs/reference/kustomize/) · [kind](https://kind.sigs.k8s.io/) · [Grafana Alloy](https://grafana.com/docs/alloy/latest/)
