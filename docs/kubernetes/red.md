# Guía de red en Kubernetes

Este documento es el **deep-dive de la red** del despliegue: NetworkPolicy
(matriz completa), Services y DNS, flujo este-oeste, cross-namespace hacia
observabilidad, puntos de entrada (port-forward / Ingress / TLS) y las
diferencias con Docker Compose. Es la referencia canónica: si una política o
un Service cambian, se documentan acá.

El resumen ejecutivo vive en [`configuracion.md` §10](./configuracion.md); esta
guía lo detalla. Para operar con `kubectl` (port-forward, endpoints, smoke
test) usar [`comandos.md`](./comandos.md).

| Querés… | Ir a |
|---|---|
| Resumen de red + TLS | [`configuracion.md` §10](./configuracion.md) |
| Comandos de operación y smoke test | [`comandos.md` §6 y §9](./comandos.md) |
| Estructura de los manifiestos | [`k8s/README.md`](../../k8s/README.md) |
| Variables de entorno (URLs entre servicios) | [`enviroment.md`](../environment/enviroment.md) |

---

## 1. Modelo de red

Dos namespaces, cada uno con su default-deny. Todo el tráfico permitido está
enumerado punto por punto en las NetworkPolicy (allowlist; lo no listado queda
bloqueado).

```text
                    LOCAL                              PRODUCCIÓN
             ┌─────────────────────┐          ┌──────────────────────────┐
  navegador──┤ port-forward        │          │ DNS → Ingress            │
   :3000 ───►│ svc/gateway 8080:80 │          │host api.CHANGE-ME → gw   │
             └──────────┬──────────┘          └────────────┬─────────────┘
                        ▼                                  ▼
             ┌──────────────────────────────────────────────────────────┐
             │ gateway (ClusterIP :80 → pod :8080)                      │
             └───┬──────────┬──────────┬──────────┬──────────┬──────────┘
                 ▼          ▼          ▼          ▼          ▼
            auth-svc   user-svc   product-svc order-svc payment-svc   :8080
                 │          │          ▲    ▲       │        │  ▲
                 │          │          │    │       │        │  │
                 ▼          ▼          ▼    │       ▼        ▼  │
             auth-db   users-db  products-db│  orders-db payments-db  :5432
                                            └─────────┘        │
                                     order→product REST        │
                                     payment→order REST ───────┘
                        └──────────┬──────────┘
                                   ▼
                              kafka :9092          (StatefulSets, solo local)

   namespace latiendita ──────────────────────────────┐
                                                      │ OTLP :4318 (apps → alloy)
   namespace latiendita-observability                 ▼
     alloy (DaemonSet) ──► Tempo :4317 / Loki :3100
     prometheus ──► scrape pod IPs de apps :8080 (¡no Services!)
     loki ruler ──► alertmanager :9093
     grafana ──► datasources (prometheus/loki/tempo)
```

| Concepto | Local (kind) | Producción |
|---|---|---|
| Entrada al gateway | `kubectl port-forward svc/gateway` | Ingress → `gateway:80`, path `/api` |
| Ingress de Grafana | port-forward (no hay Ingress) | host propio `grafana.CHANGE-ME…` |
| TLS | no aplica | cert-manager + Let's Encrypt (HTTP-01) |
| NetworkPolicy enforceada | sí (verificado empíricamente, §3.6) | sí (CNI del clúster: Calico, Cilium…) |
| Egress deny (apps) | sí (todo in-cluster) | **no** (DB/Kafka externos, §3.2) |
| DB / Kafka | StatefulSets in-cluster | servicios gestionados (endpoints externos) |

---

## 2. Services y DNS

### 2.1 Services con IP virtual (ClusterIP normal)

IP única estable por Service; el tráfico se balancea entre los pods que
matchean el selector. **Patrón de apps: `port: 80 → targetPort: http (8080)`**
— los pods escuchan en 8080 (Spring Boot) y el Service expone 80 para que las
URLs internas (`http://auth-service`, `http://gateway`) sean cortas y
consistentes.

