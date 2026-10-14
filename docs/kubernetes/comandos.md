# Comandos de Kubernetes

Referencia rápida de `kubectl` para operar el clúster de La Tiendita. Todos los ejemplos
asumen el namespace `latiendita` (recordá pasarlo siempre con `-n latiendita`).

La observabilidad (Prometheus, Loki, Tempo, Grafana, Alloy y Alertmanager) corre en su
propio namespace: **`latiendita-observability`** → sección 8.

**Contexto actual**: verificalo siempre con `kubectl config current-context` antes de
tocar nada. Los ejemplos de este doc asumen el clúster kind `latiendita-dev`
(contexto `kind-latiendita-dev`); si tu contexto activo es otro — p. ej. el de otro
proyecto — **no mutar nada** hasta cambiarlo con `kubectl config use-context`.

> **Nota**: el clúster kind no trae `metrics-server`, por lo que `kubectl top` no funciona
> hasta instalarlo. Para métricas de CPU/memoria de pods, primero:
> `kubectl apply -f https://raw.githubusercontent.com/kubernetes/metrics-server/latest/components.yaml`

---

## Quick path - ¿está todo vivo?

```powershell
kubectl get pods -n latiendita                          # 12 pods Running 1/1
kubectl get pods -n latiendita-observability            # 6 workloads 1/1 (observabilidad)
kubectl get events -n latiendita --sort-by=.metadata.creationTimestamp
kubectl port-forward svc/gateway 8080:80 -n latiendita  # en otra terminal
curl http://localhost:8080/actuator/health/readiness     # status: UP
```

---

## 1. Descubrimiento y contexto

| Comando | Para qué |
|---|---|
| `kubectl config current-context` | Ver en qué clúster estás apuntando (antes de mutar, siempre) |
| `kubectl config get-contexts` | Listar todos los contextos disponibles |
| `kubectl config use-context kind-latiendita-dev` | Cambiar de contexto |
| `kubectl version --client` | Versión del cliente kubectl |
| `kubectl get nodes -o wide` | Nodos, versión de K8s, IP interna/externa |
| `kubectl api-resources` | Todos los recursos de la API servidos |
| `kubectl api-resources --namespaced=false` | Recursos a nivel de clúster |

## 2. Estado de workloads

| Comando | Para qué |
|---|---|
| `kubectl get deploy,statefulset,svc,pods -n latiendita` | Vista general de todo |
| `kubectl get pods -n latiendita -o wide` | Pods con nodo e IP asignada |
| `kubectl get pods -n latiendita -l app.kubernetes.io/name=gateway` | Filtrar por label |
| `kubectl get deploy -n latiendita` | Solo Deployments |
| `kubectl get sts -n latiendita` | Solo StatefulSets (bases de datos, Kafka) |
| `kubectl get svc -n latiendita` | Servicios y puertos expuestos |
| `kubectl get endpoints -n latiendita` | IPs reales a las que apunta cada Service (si está vacío, el selector no matchea) |
| `kubectl get events -n latiendita --sort-by=.metadata.creationTimestamp` | Eventos del namespace, ordenados por tiempo |
| `kubectl describe pod <pod> -n latiendita` | Detalle completo: estado, eventos, mounts, probes |

## 3. Logs

| Comando | Para qué |
|---|---|
| `kubectl logs deploy/auth-service -n latiendita --tail=100` | Últimas 100 líneas |
| `kubectl logs deploy/auth-service -n latiendita -f` | Seguir en vivo (streaming) |
| `kubectl logs deploy/auth-service -n latiendita --previous` | Logs del contenedor anterior (tras un crash/restart) |
| `kubectl logs deploy/auth-service -n latiendita --timestamps` | Con timestamp ISO para correlacionar |
| `kubectl logs -n latiendita -l app.kubernetes.io/name=gateway --max-log-requests=5` | Logs de todos los pods con ese label |

## 4. Debug de un pod

