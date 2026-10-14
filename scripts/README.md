# scripts/

Automatización del entorno local y de los despliegues de La Tiendita.

## Cada script, en dos versiones

| bash (Linux / macOS / Windows con Git Bash o WSL) | PowerShell (5.1 o superior) | Qué hace |
|---|---|---|
| `scripts/install-tools.sh` | `scripts/install-tools.ps1` | Verifica (e instala) las herramientas del entorno |
| `scripts/kind-up.sh` | `scripts/kind-up.ps1` | Levanta el entorno local completo sobre kind |
| `scripts/build-images.sh` | `scripts/build-images.ps1` | Construye las imágenes Docker de los servicios |
| `scripts/deploy.sh` | `scripts/deploy.ps1` | Aplica manifests de Kubernetes de forma idempotente |

De un mismo nombre base hay **las dos versiones, con las mismas opciones, los mismos
pasos numerados, la misma salida en español y los mismos códigos de salida**. Elegir
una u otra es cuestión de entorno, no de funcionalidad.

> Los `.sh` no tienen bit de ejecución en Windows: correrlos como `bash scripts/<nombre>.sh`
> (o `./<nombre>.sh` en Linux/macOS si se les da permiso).

```bash
# bash
bash scripts/install-tools.sh --check
bash scripts/kind-up.sh
bash scripts/deploy.sh -s k8s/apps/environments/local/secrets.env k8s/apps/environments/local
```

```powershell
# PowerShell
.\scripts\install-tools.ps1 -Check
.\scripts\kind-up.ps1
.\scripts\deploy.ps1 -s k8s/apps/environments/local/secrets.env k8s/apps/environments/local
```

> En PowerShell, `install-tools.ps1` y `deploy.ps1` aceptan `-Help` (o `-h`).
> `kind-up.ps1` y `build-images.ps1` no tienen interruptor de ayuda: usan el help
> integrado de PowerShell, que trae lo mismo (descripción, parámetros y ejemplos):
>
> ```powershell
> Get-Help .\scripts\kind-up.ps1 -Full
> ```

## Flujo típico

```text
1. install-tools   →  docker, kubectl, kind (+ git) listos
2. kind-up         →  clúster kind + secrets + imágenes + manifests + pods listos
3. deploy          →  re-despliegues puntuales sobre lo que ya corre
```

`kind-up` **no despliega observabilidad**: Prometheus/Grafana/Loki/Tempo/Alloy/Alertmanager
se aplican aparte con `kubectl apply -k k8s/observability/environments/local`.

---

## install-tools

Verifica e instala las herramientas necesarias. Por defecto instala lo que falte;
con `--check` solo verifica y no toca nada.

- **Obligatorias:** `docker`, `kubectl`, `kind` → si faltan, sale con código `2`.
- **Recomendada:** `git` → si falta, solo advierte (no cambia el código de salida).
- Detecta el SO y el gestor de paquetes: **winget** en Windows, **brew** en macOS,
  **apt-get / dnf / yum / pacman / zypper** en Linux. Cuando no hay gestor,
  `kubectl` y `kind` se instalan como binario oficial en `/usr/local/bin`.
- Último paso: estado de las cuatro herramientas + que el daemon de Docker responda.

| bash | PowerShell |
|---|---|
| `--check` | `-Check` |
| `-h`, `--help` | `-Help` (o `-h`) |

```bash
bash scripts/install-tools.sh            # instala lo que falte
bash scripts/install-tools.sh --check    # solo verifica
```

---

## kind-up

Levanta el entorno local completo en 8 pasos:

1. **Preflight** — `docker`, `kubectl`, `kind` presentes y daemon activo.
2. **Clúster kind** — crea o reutiliza el clúster y activa el contexto.
3. **Secrets locales** — crea `secrets.env` desde su `.example` si no existe (nunca lo pisa).
4. **Imágenes** — build de los 6 servicios (embebido, no invoca a `build-images`) + `kind load`.
5. **Manifests** — `kubectl apply` del environment local.
6. **Infraestructura** — espera los StatefulSets (`auth-db`, `users-db`, `products-db`,
   `orders-db`, `payments-db`, `kafka`).
7. **Servicios** — espera los Deployments (`auth-service`, `user-service`,
   `product-service`, `order-service`, `payment-service`, `gateway`).
8. **Verificación** — todos los pods del namespace `latiendita` en `Running`/`Completed`.

| bash | PowerShell | Default |
|---|---|---|
| `-c`, `--cluster <nombre>` | `-ClusterName` | `latiendita-dev` (contexto `kind-latiendita-dev`) |
| `-t`, `--tag <tag>` | `-Tag` | `dev` |
| `--skip-build` | `-SkipBuild` | build activado |
| `--timeout <seg>` | `-Timeout` | `300` (por workload) |
| `-h`, `--help` | — | — |

---

## build-images

Construye `latiendita/<servicio>:<tag>` para cada servicio de `services/`, muestra el
avance por servicio (tiempo y tamaño) y cierra con una tabla de resultados. Si algún
build falla, sigue con el resto y termina con error al final.

Servicios: `auth-service`, `user-service`, `product-service`, `order-service`,
`payment-service`, `gateway`.

> `eureka-server` **no** está en este listado: en Kubernetes no se despliega (los
> servicios corren con `EUREKA_ENABLED=false` y el tráfico entra por el Gateway);
> solo aplica al desarrollo local con Docker Compose.

| bash | PowerShell | Default |
|---|---|---|
| `-t`, `--tag <tag>` | `-Tag` | `dev` |
| `-s`, `--service <nombre>` (repetible) | `-Services` (array) | los 6 servicios |
| `--no-cache` | `-NoCache` | caché activada |
| `--pull` | `-Pull` | sin `--pull` |
| `-h`, `--help` | — | — |

