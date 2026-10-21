#!/usr/bin/env bash
set -euo pipefail

# build-images.sh - build de imágenes Docker de La Tiendita.
#
# Par nativo de scripts/build-images.ps1: mismos objetivos, mismas opciones,
# mismos pasos y mismo contrato de códigos de salida.
#
# Contrato de códigos de salida (idéntico al de build-images.ps1):
#   1  uso incorrecta / opción o servicio desconocido / repo sin services/ ni frontend/
#   2  docker ausente o daemon de Docker caído
#   3  falló el build de al menos un servicio
#
# Autocontenido: no sourcea librerías ni invoca a otros scripts del repo.
# Correr desde la raíz del repo.

ALL_SERVICES=(auth-service user-service product-service order-service payment-service gateway frontend)

# Contexto de build por servicio: los microservicios viven en services/<nombre>
# y el frontend en frontend/ (raíz del repo).
build_context() {
  if [[ "$1" == "frontend" ]]; then printf '%s' './frontend'; else printf '%s' "./services/$1"; fi
}

usage() {
  cat <<'EOF'
Uso: scripts/build-images.sh [opciones]

Construye "latiendita/<servicio>:<tag>" para cada servicio: los microservicios
viven en services/ y el frontend en frontend/.
Muestra el avance por servicio (tiempo y tamaño) y cierra con un resumen.
Si algún build falla, continúa con el resto y termina con error al final.

Opciones:
  -t, --tag <tag>          Tag de las imágenes (default: dev)
  -s, --service <nombre>   Construye solo este servicio (se puede repetir)
      --no-cache           Fuerza el build sin caché de capas
      --pull               Fuerza la actualización de la imagen base
  -h, --help               Esta ayuda

Servicios válidos: auth-service, user-service, product-service,
                   order-service, payment-service, gateway, frontend

Códigos de salida: 1 uso · 2 docker no disponible · 3 build fallido
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
TAG="dev"
SERVICES=()
NO_CACHE=0
PULL=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    -t|--tag)     TAG="${2:?valor faltante para $1}"; shift 2 ;;
    -s|--service) SERVICES+=("${2:?valor faltante para $1}"); shift 2 ;;
    --no-cache)   NO_CACHE=1; shift ;;
    --pull)       PULL=1; shift ;;
    -h|--help)    usage; exit 0 ;;
    --)           shift; break ;;
    -*)           usage >&2; fail "opción desconocida: $1" ;;
    *)            usage >&2; fail "argumento inesperado: $1" ;;
  esac