| Service | Namespace | Puerto → targetPort | Anotaciones Prometheus |
|---|---|---|---|
| `gateway` | latiendita | `http` 80 → `http` (8080) | `prometheus.io/scrape: "true"`, `path: /actuator/prometheus` |
| `auth-service` | latiendita | `http` 80 → `http` | ídem |
| `user-service` | latiendita | `http` 80 → `http` | ídem |
| `product-service` | latiendita | `http` 80 → `http` | ídem |
| `order-service` | latiendita | `http` 80 → `http` | ídem |
| `payment-service` | latiendita | `http` 80 → `http` | ídem |
| `grafana` | observability | `http` 3000 | scrape `/metrics` port `3000` |
| `alertmanager` | observability | `http` 9093 | scrape `/metrics` port `9093` |
| `alloy` | observability | `otlp-grpc` 4317, `otlp-http` 4318, `ui` 12345 | scrape `/metrics` port `12345` |

Las anotaciones `prometheus.io/*` son lo que Prometheus descubre (role
`endpoints`): sin ellas el servicio no se scrapea.

### 2.2 Services headless (`clusterIP: None`)

Sin IP virtual: el DNS devuelve **las IPs de los pods directamente**. Todos
son `spec.serviceName` de un StatefulSet — la identidad de red por réplica es
lo que da estabilidad (`auth-db-0.auth-db…`).

| Service headless | StatefulSet | Puerto | Nota |
|---|---|---|---|
| `auth-db` | `auth-db` | `pg` 5432 | Service embebido en el statefulset |
| `users-db` | `users-db` | `pg` 5432 | ídem |
| `products-db` | `products-db` | `pg` 5432 | ídem |
| `orders-db` | `orders-db` | `pg` 5432 | ídem |
| `payments-db` | `payments-db` | `pg` 5432 | ídem |
| `kafka` | `kafka` | `plaintext` 9092 | `publishNotReadyAddresses: true` (bootstrap sin esperar readiness) |
| `prometheus` | `prometheus` | `http` 9090 | observability |
| `loki` | `loki` | `http` 3100, `grpc` 9096 | observability |
| `tempo` | `tempo` | `http` 3200, `otlp-grpc` 4317, `otlp-http` 4318 | observability |

**Cuándo headless y cuándo IP virtual**: StatefulSets → headless (identidad
por réplica, DNS estable aunque el pod esté NotReady con
`publishNotReadyAddresses`). Deployments (apps, grafana, alloy…) → ClusterIP
con IP virtual (un solo endpoint que balancea).

### 2.3 DNS: corto vs FQDN

| Forma | Ejemplo | Alcance |
|---|---|---|
| Corto | `http://auth-service` | mismo namespace |
| FQDN | `http://auth-service.latiendita.svc.cluster.local` | explícito, útil en configs |
| Cross-namespace (obligatorio FQDN) | `http://alloy.latiendita-observability.svc.cluster.local:4318` | cruza namespaces |

La resolución corta `alloy:4318` **solo funciona dentro de**
`latiendita-observability`; por eso `OTLP_TRACING_ENDPOINT` en los configmaps
de apps usa el FQDN completo — es el único punto del repo que cruza namespaces
por DNS.

### 2.4 NetworkPolicy y DNS: la puerta que siempre se abre

El DNS del clúster (CoreDNS en `kube-system`) es lo único que todos los pods
pueden alcanzar con egress deny activo: sin `allow-dns`, nada resuelve y todo
falla igual (pero de forma confusa). Ver §3.2 y §3.4.

---

## 3. Matriz de NetworkPolicy

**Principio**: cada namespace tiene un `default-deny` (sin reglas = todo
bloqueado) y luego solo se abre lo enumerado. NetworkPolicy es **por pod** (no
por namespace) y el selector clave de todo el repo es
`app.kubernetes.io/name`.

**Regla de oro (el espejo)**: NetworkPolicy exige que **AMBOS extremos**
permitan el flujo — el lado ingress del destino **y** el lado egress del
origen deben tener una regla que lo habilite. Una sola dirección abierta no
basta. Esto explica por qué existen políticas "espejo" en ambos archivos
(ver §3.5).

