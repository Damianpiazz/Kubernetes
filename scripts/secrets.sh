#!/usr/bin/env bash
set -euo pipefail

# secrets.sh - genera los archivos .env / secrets.env del repo desde sus
# plantillas .example, con valores aleatorios fuertes.
#
# Par nativo de scripts/secrets.ps1: mismos destinos, mismas opciones, mismos
# pasos y mismo contrato de códigos de salida.
#
# Destinos (relativos a la raíz del repo):
#   compose               .env.example                                                  -> .env
#   local                 k8s/apps/environments/local/secrets.env.example               -> .../secrets.env
#   observability-local   k8s/observability/environments/local/secrets.env.example      -> .../secrets.env
#   apps                  k8s/apps/environments/production/secrets.env.example          -> .../secrets.env
#   observability         k8s/observability/environments/production/secrets.env.example -> .../secrets.env
#
# Reglas:
#   - Nunca pisa un destino que ya existe, salvo --force (misma regla que kind-up).
#   - Se parte del .example: comentarios, orden y claves no secretas se conservan;
#     solo se generan valores para claves secretas (JWT_SECRET, *_DB_PASSWORD,
#     *_ADMIN_PASSWORD).
#   - Las claves que la plantilla deja vacías (Mercado Pago) quedan vacías: la
#     feature sigue deshabilitada.
#   - Con --check no se escribe nada: reporta faltantes y valores de plantilla.
#
# Contrato de códigos de salida (idéntico al de secrets.ps1):
#   1  uso incorrecta / opción o target desconocido
#   2  falta una plantilla .example
#   3  no se pudo escribir el destino
#   4  --check encontró destinos faltantes o con valores de plantilla
#
# Autocontenido: no sourcea librerías ni invoca a otros scripts del repo.

usage() {
  cat <<'EOF'
Uso: scripts/secrets.sh [opciones]

Genera los archivos .env / secrets.env del repo desde sus plantillas .example,
con valores aleatorios fuertes para las claves secretas.

Destinos:
  compose               .env.example  -> .env                                     (Docker Compose)
  local                 k8s/apps/environments/local/secrets.env.example           -> secrets.env
  observability-local   k8s/observability/environments/local/secrets.env.example  -> secrets.env
  apps                  k8s/apps/environments/production/secrets.env.example      -> secrets.env
  observability         k8s/observability/environments/production/secrets.env...  -> secrets.env

Opciones:
  -t, --target <id>   Qué destinos generar (por defecto: todo)
                      Valores: todo, compose, local, observability-local, production,
                      apps, observability
      --force         Regenerar también los destinos que ya existen
      --check         Solo verifica; no escribe nada (salta con código 4 si hay
                      faltantes o valores de plantilla)
  -h, --help          Esta ayuda

Comportamiento:
  - Nunca se pisa un archivo que ya existe (salvo --force).
  - Se conservan comentarios y claves no secretas de la plantilla; se generan
    valores solo para JWT_SECRET, *_DB_PASSWORD y *_ADMIN_PASSWORD.
  - Las claves vacías en la plantilla (Mercado Pago) quedan vacías.
  - Los archivos generados son gitignored: no se commitean.

Códigos de salida: 1 uso · 2 falta una plantilla · 3 escritura falló · 4 --check con problemas
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

# --- Destinos ----------------------------------------------------------------
# id|plantilla|destino
TARGETS=(
  'compose|.env.example|.env'
  'local|k8s/apps/environments/local/secrets.env.example|k8s/apps/environments/local/secrets.env'
  'observability-local|k8s/observability/environments/local/secrets.env.example|k8s/observability/environments/local/secrets.env'
  'apps|k8s/apps/environments/production/secrets.env.example|k8s/apps/environments/production/secrets.env'
  'observability|k8s/observability/environments/production/secrets.env.example|k8s/observability/environments/production/secrets.env'
)

target_field() {  # target_field <id> <1=plantilla|2=destino>
  local row id tmpl dest
  for row in "${TARGETS[@]}"; do
    IFS='|' read -r id tmpl dest <<< "$row"
    if [[ "$id" == "$1" ]]; then
      case "$2" in
        1) printf '%s' "$tmpl" ;;
        2) printf '%s' "$dest" ;;
      esac
      return 0
    fi
  done
  return 1
}

# --- Generación --------------------------------------------------------------
# rand_token <bytes>: token base64url sin padding (sin +, / ni =).
# -d '\r\n': en Windows openssl/base64 escriben en modo texto (\r\n) y el \r
# no debe quedar dentro del valor.
rand_token() {
  local b64
  if command -v openssl >/dev/null 2>&1; then
    b64="$(openssl rand -base64 "$1" 2>/dev/null | tr -d '\r\n' || true)"
  fi
  if [[ -z "$b64" ]]; then
    b64="$( (head -c "$1" /dev/urandom | base64 | tr -d '\r\n') || true )"
  fi
  printf '%s' "$(printf '%s' "$b64" | tr '+/' '-_' | tr -d '=')"
}

