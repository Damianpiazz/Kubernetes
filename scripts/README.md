# scripts/

Automatización del entorno local y de los despliegues de La Tiendita.

## Cada script, en dos versiones

| bash (Linux / macOS / Windows con Git Bash o WSL) | PowerShell (5.1 o superior) | Qué hace |
|---|---|---|
| `scripts/install-tools.sh` | `scripts/install-tools.ps1` | Verifica (e instala) las herramientas del entorno |
| `scripts/secrets.sh` | `scripts/secrets.ps1` | Genera los `.env` / `secrets.env` desde sus plantillas |
| `scripts/kind-up.sh` | `scripts/kind-up.ps1` | Levanta el entorno local completo sobre kind |
| `scripts/kind-down.sh` | `scripts/kind-down.ps1` | Limpia el entorno local de kind (clúster, imágenes) |
| `scripts/build-images.sh` | `scripts/build-images.ps1` | Construye las imágenes Docker de los servicios |
| `scripts/deploy.sh` | `scripts/deploy.ps1` | Aplica manifests de Kubernetes de forma idempotente |

De un mismo nombre base hay **las dos versiones, con las mismas opciones, los mismos
pasos numerados, la misma salida en español y los mismos códigos de salida**. Elegir
una u otra es cuestión de entorno, no de funcionalidad.

> Los `.sh` no tienen bit de ejecución en Windows: correrlos como `bash scripts/<nombre>.sh`
> (o `./<nombre>.sh` en Linux/macOS si se les da permiso).

Vista rápida de la CLI de cada script (los dos primeros pasos, en modo
verificación — no levantan nada):

```bash
# bash
bash scripts/install-tools.sh --check
bash scripts/secrets.sh --check
```

```powershell
# PowerShell
.\scripts\install-tools.ps1 -Check
.\scripts\secrets.ps1 -Check
```

> En PowerShell, `install-tools.ps1`, `deploy.ps1` y `secrets.ps1` aceptan `-Help`
> (o `-h`). `kind-up.ps1`, `kind-down.ps1` y `build-images.ps1` no tienen
> interruptor de ayuda: usan el help integrado de PowerShell, que trae lo mismo
> (descripción, parámetros y ejemplos):
>
> ```powershell
> Get-Help .\scripts\kind-up.ps1 -Full
> ```

## Orden de ejecución

Para levantar el entorno **desde cero**, el orden es este y ningún otro:

```text
1. install-tools   →  docker, kubectl, kind (+ git) listos        [requerido]
2. secrets         →  .env y secrets.env con valores fuertes      [recomendado]
3. kind-up         →  clúster + build + manifests + pods listos   [requerido]
4. (observabilidad) → Prometheus/Grafana/Loki/…  aparte, a mano   [opcional]
```