| Comando | Para qué |
|---|---|
| `kubectl exec -it <pod> -n latiendita -- sh` | Shell dentro del pod (alpine) o `-- bash` (JVM) |
| `kubectl exec -n latiendita <pod> -- env \| grep SPRING` | Ver variables de entorno inyectadas |
| `kubectl exec -n latiendita products-db-0 -- psql -U products -d products -c '\dt'` | Listar tablas en Postgres |
| `kubectl exec -n latiendita kafka-0 -- /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list` | Listar topics de Kafka |
| `kubectl port-forward svc/product-service 8082:80 -n latiendita` | Exponer un servicio localmente para probarlo directo |
| `kubectl port-forward pod/<pod> 9090:8080 -n latiendita` | Port-forward a un pod específico (debug aislado) |

## 5. Rollouts y despliegues

| Comando | Para qué |
|---|---|
| `kubectl apply -k k8s/apps/environments/local` | Aplicar los manifiestos (idempotente) |
| `kubectl diff -k k8s/apps/environments/local` | **Ver qué cambiaría antes de aplicar** (exit 1 si hay diff) |
| `kubectl kustomize k8s/apps/environments/local` | Renderizar los manifiestos finales sin tocar el clúster |
| `kubectl rollout status deploy/gateway -n latiendita --timeout=120s` | Esperar a que termine el rollout |
| `kubectl rollout status sts/kafka -n latiendita` | Lo mismo para StatefulSets |
| `kubectl rollout history deploy/gateway -n latiendita` | Historial de revisiones |
| `kubectl rollout undo deploy/gateway -n latiendita` | Volver a la revisión anterior |
| `kubectl rollout restart deploy/gateway -n latiendita` | Forzar restart (rotar pods sin cambiar config) |
| `kubectl set image deploy/gateway app=latiendita/gateway:v2 -n latiendita` | Cambiar la imagen de un Deployment |
| `kubectl scale deploy/gateway --replicas=2 -n latiendita` | Escalar réplicas manualmente |

## 6. Red y smoke test

| Comando | Para qué |
|---|---|
| `kubectl get svc -n latiendita` | Ver puertos del Service (ClusterIP:80 → pod:8080) |
| `kubectl get endpoints gateway -n latiendita` | Verificar que el Service tiene endpoints reales |
| `kubectl port-forward svc/gateway 8080:80 -n latiendita` | Exponer el gateway localmente |
| `curl http://localhost:8080/actuator/health/readiness` | Health check del gateway |
| `curl http://localhost:8080/api/products -H "Authorization: Bearer <token>"` | Catálogo (requiere JWT; sin token → `401`) |
| `curl -X POST http://localhost:8080/api/auth/register -H "Content-Type: application/json" -d '{"email":"test@test.com","username":"testuser","password":"Passw0rd!"}'` | Registrar usuario |
| `curl -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" -d '{"email":"test@test.com","password":"Passw0rd!"}'` | Login → devuelve JWT |
| `curl http://localhost:8080/api/orders -H "Authorization: Bearer <token>"` | Probar endpoint protegido |

## 7. Infraestructura del repo

> Cada script existe en dos versiones con el mismo nombre, las mismas opciones
> y los mismos códigos de salida: `scripts/<nombre>.sh` (bash: Linux, macOS y
> Windows vía Git Bash/WSL) y `scripts/<nombre>.ps1` (PowerShell 5.1+). Todos
> son autocontenidos: no dependen de librerías ni de otros scripts del repo.
> Si los `.sh` no tienen bit de ejecución, correrlos como `bash scripts/<nombre>.sh`.