# rand_alnum <n>: n caracteres [A-Za-z0-9] (sin signos, seguro para JDBC URLs).
rand_alnum() {
  local out=''
  if command -v openssl >/dev/null 2>&1; then
    out="$( (openssl rand -base64 96 2>/dev/null | tr -dc 'A-Za-z0-9' | head -c "$1") || true )"
  fi
  if (( ${#out} < $1 )); then
    out="$( (tr -dc 'A-Za-z0-9' </dev/urandom | head -c "$1") || true )"
  fi
  printf '%s' "$out"
}

# is_secret_key <clave>: las claves con valor generado; el resto se copia tal cual.
is_secret_key() {
  case "$1" in
    JWT_SECRET|*_DB_PASSWORD|*_ADMIN_PASSWORD) return 0 ;;
    *) return 1 ;;
  esac
}

# is_template_value <valor>: marca de plantilla que deploy.* rechaza en producción.
is_template_value() {
  local lc
  lc="$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')"
  case "$lc" in
    *changeme*|*change-me*|*dev-only*) return 0 ;;
    *) return 1 ;;
  esac
}

# generate_file <plantilla> <destino> -> GEN_SECRETS / GEN_EMPTY
GEN_SECRETS=0
GEN_EMPTY=0
generate_file() {
  local tmpl="$1" dest="$2" line key val export_kw out=''
  local secrets=0 empty=0
  while IFS= read -r line || [[ -n "$line" ]]; do
    # Normaliza CRLF: las plantillas pueden tener \r y el valor no debe heredarlo.
    line="${line%$'\r'}"
    if [[ "$line" =~ ^[[:space:]]*(export[[:space:]]+)?([A-Za-z_][A-Za-z0-9_]*)=(.*)$ ]]; then
      export_kw="${BASH_REMATCH[1]}"
      key="${BASH_REMATCH[2]}"
      val="${BASH_REMATCH[3]}"
      if is_secret_key "$key"; then
        case "$key" in
          JWT_SECRET) val="$(rand_token 48)" ;;
          *)          val="$(rand_alnum 24)" ;;
        esac
        secrets=$((secrets + 1))
      elif [[ -z "$val" ]]; then
        empty=$((empty + 1))
      fi
      out+="${export_kw}${key}=${val}"$'\n'
    else
      out+="$line"$'\n'
    fi
  done < "$tmpl"
  printf '%s' "$out" > "$dest" || return 1
  GEN_SECRETS=$secrets
  GEN_EMPTY=$empty
  return 0
}

# verify_file <archivo> -> WEAK_KEYS / EMPTY_KEYS (claves con valor de plantilla
# o vacías; no distingue secretas: reporta todo lo que deploy.* miraría).
WEAK_KEYS=()
EMPTY_KEYS=()
verify_file() {
  local file="$1" line key val
  WEAK_KEYS=()
  EMPTY_KEYS=()
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    if [[ "$line" =~ ^[[:space:]]*(export[[:space:]]+)?([A-Za-z_][A-Za-z0-9_]*)=(.*)$ ]]; then
      key="${BASH_REMATCH[2]}"
      val="${BASH_REMATCH[3]}"
      if [[ -z "$val" ]]; then
        EMPTY_KEYS+=("$key")
      elif is_template_value "$val"; then
        WEAK_KEYS+=("$key")
      fi
    fi
  done < "$file"
}

# --- Opciones ----------------------------------------------------------------
TARGET_SEL="todo"
FORCE=0
CHECK_ONLY=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    -t|--target)
      [[ $# -ge 2 ]] || { usage >&2; fail "--target requiere un valor" 1; }
      TARGET_SEL="$2"; shift 2 ;;
    --target=*) TARGET_SEL="${1#--target=}"; shift ;;
    --force)    FORCE=1; shift ;;
    --check)    CHECK_ONLY=1; shift ;;
    -h|--help)  usage; exit 0 ;;
    --)         shift; break ;;
    -*)         usage >&2; fail "opción desconocida: $1" ;;
    *)          usage >&2; fail "argumento inesperado: $1" ;;
  esac
done
if (( $# > 0 )); then usage >&2; fail "argumento inesperado: $1"; fi

case "$TARGET_SEL" in
  todo)                        SELECTED_IDS=(compose local observability-local apps observability) ;;
  production)                  SELECTED_IDS=(apps observability) ;;
  compose|local|observability-local|apps|observability) SELECTED_IDS=("$TARGET_SEL") ;;
  *) usage >&2; fail "target desconocido: $TARGET_SEL" 1 \
       "targets válidos: todo, compose, local, observability-local, production, apps, observability" ;;
