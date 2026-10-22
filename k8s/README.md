# Despliegues en Kubernetes

Estructura de los manifiestos. **Convención: `<stack>/base` + `<stack>/environments/<entorno>`**
(el término "overlay" ya no se usa en el repo).

```
k8s/
├── apps/                              Stack: 6 microservicios + frontend (ns latiendita)
│   ├── base/                          Manifiestos neutrales, sin entorno ni credenciales
│   └── environments/
│       ├── local/                     kind/k3d: base + data + secretos locales + egress aislado
│       └── production/                Clúster real: imágenes, réplicas, DB/Kafka externas, TLS
│
├── data/                              Stack: tier de datos SOLO local (ns latiendita)
│   └── base/                          5 × Postgres + Kafka + Azurite (StatefulSet). No va a producción.
│
├── observability/                     Stack: observabilidad (ns latiendita-observability)
│   ├── base/                          Prometheus, Loki, Tempo, Grafana, Alloy, Alertmanager + RBAC + NetworkPolicy
│   └── environments/
│       ├── local/                     Punto de entrada local + secretos de Grafana (dev)
│       └── production/                Ingress de Grafana (TLS) + secretos reales
│
└── cluster-scope/                     Recursos de ámbito de clúster (fuera de kustomize)
    └── clusterissuer-letsencrypt.yaml cert-manager → Let's Encrypt (aplicar una vez con kubectl apply -f)
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

# producción: requiere crear antes k8s\apps\environments\production\secrets.env
.\scripts\deploy.ps1 -s k8s\apps\environments\production\secrets.env k8s\apps\environments\production
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
4. **Las credenciales no se commitean**: van en `secrets.env` (gitignored con el
   patrón global `k8s/**/secrets.env`), con `secrets.env.example` como plantilla
   (o se generan con `scripts/secrets.*`, que rellena valores aleatorios fuertes
   sin pisar lo existente). Cada environment tiene el suyo — apps (local y
   producción) y observabilidad (local y producción):
   `k8s/{apps,observability}/environments/…/secrets.env`.
   En producción, si el archivo no existe el render falla y si algún valor sigue
   siendo de plantilla (`CHANGE-ME`/`changeme`) `deploy.*` sale con código 6.
   Excepción a este patrón: los Secretos de TLS los emite y renueva
   cert-manager (nadie los genera a mano).
5. **La red está aislada con NetworkPolicy** (default-deny + matriz explícita,
   declarada en cada `base` y ampliada en los environments). En **local** el
   aislamiento es estructural: la CNI de kind (kindnet) no enforcea policies y
   el acceso sigue siendo por port-forward. En **producción** la CNI del clúster
   sí debe enforcear (Calico, Cilium, etc.) y hay que reemplazar los
   `CHANGE-ME` de las policies de ingress (namespace del controlador).
6. **TLS en producción lo gestiona cert-manager** (Let's Encrypt, HTTP-01): los
   Ingress llevan la anotación `cert-manager.io/cluster-issuer` y el ClusterIssuer
   vive en `k8s/cluster-scope/` (recurso cluster-scoped, se aplica una vez:
   `kubectl apply -f k8s/cluster-scope/clusterissuer-letsencrypt.yaml`).
   Requisito previo del clúster: cert-manager instalado (ver
   [`docs/kubernetes/configuracion.md`](../docs/kubernetes/configuracion.md) §10).

## Verificar sin tocar el clúster

```powershell
kubectl kustomize k8s/apps/environments/local
kubectl kustomize k8s/apps/environments/production   # falla si falta production\secrets.env
kubectl kustomize k8s/observability/environments/local
kubectl kustomize k8s/observability/environments/production   # ídem: secrets.env propio
```
