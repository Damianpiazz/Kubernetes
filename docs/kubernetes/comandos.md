# Comandos de Kubernetes

Referencia rápida de `kubectl` para operar el clúster de La Tiendita. Todos los ejemplos
asumen el namespace `latiendita` (recordá pasarlo siempre con `-n latiendita`).

**Contexto actual**: clúster kind `latiendita-dev` → contexto `kind-latiendita-dev`.

> **Nota**: el clúster kind no trae `metrics-server`, por lo que `kubectl top` no funciona
> hasta instalarlo. Para métricas de CPU/memoria de pods, primero:
> `kubectl apply -f https://raw.githubusercontent.com/kubernetes/metrics-server/latest/components.yaml`

---

## Quick path - ¿está todo vivo?

```powershell
kubectl get pods -n latiendita                          # 12 pods Running 1/1
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
| `kubectl apply -k k8s/overlays/local` | Aplicar los manifiestos (idempotente) |
| `kubectl diff -k k8s/overlays/local` | **Ver qué cambiaría antes de aplicar** (exit 1 si hay diff) |
| `kubectl kustomize k8s/overlays/local` | Renderizar los manifiestos finales sin tocar el clúster |
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
| `curl http://localhost:8080/api/products` | Catálogo sin auth (público) |
| `curl -X POST http://localhost:8080/api/auth/register -H "Content-Type: application/json" -d '{"email":"test@test.com","username":"testuser","password":"Passw0rd!"}'` | Registrar usuario |
| `curl -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" -d '{"email":"test@test.com","password":"Passw0rd!"}'` | Login → devuelve JWT |
| `curl http://localhost:8080/api/orders -H "Authorization: Bearer <token>"` | Probar endpoint protegido |

## 7. Infraestructura del repo

| Comando | Para qué |
|---|---|
| `.\scripts\deploy.sh -s k8s/overlays/local/secrets.env k8s/overlays/local` | Deploy idempotente completo (valida secretos, aplica, espera) |
| `.\scripts\deploy.sh ... --logs gateway --log-tail 50` | Deploy + tail de logs al final |
| `.\scripts\deploy.sh ... --timeout 120` | Cambiar el timeout de espera (default 300s) |
| `kubectl get pvc -n latiendita` | Volumes persistentos de los StatefulSets |
| `kubectl exec -n latiendita auth-db-0 -- psql -U auth -d auth -c '\dt'` | Verificar tablas creadas por Hibernate |
| `kubectl exec -n latiendita kafka-0 -- /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe` | Ver topics con sus particiones |

## 8. Comandos destructivos — usar con cuidado

> **Regla**: siempre `kubectl diff` o `--dry-run=client` antes, y confirmar el alcance.

| Comando | Riesgo | Alternativa segura |
|---|---|---|
| `kubectl delete pod <pod> -n latiendita` | Mata el pod (lo recrea el controller) | `kubectl rollout restart deploy/<x> -n latiendita` |
| `kubectl delete -k k8s/overlays/local` | Borra todo el namespace | `kubectl scale deploy/<x> --replicas=0` para pausar |
| `kubectl drain <node> --ignore-daemonsets` | Saca pods del nodo (requiere --force si hay PV) | `kubectl cordon <node>` para evitar nuevos pods |
| `kubectl scale deploy/gateway --replicas=0 -n latiendita` | Corta el tráfico | `kubectl rollout restart` para un bounce |

## 9. Checklist de salud

Antes de decir "está funcionando", verificar:

- [ ] `kubectl get pods` → todos `Running` y `1/1` (no `0/1`, no `CrashLoopBackOff`)
- [ ] `kubectl get events` → sin errores recientes (`Failed`, `BackOff`, `Unhealthy`)
- [ ] `kubectl get endpoints gateway` → tiene al menos una IP asignada
- [ ] `curl localhost:8080/actuator/health/readiness` (vía port-forward) → `status: UP`
- [ ] Flujo completo: register → login → GET `/api/products` → todos responden