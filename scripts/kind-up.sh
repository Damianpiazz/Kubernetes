#!/usr/bin/env bash
set -euo pipefail

# kind-up.sh - levanta el entorno local completo de La Tiendita sobre kind.
#
# Par nativo de scripts/kind-up.ps1: mismos objetivos, mismas opciones, mismos
# pasos y mismo contrato de códigos de salida.
#
#   1. Verifica docker, kubectl y kind.
#   2. Crea (o reutiliza) el clúster kind y activa su contexto.
#   3. Crea k8s/apps/environments/local/secrets.env desde el example si no existe.
#   4. Construye las imágenes y las carga en el clúster (kind load).
#   5. Aplica k8s/apps/environments/local y espera StatefulSets y Deployments.
#   6. Verifica que todos los pods queden listos.
#
# Contrato de códigos de salida (idéntico al de kind-up.ps1):
#   1  uso incorrecta / repo sin k8s/apps/environments/local
#   2  herramientas faltantes o daemon de Docker caído
#   3  clúster no creado / contexto no activado
#   4  secrets locales (falta el example o no se pudo copiar)
#   5  build fallido / imagen ausente / kind load fallido
#   6  kubectl apply falló
#   7  algún StatefulSet o Deployment no quedó listo
#   8  pods del namespace no listos

usage() {
  cat <<'EOF'
Uso: scripts/kind-up.sh [opciones]

Levanta el entorno local completo de La Tiendita sobre kind: clúster,
secrets locales, imágenes, manifests y espera de los workloads.

Opciones:
  -c, --cluster <nombre>   Nombre del clúster kind (default: latiendita-dev)
  -t, --tag <tag>          Tag de las imágenes (default: dev)
      --skip-build         Omite el build y usa las imágenes ya presentes
                           en el daemon de Docker
      --timeout <seg>      Segundos máximos de espera por rollout (default: 300)
  -h, --help               Esta ayuda

Códigos de salida: 1 uso · 2 herramientas · 3 clúster · 4 secrets ·
                   5 imágenes · 6 apply · 7 rollout · 8 pods
EOF
}

# --- console (bloque espejo) --------------------------------------------------
# Helpers embebidos: este bloque es idéntico en todos los .sh de scripts/ que lo declaran.
# Sin dependencias externas; si cambia acá, cambia en los demás.
if [[ -z "${NO_COLOR:-}" && -t 1 && "${TERM:-}" != dumb ]]; then
  C_RESET=$'\033[0m'; C_DIM=$'\033[2m'; C_CYAN=$'\033[36m'
  C_GREEN=$'\033[32m'; C_YELLOW=$'\033[33m'; C_RED=$'\033[31m'
else
  C_RESET=''; C_DIM=''; C_CYAN=''; C_GREEN=''; C_YELLOW=''; C_RED=''
fi

TOTAL_STEPS=0
STEP=0
STEP_T0MS=0
WARN_COUNT=0
NEXT_STEPS=()

# now_ms: milisegundos desde epoch. GNU date soporta %N; BSD/macOS no, y ahí se
# cae a segundos de precisión (el formato de salida no cambia).
now_ms() {
  local ns
  ns="$(date +%s%N 2>/dev/null || echo '')"
  if [[ "$ns" =~ ^[0-9]{16,}$ ]]; then
    printf '%s' "$(( ns / 1000000 ))"
  else
    printf '%s' "$(( $(date +%s) * 1000 ))"
  fi
}

fmt_duration() {  # fmt_duration <milisegundos>
  local ms="$1"
  if (( ms < 1000 )); then
    printf '%s ms' "$ms"
  elif (( ms < 60000 )); then
    awk -v m="$ms" 'BEGIN { printf "%.1f s", m / 1000 }'
  else
    awk -v m="$ms" 'BEGIN { printf "%dm %02ds", int(m / 60000), int((m % 60000) / 1000) }'
  fi
}

fmt_bytes() {  # fmt_bytes <bytes>
  local b="$1"
  if (( b >= 1073741824 )); then
    awk -v n="$b" 'BEGIN { printf "%.2f GB", n / 1073741824 }'
  elif (( b >= 1048576 )); then
    awk -v n="$b" 'BEGIN { printf "%.0f MB", n / 1048576 }'
  elif (( b >= 1024 )); then
    awk -v n="$b" 'BEGIN { printf "%.0f KB", n / 1024 }'
  else
    printf '%s B' "$b"
  fi
}

