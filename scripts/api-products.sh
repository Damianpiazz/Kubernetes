#!/usr/bin/env bash
set -euo pipefail

# api-products.sh - consulta el API de productos de La Tiendita a través del
# gateway (solo lectura).
#
# Par nativo de scripts/api-products.ps1: mismas consultas, mismas opciones,
# mismos pasos y mismo contrato de códigos de salida.
#
# Pasos:
#   1. Preflight  GET {base}/actuator/health/readiness
#   2. Productos  GET {base}/api/products?page=&size=&sort=
#   3. Detalle    GET {base}/api/products/{id}
#   4. Categorías GET {base}/api/categories
#
# Los GET de /api/products y /api/categories son públicos en el gateway
# (SecurityConfig del gateway los deja en permitAll), así que no hace falta
# token. Escribir (POST/PUT/DELETE) sí lo requiere: ver
# docs/kubernetes/comandos.md sección 9.
#
# Solo GET: no escribe nada en el clúster ni en la base de datos.
#
# jq es OPCIONAL: si está instalado se usa para formatear el JSON y para tomar
# el primer productId de la página (paso 3); sin jq el cuerpo se imprime plano
# y el paso 3 se omite salvo que se pase --id.
#
# Contrato de códigos de salida (idéntico al de api-products.ps1):
#   1  uso incorrecta / opción inválida
#   2  el gateway no responde
#   3  GET /api/products falló
#   4  GET /api/products/{id} falló
#   5  GET /api/categories falló
#
# Autocontenido: no sourcea librerías ni invoca a otros scripts del repo.

usage() {
  cat <<'EOF'
Uso: scripts/api-products.sh [opciones]

Consulta el API de productos de La Tiendita a través del gateway (solo lectura).

Pasos:
  1. Preflight  GET {base}/actuator/health/readiness
  2. Productos  GET {base}/api/products?page=&size=&sort=
  3. Detalle    GET {base}/api/products/{id}
  4. Categorías GET {base}/api/categories

Opciones:
      --base <url>   URL base del gateway (por defecto: http://localhost:8080)
      --page <n>     Página a consultar, 0-based (por defecto: 0)
      --size <n>     Tamaño de página, 1-500 (por defecto: 10)
      --sort <campo[,asc|desc]>
                     Orden; campos: productId, title, sku, price, quantity, categoryId
      --id <id>      productId del paso 3 (por defecto: el primero de la página)
  -h, --help         Esta ayuda

Comportamiento:
  - Solo GET: no escribe en el clúster ni en la base de datos.
  - Los GET de /api/products y /api/categories son públicos en el gateway.
  - El port-forward debe estar activo:
    kubectl port-forward svc/gateway 8080:80 -n latiendita
  - jq es opcional: sin él el JSON se imprime plano y el paso 3 requiere --id.

Códigos de salida: 1 uso · 2 gateway sin respuesta · 3 products · 4 detalle · 5 categorías
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
BASE='http://localhost:8080'
PAGE=0
SIZE=10
SORT=''
ID=''

while [[ $# -gt 0 ]]; do
  case "$1" in
    --base)
      [[ $# -ge 2 ]] || { usage >&2; fail "--base requiere un valor" 1; }
      BASE="$2"; shift 2 ;;
    --base=*) BASE="${1#--base=}"; shift ;;
    --page)
      [[ $# -ge 2 ]] || { usage >&2; fail "--page requiere un valor" 1; }
      PAGE="$2"; shift 2 ;;
    --page=*) PAGE="${1#--page=}"; shift ;;
    --size)
      [[ $# -ge 2 ]] || { usage >&2; fail "--size requiere un valor" 1; }
      SIZE="$2"; shift 2 ;;
    --size=*) SIZE="${1#--size=}"; shift ;;
    --sort)
      [[ $# -ge 2 ]] || { usage >&2; fail "--sort requiere un valor" 1; }
      SORT="$2"; shift 2 ;;
    --sort=*) SORT="${1#--sort=}"; shift ;;
    --id)
      [[ $# -ge 2 ]] || { usage >&2; fail "--id requiere un valor" 1; }
      ID="$2"; shift 2 ;;
    --id=*) ID="${1#--id=}"; shift ;;
    -h|--help) usage; exit 0 ;;
    --) shift; break ;;
    -*) usage >&2; fail "opción desconocida: $1" ;;
    *)  usage >&2; fail "argumento inesperado: $1" ;;
  esac
