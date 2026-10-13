#!/usr/bin/env bash
set -euo pipefail

# deploy.sh - idempotent Kubernetes deploy for La Tiendita
#
# Renders the given manifests (kustomize or plain YAML), validates that every
# secretKeyRef key is present in the secrets file, creates missing namespaces,
# applies, waits for every workload and reports status.
#
# Exit codes (stable contract):
#   1 usage error          2 secrets file missing/sourcing failed
#   3 kubectl apply failed 4 workload(s) not ready within timeout
#   5 secrets file lacks keys required by the manifests
#
# User-facing output is Spanish; these comments stay English for maintainers.

usage() {
  cat <<'EOF'
Uso: scripts/deploy.sh [opciones] <manifest> [<manifest> ...]

  <manifest>  Archivo o directorio. Un directorio con kustomization.yaml se
              renderiza con `kubectl kustomize`; si no, se aplica cada
              *.yaml / *.yml de primer nivel.

Opciones:
  -s, --secrets <archivo>   Archivo .env a sourcear y validar
                            (default: ./secrets.env, o $SECRETS_FILE)
  -n, --namespace <ns>      Garantiza este namespace antes de aplicar
  -t, --timeout <seg>       Espera máxima por workload (default: 300)
  -l, --logs <nombre>       Tail de logs de ese Deployment/StatefulSet al final
      --log-tail <n>        Líneas a mostrar con --logs (default: 100)
  -d, --dry-run             Renderiza y valida, pero no aplica nada
  -h, --help                Esta ayuda

Códigos de salida: 1 uso, 2 secrets, 3 apply, 4 no ready, 5 claves faltantes
EOF
}

# --- Console -----------------------------------------------------------------
if [[ -z "${NO_COLOR:-}" && -t 1 ]]; then
  C_RESET=$'\033[0m'; C_DIM=$'\033[2m'; C_CYAN=$'\033[36m'
  C_GREEN=$'\033[32m'; C_YELLOW=$'\033[33m'; C_RED=$'\033[31m'
else
  C_RESET=''; C_DIM=''; C_CYAN=''; C_GREEN=''; C_YELLOW=''; C_RED=''
fi

SECONDS=0
STEP=0
STEP_T0=0
TOTAL_STEPS=6

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
  STEP_T0=$SECONDS
}

done_step() {
  printf '  %s✓%s %s %s· %ss%s\n' "$C_GREEN" "$C_RESET" "$*" "$C_DIM" "$((SECONDS - STEP_T0))" "$C_RESET"
}

note() { printf '      %s%s%s\n' "$C_DIM" "$*" "$C_RESET"; }
ok()   { printf '    %s✓%s %s\n' "$C_GREEN" "$C_RESET" "$*"; }
warn() { printf '    %s!%s %s\n' "$C_YELLOW" "$C_RESET" "$*" >&2; }

fail() {
  local msg="$1" code="${2:-1}"
  printf '\n  %s✗%s %s\n' "$C_RED" "$C_RESET" "$msg" >&2
  printf '  %sTiempo total: %ss%s\n' "$C_DIM" "$SECONDS" "$C_RESET" >&2
  exit "$code"
}

# --- Options -----------------------------------------------------------------
SECRETS_FILE="${SECRETS_FILE:-./secrets.env}"
NAMESPACE_FLAG=""
TIMEOUT=300
LOGS_TARGET=""
LOG_TAIL=100
DRY_RUN=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    -s|--secrets)   SECRETS_FILE="${2:?valor faltante para $1}"; shift 2 ;;
    -n|--namespace) NAMESPACE_FLAG="${2:?valor faltante para $1}"; shift 2 ;;
    -t|--timeout)   TIMEOUT="${2:?valor faltante para $1}"; shift 2 ;;
    -l|--logs)      LOGS_TARGET="${2:?valor faltante para $1}"; shift 2 ;;
    --log-tail)     LOG_TAIL="${2:?valor faltante para $1}"; shift 2 ;;
    -d|--dry-run)   DRY_RUN=1; shift ;;
    -h|--help)      usage; exit 0 ;;
    --)             shift; break ;;
    -*)             usage >&2; fail "opción desconocida: $1" ;;
    *)              break ;;
  esac
done