### 3.1 Apps — ingress (`k8s/apps/base/networkpolicy.yaml`, local y producción)

Cuatro políticas sobre el namespace `latiendita`:

| # | Política | Selecciona | Permite (ingress) |
|---|---|---|---|
| 1 | `default-deny-ingress` | todos los pods | nada (sin reglas) |
| 2 | `allow-app-ingress` | auth, user, product, order, payment | `gateway:8080`, `order-service:8080`, `payment-service:8080` |
| 3 | `allow-data-ingress` | las 5 DBs + `kafka` | apps+gateway → `5432` y `9092` |
| 4 | `allow-scrape` | los 5 servicios + `gateway` | (ns observability + pod prometheus) → `8080` |

Detalle de `allow-app-ingress` (los 3 orígenes del este-oeste):

```yaml
# gateway enruta /api/auth, /api/users, /api/products, /api/orders, /api/payments
- from: [podSelector: app.kubernetes.io/name: gateway]         → :8080
# order consulta stock (PRODUCT_SERVICE_BASE_URL)
- from: [podSelector: app.kubernetes.io/name: order-service]   → :8080
# payment confirma estado del pedido (ORDER_SERVICE_BASE_URL)
- from: [podSelector: app.kubernetes.io/name: payment-service] → :8080
```

`allow-data-ingress` queda **inerte en producción** (las DB/Kafka son
externas; nadie matchea los pods), pero se mantiene en base porque la matriz
es igual en ambos entornos.

`allow-scrape` existe porque Prometheus con `kubernetes_sd role=endpoints`
scrapea **pod IPs, no Services**: sin esta regla el scrape cae entero
(`TargetDown` 100%). El `from` combina `namespaceSelector`
(`latiendita-observability`) **+** `podSelector` (`prometheus`) — ambos deben
matchear (semántica AND dentro de un elemento de `from`).

### 3.2 Apps — egress, solo local (`…/environments/local/networkpolicy-egress.yaml`)

Solo en local porque **acá todo es in-cluster** (DB/Kafka son StatefulSets):
se puede negar la salida sin romper nada. En producción las DB/Kafka tienen
endpoints externos y un deny así las rompería (resolución por FQDN externo solo
la soportan Cilium y similares de forma portable).

| # | Política | Selecciona | Permite (egress) |
|---|---|---|---|
| 1 | `default-deny-egress` | todos los pods | nada |
| 2 | `allow-dns` | todos los pods | kube-system → `53/UDP`, `53/TCP` (CoreDNS) |
| 3 | `allow-app-egress-data` | las 6 apps | DBs+kafka → `5432`, `9092` |
| 4 | `allow-app-egress-east-west` | las 6 apps | backends → `8080` (espejo de §3.1) |
| 5 | `allow-app-egress-otlp` | las 6 apps | (ns observability + pod alloy) → `4318` |

### 3.3 Apps — ingress adicional de producción (`…/environments/production/networkpolicy-ingress.yaml`)

| Política | Selecciona | Permite |
|---|---|---|
| `allow-ingress-controller` | `gateway` | (ns `CHANGE-ME-ingress-nginx`) → `8080` |

El `CHANGE-ME` es el namespace real del controlador del Ingress
(`ingress-nginx` / `traefik-system` / managed), coherente con el
`ingressClassName` del Ingress. Es el **único origen externo** que entra al
gateway en producción.

### 3.4 Observabilidad (`k8s/observability/base/networkpolicy.yaml`, idéntico en ambos entornos)

Diez políticas en `latiendita-observability`. A diferencia de apps, el egress
aplica igual en local y producción: todo es in-cluster.

**Ingress:**

| # | Política | Selecciona | Permite |
|---|---|---|---|
| 1 | `default-deny` | todos | nada (Ingress + Egress) |
| 2 | `allow-alloy-otlp` | `alloy` | ns `latiendita` → `4317`, `4318` (lado receptor del OTLP) |
| 3 | `allow-same-ns` | alertmanager, prometheus, loki, tempo | mismo ns → `9093`, `9090`, `3100`, `4317` |
| 4 | `allow-scrape` | todos | pod `prometheus` → `9090`, `9093`, `3000`, `3100`, `3200`, `12345` |

