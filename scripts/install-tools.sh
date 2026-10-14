#!/usr/bin/env bash
set -euo pipefail

# install-tools.sh - verifica (e instala) las herramientas del repo.
#
# Par nativo de scripts/install-tools.ps1: mismas herramientas, mismas opciones,
# mismos pasos y mismo contrato de códigos de salida.
#
# Herramientas:
#   obligatorias  docker, kubectl, kind
#   recomendada   git          (si falta solo advierte, no cambia el exit code)
#
# Por defecto instala lo que falte; con --check solo verifica.
# Contrato de códigos de salida (idéntico al de install-tools.ps1):
#   1  uso incorrecta / opción desconocida
#   2  falta una herramienta obligatoria (o su instalación falló)
#
# Autocontenido: no sourcea librerías ni invoca a otros scripts del repo.

REQUIRED_TOOLS=(docker kubectl kind)
OPTIONAL_TOOLS=(git)

usage() {
  cat <<'EOF'
Uso: scripts/install-tools.sh [opciones]

Verifica (e instala, que es lo que hace por defecto) las herramientas
necesarias para trabajar con el repo.

  obligatorias   docker, kubectl, kind
  recomendada    git (si falta, solo advierte)

Opciones:
      --check      Solo verifica; no instala nada
  -h, --help       Esta ayuda

Comportamiento:
  - Detecta el SO (Linux, macOS o Windows/Git Bash) y el gestor de paquetes:
    winget en Windows, brew en macOS, apt/dnf/yum/pacman/zypper en Linux.
  - kubectl y kind se instalan como binario oficial (GitHub/Kubernetes) cuando
    el SO no ofrece un gestor de paquetes.
  - En el paso final verifica que el daemon de Docker responda.

Códigos de salida: 1 uso · 2 falta una herramienta obligatoria
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

# --- Plataforma --------------------------------------------------------------
UNAME_S="$(uname -s 2>/dev/null || echo unknown)"
UNAME_M="$(uname -m 2>/dev/null || echo unknown)"
case "$UNAME_S" in
  Linux*)              OS_KIND=linux ;;
  Darwin*)             OS_KIND=darwin ;;
  MINGW*|MSYS*|CYGWIN*) OS_KIND=windows ;;
  *)                   OS_KIND=unknown ;;
esac

case "$UNAME_M" in
  x86_64|amd64) ARCH_K8S=amd64 ;;
  aarch64|arm64) ARCH_K8S=arm64 ;;
  *)            ARCH_K8S=amd64 ;;
esac

PKG=""
PKG_LABEL="<ninguno>"
WINGET=""
BREW=""

detect_platform() {
  if [[ "$OS_KIND" == windows ]]; then
    WINGET="$(command -v winget 2>/dev/null || true)"
    if [[ -z "$WINGET" && -n "${LOCALAPPDATA:-}" && -f "$LOCALAPPDATA/Microsoft/WindowsApps/winget.exe" ]]; then
      WINGET="$LOCALAPPDATA/Microsoft/WindowsApps/winget.exe"
    fi
    if [[ -n "$WINGET" ]]; then PKG=winget; PKG_LABEL="winget ($WINGET)"; fi
  elif [[ "$OS_KIND" == darwin ]]; then
    BREW="$(command -v brew 2>/dev/null || true)"
    if [[ -n "$BREW" ]]; then PKG=brew; PKG_LABEL="brew ($BREW)"; fi
  else
    local c
    for c in apt-get dnf yum pacman zypper; do
      if command -v "$c" >/dev/null 2>&1; then PKG="$c"; PKG_LABEL="$c"; break; fi
    done
  fi
  [[ -n "$PKG" ]] || PKG_LABEL="<ninguno encontrado>"
}

# run_as_root <cmd...>: instala con root/sudo si hace falta.
run_as_root() {
  if [[ "$(id -u)" == "0" ]]; then
    "$@"
  elif command -v sudo >/dev/null 2>&1; then
    sudo "$@"
  else
    note "se requieren permisos de root para ejecutar: $*"
    return 1
  fi
}

