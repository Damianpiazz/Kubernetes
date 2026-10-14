# Despliegues en Kubernetes

Estructura de los manifiestos. **Convención: `<stack>/base` + `<stack>/environments/<entorno>`**
(el término "overlay" ya no se usa en el repo).

```
k8s/
├── apps/                              Stack: los 6 microservicios   (ns latiendita)
│   ├── base/                          Manifiestos neutrales, sin entorno ni credenciales
│   └── environments/
│       ├── local/                     kind/k3d: base + data + secretos locales
│       └── production/                Clúster real: imágenes, réplicas, DB/Kafka externas
│
├── data/                              Stack: tier de datos SOLO local (ns latiendita)
│   └── base/                          5 × Postgres (StatefulSet) + Kafka. No va a producción.
│
└── observability/                     Stack: observabilidad (ns latiendita-observability)
    ├── base/                          Prometheus, Loki, Tempo, Grafana, Alloy, Alertmanager + RBAC
    └── environments/
        ├── local/                     Punto de entrada local + secretos de Grafana (dev)
        └── production/                Ingress de Grafana + secretos reales
```

La **configuración** no vive acá: está en [`config/observability/`](../config/observability),
compartida con Docker Compose (la misma `loki.yml`/`tempo.yml`/dashboard en ambos entornos).

## Puntos de entrada

| Entorno | Aplicación | Observabilidad |
|---|---|---|
| **local** (kind/k3d) | `kubectl apply -k k8s/apps/environments/local` | `kubectl apply -k k8s/observability/environments/local` |
| **production** | `kubectl apply -k k8s/apps/environments/production` | `kubectl apply -k k8s/observability/environments/production` |

Con los scripts (validan secretos, esperan rollouts, informan estado):

```powershell
.\scripts\kind-up.ps1                                            # entorno local completo
.\scripts\deploy.ps1 -s k8s\apps\environments\local\secrets.env k8s\apps\environments\local
```

## Reglas

1. **Nunca aplicar `base/` directamente.** Los secretos (`latiendita-secrets`,
   `grafana-admin`) se generan en cada `environment`; aplicar la base deja los pods
   sin Secret y el rollout queda en `CreateContainerConfigError`.
2. **`namespace` se declara en cada `base/`** (apps y data → `latiendita`,
   observabilidad → `latiendita-observability`). Por eso los stacks se despliegan
   por separado: un `namespace:` a nivel de entorno pisaría el del otro stack.
3. **`data/` sólo se instancia en `environments/local`.** En producción las bases de
   datos y Kafka son servicios gestionados y sus endpoints entran por
   `configMapGenerator` en `k8s/apps/environments/production`.
4. **Las credenciales no se commitean**: van en `secrets.env` (gitignored), con
   `secrets.env.example` como plantilla.

## Verificar sin tocar el clúster

```powershell
kubectl kustomize k8s/apps/environments/local
kubectl kustomize k8s/apps/environments/production
kubectl kustomize k8s/observability/environments/local
kubectl kustomize k8s/observability/environments/production
```