**Egress:**

| # | Política | Selecciona | Permite |
|---|---|---|---|
| 5 | `allow-dns` | todos | kube-system → `53/UDP+TCP` |
| 6 | `allow-apiserver` | alloy, prometheus | `443/TCP` (API server del clúster; destino genérico deliberado porque la IP no es portable) |
| 7 | `allow-alloy-egress` | `alloy` | mismo ns → `4317` (Tempo), `3100` (Loki) |
| 8 | `allow-loki-egress` | `loki` | mismo ns → `9093` (ruler → Alertmanager) |
| 9 | `allow-prometheus-egress` | `prometheus` | mismo ns → `9093`, `3000`, `3100`, `3200`, `12345`; ns `latiendita` → `8080` (scrape de apps) |
| 10 | `allow-grafana-egress` | `grafana` | mismo ns → `9090`, `3100`, `3200` (datasources) |

La política 6 (`allow-apiserver`) es la que permite que Alloy lea
`pods/log` y Prometheus descubra endpoints vía la API de Kubernetes — requiere
también el RBAC `observability-read` (§6.3).

### 3.5 El espejo en la práctica

Ejemplo con el gateway → backend (order-service):

| Lado | Política que permite | Archivo |
|---|---|---|
| Ingress de `order-service` | `allow-app-ingress` (from gateway) | apps/base |
| Egress de `gateway` | `allow-app-egress-east-west` (to backends) | apps/local |

Si falta **cualquiera** de los dos, el flujo cae. Y el síntoma es
**engañoso**: sin el egress del gateway, el proxy del gateway no alcanza al
backend y responde **401 vía `/error`** — parece problema de autenticación y
es problema de red. Ante un 401 inesperado del gateway hacia un endpoint
válido, revisar la matriz antes que el JWT.

### 3.6 kindnet: qué se verificó

Aunque kindnet **no documenta** soporte de NetworkPolicy, en **kind v0.33.0
se verificó empíricamente que las políticas SÍ cortan tráfico**: un egress
faltante rompía el este-oeste entre pods con timeouts exactamente sobre la
matriz declarada. Conclusión operativa: **tratar las políticas como efectivas
en local** (no son mera validación estructural).

El `kubectl port-forward` **nunca** se ve afectado por NetworkPolicy: el
kubelet inyecta ese tráfico fuera del datapath del CNI. Si el port-forward
funciona pero el tráfico entre pods no, el problema es la matriz.

---

## 4. Flujo este-oeste (service-to-service)

Quién llama a quién por REST dentro del clúster, y qué permite cada lado:

| Flujo | Origen | Destino | Puerto | Lado ingress | Lado egress (solo local) |
|---|---|---|---|---|---|
| gateway → todos los backends | `gateway` | auth/user/product/order/payment | 8080 | `allow-app-ingress` | `allow-app-egress-east-west` |
| order → product (stock) | `order-service` | `product-service` | 8080 | `allow-app-ingress` | `allow-app-egress-east-west` |
| payment → order (estado) | `payment-service` | `order-service` | 8080 | `allow-app-ingress` | `allow-app-egress-east-west` |

No hay otros flujos REST declarados. Si aparece uno nuevo (un servicio llama a
otro), hay que agregar **ambos lados** de la matriz — ingress en
`k8s/apps/base/networkpolicy.yaml` y egress en
`k8s/apps/environments/local/networkpolicy-egress.yaml`.

---

## 5. Flujo a datos

| Flujo | Puerto | Política ingress (base) | Política egress (local) |
|---|---|---|---|
| apps → Postgres (`*-db`) | 5432 | `allow-data-ingress` | `allow-app-egress-data` |
| apps (sin product) → kafka | 9092 | `allow-data-ingress` | `allow-app-egress-data` |

- Los pods de **datos no generan tráfico saliente**: no reciben permisos de
  egress y quedan sin salir del clúster (salvo su propio DNS).