| Comando | Para qué |
|---|---|
| `scripts/install-tools.sh --check` (o `install-tools.ps1 -Check`) | Verifica docker, kubectl, kind y git; sin `--check` instala lo que falte |
| `scripts/kind-up.sh` (o `kind-up.ps1`) | Entorno local completo: clúster kind, secrets, imágenes, manifests y pods listos |
| `scripts/build-images.sh --tag dev` (o `build-images.ps1 -Tag dev`) | Construye `latiendita/<servicio>:<tag>` de los seis servicios |
| `.\scripts\deploy.sh -s k8s/apps/environments/local/secrets.env k8s/apps/environments/local` | Deploy idempotente completo (valida secretos, aplica, espera) |
| `.\scripts\deploy.sh ... --logs gateway --log-tail 50` | Deploy + tail de logs al final |
| `.\scripts\deploy.sh ... --timeout 120` | Cambiar el timeout de espera (default 300s, **por workload**) |
| `.\scripts\deploy.sh -s k8s/apps/environments/local/secrets.env k8s/observability/environments/local` | Deploy idempotente del stack de observabilidad |
| `.\scripts\deploy.sh -s k8s/apps/environments/production/secrets.env k8s/apps/environments/production` | Deploy de producción (crear antes el `secrets.env` desde su `.example`; con valores `CHANGE-ME` sale **6**) |
| `kubectl rollout restart sts/prometheus sts/loki sts/tempo -n latiendita-observability` | Recargar config de los observables (ConfigMaps sin hash) |
| `kubectl get pvc -n latiendita` | Volumes persistentos de los StatefulSets |
| `kubectl exec -n latiendita auth-db-0 -- psql -U auth -d auth -c '\dt'` | Verificar tablas creadas por Hibernate |
| `kubectl exec -n latiendita kafka-0 -- /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe` | Ver topics con sus particiones |

Códigos de salida (iguales en `.sh` y `.ps1`):

| Script | Códigos |
|---|---|
| `install-tools` | `1` uso · `2` falta una herramienta obligatoria |
| `build-images` | `1` uso · `2` docker no disponible · `3` build fallido |
| `kind-up` | `1` uso · `2` herramientas · `3` clúster · `4` secrets · `5` imágenes · `6` apply · `7` rollout · `8` pods |
| `deploy` | `1` uso · `2` secrets · `3` apply · `4` no ready · `5` claves faltantes · `6` valores de plantilla en target `production` |

## 8. Observabilidad

Stack completo en su propio namespace: **Prometheus** (métricas + alertas), **Alertmanager**
(recepción/deduplicación de alertas), **Loki** (logs + ruler), **Tempo** (trazas), **Alloy**
(agente que junta logs y recibe OTLP) y **Grafana** (dashboard RED + enlaces trazas↔logs).

La config vive en `config/observability/` y se comparte con Docker Compose: el mismo `loki.yml`,
`tempo.yml`, `datasources.yml` y dashboard se usan en ambos entornos. En Kubernetes se
embeben como ConfigMaps vía `config/observability/kustomization.yaml`.

**Desplegar**

```powershell
kubectl apply -k k8s/observability/environments/local                         # local / dev
kubectl apply -k k8s/observability/environments/production     # producción (ingress + secretos)
```

> **Producción**: los dos overlays `…/environments/production` (apps y
> observabilidad) **fallan al renderizar** si falta su `secrets.env` (gitignored;
> crear desde el `.example` de cada uno). Si algún valor sigue siendo de plantilla
> (`CHANGE-ME`/`changeme`), `scripts/deploy.*` sale con código **6**.

`kind-up.ps1` no lo toca: el stack se aplica aparte, así se pueden desplegar y
reiniciar la observabilidad y las aplicaciones de forma independiente.

**Acceso (port-forward)**

| Comando | Para qué |
|---|---|
| `kubectl port-forward svc/grafana 3000:3000 -n latiendita-observability` | Grafana en http://localhost:3000 (admin / admin) |
| `kubectl port-forward svc/prometheus 9090:9090 -n latiendita-observability` | UI y API de Prometheus |
| `kubectl port-forward svc/alertmanager 9093:9093 -n latiendita-observability` | UI de Alertmanager |
| `kubectl port-forward svc/loki 3100:3100 -n latiendita-observability` | API de Loki |
| `kubectl port-forward svc/tempo 3200:3200 -n latiendita-observability` | API de Tempo |
| `kubectl port-forward svc/alloy 12345:12345 -n latiendita-observability` | UI de Alloy |

**Verificar la cadena de punta a punta**