done
if (( $# > 0 )); then usage >&2; fail "argumento inesperado: $1"; fi

BASE="${BASE%/}"
if [[ -z "$BASE" || ! "$BASE" =~ ^https?:// ]]; then
  usage >&2
  fail "El --base debe ser una URL http(s) (recibido: '$BASE')." 1
fi
[[ "$PAGE" =~ ^[0-9]+$ ]] || fail "--page debe ser un número mayor o igual a 0 (recibido: '$PAGE')." 1
[[ "$SIZE" =~ ^[0-9]+$ ]] || fail "--size debe ser un número (recibido: '$SIZE')." 1
(( SIZE >= 1 && SIZE <= 500 )) || fail "--size debe estar entre 1 y 500 (recibido: $SIZE)." 1
SORT="${SORT#"${SORT%%[![:space:]]*}"}"
SORT="${SORT%"${SORT##*[![:space:]]}"}"
VALID_SORT='productId title sku price quantity categoryId'
if [[ -n "$SORT" ]]; then
  SORT_FIELD="${SORT%%,*}"
  SORT_OK=0
  for f in $VALID_SORT; do [[ "$f" == "$SORT_FIELD" ]] && SORT_OK=1 && break; done
  if (( SORT_OK == 0 )); then
    usage >&2
    fail "Campo de orden inválido: '$SORT_FIELD'." 1 "campos válidos: ${VALID_SORT// /, }"
  fi
fi

assert_tool curl "Instalar curl (apt install curl / brew install curl) y reintentar."

HAVE_JQ=0
command -v jq >/dev/null 2>&1 && HAVE_JQ=1

TOTAL_STEPS=4
SORT_LABEL='(default del backend)'
[[ -n "$SORT" ]] && SORT_LABEL="$SORT"
DETAIL_LABEL='(auto: primer producto de la página)'
[[ -n "$ID" ]] && DETAIL_LABEL="$ID"

header "La Tiendita · API de productos (solo lectura)" \
  "gateway    : $BASE" \
  "página     : $PAGE · tamaño $SIZE" \
  "orden      : $SORT_LABEL" \
  "detalle    : $DETAIL_LABEL"

# --- HTTP --------------------------------------------------------------------
HTTP_STATUS=0
HTTP_BODY=''
HTTP_MS=0
TOTAL_HTTP_MS=0

http_get() {  # http_get <url> -> HTTP_STATUS / HTTP_BODY / HTTP_MS (+ TOTAL_HTTP_MS)
  local url="$1" tmp t0 t1
  tmp="$(mktemp 2>/dev/null || echo "/tmp/api-products.$$")"
  t0="$(now_ms)"
  HTTP_STATUS="$(curl -sS -o "$tmp" -w '%{http_code}' --max-time 30 "$url" 2>/dev/null)" \
    || HTTP_STATUS="000"
  t1="$(now_ms)"
  HTTP_MS=$(( t1 - t0 ))
  TOTAL_HTTP_MS=$(( TOTAL_HTTP_MS + HTTP_MS ))
  HTTP_BODY="$(cat "$tmp" 2>/dev/null || true)"
  rm -f "$tmp"
}

status_label() {  # status_label  -> 'HTTP 404' o 'sin respuesta'
  if [[ "$HTTP_STATUS" != "000" && -n "$HTTP_STATUS" ]]; then
    printf 'HTTP %s' "$HTTP_STATUS"
  else
    printf 'sin respuesta'
  fi
}

json_field() {  # json_field <json> <filtro jq> -> stdout ('' si no hay jq o no matchea)
  (( HAVE_JQ == 1 )) || return 0
  printf '%s' "$1" | jq -r "$2" 2>/dev/null || true
}

print_body() {  # print_body <body>
  local body="$1" out=''
  if [[ -z "${body//[$' \t\r\n']/}" ]]; then
    note '(sin cuerpo)'
    return 0
  fi
  if (( HAVE_JQ == 1 )); then
    out="$(printf '%s' "$body" | jq . 2>/dev/null || true)"
  fi
  [[ -n "$out" ]] || out="$body"
  while IFS= read -r line; do note "$line"; done <<< "$out"
}

# --- 1. Preflight ------------------------------------------------------------
step "Preflight (gateway)"
http_get "$BASE/actuator/health/readiness"
if [[ "$HTTP_STATUS" != "200" ]]; then
  fail "El gateway no responde en $BASE ($(status_label))." 2 \
    "Levantar el port-forward: kubectl port-forward svc/gateway 8080:80 -n latiendita"
fi
note "GET /actuator/health/readiness  $HTTP_STATUS  $HTTP_MS ms"
print_body "$HTTP_BODY"
done_step "gateway accesible"

# --- 2. Productos ------------------------------------------------------------
step "Productos"
PRODUCTS_URL="$BASE/api/products?page=$PAGE&size=$SIZE"
[[ -n "$SORT" ]] && PRODUCTS_URL="${PRODUCTS_URL}&sort=${SORT}"
http_get "$PRODUCTS_URL"
if [[ "$HTTP_STATUS" != "200" ]]; then
  fail "GET /api/products falló ($(status_label))." 3 \
    "Verificar que product-service esté listo: kubectl get pods -n latiendita"
fi
note "GET $PRODUCTS_URL  $HTTP_STATUS  $HTTP_MS ms"
print_body "$HTTP_BODY"

TOTAL_ELEMENTS="$(json_field "$HTTP_BODY" '.totalElements // empty')"
TOTAL_PAGES="$(json_field "$HTTP_BODY" '.totalPages // empty')"
FIRST_ID="$(json_field "$HTTP_BODY" '.content[0].productId // empty')"
PAGE_COUNT="$(json_field "$HTTP_BODY" '.content | length // empty')"
if [[ -n "$TOTAL_ELEMENTS" ]]; then
  note "totalElements=$TOTAL_ELEMENTS · totalPages=$TOTAL_PAGES · elementos en esta página=$PAGE_COUNT"
elif (( HAVE_JQ == 0 )); then
  note "sin jq: no se pudo leer totalElements (instalar jq para el resumen y el detalle automático)"
fi
done_step "${PAGE_COUNT:-?} elemento(s) en la página"

# --- 3. Detalle ---------------------------------------------------------------
step "Detalle de producto"
DETAIL_ID="$ID"
[[ -n "$DETAIL_ID" ]] || DETAIL_ID="$FIRST_ID"
if [[ -z "$DETAIL_ID" ]]; then
  note "sin id para el detalle (página vacía, respuesta sin productId o sin jq); pasar --id <productId>"
  done_step "omitido"
else
  DETAIL_URL="$BASE/api/products/$DETAIL_ID"
  http_get "$DETAIL_URL"
  if [[ "$HTTP_STATUS" != "200" ]]; then
    fail "GET /api/products/$DETAIL_ID falló ($(status_label))." 4 \
      "Verificar que el id exista (paso 2) o que product-service esté listo."
  fi
  note "GET $DETAIL_URL  $HTTP_STATUS  $HTTP_MS ms"
  print_body "$HTTP_BODY"
  done_step "producto $DETAIL_ID encontrado"
fi

# --- 4. Categorías ------------------------------------------------------------
step "Categorías"
CATEGORIES_URL="$BASE/api/categories"
http_get "$CATEGORIES_URL"
if [[ "$HTTP_STATUS" != "200" ]]; then
  fail "GET /api/categories falló ($(status_label))." 5 \
    "Verificar que product-service esté listo: kubectl get pods -n latiendita"
fi
note "GET $CATEGORIES_URL  $HTTP_STATUS  $HTTP_MS ms"
print_body "$HTTP_BODY"
CATEGORIES_COUNT="$(json_field "$HTTP_BODY" 'length // empty')"
done_step "${CATEGORIES_COUNT:-?} categoría(s)"

# --- Resumen -----------------------------------------------------------------
NEXT_STEPS=(
  "kubectl port-forward svc/product-service 8082:80 -n latiendita   (directo, sin gateway)"
  "docs/kubernetes/comandos.md  sección 6 y 9 (endpoints y escritura con JWT)"
)
summary "Consulta del API de productos" \
  "gateway    : $BASE" \
  "página     : $PAGE · ${TOTAL_ELEMENTS:-no disponible} elemento(s) en total" \
  "peticiones : 4/4 OK · $(fmt_duration $TOTAL_HTTP_MS)"