- La resolución es por Service headless (`auth-db:5432`), estable por
  StatefulSet (§2.2).
- En **producción** ambas políticas quedan inerte: DB/Kafka externas, los pods
  matcheados no existen.

---

## 6. Flujo a observabilidad (cross-namespace)

El único punto donde `latiendita` habla con `latiendita-observability` (y
viceversa). Cada flujo necesita el lado que le corresponde:

### 6.1 Trazas (OTLP): apps → alloy

| Lado | Política | Puerto |
|---|---|---|
| Egress de apps (local) | `allow-app-egress-otlp` | → alloy `4318` (OTLP HTTP) |
| Ingress de alloy | `allow-alloy-otlp` (ns `latiendita`) | `4317` gRPC + `4318` HTTP |

Alloy reenvía a Tempo (`allow-alloy-egress`: → `4317`) y Loki (→ `3100`).

### 6.2 Logs: alloy → API de Kubernetes → Loki

Alloy (DaemonSet) no monta `docker.sock` ni hostPath: lee `pods/log` vía la
API de Kubernetes. Requiere:

| Componente | Qué aporta |
|---|---|
| `allow-apiserver` (egress alloy) | `443` hacia la API |
| RBAC `observability-read` (§6.3) | permiso de lectura de `pods/log` en `latiendita` |

### 6.3 Scrape de métricas: prometheus → apps (por pod IPs)

| Lado | Política | Detalle |
|---|---|---|
| Egress de prometheus | `allow-prometheus-egress` → ns `latiendita:8080` | scrapea **pod IPs**, no Services |
| Ingress de apps | `allow-scrape` (from prometheus) | `8080` |

Y el RBAC que lo hace posible (vive en apps porque un `namespace:` a nivel de
entorno de observabilidad pisaría el correcto):

```yaml
# k8s/apps/base/observability-rbac.yaml
Role observability-read (ns latiendita):
  - services, endpoints, pods: get, list, watch
  - pods/log: get
RoleBinding → ServiceAccounts: prometheus, alloy (ambos en latiendita-observability)
```

### 6.4 Alertas: loki ruler → alertmanager

`allow-loki-egress` (loki → `9093`). Las reglas de Prometheus (sin política
adicional: mismo namespace, cubiertas por `allow-prometheus-egress`) también
apuntan a `alertmanager:9093`.

### 6.5 Dashboards: grafana → datasources

`allow-grafana-egress` → prometheus `9090`, loki `3100`, tempo `3200`.

### 6.6 Ingress de producción para Grafana

`allow-ingress-controller` (ns `CHANGE-ME-ingress-nginx` → grafana `3000`),
equivalente al de apps pero para el host propio de Grafana.

---

## 7. Puntos de entrada

### 7.1 Local: port-forward

```bash
kubectl port-forward svc/gateway 8080:80 -n latiendita
```

Funciona **con todas las NetworkPolicy activas** porque el kubelet inyecta el
tráfico fuera del datapath del CNI (§3.6). Es la única entrada en local: no
hay Ingress ni LB.

### 7.2 Producción: Ingress

| Ingress | Host | Path | Backend | TLS Secret |
|---|---|---|---|---|
| `la-tiendita` (apps) | `api.CHANGE-ME.example.com` | `/api` (Prefix) | `gateway:80` | `la-tiendita-tls` |
| `grafana` (observability) | `grafana.CHANGE-ME.example.com` | `/` (Prefix) | `grafana:3000` | `grafana-tls` |

- El host de apps expone **solo la API** (`/api`): la SPA vive en Vercel
  (fuera del clúster) y solo llama por HTTPS (mixed content sin TLS).
- Grafana tiene **host propio** deliberadamente: evita el modo subpath de
  Grafana (`GF_SERVER_ROOT_URL` + `SERVE_FROM_SUB_PATH`), propenso a romper
  assets.

### 7.3 TLS: cert-manager + Let's Encrypt