rep() {  # rep <char> <n>
  local out='' i
  for (( i = 0; i < $2; i++ )); do out+="$1"; done
  printf '%s' "$out"
}

header() {
  printf '\n%s------------------------------------------------------------------------------%s\n' "$C_DIM" "$C_RESET"
  printf '  %s\n' "$1"
  shift
  local line
  for line in "$@"; do printf '  %s%s%s\n' "$C_DIM" "$line" "$C_RESET"; done
  printf '%s------------------------------------------------------------------------------%s\n' "$C_DIM" "$C_RESET"
}

step() {
  STEP=$((STEP + 1))
  printf '\n%s[%d/%d] %s%s\n' "$C_CYAN" "$STEP" "$TOTAL_STEPS" "$1" "$C_RESET"
  STEP_T0MS=$(now_ms)
}

done_step() {
  printf '  %s✓%s %s %s· %s%s\n' "$C_GREEN" "$C_RESET" "$*" "$C_DIM" \
    "$(fmt_duration $(( $(now_ms) - STEP_T0MS )))" "$C_RESET"
}

note() { printf '      %s%s%s\n' "$C_DIM" "$*" "$C_RESET"; }
info() { printf '    %s%s%s\n' "$C_DIM" "$*" "$C_RESET"; }
ok()   { printf '    %s✓%s %s\n' "$C_GREEN" "$C_RESET" "$*"; }
warn() { WARN_COUNT=$((WARN_COUNT + 1)); printf '    %s!%s %s\n' "$C_YELLOW" "$C_RESET" "$*" >&2; }

fail() {  # fail <mensaje> [código=1] [pista]
  local msg="$1" code="${2:-1}" hint="${3:-}"
  printf '\n  %s✗%s %s\n' "$C_RED" "$C_RESET" "$msg" >&2
  if [[ -n "$hint" ]]; then
    printf '         %s→ %s%s\n' "$C_YELLOW" "$hint" "$C_RESET" >&2
  fi
  printf '  %sTiempo total: %s%s\n' "$C_DIM" \
    "$(fmt_duration $(( $(now_ms) - START_MS )))" "$C_RESET" >&2
  exit "$code"
}

assert_tool() {  # assert_tool <comando> <pista> [código=2]
  command -v "$1" >/dev/null 2>&1 && return 0
  fail "Falta la herramienta '$1'." "${3:-2}" "${2:-Instalar '$1' y reintentar.}"
}

# run_tool <cmd> [args...] -> TOOL_OUT / TOOL_RC (no aborta con set -e)
run_tool() {
  TOOL_RC=0
  TOOL_OUT="$("$@" 2>&1)" || TOOL_RC=$?
}

summary() {  # summary <título> [fila...]  (NEXT_STEPS: array de próximos pasos)
  local title="$1" row
  shift
  printf '\n%s------------------------------------------------------------------------------%s\n' "$C_DIM" "$C_RESET"
  printf '  %s\n' "$title"
  for row in "$@"; do printf '    %s\n' "$row"; done
  if (( ${#NEXT_STEPS[@]} > 0 )); then
    printf '\n  %sPróximos pasos:%s\n' "$C_DIM" "$C_RESET"
    for row in "${NEXT_STEPS[@]}"; do printf '    %s%s%s\n' "$C_CYAN" "$row" "$C_RESET"; done
  fi
  printf '%s------------------------------------------------------------------------------%s\n' "$C_DIM" "$C_RESET"
  if (( WARN_COUNT > 0 )); then
    printf '  %s%s advertencia(s) durante la ejecución%s\n' "$C_YELLOW" "$WARN_COUNT" "$C_RESET"
  fi
  printf '  %sDuración total: %s%s\n' "$C_DIM" "$(fmt_duration $(( $(now_ms) - START_MS )))" "$C_RESET"
  printf '\n'
}
# --- fin console -------------------------------------------------------------
START_MS=$(now_ms)

# --- Opciones ----------------------------------------------------------------
CLUSTER_NAME="latiendita-dev"
TAG="dev"
SKIP_BUILD=0
TIMEOUT=300

while [[ $# -gt 0 ]]; do
  case "$1" in
    -c|--cluster)   CLUSTER_NAME="${2:?valor faltante para $1}"; shift 2 ;;
    -t|--tag)       TAG="${2:?valor faltante para $1}"; shift 2 ;;
    --skip-build)   SKIP_BUILD=1; shift ;;
    --timeout)      TIMEOUT="${2:?valor faltante para $1}"; shift 2 ;;
    -h|--help)      usage; exit 0 ;;
    --)             shift; break ;;
    -*)             usage >&2; fail "opción desconocida: $1" ;;
    *)              usage >&2; fail "argumento inesperado: $1" ;;
  esac