```bash
bash scripts/build-images.sh --tag dev -s gateway --no-cache
```

---

## deploy

Deploy idempotente: renderiza los manifests, valida los secrets, crea namespaces,
aplica, espera cada workload y reporta estado.

**Los manifests** son archivos o directorios:

- directorio **con `kustomization.yaml`** → se renderiza con `kubectl kustomize`;
- directorio **sin él** → se aplica cada `*.yaml` / `*.yml` de primer nivel (orden alfabético);
- archivo suelto → se aplica tal cual.

**Opciones (iguales en `.sh` y `.ps1`):**

| Opción | Para qué |
|---|---|
| `-s`, `--secrets <archivo>` | Archivo `.env` a leer y validar (default `./secrets.env`, o la variable `SECRETS_FILE`) |
| `-n`, `--namespace <ns>` | Garantiza ese namespace antes de aplicar |
| `-t`, `--timeout <seg>` | Espera máxima **por workload** (default `300`) |
| `-l`, `--logs <nombre>` | Tail de logs de ese Deployment/StatefulSet al final |
| `--log-tail <n>` | Líneas a mostrar con `--logs` (default `100`) |
| `-d`, `--dry-run` | Renderiza y valida, pero no aplica nada |
| `-h`, `--help` | Ayuda |

> `deploy.ps1` no declara `param()`: lee los argumentos crudos de `$args` para
> replicar exactamente la CLI de `deploy.sh` (incluidas las opciones con guiones
> dobles como `--dry-run`). Por eso no soporta `-WhatIf`.

Pasos: cargar secrets → renderizar → validar claves `secretKeyRef` (y, en targets
`production`, rechazar valores de plantilla) → namespaces →
aplicar (o dry-run) → esperar workloads → informe de estado, URLs de acceso,
logs opcionales y resumen.

```bash
# con secrets explícitos
bash scripts/deploy.sh -s k8s/apps/environments/local/secrets.env k8s/apps/environments/local

# producción (el secrets.env hay que crearlo desde su .example: es gitignored)
bash scripts/deploy.sh -s k8s/apps/environments/production/secrets.env k8s/apps/environments/production

# validación sin tocar el clúster
bash scripts/deploy.sh --dry-run k8s/apps/environments/local

# deploy + tail de logs del gateway
bash scripts/deploy.sh k8s/apps/environments/local --logs gateway --log-tail 50
```

---

## Códigos de salida

Iguales en `.sh` y `.ps1`; pensados para usarlos desde CI o con `$?` / `$LASTEXITCODE`.

| Script | Códigos |
|---|---|
| `install-tools` | `1` uso · `2` falta una herramienta obligatoria |
| `build-images` | `1` uso · `2` docker no disponible · `3` build fallido |
| `kind-up` | `1` uso · `2` herramientas · `3` clúster · `4` secrets · `5` imágenes · `6` apply · `7` rollout · `8` pods |
| `deploy` | `1` uso · `2` secrets · `3` apply · `4` no ready · `5` claves faltantes · `6` valores de plantilla en target `production` |

---

## Convenciones

- **Autocontenidos**: ningún script sourcea librerías ni invoca a otro script del repo.
  La ruta `scripts/lib/` ya no existe.
- **Bloque espejo**: los helpers de consola viven embebidos entre
  `# --- console (bloque espejo) ---` y `# --- fin console ---`, y ese bloque es
  **idéntico** en todos los `.sh` (y, por separado, idéntico en todos los `.ps1`).
  Si cambia en uno, se replica en los demás.
- **Salida**: en español, pasos numerados `[n/N]`, símbolos `✓` (ok), `!` (aviso),
  `✗` (error), y un resumen final con duración. `NO_COLOR` (o una salida que no sea
  TTY) desactiva los colores.
- **Encoding**: los `.ps1` van en **UTF-8 con BOM** (sin él, PowerShell 5.1 rompe
  `✓`, `✗` y los acentos al redirigir) y los `.sh` en **LF sin BOM** con
  `#!/usr/bin/env bash` y `set -euo pipefail`.
- **Desde la raíz del repo**: todos usan rutas relativas (`services/`, `k8s/...`)
  y fallan con código `1` si no encuentran el directorio esperado.
- **Errores de kubectl/docker**: en los `.ps1`, `$ErrorActionPreference` queda en
  `Continue` y el resultado se valida por `$LASTEXITCODE`, porque los comandos
  nativos escriben en stderr aunque salgan bien.

## Verificar un cambio

```bash
bash -n scripts/install-tools.sh && bash -n scripts/kind-up.sh \
  && bash -n scripts/build-images.sh && bash -n scripts/deploy.sh
```

```powershell
Get-ChildItem scripts\*.ps1 | ForEach-Object {
    $err = $null
    [System.Management.Automation.PSParser]::Tokenize((Get-Content $_.FullName -Raw), [ref]$err) | Out-Null
    '{0}: {1} error(es)' -f $_.Name, $err.Count
}
```

Y una corrida real que no toca el clúster: `--help` / `-Help` en cualquiera, y
`--check` / `-Check` en `install-tools`.

## Referencias

- [`docs/kubernetes/comandos.md`](../docs/kubernetes/comandos.md) §7 — comandos
  equivalentes en kubectl y tabla de códigos de salida (no se repiten aquí).
- [`k8s/README.md`](../k8s/README.md) — estructura de los manifests y reglas de
  los environments (`k8s/apps/environments/local`, `.../production`, etc.).
