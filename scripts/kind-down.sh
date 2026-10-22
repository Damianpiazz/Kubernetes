#!/usr/bin/env bash
set -euo pipefail

# kind-down.sh - limpia el entorno local de La Tiendita sobre kind.
#
# Par nativo de scripts/kind-down.ps1: mismos objetivos, mismas opciones, mismos
# pasos y mismo contrato de códigos de salida.
#
#   1. Verifica docker y kind, y que el daemon responda.
#   2. Inventario de lo que se va a borrar (clúster, contexto, nodos, imágenes).
#   3. Aviso de pérdida de datos y confirmación (o --dry-run / --yes).
#   4. Borra el clúster kind, sus contenedores de nodo y su contexto.
#   5. Borra las imágenes latiendita/<servicio>:<tag> (salvo --keep-images).
#   6. Limpia la build cache (solo con --cache).
#   7. Verifica que no quede nada y reporta el espacio recuperado.
#
# Contrato de códigos de salida (idéntico al de kind-down.ps1):
#   1  uso incorrecta
#   2  herramientas faltantes o daemon de Docker caído
#   3  confirmación rechazada / abortado
#   4  clúster o nodo no borrado
#   5  imágenes no borradas
#   6  build cache no limpiada
#   7  quedaron recursos tras la limpieza

usage() {
  cat <<'EOF'
Uso: scripts/kind-down.sh [opciones]

Limpia el entorno local de La Tiendita sobre kind: borra el clúster, sus
contenedores de nodo, su contexto de kubeconfig y las imágenes construidas.

ADVERTENCIA: los datos de las bases (Postgres/Kafka/Azurite) viven en volúmenes
dentro del nodo de kind. Borrar el clúster los destruye de forma permanente.

Opciones:
  -c, --cluster <nombre>   Nombre del clúster kind (default: latiendita-dev)
  -t, --tag <tag>          Tag de las imágenes a borrar (default: dev)
      --keep-images        No borra las imágenes, solo el clúster
      --cache              También limpia la build cache (docker builder prune)
  -y, --yes                No pide confirmación (para CI)
  -d, --dry-run            Solo muestra el inventario; no borra nada
  -h, --help               Esta ayuda

Códigos de salida: 1 uso · 2 herramientas · 3 confirmación · 4 clúster ·
                   5 imágenes · 6 cache · 7 quedaron recursos
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
KEEP_IMAGES=0
CLEAN_CACHE=0
ASSUME_YES=0
DRY_RUN=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    -c|--cluster)   CLUSTER_NAME="${2:?valor faltante para $1}"; shift 2 ;;
    -t|--tag)       TAG="${2:?valor faltante para $1}"; shift 2 ;;
    --keep-images)  KEEP_IMAGES=1; shift ;;
    --cache)        CLEAN_CACHE=1; shift ;;
    -y|--yes)       ASSUME_YES=1; shift ;;
    -d|--dry-run)   DRY_RUN=1; shift ;;
    -h|--help)      usage; exit 0 ;;
    --)             shift; break ;;
    -*)             usage >&2; fail "opción desconocida: $1" ;;
    *)              usage >&2; fail "argumento inesperado: $1" ;;
  esac