esac

TOTAL_STEPS=3
if (( CHECK_ONLY == 1 )); then
  MODE="verificación (--check)"
  WORK_STEP="Verificación (--check)"
else
  MODE="generar"
  if (( FORCE == 1 )); then MODE="generar (--force)"; fi
  WORK_STEP="Generación"
fi

header "La Tiendita · generación de secretos" \
  "modo       : $MODE" \
  "targets    : ${SELECTED_IDS[*]}"

# --- 1. Plantillas -----------------------------------------------------------
step "Plantillas"
for id in "${SELECTED_IDS[@]}"; do
  tmpl="$(target_field "$id" 1)"
  [[ -f "$tmpl" ]] || fail "No existe la plantilla '$tmpl'." 2 \
    "Correr desde la raíz del repo (falta $tmpl)."
  ok "$tmpl"
done
done_step "${#SELECTED_IDS[@]} plantilla(s) encontrada(s)"

# --- 2. Destinos -------------------------------------------------------------
step "Destinos"
MISSING_COUNT=0
for id in "${SELECTED_IDS[@]}"; do
  dest="$(target_field "$id" 2)"
  if [[ -f "$dest" ]]; then
    ok "$(printf '%-14s %s' "$id" "$dest")"
  else
    MISSING_COUNT=$((MISSING_COUNT + 1))
    info "$(printf '%-14s %s (falta)' "$id" "$dest")"
  fi
done
done_step "$MISSING_COUNT destino(s) sin generar"

# --- 3. Generación / verificación --------------------------------------------
step "$WORK_STEP"
GENERATED=0
OMITTED=0
WEAK_TOTAL=0
CHECK_MISSING=0

for id in "${SELECTED_IDS[@]}"; do
  tmpl="$(target_field "$id" 1)"
  dest="$(target_field "$id" 2)"

  if (( CHECK_ONLY == 1 )); then
    if [[ ! -f "$dest" ]]; then
      CHECK_MISSING=$((CHECK_MISSING + 1))
      warn "$id: falta $dest (generar con: scripts/secrets.sh --target $id)"
      continue
    fi
    verify_file "$dest"
    if (( ${#WEAK_KEYS[@]} > 0 )); then
      WEAK_TOTAL=$((WEAK_TOTAL + ${#WEAK_KEYS[@]}))
      warn "$id: valores de plantilla en $dest → ${WEAK_KEYS[*]}"
    else
      ok "$id: $dest sin valores de plantilla"
    fi
    if (( ${#EMPTY_KEYS[@]} > 0 )); then
      note "$id: claves vacías (features opcionales deshabilitadas): ${EMPTY_KEYS[*]}"
    fi
    continue
  fi

  if [[ -f "$dest" && $FORCE -eq 0 ]]; then
    warn "$id: ya existe $dest, se omite (usar --force para regenerar)"
    OMITTED=$((OMITTED + 1))
    continue
  fi

  generate_file "$tmpl" "$dest" || \
    fail "No se pudo escribir '$dest'." 3 "Verificar permisos del directorio."
  verify_file "$dest"
  if (( ${#WEAK_KEYS[@]} > 0 )); then
    fail "El archivo generado '$dest' quedó con valores de plantilla: ${WEAK_KEYS[*]}" 3
  fi
  GENERATED=$((GENERATED + 1))
  ok "$id: $dest · $GEN_SECRETS secreto(s) generados"
  if (( GEN_EMPTY > 0 )); then
    note "$id: $GEN_EMPTY clave(s) vacía(s) conservadas (features opcionales)"
  fi
done

if (( CHECK_ONLY == 1 )); then
  if (( CHECK_MISSING > 0 || WEAK_TOTAL > 0 )); then
    done_step "$CHECK_MISSING destino(s) faltantes · $WEAK_TOTAL valor(es) de plantilla"
    fail "Hay destinos sin generar o con valores de plantilla." 4 \
      "Generarlos con: scripts/secrets.sh --target todo"
  fi
  done_step "todos los destinos generados y sin valores de plantilla"
else
  done_step "$GENERATED generado(s) · $OMITTED omitido(s)"
fi

NEXT_STEPS=(
  "scripts/kind-up.sh     (o .\\scripts\\kind-up.ps1) para levantar el entorno local"
  "scripts/deploy.sh -s k8s/apps/environments/production/secrets.env k8s/apps/environments/production"
)
summary "Secretos del repo" \
  "modo        : $MODE" \
  "targets     : ${SELECTED_IDS[*]}" \
  "resultados  : $GENERATED generado(s), $OMITTED omitido(s), $CHECK_MISSING faltante(s)" \
  "nota        : los archivos generados son gitignored, no se commitean"