Ambos Ingress llevan `cert-manager.io/cluster-issuer: letsencrypt-prod`; los
Secretos TLS los emite y renueva cert-manager (nadie los genera a mano). El
`ClusterIssuer` es cluster-scoped, vive en `k8s/cluster-scope/` y se aplica
una vez — el walkthrough completo (instalación, registro, verificación y
staging) está en [`configuracion.md` §10.2](./configuracion.md).

---

## 8. CORS ≠ NetworkPolicy

Dos mecanismos distintos que suenan a "seguridad de red" y no lo son:

| | CORS | NetworkPolicy |
|---|---|---|
| ¿Qué filtra? | Orígenes de **navegador** (headers HTTP) | Tráfico de red entre pods/host |
| ¿Dónde vive? | En el **gateway** (config `CORS_ALLOWED_ORIGINS`) | En el CNI del clúster |
| ¿Aplica a curl / server-to-server? | no | sí |

Un `curl` ignora CORS; una NetworkPolicy no sabe qué es un "origen de
navegador". La política de orígenes del frontend (Vercel en producción, `*` en
local) se resuelve **en la aplicación**, no en la red.

---

## 9. Depurar red

Chequeos rápidos (detalle de operación en [`comandos.md`](./comandos.md)):

| Síntoma | Qué revisar |
|---|---|
| 401 inesperado del gateway a un endpoint válido | Matriz este-oeste: ¿falta el egress del gateway? (§3.5) |
| Timeout entre pods (pero port-forward funciona) | NetworkPolicy del flujo; recordar que el espejo exige ambos lados |
| `TargetDown` 100% en Prometheus | `allow-scrape` (apps) + `allow-scrape` (observability) + anotaciones `prometheus.io/*` |
| Alloy sin enviar logs/trazas | `allow-apiserver` + RBAC `observability-read`; `OTLP_TRACING_ENDPOINT` con FQDN correcto |
| Pods sin resolver nombres | `allow-dns` (CoreDNS en `kube-system`, 53) |

```bash
# ¿Qué hay aplicado y cuál matchea al pod?
kubectl get netpol -n latiendita
kubectl describe netpol <nombre> -n latiendita

# ¿El Service tiene endpoints reales (pods vivo tras el selector)?
kubectl get endpoints <svc> -n latiendita

# Traza de conectividad (desde un pod con shell):
kubectl exec -n latiendita <pod> -- curl -sv --max-time 3 http://<destino>:<puerto>
```

---

## 10. Diferencias con Docker Compose

Compose modela la segmentación con **redes** (una red por base de datos +
`backend` interna + `frontend` expuesta):

| Compose | Kubernetes |
|---|---|
| Redes `data-*` (`internal: true`): una por DB | NetworkPolicy `allow-data-ingress` + `allow-app-egress-data` (matriz por pod, no por red) |
| Red `backend` (internal) | default-deny + matriz este-oeste |
| Red `frontend` (única expuesta) | Ingress (producción) / port-forward (local) |
| Aislamiento por pertenencia a red | Aislamiento por regla explícita entre pods (finer-grained) |
| No hay equivalente | FQDN cross-namespace (`alloy.latiendita-observability…`) |

El modelo Compose es "quién está en qué red"; el de Kubernetes es "qué flujos
están permitidos punto por punto" — más verboso pero no depende de la topología
de redes del runtime.

---

## Referencias

- [`configuracion.md` §10](./configuracion.md) — resumen de red y TLS.
- [`comandos.md`](./comandos.md) — operación, smoke test y checklist.
- [`k8s/README.md`](../../k8s/README.md) — estructura de manifiestos y reglas.
- Manifiestos: `k8s/apps/base/networkpolicy.yaml`,
  `k8s/apps/environments/local/networkpolicy-egress.yaml`,
  `k8s/apps/environments/production/networkpolicy-ingress.yaml`,
  `k8s/observability/base/networkpolicy.yaml`,
  `k8s/apps/base/observability-rbac.yaml`,
  `k8s/cluster-scope/clusterissuer-letsencrypt.yaml`.
- [Kubernetes NetworkPolicy](https://kubernetes.io/docs/concepts/services-networking/network-policies/)