`deploy` y `build-images` **no van en esta secuencia**: son herramientas de
mantenimiento sobre un entorno que ya existe. Ver
[Después del primer levantamiento](#después-del-primer-levantamiento).

### 1 · install-tools — siempre primero

Todo lo demás depende de `docker`, `kubectl` y `kind`. Si faltan, `kind-up`
falla en su paso 1 (código `2`) y `deploy` ni siquiera puede hablar con el
clúster. Corre una sola vez por máquina; con `--check` / `-Check` solo verifica.

### 2 · secrets — antes que `kind-up` (pero opcional)

`kind-up` crea `k8s/apps/environments/local/secrets.env` desde su `.example`
si no existe, con **valores de desarrollo**. `secrets` lo que aporta son
valores **fuertes y aleatorios** (y genera los demás targets, incluidos los de
producción, que no existen hasta que se generan).

- ¿Nunca corriste `secrets`? `kind-up` igual funciona, pero con credenciales
  de desarrollo.
- ¿Vas a desplegar a producción? `secrets` es **obligatorio**: `deploy` sale
  con código `6` mientras queden valores de plantilla.

Nunca pisa un archivo ya existente (salvo `--force`), así que correrlo dos
veces es inofensivo.

### 3 · kind-up — el que realmente levanta todo

Un solo comando hace el trabajo pesado en 8 pasos internos: crea (o reutiliza)
el clúster kind, genera secrets si faltan, **construye las imágenes** (build
embebido, no invoca a `build-images`), las carga con `kind load`, aplica los
manifests y espera a que StatefulSets y Deployments queden listos.

Si el clúster ya existe, lo reutiliza: es seguro correrlo de nuevo.

### 4 · Observabilidad — aparte, a mano

`kind-up` **no** despliega Prometheus/Grafana/Loki/Tempo/Alloy/Alertmanager.
Se aplican cuando haga falta, con:

```bash
kubectl apply -k k8s/observability/environments/local
```

### Después del primer levantamiento

| Querés… | Usá |
|---|---|
| Re-aplicar manifests / cambiar configuración | `deploy` sobre `k8s/apps/environments/local` |
| Reconstruir una o varias imágenes | `build-images` (y después `deploy`, o `kind-up --skip-build` / `-SkipBuild`) |
| Levantar todo de cero de nuevo | `kind-up` (reutiliza el clúster) |
| Validar sin tocar el clúster | `deploy --dry-run` o `secrets --check` |

`build-images` solo hace falta si vas a usar `kind-up --skip-build` /
`-SkipBuild` (para cargar imágenes ya construidas) o si querés ver el detalle
y tiempos de build por servicio: `kind-up` ya construye todo lo que hace
falta por su cuenta.

## Copiar y pegar: de cero a corriendo

```bash
# bash — desde la raíz del repo
bash scripts/install-tools.sh
bash scripts/secrets.sh
bash scripts/kind-up.sh
kubectl apply -k k8s/observability/environments/local   # opcional
```

```powershell
# PowerShell — desde la raíz del repo
.\scripts\install-tools.ps1
.\scripts\secrets.ps1
.\scripts\kind-up.ps1
kubectl apply -k k8s/observability/environments/local   # opcional
```

Los ejemplos con `--check` / `-Check` de la sección anterior son para
**verificar sin instalar ni generar nada**, no para el primer levantamiento.

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

## secrets

Genera los archivos de entorno del repo desde sus plantillas `.example`, con
valores aleatorios fuertes en lugar de los `changeme-*`, `CHANGE-ME-*` y
`dev-only-*` de plantilla.

| `--target` | Plantilla | Destino |
|---|---|---|
| `compose` | `.env.example` | `.env` (Docker Compose) |
| `local` | `k8s/apps/environments/local/secrets.env.example` | `…/secrets.env` |
| `observability-local` | `k8s/observability/environments/local/secrets.env.example` | `…/secrets.env` (Grafana dev) |
| `apps` | `k8s/apps/environments/production/secrets.env.example` | `…/secrets.env` |
| `observability` | `k8s/observability/environments/production/secrets.env.example` | `…/secrets.env` |
| `production` | agrupa `apps` + `observability` | |
| `todo` (default) | todos los anteriores | |

**Reglas:**

- **Nunca pisa** un destino que ya existe (salvo `--force`), igual que `kind-up`.
- Se parte del `.example`: comentarios, orden y claves no secretas se conservan
  (`GF_SECURITY_ADMIN_USER`, `TRACING_SAMPLING_PROBABILITY`, Mercado Pago **vacío**);
  solo se generan valores para `JWT_SECRET`, `*_DB_PASSWORD` y `*_ADMIN_PASSWORD`,
  **distintos por archivo**.
- Formatos: `JWT_SECRET` de 48 bytes en base64url (sin `+`, `/`, `=`) y contraseñas
  de 24 caracteres `[A-Za-z0-9]` (sin signos, seguras para JDBC URLs).
- Salida en UTF-8 sin BOM y con finales de línea LF; los archivos generados son
  gitignored: **no se commitean**.

| bash | PowerShell | Default |
|---|---|---|
| `-t`, `--target <id>` | `-Target` | `todo` |
| `--force` | `-Force` | sin forzar: los existentes se omiten |
| `--check` | `-Check` | genera |
| `-h`, `--help` | `-Help` (o `-h`) | — |

Pasos: plantillas → destinos (qué existe / qué falta) → generación, o
verificación con `--check` (que reporta faltantes y valores de plantilla).

```bash
bash scripts/secrets.sh                      # genera los 4 que falten
bash scripts/secrets.sh --target production   # solo los dos de producción
bash scripts/secrets.sh --check               # verifica; sale con 4 si hay problemas
```

```powershell
.\scripts\secrets.ps1 -Target production -Force
.\scripts\secrets.ps1 -Check
```

> `kind-up` sigue creando el `secrets.env` local desde su `.example` si falta (con
> valores de desarrollo, y no lo pisa): `secrets` es el que lo reemplaza por valores
> fuertes. Los dos de producción no existen hasta que se generan, y `deploy` sale
> con código `6` en targets `production` mientras queden valores de plantilla.

---

## kind-up

Levanta el entorno local completo en 8 pasos:

1. **Preflight** — `docker`, `kubectl`, `kind` presentes y daemon activo.
2. **Clúster kind** — crea o reutiliza el clúster y activa el contexto.
3. **Secrets locales** — crea `secrets.env` desde su `.example` si no existe (nunca lo pisa).
4. **Imágenes** — build de los 7 servicios (embebido, no invoca a `build-images`) + `kind load`.
5. **Manifests** — `kubectl apply` del environment local.
6. **Infraestructura** — espera los StatefulSets (`auth-db`, `users-db`, `products-db`,
   `orders-db`, `payments-db`, `kafka`, `azurite`).
7. **Servicios** — espera los Deployments (`auth-service`, `user-service`,
   `product-service`, `order-service`, `payment-service`, `gateway`, `frontend`).
8. **Verificación** — todos los pods del namespace `latiendita` en `Running`/`Completed`.

| bash | PowerShell | Default |
|---|---|---|
| `-c`, `--cluster <nombre>` | `-ClusterName` | `latiendita-dev` (contexto `kind-latiendita-dev`) |
| `-t`, `--tag <tag>` | `-Tag` | `dev` |
| `--skip-build` | `-SkipBuild` | build activado |
| `--timeout <seg>` | `-Timeout` | `300` (por workload) |
| `-h`, `--help` | — | — |

---

## kind-down

Limpia el entorno local que `kind-up` dejó levantado, en 7 pasos. **Es
destructivo y no tiene vuelta atrás.**

1. **Preflight** — `docker` y `kind` presentes y daemon activo (`kubectl`
   opcional: solo se usa para limpiar el contexto).
2. **Inventario** — lista exactamente qué se va a borrar: clúster, contenedores
   de nodo, contexto de kubeconfig e imágenes, con el tamaño total.
3. **Confirmación** — avisa que los volúmenes de Postgres/Kafka/Azurite viven **dentro
   del nodo de kind** y se destruyen para siempre, y pide `s`. Con `-DryRun`
   termina acá sin tocar nada; con `-Yes` no pregunta.
4. **Clúster kind** — `kind delete cluster`, los contenedores de nodo que
   quedaron y la entrada de contexto en kubeconfig.
5. **Imágenes** — borra `latiendita/<servicio>:<tag>`.
6. **Build cache** — solo si se pidió `-Cache`.
7. **Verificación** — confirma que no quedó nada y reporta el espacio liberado.

Es idempotente: si no hay nada que limpiar, lo dice y sale con `0`.

| bash | PowerShell | Default |
|---|---|---|
| `-c`, `--cluster <nombre>` | `-ClusterName` | `latiendita-dev` (contexto `kind-latiendita-dev`) |
| `-t`, `--tag <tag>` | `-Tag` | `dev` |
| `--keep-images` | `-KeepImages` | se borran |
| `--cache` | `-Cache` | se conserva |
| `-y`, `--yes` | `-Yes` | pide confirmación |
| `-d`, `--dry-run` | `-DryRun` | limpieza real |
| `-h`, `--help` | — | — |

```bash
bash scripts/kind-down.sh --dry-run    # ver qué se borraría, sin borrar nada
bash scripts/kind-down.sh --yes        # limpiar todo sin preguntar
```

```powershell
.\scripts\kind-down.ps1 -DryRun
.\scripts\kind-down.ps1 -Yes -Cache
```

> `--cache` / `-Cache` es opt-in a propósito: limpiar la build cache te obliga a
> un cold rebuild de los 7 servicios la próxima vez que corras `kind-up`.

---

## build-images

Construye `latiendita/<servicio>:<tag>` para cada servicio — los microservicios
viven en `services/` y el frontend en `frontend/` —, muestra el
avance por servicio (tiempo y tamaño) y cierra con una tabla de resultados. Si algún
build falla, sigue con el resto y termina con error al final.

Servicios: `auth-service`, `user-service`, `product-service`, `order-service`,
`payment-service`, `gateway`, `frontend`.

> `eureka-server` **no** está en este listado: en Kubernetes no se despliega (los
> servicios corren con `EUREKA_ENABLED=false` y el tráfico entra por el Gateway);
> solo aplica al desarrollo local con Docker Compose.

| bash | PowerShell | Default |
|---|---|---|
| `-t`, `--tag <tag>` | `-Tag` | `dev` |
| `-s`, `--service <nombre>` (repetible) | `-Services` (array) | los 7 servicios |
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
| `secrets` | `1` uso o target desconocido · `2` falta una plantilla `.example` · `3` escritura falló · `4` `--check` con faltantes o valores de plantilla |
| `build-images` | `1` uso · `2` docker no disponible · `3` build fallido |
| `kind-up` | `1` uso · `2` herramientas · `3` clúster · `4` secrets · `5` imágenes · `6` apply · `7` rollout · `8` pods |
| `kind-down` | `1` uso · `2` herramientas · `3` confirmación rechazada · `4` clúster o nodo · `5` imágenes · `6` build cache · `7` quedaron recursos |
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
bash -n scripts/install-tools.sh && bash -n scripts/secrets.sh && bash -n scripts/kind-up.sh \
  && bash -n scripts/kind-down.sh && bash -n scripts/build-images.sh && bash -n scripts/deploy.sh
```

```powershell
Get-ChildItem scripts\*.ps1 | ForEach-Object {
    $err = $null
    [System.Management.Automation.PSParser]::Tokenize((Get-Content $_.FullName -Raw), [ref]$err) | Out-Null
    '{0}: {1} error(es)' -f $_.Name, $err.Count
}
```

Y una corrida real que no toca el clúster: `--help` / `-Help` en cualquiera,
`--check` / `-Check` en `install-tools` y `secrets` (`secrets --check` además
no escribe nada), y `--dry-run` / `-DryRun` en `kind-down` y `deploy` (solo
informan, no borran ni aplican nada).

## Referencias

- [`docs/kubernetes/comandos.md`](../docs/kubernetes/comandos.md) §7 — comandos
  equivalentes en kubectl y tabla de códigos de salida (no se repiten aquí).
- [`k8s/README.md`](../k8s/README.md) — estructura de los manifests y reglas de
  los environments (`k8s/apps/environments/local`, `.../production`, etc.).