done
if (( $# > 0 )); then usage >&2; fail "argumento inesperado: $1"; fi

[[ -d services && -d frontend ]] || fail "no se encontró el directorio services/ ni frontend/: correr desde la raíz del repo." 1

TOTAL_STEPS=3
header "La Tiendita · build de imágenes" \
  "tag        : $TAG" \
  "servicios  : services/ + frontend/"

# --- 1. Preflight ------------------------------------------------------------
step "Preflight"
assert_tool docker "Instalar Docker Desktop y dejarlo corriendo antes de continuar."

run_tool docker info --format '{{.ServerVersion}}'
if (( TOOL_RC != 0 )); then
  fail "El daemon de Docker no está corriendo." 2 "Iniciar Docker Desktop y reintentar."
fi
DAEMON_VERSION="$TOOL_OUT"
run_tool docker version --format '{{.Client.Version}}'
CLIENT_VERSION="$TOOL_OUT"
done_step "Docker ${DAEMON_VERSION//$'\n'/} (cliente ${CLIENT_VERSION//$'\n'/})"

# --- 2. Selección de servicios ----------------------------------------------
step "Selección de servicios"
if (( ${#SERVICES[@]} > 0 )); then SELECTED=("${SERVICES[@]}"); else SELECTED=("${ALL_SERVICES[@]}"); fi

UNKNOWN=()
for s in "${SELECTED[@]}"; do
  found=0
  for a in "${ALL_SERVICES[@]}"; do
    if [[ "$a" == "$s" ]]; then found=1; break; fi
  done
  if (( found == 0 )); then UNKNOWN+=("$s"); fi
done
if (( ${#UNKNOWN[@]} > 0 )); then
  fail "Servicio(s) desconocido(s): ${UNKNOWN[*]}" 1 "Válidos: ${ALL_SERVICES[*]}"
fi

# Orden fijo de construcción, independientemente del orden de entrada (y sin
# repetidos), igual que el filtro de build-images.ps1.
ORDERED=()
for a in "${ALL_SERVICES[@]}"; do
  for s in "${SELECTED[@]}"; do
    if [[ "$s" == "$a" ]]; then ORDERED+=("$a"); break; fi
  done
done
SELECTED=("${ORDERED[@]}")
if (( ${#SELECTED[@]} == 0 )); then
  fail "No hay servicios para construir." 1 "Revisar --service."
fi
note "${#SELECTED[@]} de ${#ALL_SERVICES[@]} servicio(s): ${SELECTED[*]}"
done_step "${#SELECTED[@]} imagen(es) a construir"

# --- 3. Build ----------------------------------------------------------------
step "Build de imágenes"
RESULT_IMAGES=()
RESULT_BYTES=()
RESULT_MS=()
BUILT_SERVICES=()
FAILED=()

for s in "${SELECTED[@]}"; do
  IMAGE="latiendita/$s:$TAG"
  BUILD_ARGS=(build -t "$IMAGE")
  if (( NO_CACHE == 1 )); then BUILD_ARGS+=(--no-cache); fi
  if (( PULL == 1 )); then BUILD_ARGS+=(--pull); fi
  BUILD_ARGS+=("$(build_context "$s")")

  printf '\n'
  info "$IMAGE"

  start_ms=$(now_ms)
  rc=0
  docker "${BUILD_ARGS[@]}" || rc=$?
  took=$(( $(now_ms) - start_ms ))

  if (( rc != 0 )); then
    FAILED+=("$s")
    warn "build falló para '$s' (exit code $rc)"
    continue
  fi

  bytes=0
  run_tool docker image inspect -f '{{.Size}}' "$IMAGE"
  if (( TOOL_RC == 0 )) && [[ "$TOOL_OUT" =~ ^[0-9]+$ ]]; then
    bytes="$TOOL_OUT"
  fi

  RESULT_IMAGES+=("$IMAGE")
  RESULT_BYTES+=("$bytes")
  RESULT_MS+=("$took")
  BUILT_SERVICES+=("$s")
  ok "$IMAGE · $(fmt_bytes "$bytes") · $(fmt_duration "$took")"
done

printf '\n'
if (( ${#RESULT_IMAGES[@]} > 0 )); then
  printf '      %-28s %10s %10s\n' "Imagen" "Tamaño" "Tiempo"
  printf '      %s %s %s\n' "$(rep '-' 28)" "$(rep '-' 10)" "$(rep '-' 10)"
  for i in "${!RESULT_IMAGES[@]}"; do
    printf '      %-28s %10s %10s\n' "${RESULT_IMAGES[$i]}" \
      "$(fmt_bytes "${RESULT_BYTES[$i]}")" "$(fmt_duration "${RESULT_MS[$i]}")"
  done
fi

if (( ${#FAILED[@]} > 0 )); then
  fail "Falló el build de ${#FAILED[@]} servicio(s): ${FAILED[*]}" 3 \
    "Revisar el log de cada build arriba. --no-cache puede ayudar si la caché está corrupta."
fi

TOTAL_BYTES=0
for b in "${RESULT_BYTES[@]}"; do TOTAL_BYTES=$((TOTAL_BYTES + b)); done

summary "Build finalizado" \
  "imágenes   : ${#RESULT_IMAGES[@]} con tag $TAG" \
  "tamaño     : $(fmt_bytes "$TOTAL_BYTES")" \
  "servicios  : ${BUILT_SERVICES[*]}"