done
if (( $# > 0 )); then usage >&2; fail "argumento inesperado: $1"; fi
[[ "$TIMEOUT" =~ ^[0-9]+$ ]] || fail "--timeout debe ser un número" 1

NAMESPACE="latiendita"
OVERLAY="k8s/apps/environments/local"
SECRETS_FILE="k8s/apps/environments/local/secrets.env"
SECRETS_EXAMPLE="k8s/apps/environments/local/secrets.env.example"
CONTEXT="kind-${CLUSTER_NAME}"
TOTAL_STEPS=8

APPS=(auth-service user-service product-service order-service payment-service gateway)
STATEFULSETS=(auth-db users-db products-db orders-db payments-db kafka)

[[ -d "$OVERLAY" ]] || fail "no existe $OVERLAY: correr desde la raíz del repo." 1

header "La Tiendita · entorno local (kind)" \
  "clúster    : $CLUSTER_NAME" \
  "contexto   : $CONTEXT" \
  "tag        : $TAG" \
  "namespace  : $NAMESPACE"

# --- 1. Preflight ------------------------------------------------------------
step "Preflight"
assert_tool docker "Instalar Docker Desktop y dejarlo corriendo antes de continuar."
assert_tool kubectl "Instalar kubectl (winget install Kubernetes.kubectl / apt-get install kubectl)."
assert_tool kind "Instalar kind (winget install Kubernetes.kind / kind sigs.k8s.io)."

run_tool docker info --format '{{.ServerVersion}}'
if (( TOOL_RC != 0 )); then
  fail "El daemon de Docker no está corriendo." 2 "Iniciar Docker Desktop y reintentar."
fi
DAEMON_VERSION="${TOOL_OUT//$'\n'/}"

# kubectl --short fue removido en 1.28: se intenta y se cae a --client.
run_tool kubectl version --client --short
if (( TOOL_RC != 0 )); then run_tool kubectl version --client; fi
KTEXT="$(printf '%s\n' "$TOOL_OUT" | grep -m1 'Client Version' || true)"
if [[ -z "$KTEXT" ]]; then KTEXT="$(printf '%s\n' "$TOOL_OUT" | head -n1)"; fi
KTEXT="${KTEXT#"${KTEXT%%[![:space:]]*}"}"
[[ -n "$KTEXT" ]] || KTEXT="desconocida"
run_tool kind version
KIND_TEXT="$(printf '%s\n' "$TOOL_OUT" | head -n1)"
KIND_TEXT="${KIND_TEXT#"${KIND_TEXT%%[![:space:]]*}"}"
[[ -n "$KIND_TEXT" ]] || KIND_TEXT="desconocida"

note "docker  $DAEMON_VERSION"
note "kubectl $KTEXT"
note "kind    $KIND_TEXT"
done_step "herramientas listas"

# --- 2. Clúster --------------------------------------------------------------
step "Clúster kind"
run_tool kubectl config get-contexts -o name
HAS_CONTEXT=0
while IFS= read -r ctx; do
  if [[ "$ctx" == "$CONTEXT" ]]; then HAS_CONTEXT=1; break; fi
done <<< "$TOOL_OUT"

if (( HAS_CONTEXT == 0 )); then
  note "creando clúster '$CLUSTER_NAME' (puede tardar varios minutos la primera vez)..."
  rc=0
  kind create cluster --name "$CLUSTER_NAME" || rc=$?
  if (( rc != 0 )); then
    fail "No se pudo crear el clúster '$CLUSTER_NAME'." 3 "Revisar el log de kind arriba."
  fi
else
  note "reutilizando clúster existente '$CLUSTER_NAME'"
  run_tool kubectl cluster-info --context "$CONTEXT"
  if (( TOOL_RC != 0 )); then
    fail "El clúster '$CLUSTER_NAME' existe pero no responde." 3 \
      "Borrarlo y volver a empezar: kind delete cluster --name $CLUSTER_NAME"
  fi
fi

run_tool kubectl config use-context "$CONTEXT"
if (( TOOL_RC != 0 )); then
  fail "No se pudo activar el contexto '$CONTEXT'." 3
fi

run_tool kubectl version -o 'jsonpath={.serverVersion.gitVersion}'
SERVER_VERSION="${TOOL_OUT//$'\n'/}"
if (( TOOL_RC == 0 )) && [[ -n "${SERVER_VERSION//[$'\t\r\n ']/}" ]]; then
  run_tool kubectl config current-context
  note "contexto activo: ${TOOL_OUT//$'\n'/} · k8s $SERVER_VERSION"
else
  note "contexto activo: $CONTEXT"
fi
done_step "clúster '$CLUSTER_NAME' disponible"

# --- 3. Secrets locales ------------------------------------------------------
step "Secrets locales"
if [[ -f "$SECRETS_FILE" ]]; then
  note "$SECRETS_FILE"
  done_step "ya existe, no se toca"
else
  if [[ ! -f "$SECRETS_EXAMPLE" ]]; then
    fail "No existe $SECRETS_FILE ni su example." 4
  fi
  cp "$SECRETS_EXAMPLE" "$SECRETS_FILE" || fail "No se pudo crear $SECRETS_FILE." 4
  note "$SECRETS_FILE  <-  $SECRETS_EXAMPLE"
  warn "secrets.env creado con valores de desarrollo: no usar fuera de este clúster."
  done_step "creado desde el example"
fi

# --- 4. Imágenes -------------------------------------------------------------
step "Imágenes"
if (( SKIP_BUILD == 1 )); then
  note "build omitido (--skip-build): se usan las imágenes ya presentes en Docker."
else
  BUILD_T0=$(now_ms)
  FAILED_BUILD=()
  for s in "${APPS[@]}"; do
    IMAGE="latiendita/$s:$TAG"
    printf '\n'
    info "$IMAGE"
    rc=0
    docker build -t "$IMAGE" "./services/$s" || rc=$?
    if (( rc != 0 )); then
      FAILED_BUILD+=("$s")
      warn "build falló para '$s' (exit code $rc)"
    fi
  done
  if (( ${#FAILED_BUILD[@]} > 0 )); then
    fail "Falló el build de ${#FAILED_BUILD[@]} servicio(s): ${FAILED_BUILD[*]}" 5 \
      "Correr scripts/build-images.sh para ver el detalle."
  fi
  note "build: $(fmt_duration $(( $(now_ms) - BUILD_T0 )))"
fi

for s in "${APPS[@]}"; do
  IMAGE="latiendita/$s:$TAG"
  run_tool docker image inspect -f '{{.Id}}' "$IMAGE"
  if (( TOOL_RC != 0 )); then
    fail "No existe la imagen '$IMAGE'." 5 \
      "Correr scripts/build-images.sh --tag $TAG o sacar --skip-build."
  fi
  load_t0=$(now_ms)
  run_tool kind load docker-image "$IMAGE" --name "$CLUSTER_NAME"
  if (( TOOL_RC != 0 )); then
    fail "No se pudo cargar '$IMAGE' en el clúster." 5 "Revisar el log de kind arriba."
  fi
  ok "$(printf '%-26s %s' "$IMAGE" "$(fmt_duration $(( $(now_ms) - load_t0 )))")"
done
done_step "${#APPS[@]} imágenes cargadas en el clúster"

# --- 5. Manifests ------------------------------------------------------------
step "Aplicar manifests ($OVERLAY)"
run_tool kubectl apply -k "$OVERLAY"
while IFS= read -r line; do
  if [[ -n "${line//[[:space:]]/}" ]]; then note "$line"; fi
done <<< "$TOOL_OUT"
if (( TOOL_RC != 0 )); then
  fail "kubectl apply falló." 6 "Revisar el render: kubectl kustomize $OVERLAY"
fi
APPLIED="$(printf '%s\n' "$TOOL_OUT" | grep -cE 'configured|created|unchanged' || true)"
done_step "$APPLIED recursos aplicados"

# --- 6. Infraestructura ------------------------------------------------------
step "Esperar infraestructura (StatefulSets)"
for d in "${STATEFULSETS[@]}"; do
  wait_t0=$(now_ms)
  run_tool kubectl rollout status "statefulset/$d" -n "$NAMESPACE" "--timeout=${TIMEOUT}s"
  if (( TOOL_RC != 0 )); then
    fail "statefulset/$d no quedó listo en ${TIMEOUT}s." 7 \
      "kubectl describe statefulset $d -n $NAMESPACE; kubectl get pods -n $NAMESPACE"
  fi
  ok "$(printf '%-16s %s' "$d" "$(fmt_duration $(( $(now_ms) - wait_t0 )))")"
done
done_step "${#STATEFULSETS[@]}/${#STATEFULSETS[@]} listos"

# --- 7. Servicios ------------------------------------------------------------
step "Esperar servicios (Deployments)"
for s in "${APPS[@]}"; do
  wait_t0=$(now_ms)
  run_tool kubectl rollout status "deployment/$s" -n "$NAMESPACE" "--timeout=${TIMEOUT}s"
  if (( TOOL_RC != 0 )); then
    fail "deployment/$s no quedó listo en ${TIMEOUT}s." 7 \
      "kubectl describe deployment $s -n $NAMESPACE; kubectl logs deploy/$s -n $NAMESPACE --tail=50"
  fi
  ok "$(printf '%-22s %s' "$s" "$(fmt_duration $(( $(now_ms) - wait_t0 )))")"
done
done_step "${#APPS[@]}/${#APPS[@]} listos"

# --- 8. Verificación ---------------------------------------------------------
step "Verificación"
run_tool kubectl get pods -n "$NAMESPACE" --no-headers
if (( TOOL_RC != 0 )); then
  fail "No se pudieron listar los pods del namespace '$NAMESPACE'." 8
fi

TOTAL_PODS=0
NOT_READY=()
cols=()
while IFS= read -r line; do
  if [[ -z "${line//[[:space:]]/}" ]]; then continue; fi
  read -ra cols <<< "$line"
  if (( ${#cols[@]} < 3 )); then continue; fi
  TOTAL_PODS=$((TOTAL_PODS + 1))
  READY="${cols[1]}"
  STATUS="${cols[2]}"
  if [[ "$READY" == */* ]]; then
    CUR="${READY%%/*}"; WANT="${READY##*/}"
  else
    CUR=''; WANT=''
  fi
  IS_READY=0
  if [[ "$CUR" == "$WANT" ]] && { [[ "$STATUS" == "Running" ]] || [[ "$STATUS" == "Completed" ]]; }; then
    IS_READY=1
  fi
  if (( IS_READY == 0 )); then NOT_READY+=("$line"); fi
done <<< "$TOOL_OUT"

if (( TOTAL_PODS == 0 )); then
  fail "El namespace '$NAMESPACE' no tiene pods." 8 "kubectl get all -n $NAMESPACE"
fi
if (( ${#NOT_READY[@]} > 0 )); then
  for p in "${NOT_READY[@]}"; do warn "$p"; done
  fail "${#NOT_READY[@]} de $TOTAL_PODS pods no están listos." 8 \
    "kubectl get pods -n $NAMESPACE; kubectl describe pod <pod> -n $NAMESPACE"
fi
done_step "$TOTAL_PODS/$TOTAL_PODS pods listos"

NEXT_STEPS=(
  "kubectl port-forward svc/gateway 8080:80 -n $NAMESPACE"
  "curl http://localhost:8080/actuator/health/readiness"
  "kubectl get pods -n $NAMESPACE -w"
)
summary "Entorno local desplegado" \
  "clúster    : $CLUSTER_NAME (contexto $CONTEXT)" \
  "namespace  : $NAMESPACE" \
  "pods       : $TOTAL_PODS listos" \
  "workloads  : ${#STATEFULSETS[@]} statefulsets + ${#APPS[@]} deployments"