done
if (( $# > 0 )); then usage >&2; fail "argumento inesperado: $1"; fi

TOTAL_STEPS=7
NAMESPACE="latiendita"
CONTEXT_NAME="kind-${CLUSTER_NAME}"
NODE_PREFIX="${CLUSTER_NAME}-"

IMAGES_POLICY="latiendita/*:$TAG"
if (( KEEP_IMAGES == 1 )); then IMAGES_POLICY="conservadas (--keep-images)"; fi
CACHE_POLICY="conservada"
if (( CLEAN_CACHE == 1 )); then CACHE_POLICY="se limpiará (--cache)"; fi

header "La Tiendita · limpieza del entorno local (kind)" \
  "clúster    : $CLUSTER_NAME" \
  "contexto   : $CONTEXT_NAME" \
  "tag        : $TAG" \
  "imágenes   : $IMAGES_POLICY" \
  "build cache: $CACHE_POLICY" \
  "modo       : $( (( DRY_RUN == 1 )) && printf 'dry-run (no borra nada)' || printf 'limpieza real' )"

# --- 1. Preflight ------------------------------------------------------------
step "Preflight"
assert_tool docker "Instalar Docker Desktop y dejarlo corriendo antes de continuar."
assert_tool kind "Instalar kind (winget install Kubernetes.kind / kind sigs.k8s.io)."

run_tool docker info --format '{{.ServerVersion}}'
if (( TOOL_RC != 0 )); then
  fail "El daemon de Docker no está corriendo." 2 "Iniciar Docker Desktop y reintentar."
fi
DAEMON_VERSION="${TOOL_OUT//$'\n'/}"
note "docker  $DAEMON_VERSION"

HAVE_KUBECTL=0
if command -v kubectl >/dev/null 2>&1; then
  HAVE_KUBECTL=1
  note "kubectl presente (se usa para limpiar el contexto de kubeconfig)"
else
  warn "kubectl ausente: el contexto '$CONTEXT_NAME' no se podrá limpiar desde acá"
fi
done_step "herramientas listas"

# --- 2. Inventario -----------------------------------------------------------
step "Inventario (qué se va a borrar)"
run_tool kind get clusters
CLUSTER_EXISTS=0
while IFS= read -r c; do
  if [[ "$c" == "$CLUSTER_NAME" ]]; then CLUSTER_EXISTS=1; break; fi
done <<< "$TOOL_OUT"

CONTEXT_EXISTS=0
if (( HAVE_KUBECTL == 1 )); then
  run_tool kubectl config get-contexts -o name
  while IFS= read -r ctx; do
    if [[ "$ctx" == "$CONTEXT_NAME" ]]; then CONTEXT_EXISTS=1; break; fi
  done <<< "$TOOL_OUT"
fi

NODE_CONTAINERS=()
run_tool docker ps -a --filter "name=${NODE_PREFIX}" --format '{{.Names}}'
while IFS= read -r n; do
  if [[ -n "$n" ]]; then NODE_CONTAINERS+=("$n"); fi
done <<< "$TOOL_OUT"

IMAGE_REFS=()
if (( KEEP_IMAGES == 0 )); then
  run_tool docker images --format '{{.Repository}}:{{.Tag}}' 'latiendita/*'
  while IFS= read -r ref; do
    if [[ -n "$ref" && "$ref" == *:"$TAG" ]]; then IMAGE_REFS+=("$ref"); fi
  done <<< "$TOOL_OUT"
fi

# Las imágenes pueden compartir ID (varios tags, un solo disco): se suman por ID
# único para no inflar el total.
IMAGE_BYTES=0
if (( ${#IMAGE_REFS[@]} > 0 )); then
  UNIQUE_IDS="$(for ref in "${IMAGE_REFS[@]}"; do docker image inspect -f '{{.Id}}' "$ref" 2>/dev/null; done | sort -u)"
  while IFS= read -r id; do
    if [[ -z "$id" ]]; then continue; fi
    sz="$(docker image inspect -f '{{.Size}}' "$id" 2>/dev/null || echo 0)"
    IMAGE_BYTES=$(( IMAGE_BYTES + ${sz:-0} ))
  done <<< "$UNIQUE_IDS"
fi

if (( CLUSTER_EXISTS == 1 )); then
  info "$(printf '%-14s %s' 'clúster kind' "$CLUSTER_NAME")"
else
  note "clúster kind  $CLUSTER_NAME (no existe)"
fi
if (( ${#NODE_CONTAINERS[@]} > 0 )); then
  for n in "${NODE_CONTAINERS[@]}"; do info "$(printf '%-14s %s' 'contenedor' "$n")"; done
else
  note "contenedores  ninguno"
fi
if (( CONTEXT_EXISTS == 1 )); then
  info "$(printf '%-14s %s' 'contexto' "$CONTEXT_NAME")"
else
  note "contexto      $CONTEXT_NAME (no existe)"
fi
if (( ${#IMAGE_REFS[@]} > 0 )); then
  for ref in "${IMAGE_REFS[@]}"; do info "$(printf '%-14s %s' 'imagen' "$ref")"; done
  note "tamaño de imágenes: $(fmt_bytes "$IMAGE_BYTES")"
else
  note "imágenes      ninguna latiendita/*:$TAG"
fi

if (( CLUSTER_EXISTS == 0 && CONTEXT_EXISTS == 0 && ${#NODE_CONTAINERS[@]} == 0 && ${#IMAGE_REFS[@]} == 0 )); then
  done_step "nada que limpiar"
  NEXT_STEPS=("scripts/kind-up.sh  para volver a levantar el entorno")
  summary "Entorno local ya limpio" \
    "clúster    : $CLUSTER_NAME (no existía)" \
    "imágenes   : 0 latiendita/*:$TAG" \
    "build cache: $CACHE_POLICY"
  exit 0
fi
done_step "$(printf '%d recurso(s) por limpiar' $(( CLUSTER_EXISTS + CONTEXT_EXISTS + ${#NODE_CONTAINERS[@]} + ${#IMAGE_REFS[@]} )))"

# --- 3. Confirmación ---------------------------------------------------------
step "Confirmación"
printf '\n'
warn "Los volúmenes de las bases (Postgres/Kafka/Azurite) viven DENTRO del nodo de kind."
warn "Borrar el clúster destruye esos datos de forma permanente e irreversible."
printf '\n'

if (( DRY_RUN == 1 )); then
  note "--dry-run: no se borró nada."
  done_step "dry-run, se omite la confirmación"
  NEXT_STEPS=("scripts/kind-down.sh  sin --dry-run para borrar de verdad")
  summary "Dry-run: nada borrado" \
    "clúster    : $CLUSTER_NAME ($( (( CLUSTER_EXISTS == 1 )) && printf 'existente' || printf 'inexistente' ))" \
    "imágenes   : ${#IMAGE_REFS[@]} latiendita/*:$TAG ($(fmt_bytes "$IMAGE_BYTES"))" \
    "build cache: $CACHE_POLICY"
  exit 0
fi

if (( ASSUME_YES == 1 )); then
  note "--yes: se continúa sin pedir confirmación."
elif [[ ! -t 0 ]]; then
  fail "Se requiere confirmación y la entrada no es un TTY." 3 \
    "Usar --yes (CI) o --dry-run (solo inventario)."
else
  printf '  ¿Borrar el entorno local? [s/N] '
  read -r ANSWER || ANSWER=''
  case "$ANSWER" in
    s|S|sí|si|SÍ|SI|y|Y|yes|YES|Yes) ;;
    *) fail "Confirmación rechazada: no se borró nada." 3 ;;
  esac
fi
done_step "confirmado"

# --- 4. Clúster kind ---------------------------------------------------------
step "Borrar clúster kind y su contexto"
if (( CLUSTER_EXISTS == 1 )); then
  note "kind delete cluster --name $CLUSTER_NAME"
  rc=0
  kind delete cluster --name "$CLUSTER_NAME" || rc=$?
  if (( rc != 0 )); then
    fail "No se pudo borrar el clúster '$CLUSTER_NAME'." 4 "Revisar el log de kind arriba."
  fi
  ok "clúster '$CLUSTER_NAME' eliminado"
else
  note "el clúster '$CLUSTER_NAME' no existía: se omite"
fi

if (( ${#NODE_CONTAINERS[@]} > 0 )); then
  for n in "${NODE_CONTAINERS[@]}"; do
    run_tool docker rm -f "$n"
    if (( TOOL_RC != 0 )); then
      fail "No se pudo eliminar el contenedor de nodo '$n'." 4 \
        "docker ps -a --filter name=$n  y revisar a mano."
    fi
    ok "contenedor $n eliminado"
  done
fi

# kind ya borra el contexto al borrar el clúster; acá se limpian los restos de
# un clúster que se mató a mano (kill del contenedor, VM caída, etc.).
if (( HAVE_KUBECTL == 1 )); then
  run_tool kubectl config get-contexts -o name
  HAS_CTX=0
  while IFS= read -r ctx; do
    if [[ "$ctx" == "$CONTEXT_NAME" ]]; then HAS_CTX=1; break; fi
  done <<< "$TOOL_OUT"
  if (( HAS_CTX == 1 )); then
    run_tool kubectl config delete-context "$CONTEXT_NAME"
    if (( TOOL_RC == 0 )); then ok "contexto $CONTEXT_NAME eliminado"; fi
    run_tool kubectl config delete-cluster "$CONTEXT_NAME"
    if (( TOOL_RC == 0 )); then ok "entrada de clúster $CONTEXT_NAME eliminada de kubeconfig"; fi
  else
    note "el contexto $CONTEXT_NAME ya no quedó en kubeconfig"
  fi
fi
done_step "clúster y nodos eliminados"

# --- 5. Imágenes -------------------------------------------------------------
step "Borrar imágenes (latiendita/*:$TAG)"
if (( KEEP_IMAGES == 1 )); then
  note "--keep-images: se conservan las imágenes locales."
  done_step "imágenes conservadas"
elif (( ${#IMAGE_REFS[@]} == 0 )); then
  note "no hay imágenes latiendita/*:$TAG que borrar."
  done_step "nada que borrar"
else
  FAILED_IMAGES=()
  for ref in "${IMAGE_REFS[@]}"; do
    run_tool docker rmi "$ref"
    if (( TOOL_RC != 0 )); then
      FAILED_IMAGES+=("$ref")
      warn "no se pudo eliminar '$ref' (exit code $TOOL_RC)"
      continue
    fi
    ok "$ref"
  done
  if (( ${#FAILED_IMAGES[@]} > 0 )); then
    fail "No se pudieron eliminar ${#FAILED_IMAGES[@]} imagen(es): ${FAILED_IMAGES[*]}" 5 \
      "docker rmi <imagen> a mano, o revisar que ningún contenedor la esté usando."
  fi
  done_step "${#IMAGE_REFS[@]} imagen(es) eliminadas ($(fmt_bytes "$IMAGE_BYTES"))"
fi

# --- 6. Build cache ----------------------------------------------------------
step "Build cache"
if (( CLEAN_CACHE == 1 )); then
  run_tool docker builder prune -f
  if (( TOOL_RC != 0 )); then
    fail "No se pudo limpiar la build cache." 6 "docker builder prune --force a mano."
  fi
  while IFS= read -r line; do
    if [[ -n "${line//[[:space:]]/}" ]]; then note "$line"; fi
  done <<< "$TOOL_OUT"
  done_step "build cache limpiada"
else
  note "--cache no indicado: se conserva la build cache."
  done_step "build cache conservada"
fi

# --- 7. Verificación ---------------------------------------------------------
step "Verificación"
LEFTOVER=()

run_tool kind get clusters
while IFS= read -r c; do
  if [[ "$c" == "$CLUSTER_NAME" ]]; then LEFTOVER+=("clúster kind: $CLUSTER_NAME"); fi
done <<< "$TOOL_OUT"

if (( HAVE_KUBECTL == 1 )); then
  run_tool kubectl config get-contexts -o name
  while IFS= read -r ctx; do
    if [[ "$ctx" == "$CONTEXT_NAME" ]]; then LEFTOVER+=("contexto kubeconfig: $CONTEXT_NAME"); fi
  done <<< "$TOOL_OUT"
fi

if (( KEEP_IMAGES == 0 )); then
  run_tool docker images --format '{{.Repository}}:{{.Tag}}' 'latiendita/*'
  while IFS= read -r ref; do
    if [[ -n "$ref" && "$ref" == *:"$TAG" ]]; then LEFTOVER+=("imagen: $ref"); fi
  done <<< "$TOOL_OUT"
fi

if (( ${#LEFTOVER[@]} > 0 )); then
  for l in "${LEFTOVER[@]}"; do warn "$l"; done
  fail "Quedaron ${#LEFTOVER[@]} recurso(s) tras la limpieza." 7 \
    "kind delete cluster --name $CLUSTER_NAME; kubectl config delete-context $CONTEXT_NAME"
fi
done_step "sin restos"

NEXT_STEPS=(
  "scripts/kind-up.sh     para volver a levantar el entorno"
  "scripts/secrets.sh     si también querés regenerar los secrets locales"
)
summary "Entorno local limpio" \
  "clúster    : $CLUSTER_NAME eliminado" \
  "imágenes   : ${#IMAGE_REFS[@]} latiendita/*:$TAG eliminadas" \
  "espacio    : $(fmt_bytes "$IMAGE_BYTES") liberado en imágenes" \
  "build cache: $CACHE_POLICY"