download() {  # download <url> <destino>
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL --retry 2 "$1" -o "$2"
  elif command -v wget >/dev/null 2>&1; then
    wget -q -O "$2" "$1"
  else
    note "falta curl o wget para descargar $1"
    return 1
  fi
}

# static_install <url> <nombre>: binario a /usr/local/bin (con sudo si hace falta).
static_install() {
  local url="$1" name="$2" tmp
  if ! command -v curl >/dev/null 2>&1 && ! command -v wget >/dev/null 2>&1; then
    note "falta curl o wget para descargar $name"
    return 1
  fi
  tmp="$(mktemp -d)" || return 1
  if ! download "$url" "$tmp/$name"; then
    note "no se pudo descargar: $url"
    rm -rf "$tmp"
    return 1
  fi
  chmod +x "$tmp/$name"
  if [[ -w /usr/local/bin ]]; then
    mv "$tmp/$name" "/usr/local/bin/$name" || { rm -rf "$tmp"; return 1; }
  else
    run_as_root install -m 0755 "$tmp/$name" "/usr/local/bin/$name" || { rm -rf "$tmp"; return 1; }
  fi
  rm -rf "$tmp"
  return 0
}

tool_version() {  # tool_version <nombre> -> primera línea legible (siempre rc 0)
  local out=""
  case "$1" in
    docker)  out="$(docker --version 2>/dev/null | head -n1 || true)" ;;
    kubectl)
      out="$(kubectl version --client 2>/dev/null | grep -m1 'Client Version' || true)"
      if [[ -z "$out" ]]; then
        out="$(kubectl version --client 2>/dev/null | head -n1 || true)"
      fi
      ;;
    kind)    out="$(kind version 2>/dev/null | head -n1 || true)" ;;
    git)     out="$(git --version 2>/dev/null | head -n1 || true)" ;;
    *)       out="$("$1" --version 2>/dev/null | head -n1 || true)" ;;
  esac
  printf '%s' "$out"
  return 0
}

tool_hint() {
  case "$1" in
    docker)  echo "Ver https://docs.docker.com/get-docker/ (Docker Desktop en Windows/macOS)." ;;
    kubectl) echo "Ver https://kubernetes.io/docs/tasks/tools/" ;;
    kind)    echo "Ver https://kind.sigs.k8s.io/docs/user/quick-start/" ;;
    git)     echo "Ver https://git-scm.com/downloads" ;;
    *)       echo "Instalar '$1' y reintentar." ;;
  esac
}

# --- Instalación por herramienta ---------------------------------------------
install_docker() {
  case "$OS_KIND" in
    windows)
      [[ -n "$WINGET" ]] || { note "winget no está disponible"; return 1; }
      "$WINGET" install -e --id Docker.DockerDesktop --accept-package-agreements --accept-source-agreements
      ;;
    darwin)
      [[ -n "$BREW" ]] || { note "falta brew: https://brew.sh"; return 1; }
      brew install --cask docker
      ;;
    linux)
      case "$PKG" in
        apt-get) run_as_root env DEBIAN_FRONTEND=noninteractive apt-get update -q && run_as_root env DEBIAN_FRONTEND=noninteractive apt-get install -y docker.io ;;
        dnf)     run_as_root dnf install -y docker ;;
        yum)     run_as_root yum install -y docker ;;
        pacman)  run_as_root pacman -S --noconfirm --needed docker ;;
        zypper)  run_as_root zypper --non-interactive install docker ;;
        *)       note "sin gestor de paquetes: ver https://docs.docker.com/engine/install/"; return 1 ;;
      esac
      ;;
    *) return 1 ;;
  esac
}

install_kubectl() {
  case "$OS_KIND" in
    windows)
      [[ -n "$WINGET" ]] || { note "winget no está disponible"; return 1; }
      "$WINGET" install -e --id Kubernetes.kubectl --accept-package-agreements --accept-source-agreements
      ;;
    darwin)
      if [[ -n "$BREW" ]]; then brew install kubectl && return 0; fi
      install_kubectl_static
      ;;
    linux)
      install_kubectl_static
      ;;
    *) return 1 ;;
  esac
}