[[ $# -gt 0 ]] || { usage >&2; fail "no se indicaron manifests"; }
command -v kubectl >/dev/null 2>&1 || fail "kubectl no está en el PATH" 1
[[ "$TIMEOUT" =~ ^[0-9]+$ ]] || fail "--timeout debe ser un número"
[[ "$LOG_TAIL" =~ ^[0-9]+$ ]] || fail "--log-tail debe ser un número"
[[ "$DRY_RUN" -eq 1 ]] && TOTAL_STEPS=5

CURRENT_CONTEXT="$(kubectl config current-context 2>/dev/null || true)"
header "La Tiendita · deploy Kubernetes$( [[ "$DRY_RUN" -eq 1 ]] && echo ' (dry-run)')" \
  "contexto  : ${CURRENT_CONTEXT:-<desconocido>}" \
  "secrets   : $SECRETS_FILE" \
  "timeout   : ${TIMEOUT}s por workload"

# --- 1. Secrets --------------------------------------------------------------
step "Cargar secrets"
[[ -f "$SECRETS_FILE" ]] || fail "no existe el archivo de secrets: $SECRETS_FILE" 2
set -a
# shellcheck disable=SC1090
. "$SECRETS_FILE" || fail "no se pudo sourcear: $SECRETS_FILE" 2
set +a
done_step "$SECRETS_FILE"

# --- 2. Render manifests -----------------------------------------------------
step "Renderizar manifests"
RENDERED="$(mktemp)"
trap 'rm -f "$RENDERED"' EXIT

for path in "$@"; do
  [[ -e "$path" ]] || fail "no existe el path: $path"
  if [[ -d "$path" && -f "$path/kustomization.yaml" ]]; then
    note "kustomize: $path"
    kubectl kustomize "$path" >>"$RENDERED" || fail "kustomize falló: $path" 3
    printf '\n---\n' >>"$RENDERED"
  elif [[ -d "$path" ]]; then
    note "directorio: $path"
    found=0
    while IFS= read -r f; do
      found=1
      note "  $f"
      printf -- '---\n# Source: %s\n' "$f" >>"$RENDERED"
      cat "$f" >>"$RENDERED"
    done < <(find "$path" -maxdepth 1 -type f \( -name '*.yaml' -o -name '*.yml' \) | sort)
    (( found == 1 )) || fail "sin *.yaml de primer nivel en: $path"
  else
    note "archivo: $path"
    printf -- '---\n# Source: %s\n' "$path" >>"$RENDERED"
    cat "$path" >>"$RENDERED"
  fi
done
[[ -s "$RENDERED" ]] || fail "el render quedó vacío"
RESOURCE_COUNT="$(grep -c '^kind:' "$RENDERED" || true)"
done_step "$RESOURCE_COUNT recursos renderizados"

# --- 3. Validate secret keys required by the manifests -----------------------
step "Validar claves de secrets"
# Every `secretKeyRef.key` in the rendered output must exist in the sourced
# secrets file, otherwise pods would mount an incomplete Secret.
REQUIRED_KEYS="$(awk '
  /secretKeyRef:/            { want = 1; next }
  want && /^[[:space:]]*key:[[:space:]]*/ { print $2; want = 0 }
' "$RENDERED" | sort -u)"

if [[ -n "$REQUIRED_KEYS" ]]; then
  MISSING=""
  EMPTY=""
  while IFS= read -r key; do
    [[ -n "$key" ]] || continue
    if ! declare -p "$key" >/dev/null 2>&1; then
      MISSING="$MISSING $key"
    elif [[ -z "${!key}" ]]; then
      EMPTY="$EMPTY $key"
    fi
  done <<<"$REQUIRED_KEYS"
  if [[ -n "$MISSING" ]]; then
    fail "el archivo '$SECRETS_FILE' no define claves requeridas por los manifests:$MISSING" 5
  fi
  [[ -z "$EMPTY" ]] || warn "claves definidas pero vacías (features opcionales deshabilitadas):$EMPTY"
  done_step "$(tr '\n' ' ' <<<"$REQUIRED_KEYS" | xargs) validadas"
else
  done_step "los manifests no referencian secretKeyRef"
fi

# --- 4. Namespaces -----------------------------------------------------------
step "Namespaces"
WORKLOADS="$(awk '
  function flush() {
    if (kind != "" && name != "" &&
        (kind == "Deployment" || kind == "StatefulSet" || kind == "DaemonSet" || kind == "Job"))
      print tolower(kind) "|" name "|" (ns == "" ? "-" : ns)
    kind = ""; name = ""; ns = ""; in_meta = 0
  }
  /^---/               { flush(); next }
  /^kind:/             { kind = $2; next }
  /^metadata:/         { in_meta = 1; next }
  in_meta && /^  name:/      { name = $2; next }
  in_meta && /^  namespace:/ { ns = $2; next }
  in_meta && /^[A-Za-z]/     { in_meta = 0 }
  END                  { flush() }
' "$RENDERED")"

# Namespaces referenced by manifests: explicit Namespace resources plus every
# metadata.namespace (exactly 2-space indent, so nested pod metadata is skipped).
NEEDED_NS="$(awk '
  /^---/ { if (pend_ns != "") { print pend_ns; pend_ns = "" } kind = ""; next }
  /^kind:/ { kind = $2 }
  kind == "Namespace" && /^  name:/ { pend_ns = $2 }
  /^  namespace:/ { print $2 }
  END { if (pend_ns != "") print pend_ns }
' "$RENDERED" | sort -u)"
[[ -n "$NAMESPACE_FLAG" ]] && NEEDED_NS="$(printf '%s\n%s\n' "$NEEDED_NS" "$NAMESPACE_FLAG" | sort -u)"

if [[ -n "$NEEDED_NS" ]]; then
  while IFS= read -r ns; do
    [[ -n "$ns" ]] || continue
    if [[ "$DRY_RUN" -eq 1 ]]; then
      note "dry-run: se usaría el namespace '$ns'"
      continue
    fi
    note "garantizando namespace '$ns'"
    kubectl create namespace "$ns" --dry-run=client -o yaml | kubectl apply -f - >/dev/null \
      || fail "no se pudo crear el namespace: $ns" 3
  done <<<"$NEEDED_NS"
else
  note "los manifests no referencian namespaces explícitos"
fi

STATUS_NS="${NAMESPACE_FLAG:-$(awk -F'|' '$3 != "-" { print $3; exit }' <<<"$WORKLOADS")}"
STATUS_NS="${STATUS_NS:-$(head -n1 <<<"$NEEDED_NS")}"
STATUS_NS="${STATUS_NS:-default}"
done_step "namespace de estado: $STATUS_NS"

# --- 5. Apply ----------------------------------------------------------------
if [[ "$DRY_RUN" -eq 1 ]]; then
  step "Dry-run de aplicación"
  DRY_OUT="$(kubectl apply --dry-run=server -f "$RENDERED" 2>&1)" || {
    printf '%s\n' "$DRY_OUT" >&2
    fail "el dry-run del server falló" 3
  }
  CHANGED="$(printf '%s\n' "$DRY_OUT" | grep -vc 'unchanged' || true)"
  UNCHANGED="$(printf '%s\n' "$DRY_OUT" | grep -c 'unchanged' || true)"
  printf '%s\n' "$DRY_OUT" | grep -v 'unchanged' | sed 's/ *(server dry run)//;s/[[:space:]]*$//;s/^/    /' || true
  done_step "$CHANGED cambiarían, $UNCHANGED sin cambios; no se aplicó nada"
else
  step "Aplicar manifests"
  APPLY_OUT="$(kubectl apply -f "$RENDERED" 2>&1)" || {
    printf '%s\n' "$APPLY_OUT" >&2
    fail "kubectl apply falló" 3
  }
  printf '%s\n' "$APPLY_OUT" | sed 's/^/    /'
  APPLIED="$(printf '%s\n' "$APPLY_OUT" | grep -c 'created\|configured\|unchanged' || true)"
  done_step "$APPLIED recursos aplicados"
fi

# --- 6. Wait for workloads ---------------------------------------------------
WORKLOAD_COUNT="$(grep -c '|' <<<"$WORKLOADS" || true)"
if [[ "$DRY_RUN" -eq 1 ]]; then
  if [[ -n "$WORKLOADS" ]]; then
    DEP="$(grep -c '^deployment|' <<<"$WORKLOADS" || true)"
    STS="$(grep -c '^statefulset|' <<<"$WORKLOADS" || true)"
    DS="$(grep -c '^daemonset|' <<<"$WORKLOADS" || true)"
    JOBS="$(grep -c '^job|' <<<"$WORKLOADS" || true)"
    note "$WORKLOAD_COUNT workloads detectados (ns $STATUS_NS): $DEP deployments, $STS statefulsets, $DS daemonsets, $JOBS jobs"
  else
    note "sin workloads en los manifests"
  fi
else
  step "Esperar workloads (hasta ${TIMEOUT}s)"
  if [[ -n "$WORKLOADS" ]]; then
    DEADLINE=$((SECONDS + TIMEOUT))
    NOT_READY=0
    while IFS='|' read -r kind name ns; do
      [[ -n "$name" ]] || continue
      [[ "$ns" == "-" ]] && ns="$STATUS_NS"
      REMAIN=$((DEADLINE - SECONDS))
      (( REMAIN > 0 )) || REMAIN=1
      if ROLLOUT_OUT="$(kubectl rollout status "$kind/$name" -n "$ns" --timeout="${REMAIN}s" 2>&1)"; then
        ok "$(printf '%-28s %s' "$kind/$name" "ns $ns")"
      else
        printf '    %s✗%s %s/%s (namespace %s)\n' "$C_RED" "$C_RESET" "$kind" "$name" "$ns" >&2
        printf '%s\n' "$ROLLOUT_OUT" | sed 's/^/      /' >&2
        printf '      %seventos recientes:%s\n' "$C_DIM" "$C_RESET" >&2
        kubectl get events -n "$ns" \
          --field-selector "involvedObject.name=$name" \
          --sort-by=.lastTimestamp 2>/dev/null | tail -n 5 | sed 's/^/      /' >&2 || true
        NOT_READY=$((NOT_READY + 1))
      fi
    done <<<"$WORKLOADS"
    (( NOT_READY == 0 )) || fail "$NOT_READY workload(s) no quedaron listos dentro de ${TIMEOUT}s" 4
    done_step "$WORKLOAD_COUNT/$WORKLOAD_COUNT workloads listos"
  else
    done_step "sin Deployments/StatefulSets/DaemonSets/Jobs: nada que esperar"
  fi
fi

# --- 7. Status tables --------------------------------------------------------
if [[ "$DRY_RUN" -eq 0 ]]; then
  printf '\n%s[informe] Estado en el namespace %s%s\n' "$C_CYAN" "$STATUS_NS" "$C_RESET"
  printf '\n  %sworkloads%s\n' "$C_DIM" "$C_RESET"
  kubectl get deploy,statefulset,daemonset -n "$STATUS_NS" -o wide 2>/dev/null | sed 's/^/  /' || true
  printf '\n  %sservices%s\n' "$C_DIM" "$C_RESET"
  kubectl get svc -n "$STATUS_NS" 2>/dev/null | sed 's/^/  /' || true
  printf '\n  %spods%s\n' "$C_DIM" "$C_RESET"
  kubectl get pods -n "$STATUS_NS" 2>/dev/null | sed 's/^/  /' || true

  # --- 8. Service access URLs ------------------------------------------------
  printf '\n  %sURLs de acceso%s\n' "$C_DIM" "$C_RESET"
  NODE_IP="$(kubectl get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}' 2>/dev/null || true)"
  kubectl get svc -n "$STATUS_NS" -o jsonpath='{range .items[*]}{.metadata.name}{"|"}{.spec.type}{"|"}{.spec.ports[0].port}{"|"}{.spec.ports[0].nodePort}{"\n"}{end}' |
  while IFS='|' read -r name type port nodeport; do
    [[ -n "$name" ]] || continue
    case "$type" in
      NodePort)
        printf '    %-22s NodePort    http://%s:%s  (service port %s)\n' \
          "$name" "${NODE_IP:-<node-ip>}" "${nodeport:-?}" "$port" ;;
      LoadBalancer)
        printf '    %-22s LoadBalancer service port %s (external IP pendiente)\n' "$name" "$port" ;;
      *)
        printf '    %-22s ClusterIP   http://%s.%s.svc.cluster.local:%s\n' \
          "$name" "$name" "$STATUS_NS" "$port" ;;
    esac
  done

  # --- 9. Optional logs ------------------------------------------------------
  if [[ -n "$LOGS_TARGET" ]]; then
    printf '\n  %slogs: %s (últimas %s líneas)%s\n' "$C_DIM" "$LOGS_TARGET" "$LOG_TAIL" "$C_RESET"
    if ! kubectl logs "deploy/$LOGS_TARGET" -n "$STATUS_NS" --tail="$LOG_TAIL" --timestamps 2>/dev/null; then
      kubectl logs "statefulset/$LOGS_TARGET" -n "$STATUS_NS" --tail="$LOG_TAIL" --timestamps \
        || fail "no se pudieron obtener logs de '$LOGS_TARGET'" 1
    fi
  fi
fi

# --- 10. Summary -------------------------------------------------------------
printf '\n%s------------------------------------------------------------------------------%s\n' "$C_DIM" "$C_RESET"
if [[ "$DRY_RUN" -eq 1 ]]; then
  printf '  %s✓%s Dry-run validado · %ss\n' "$C_GREEN" "$C_RESET" "$SECONDS"
else
  printf '  %s✓%s Deploy finalizado · %ss · namespace %s\n' "$C_GREEN" "$C_RESET" "$SECONDS" "$STATUS_NS"
fi
printf '%s------------------------------------------------------------------------------%s\n\n' "$C_DIM" "$C_RESET"