| Comando | Qué comprueba |
|---|---|
| `kubectl get pods -n latiendita-observability` | Los 6 workloads `Running` y `1/1` |
| `curl -s 'http://localhost:9090/api/v1/query?query=up'` | Cada servicio scrapeado con `value: "1"` |
| `curl -s http://localhost:9090/api/v1/rules` | Reglas de Prometheus cargadas (`Watchdog`, `TargetDown`, ...) |
| `curl -s http://localhost:9093/api/v2/alerts` | Alertas llegando a Alertmanager (con `Watchdog` siempre activa) |
| `curl -s http://localhost:3100/loki/api/v1/labels` | Que `application_name` y `level` existan como labels |
| `curl -s http://localhost:3100/loki/api/v1/rules` | Reglas del ruler de Loki (tenant `fake`) y su estado |
| `curl -s http://localhost:3200/ready` | Tempo responde `ready` |
| `curl -s http://localhost:3000/api/health` | Grafana con `database: ok` |

En Grafana: **Explore → Prometheus** con `http_server_requests_seconds_count{service="orderService"}`
(debería tener la etiqueta `service`), **Explore → Loki** con `{application_name="orderService"}`
y en el dashboard **La Tiendita RED** el panel de logs navegando a la pestaña **Traces** de un `traceId`.

**Cambios de configuración**

Los ConfigMaps se generan sin hash (`disableNameSuffixHash`) para poder inspeccionarlos,
así que después de tocar un archivo hay que reiniciar el workload que lo monta:

```powershell
kubectl apply -k k8s/observability/environments/local
kubectl rollout restart deploy/grafana deploy/alertmanager -n latiendita-observability
kubectl rollout restart sts/prometheus sts/loki sts/tempo -n latiendita-observability
kubectl rollout restart ds/alloy -n latiendita-observability
```

**Dónde vive cada cosa**

| Archivo | Qué define |
|---|---|
| `config/observability/<componente>/` | Config compartida Docker + Kubernetes (misma fuente de verdad) |
| `config/observability/kustomization.yaml` | Los ConfigMaps anteriores listos para kustomize |
| `k8s/observability/base/` | Workloads, Services, PVCs y RBAC del stack |
| `k8s/observability/environments/local/` | Punto de entrada local (secretos de Grafana de desarrollo) |
| `k8s/observability/environments/production/` | Ingress de Grafana + secretos de producción |
| `k8s/apps/base/<servicio>/service.yaml` | Anotaciones `prometheus.io/*` con las que Prometheus descubre los targets |
| `k8s/apps/base/<servicio>/configmap.yaml` | `OTLP_TRACING_ENDPOINT`: apps → Alloy → Tempo |

## 9. Comandos destructivos — usar con cuidado

> **Regla**: siempre `kubectl diff` o `--dry-run=client` antes, y confirmar el alcance.

| Comando | Riesgo | Alternativa segura |
|---|---|---|
| `kubectl delete pod <pod> -n latiendita` | Mata el pod (lo recrea el controller) | `kubectl rollout restart deploy/<x> -n latiendita` |
| `kubectl delete -k k8s/apps/environments/local` | Borra todo el namespace | `kubectl scale deploy/<x> --replicas=0` para pausar |
| `kubectl drain <node> --ignore-daemonsets` | Saca pods del nodo (requiere --force si hay PV) | `kubectl cordon <node>` para evitar nuevos pods |
| `kubectl scale deploy/gateway --replicas=0 -n latiendita` | Corta el tráfico | `kubectl rollout restart` para un bounce |

## 10. Checklist de salud

Antes de decir "está funcionando", verificar:

- [ ] `kubectl get pods` → todos `Running` y `1/1` (no `0/1`, no `CrashLoopBackOff`)
- [ ] `kubectl get events` → sin errores recientes (`Failed`, `BackOff`, `Unhealthy`)
- [ ] `kubectl get endpoints gateway` → tiene al menos una IP asignada
- [ ] `curl localhost:8080/actuator/health/readiness` (vía port-forward) → `status: UP`
- [ ] Flujo completo: register → login → GET `/api/products` con `Authorization: Bearer <token>` (del login) → todos responden
- [ ] `kubectl get pods -n latiendita-observability` → 6 workloads `Running` y `1/1`
- [ ] Prometheus tiene un target por servicio (`curl -s 'http://localhost:9090/api/v1/query?query=up'`)
- [ ] Loki expone el label `application_name` (`curl -s http://localhost:3100/loki/api/v1/labels`)