install_kubectl_static() {
  local ver
  ver="$(curl -fsSL https://dl.k8s.io/release/stable.txt 2>/dev/null || true)"
  if [[ -z "$ver" ]]; then
    note "no se pudo resolver la última versión de kubectl (https://dl.k8s.io/release/stable.txt)"
    return 1
  fi
  static_install "https://dl.k8s.io/release/$ver/bin/$OS_KIND/$ARCH_K8S/kubectl" kubectl
}

kind_tag() {
  local tag=""
  if command -v curl >/dev/null 2>&1; then
    tag="$(curl -fsSL https://api.github.com/repos/kubernetes-sigs/kind/releases/latest 2>/dev/null \
      | grep -m1 '"tag_name"' | sed -E 's/.*"tag_name": *"([^"]+)".*/\1/')"
  fi
  [[ "$tag" =~ ^v[0-9]+ ]] || tag="v0.33.0"
  printf '%s' "$tag"
}

install_kind() {
  case "$OS_KIND" in
    windows)
      [[ -n "$WINGET" ]] || { note "winget no está disponible"; return 1; }
      "$WINGET" install -e --id Kubernetes.kind --accept-package-agreements --accept-source-agreements
      ;;
    darwin)
      if [[ -n "$BREW" ]]; then brew install kind && return 0; fi
      install_kind_static
      ;;
    linux)
      install_kind_static
      ;;
    *) return 1 ;;
  esac
}

install_kind_static() {
  local tag
  tag="$(kind_tag)"
  static_install "https://github.com/kubernetes-sigs/kind/releases/download/$tag/kind-$OS_KIND-$ARCH_K8S" kind
}

install_git() {
  case "$OS_KIND" in
    windows)
      [[ -n "$WINGET" ]] || { note "winget no está disponible"; return 1; }
      "$WINGET" install -e --id Git.Git --accept-package-agreements --accept-source-agreements
      ;;
    darwin)
      if [[ -n "$BREW" ]]; then brew install git && return 0; fi
      note "instalar Git para macOS desde https://git-scm.com/downloads"
      return 1
      ;;
    linux)
      case "$PKG" in
        apt-get) run_as_root env DEBIAN_FRONTEND=noninteractive apt-get install -y git ;;
        dnf)     run_as_root dnf install -y git ;;
        yum)     run_as_root yum install -y git ;;
        pacman)  run_as_root pacman -S --noconfirm --needed git ;;
        zypper)  run_as_root zypper --non-interactive install git ;;
        *)       note "sin gestor de paquetes: ver https://git-scm.com/downloads"; return 1 ;;
      esac
      ;;
    *) return 1 ;;
  esac
}

install_tool() {
  case "$1" in
    docker)  install_docker ;;
    kubectl) install_kubectl ;;
    kind)    install_kind ;;
    git)     install_git ;;
    *)       return 1 ;;
  esac
}

# ensure_tool <nombre> <requerida:1|0>: instala (salvo --check), reporta y
# termina con código 2 si falta una herramienta obligatoria.
TOOL_STATUS=()
ensure_tool() {
  local name="$1" required="$2"
  local installed=0 ver=""

  if command -v "$name" >/dev/null 2>&1; then installed=1; fi

  if (( installed == 0 )) && (( CHECK_ONLY == 0 )); then
    note "instalando $name ..."
    install_tool "$name" || true
    if command -v "$name" >/dev/null 2>&1; then installed=1; fi
  fi

  if (( installed == 1 )); then
    ver="$(tool_version "$name")"
    [[ -n "$ver" ]] || ver="instalado"
    TOOL_STATUS+=("$name|instalado|$ver")
    done_step "$name · $ver"
    return 0
  fi

  TOOL_STATUS+=("$name|ausente|$required")
  if (( required == 1 )); then
    fail "No está instalada la herramienta obligatoria '$name'." 2 "$(tool_hint "$name")"
  fi
  warn "$name no está instalado (recomendado)"
  done_step "$name ausente (recomendado)"
}

# --- Opciones ----------------------------------------------------------------
CHECK_ONLY=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --check)   CHECK_ONLY=1; shift ;;
    -h|--help) usage; exit 0 ;;
    --)        shift; break ;;
    -*)        usage >&2; fail "opción desconocida: $1" ;;
    *)         usage >&2; fail "argumento inesperado: $1" ;;
  esac
done
if (( $# > 0 )); then usage >&2; fail "argumento inesperado: $1"; fi

detect_platform

if (( CHECK_ONLY == 1 )); then MODE="verificación (--check)"; else MODE="instalar faltantes"; fi

TOTAL_STEPS=6
header "La Tiendita · instalación de herramientas" \
  "SO         : $UNAME_S ($UNAME_M)" \
  "gestor     : $PKG_LABEL" \
  "modo       : $MODE"

# --- 1. Plataforma -----------------------------------------------------------
step "Plataforma"
case "$OS_KIND" in
  windows) note "Windows: instalación con winget (Docker Desktop, kubectl, kind, Git)" ;;
  darwin)  note "macOS: instalación con brew (o binarios oficiales si falta brew)" ;;
  linux)   note "Linux: paquetes del gestor y binarios oficiales en /usr/local/bin" ;;
  *)       note "SO no reconocido ($UNAME_S): solo se verificará lo que ya esté instalado" ;;
esac
if (( CHECK_ONLY == 1 )); then
  note "modo verificación: no se instala nada"
fi
done_step "gestor de paquetes: $PKG_LABEL"

# --- 2..5. Herramientas ------------------------------------------------------
step "docker (obligatoria)"
ensure_tool docker 1

step "kubectl (obligatoria)"
ensure_tool kubectl 1

step "kind (obligatoria)"
ensure_tool kind 1

step "git (recomendada)"
ensure_tool git 0

# --- 6. Verificación ---------------------------------------------------------
step "Verificación"
MISSING_OPT=""
for row in "${TOOL_STATUS[@]}"; do
  IFS='|' read -r n st v <<< "$row"
  if [[ "$st" == "instalado" ]]; then
    ok "$(printf '%-8s %s' "$n" "$v")"
  else
    MISSING_OPT="$MISSING_OPT $n"
  fi
done

DAEMON_OK=0
if command -v docker >/dev/null 2>&1; then
  run_tool docker info --format '{{.ServerVersion}}'
  if (( TOOL_RC == 0 )); then
    DAEMON_OK=1
    note "daemon de Docker activo (${TOOL_OUT//$'\n'/})"
  else
    warn "el daemon de Docker no responde: iniciar Docker Desktop / systemctl start docker"
  fi
fi

# Las obligatorias ya frenaron el script en su propio paso: acá solo quedan
# las recomendadas que falten.
if [[ -n "$MISSING_OPT" ]]; then
  DONE_STEP_MSG="obligatorias instaladas; falta:$MISSING_OPT (recomendado)"
elif (( DAEMON_OK == 1 )); then
  DONE_STEP_MSG="todas las herramientas instaladas y verificadas"
else
  DONE_STEP_MSG="herramientas instaladas (daemon de Docker apagado)"
fi
done_step "$DONE_STEP_MSG"

STATUS_LINE=""
for row in "${TOOL_STATUS[@]}"; do
  IFS='|' read -r n st v <<< "$row"
  STATUS_LINE="$STATUS_LINE $n=$st"
done

NEXT_STEPS=(
  "scripts/kind-up.sh     (o .\\scripts\\kind-up.ps1) para levantar el entorno"
  "scripts/deploy.sh k8s/apps/environments/local   (o deploy.ps1) para aplicar manifests"
)
summary "Instalación verificada" \
  "herramientas: docker, kubectl, kind (obligatorias) + git (recomendada)" \
  "estado      :$STATUS_LINE" \
  "modo        : $MODE"
