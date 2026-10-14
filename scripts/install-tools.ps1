<#
.SYNOPSIS
    Verifica (e instala) las herramientas necesarias para el repo.

.DESCRIPTION
    Par nativo de scripts/install-tools.sh: mismas herramientas, mismos pasos
    y mismo contrato de códigos de salida.

      obligatorias  docker, kubectl, kind
      recomendada   git (si falta solo advierte, no cambia el exit code)

    Por defecto instala lo que falte; con -Check solo verifica.
    Detecta el SO (Windows, Linux o macOS) y el gestor de paquetes: winget en
    Windows, brew en macOS, apt/dnf/yum/pacman/zypper en Linux. kubectl y kind
    se instalan como binario oficial cuando el SO no ofrece gestor.

.PARAMETER Check
    Solo verifica; no instala nada.

.PARAMETER Help
    Muestra esta ayuda.

.EXAMPLE
    ./install-tools.ps1

.EXAMPLE
    ./install-tools.ps1 -Check

.NOTES
    Script autocontenido: no dot-sourcea librerías ni invoca a otros scripts
    del repo.

    Códigos de salida (idénticos a install-tools.sh):
      1 uso incorrecta / opción desconocida
      2 falta una herramienta obligatoria (o su instalación falló)
#>
[CmdletBinding()]
param(
    [switch]$Check,
    [Alias('h')][switch]$Help
)

$ErrorActionPreference = 'Continue'

# --- console (bloque espejo) --------------------------------------------------
# Helpers embebidos: este bloque es idéntico en todos los .ps1 de scripts/ que lo declaran.
# Sin dependencias externas; si cambia acá, cambia en los demás.
$script:UseColor = -not [bool]$env:NO_COLOR
if ($env:TERM -eq 'dumb') { $script:UseColor = $false }

# Sin esto, PowerShell 5.1 escribe la salida redirigida con la codepage de la
# consola (OEM) y los símbolos/acentos quedan como '?' en pipes y logs de CI.
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }
try { [Console]::ErrorEncoding = [System.Text.Encoding]::UTF8 } catch { }

$script:Rule = [string]::new([char]0x2500, 72)
$script:StepWatch = $null
$script:ScriptWatch = [System.Diagnostics.Stopwatch]::StartNew()
$script:WarnCount = 0

function Write-Line {
    param([string]$Text = '', $Color)
    if ($script:UseColor -and $Color) { Write-Host $Text -ForegroundColor $Color }
    else { Write-Host $Text }
}

function Write-Header {
    param([string]$Title, [string[]]$Meta = @())
    Write-Host ''
    Write-Line $script:Rule DarkGray
    Write-Line "  $Title"
    foreach ($m in $Meta) { Write-Line "  $m" DarkGray }
    Write-Line $script:Rule DarkGray
}

function Start-Step {
    param([int]$Number, [int]$Total, [string]$Title)
    Write-Host ''
    Write-Line ('  [{0}/{1}] {2}' -f $Number, $Total, $Title) Cyan
    $script:StepWatch = [System.Diagnostics.Stopwatch]::StartNew()
}

function Complete-Step {
    param([string]$Summary = '')
    if ($null -ne $script:StepWatch) { $script:StepWatch.Stop() }
    $took = ''
    if ($null -ne $script:StepWatch) { $took = Format-Duration $script:StepWatch.Elapsed.TotalSeconds }
    $line = '  ✓'
    if ($Summary) { $line += " $Summary" }
    if ($took) { $line += (' ' * [Math]::Max(2, 52 - $line.Length)) + $took }
    Write-Line $line Green
}

function Write-Ok   { param([string]$Text = '') Write-Line "    ✓ $Text" Green }
function Write-Warn { param([string]$Text = '') $script:WarnCount++; Write-Line "    ! $Text" Yellow }
function Write-Note { param([string]$Text = '') Write-Line "      $Text" DarkGray }
function Write-Info { param([string]$Text = '') Write-Line "    $Text" }

function Format-Duration {
    param([double]$Seconds)
    if ($Seconds -lt 1)  { return ('{0:N0} ms' -f ($Seconds * 1000)) }
    if ($Seconds -lt 60) { return ('{0:N1} s' -f $Seconds) }
    $ts = [TimeSpan]::FromSeconds($Seconds)
    return ('{0}m {1:00}s' -f [int][Math]::Floor($ts.TotalMinutes), $ts.Seconds)
}

function Format-Bytes {
    param([long]$Bytes)
    if ($Bytes -ge 1GB) { return ('{0:N2} GB' -f ($Bytes / 1GB)) }
    if ($Bytes -ge 1MB) { return ('{0:N0} MB' -f ($Bytes / 1MB)) }
    if ($Bytes -ge 1KB) { return ('{0:N0} KB' -f ($Bytes / 1KB)) }
    return "$Bytes B"
}

function Test-Command {
    param([string]$Name)
    return [bool](Get-Command $Name -ErrorAction SilentlyContinue)
}

function Assert-Tool {
    param([string]$Name, [string]$Hint, [int]$ExitCode = 2)
    if (Test-Command $Name) { return }
    if (-not $Hint) { $Hint = "Instalar '$Name' y reintentar." }
    Stop-Script -Message "Falta la herramienta '$Name'." -Hint $Hint -ExitCode $ExitCode
}

function Invoke-Tool {
    param([string]$File, [string[]]$ToolArgs = @())
    $output = & $File @ToolArgs 2>&1 | ForEach-Object { "$_" }
    [pscustomobject]@{
        Text     = ($output -join "`n")
        Lines    = @($output)
        ExitCode = $LASTEXITCODE
    }
}

function Stop-Script {
    param([string]$Message, [string]$Hint, [int]$ExitCode = 1)
    Write-Host ''
    $text = "  ✗ $Message"
    try { [Console]::Error.WriteLine($text) } catch { Write-Host $text -ForegroundColor Red }
    if ($Hint) {
        $h = "         → $Hint"
        try { [Console]::Error.WriteLine($h) } catch { Write-Host $h -ForegroundColor Yellow }
    }
    if ($null -ne $script:ScriptWatch) {
        $script:ScriptWatch.Stop()
        $t = '  Tiempo total: {0}' -f (Format-Duration $script:ScriptWatch.Elapsed.TotalSeconds)
        try { [Console]::Error.WriteLine($t) } catch { Write-Host $t -ForegroundColor DarkGray }
    }
    exit $ExitCode
}

function Write-Summary {
    param([string]$Title = 'Resumen', [string[]]$Rows = @(), [string[]]$Next = @())
    Write-Host ''
    Write-Line $script:Rule DarkGray
    Write-Line "  $Title"
    foreach ($r in $Rows) { Write-Line "    $r" }
    if ($Next.Count -gt 0) {
        Write-Host ''
        Write-Line '  Próximos pasos:' DarkGray
        foreach ($n in $Next) { Write-Line "    $n" Cyan }
    }
    Write-Line $script:Rule DarkGray
    if ($script:WarnCount -gt 0) {
        Write-Line ('  {0} advertencia(s) durante la ejecución' -f $script:WarnCount) Yellow
    }
    if ($null -ne $script:ScriptWatch) {
        $script:ScriptWatch.Stop()
        Write-Line ('  Duración total: {0}' -f (Format-Duration $script:ScriptWatch.Elapsed.TotalSeconds)) DarkGray
    }
    Write-Host ''
}
# --- fin console -------------------------------------------------------------

function Show-Usage {
    $text = @'
Uso: scripts/install-tools.ps1 [opciones]

Verifica (e instala, que es lo que hace por defecto) las herramientas
necesarias para trabajar con el repo.

  obligatorias   docker, kubectl, kind
  recomendada    git (si falta, solo advierte)

Opciones:
  -Check          Solo verifica; no instala nada
  -Help           Esta ayuda

Comportamiento:
  - Detecta el SO (Windows, Linux o macOS) y el gestor de paquetes: winget en
    Windows, brew en macOS, apt/dnf/yum/pacman/zypper en Linux.
  - kubectl y kind se instalan como binario oficial (GitHub/Kubernetes) cuando
    el SO no ofrece un gestor de paquetes.
  - En el paso final verifica que el daemon de Docker responda.

Códigos de salida: 1 uso · 2 falta una herramienta obligatoria
'@
    Write-Host $text
}

# --- Plataforma --------------------------------------------------------------
$OsKind = 'unknown'
$ArchK8s = 'amd64'
$Pkg = ''
$PkgLabel = '<ninguno encontrado>'
$Winget = $null
$Brew = $null

try {
    $platform = [Environment]::OSVersion.Platform
    if ($platform -eq [PlatformID]::Win32NT) { $OsKind = 'windows' }
    elseif (Test-Command 'uname') {
        $u = "$(& uname -s 2>$null | Select-Object -First 1)"
        if ($u -match '^Linux') { $OsKind = 'linux' }
        elseif ($u -match '^Darwin') { $OsKind = 'darwin' }
    }
} catch { }

try {
    $arch = "$([System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture)"
    if ($arch -match 'Arm64|Aarch64') { $ArchK8s = 'arm64' }
} catch {
    if ("$env:PROCESSOR_ARCHITECTURE" -match 'ARM64') { $ArchK8s = 'arm64' }
}

function Detect-Platform {
    if ($script:OsKind -eq 'windows') {
        $script:Winget = (Get-Command 'winget' -ErrorAction SilentlyContinue)
        if (-not $script:Winget -and $env:LOCALAPPDATA) {
            $candidate = Join-Path $env:LOCALAPPDATA 'Microsoft\WindowsApps\winget.exe'
            if (Test-Path $candidate) { $script:Winget = $candidate }
        } elseif ($script:Winget) { $script:Winget = $script:Winget.Source }
        if ($script:Winget) { $script:Pkg = 'winget'; $script:PkgLabel = "winget ($($script:Winget))" }
        else { $script:PkgLabel = '<winget no disponible>' }
    } elseif ($script:OsKind -eq 'darwin') {
        $script:Brew = (Get-Command 'brew' -ErrorAction SilentlyContinue)
        if ($script:Brew) { $script:Brew = $script:Brew.Source; $script:Pkg = 'brew'; $script:PkgLabel = "brew ($($script:Brew))" }
        else { $script:PkgLabel = '<brew no disponible>' }
    } else {
        foreach ($c in @('apt-get', 'dnf', 'yum', 'pacman', 'zypper')) {
            if (Test-Command $c) { $script:Pkg = $c; $script:PkgLabel = $c; break }
        }
    }
}

# Ejecuta como root/sudo (Linux/macOS); en Windows no hace falta.
function Invoke-AsRoot {
    param([string]$File, [string[]]$CmdArgs)
    $isRoot = $false
    if (Test-Command 'id') { $isRoot = ("$(& id -u 2>$null)" -eq '0') }
    if ($isRoot) {
        & $File @CmdArgs
    } elseif (Test-Command 'sudo') {
        Write-Note "sudo $File $($CmdArgs -join ' ')"
        & sudo $File @CmdArgs
    } else {
        Write-Note "se requieren permisos de root para ejecutar: $File $($CmdArgs -join ' ')"
    }
}

function Invoke-Managed {
    param([string]$File, [string[]]$CmdArgs)
    Write-Note "$File $($CmdArgs -join ' ')"
    & $File @CmdArgs
}

function Get-UrlFile {
    param([string]$Url, [string]$Dest)
    try {
        $wc = New-Object System.Net.WebClient
        $wc.Headers.Add('User-Agent', 'install-tools.ps1')
        $wc.DownloadFile($Url, $Dest)
        return $true
    } catch {
        Write-Note "no se pudo descargar: $Url"
        return $false
    }
}

# Binario oficial a /usr/local/bin (solo Linux/macOS; en Windows va winget).
function Install-Static {
    param([string]$Url, [string]$Name)
    $tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("install-tools-" + [System.IO.Path]::GetRandomFileName())
    New-Item -ItemType Directory -Path $tmp -Force | Out-Null
    try {
        $dest = Join-Path $tmp $Name
        if (-not (Get-UrlFile -Url $Url -Dest $dest)) { return }
        if ($script:OsKind -eq 'windows') { return }
        & chmod +x $dest 2>$null
        $target = "/usr/local/bin/$Name"
        & mv -f $dest $target 2>$null
        if (-not (Test-Path $target)) { Invoke-AsRoot 'install' @('-m', '0755', $dest, $target) }
        if (-not (Test-Path $target)) { Write-Note "no se pudo instalar $Name en /usr/local/bin" }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Get-ToolVersion {
    param([string]$Name)
    switch ($Name) {
        'docker'  { $t = Invoke-Tool 'docker' @('--version'); if ($t.Lines.Count -gt 0) { return $t.Lines[0] } }
        'kubectl' {
            $t = Invoke-Tool 'kubectl' @('version', '--client')
            $hit = @($t.Lines | Where-Object { $_ -match 'Client Version' } | Select-Object -First 1)
            if ($hit.Count -gt 0) { return $hit[0] }
            if ($t.Lines.Count -gt 0) { return $t.Lines[0] }
        }
        'kind'    { $t = Invoke-Tool 'kind' @('version'); if ($t.Lines.Count -gt 0) { return $t.Lines[0] } }
        'git'     { $t = Invoke-Tool 'git' @('--version'); if ($t.Lines.Count -gt 0) { return $t.Lines[0] } }
        default   { $t = Invoke-Tool $Name @('--version'); if ($t.Lines.Count -gt 0) { return $t.Lines[0] } }
    }
    return ''
}

function Get-ToolHint {
    param([string]$Name)
    switch ($Name) {
        'docker'  { return 'Ver https://docs.docker.com/get-docker/ (Docker Desktop en Windows/macOS).' }
        'kubectl' { return 'Ver https://kubernetes.io/docs/tasks/tools/' }
        'kind'    { return 'Ver https://kind.sigs.k8s.io/docs/user/quick-start/' }
        'git'     { return 'Ver https://git-scm.com/downloads' }
        default   { return "Instalar '$Name' y reintentar." }
    }
}

# --- Instalación por herramienta ---------------------------------------------
function Install-Docker {
    switch ($script:OsKind) {
        'windows' {
            if (-not $script:Winget) { Write-Note 'winget no está disponible'; return }
            Invoke-Managed $script:Winget @('install', '-e', '--id', 'Docker.DockerDesktop', '--accept-package-agreements', '--accept-source-agreements')
        }
        'darwin' {
            if (-not $script:Brew) { Write-Note 'falta brew: https://brew.sh'; return }
            Invoke-Managed $script:Brew @('install', '--cask', 'docker')
        }
        'linux' {
            switch ($script:Pkg) {
                'apt-get' { Invoke-AsRoot 'env' @('DEBIAN_FRONTEND=noninteractive', 'apt-get', 'update', '-q'); Invoke-AsRoot 'env' @('DEBIAN_FRONTEND=noninteractive', 'apt-get', 'install', '-y', 'docker.io') }
                'dnf'     { Invoke-AsRoot 'dnf' @('install', '-y', 'docker') }
                'yum'     { Invoke-AsRoot 'yum' @('install', '-y', 'docker') }
                'pacman'  { Invoke-AsRoot 'pacman' @('-S', '--noconfirm', '--needed', 'docker') }
                'zypper'  { Invoke-AsRoot 'zypper' @('--non-interactive', 'install', 'docker') }
                default   { Write-Note 'sin gestor de paquetes: ver https://docs.docker.com/engine/install/' }
            }
        }
        default { Write-Note "SO no reconocido ($($script:OsKind))" }
    }
}

function Install-Kubectl {
    switch ($script:OsKind) {
        'windows' {
            if (-not $script:Winget) { Write-Note 'winget no está disponible'; return }
            Invoke-Managed $script:Winget @('install', '-e', '--id', 'Kubernetes.kubectl', '--accept-package-agreements', '--accept-source-agreements')
        }
        'darwin' {
            if ($script:Brew) { Invoke-Managed $script:Brew @('install', 'kubectl'); return }
            Install-KubectlStatic
        }
        'linux' { Install-KubectlStatic }
        default { Write-Note "SO no reconocido ($($script:OsKind))" }
    }
}

function Install-KubectlStatic {
    $ver = ''
    try {
        $wc = New-Object System.Net.WebClient
        $wc.Headers.Add('User-Agent', 'install-tools.ps1')
        $ver = "$($wc.DownloadString('https://dl.k8s.io/release/stable.txt'))".Trim()
    } catch { $ver = '' }
    if (-not $ver) {
        Write-Note 'no se pudo resolver la última versión de kubectl (https://dl.k8s.io/release/stable.txt)'
        return
    }
    Install-Static "https://dl.k8s.io/release/$ver/bin/$($script:OsKind)/$($script:ArchK8s)/kubectl" 'kubectl'
}

function Get-KindTag {
    try {
        $wc = New-Object System.Net.WebClient
        $wc.Headers.Add('User-Agent', 'install-tools.ps1')
        $json = $wc.DownloadString('https://api.github.com/repos/kubernetes-sigs/kind/releases/latest')
        if ($json -match '"tag_name"\s*:\s*"([^"]+)"') { return $Matches[1] }
    } catch { }
    return 'v0.33.0'
}

function Install-Kind {
    switch ($script:OsKind) {
        'windows' {
            if (-not $script:Winget) { Write-Note 'winget no está disponible'; return }
            Invoke-Managed $script:Winget @('install', '-e', '--id', 'Kubernetes.kind', '--accept-package-agreements', '--accept-source-agreements')
        }
        'darwin' {
            if ($script:Brew) { Invoke-Managed $script:Brew @('install', 'kind'); return }
            Install-KindStatic
        }
        'linux' { Install-KindStatic }
        default { Write-Note "SO no reconocido ($($script:OsKind))" }
    }
}

function Install-KindStatic {
    $tag = Get-KindTag
    Install-Static "https://github.com/kubernetes-sigs/kind/releases/download/$tag/kind-$($script:OsKind)-$($script:ArchK8s)" 'kind'
}

function Install-Git {
    switch ($script:OsKind) {
        'windows' {
            if (-not $script:Winget) { Write-Note 'winget no está disponible'; return }
            Invoke-Managed $script:Winget @('install', '-e', '--id', 'Git.Git', '--accept-package-agreements', '--accept-source-agreements')
        }
        'darwin' {
            if ($script:Brew) { Invoke-Managed $script:Brew @('install', 'git'); return }
            Write-Note 'instalar Git para macOS desde https://git-scm.com/downloads'
        }
        'linux' {
            switch ($script:Pkg) {
                'apt-get' { Invoke-AsRoot 'env' @('DEBIAN_FRONTEND=noninteractive', 'apt-get', 'install', '-y', 'git') }
                'dnf'     { Invoke-AsRoot 'dnf' @('install', '-y', 'git') }
                'yum'     { Invoke-AsRoot 'yum' @('install', '-y', 'git') }
                'pacman'  { Invoke-AsRoot 'pacman' @('-S', '--noconfirm', '--needed', 'git') }
                'zypper'  { Invoke-AsRoot 'zypper' @('--non-interactive', 'install', 'git') }
                default   { Write-Note 'sin gestor de paquetes: ver https://git-scm.com/downloads' }
            }
        }
        default { Write-Note "SO no reconocido ($($script:OsKind))" }
    }
}

function Install-Tool {
    param([string]$Name)
    switch ($Name) {
        'docker'  { Install-Docker }
        'kubectl' { Install-Kubectl }
        'kind'    { Install-Kind }
        'git'     { Install-Git }
    }
}

$script:ToolStatus = @()

# ensure_tool <nombre> <requerida>: instala (salvo -Check), reporta y termina
# con código 2 si falta una herramienta obligatoria.
function Ensure-Tool {
    param([string]$Name, [bool]$Required)
    $installed = Test-Command $Name

    if (-not $installed -and -not $Check) {
        Write-Note "instalando $Name ..."
        Install-Tool $Name
        $installed = Test-Command $Name
    }

    if ($installed) {
        $ver = Get-ToolVersion $Name
        if (-not $ver) { $ver = 'instalado' }
        $script:ToolStatus += "$Name|instalado|$ver"
        Complete-Step "$Name · $ver"
        return
    }

    $reqFlag = '0'; if ($Required) { $reqFlag = '1' }
    $script:ToolStatus += "$Name|ausente|$reqFlag"
    if ($Required) {
        Stop-Script -Message "No está instalada la herramienta obligatoria '$Name'." `
                    -Hint (Get-ToolHint $Name) -ExitCode 2
    }
    Write-Warn "$Name no está instalado (recomendado)"
    Complete-Step "$Name ausente (recomendado)"
}

# --- Opciones ----------------------------------------------------------------
if ($Help) { Show-Usage; exit 0 }

Detect-Platform

if ($Check) { $Mode = 'verificación (--check)' } else { $Mode = 'instalar faltantes' }

$OsLabel = "$([Environment]::OSVersion.VersionString) ($ArchK8s)"

Write-Header 'La Tiendita · instalación de herramientas' @(
    "SO         : $OsLabel",
    "gestor     : $PkgLabel",
    "modo       : $Mode"
)

$TotalSteps = 6

# --- 1. Plataforma -----------------------------------------------------------
Start-Step 1 $TotalSteps 'Plataforma'
switch ($OsKind) {
    'windows' { Write-Note 'Windows: instalación con winget (Docker Desktop, kubectl, kind, Git)' }
    'darwin'  { Write-Note 'macOS: instalación con brew (o binarios oficiales si falta brew)' }
    'linux'   { Write-Note 'Linux: paquetes del gestor y binarios oficiales en /usr/local/bin' }
    default   { Write-Note "SO no reconocido ($OsLabel): solo se verificará lo que ya esté instalado" }
}
if ($Check) { Write-Note 'modo verificación: no se instala nada' }
Complete-Step "gestor de paquetes: $PkgLabel"

# --- 2..5. Herramientas ------------------------------------------------------
Start-Step 2 $TotalSteps 'docker (obligatoria)'
Ensure-Tool 'docker' $true

Start-Step 3 $TotalSteps 'kubectl (obligatoria)'
Ensure-Tool 'kubectl' $true

Start-Step 4 $TotalSteps 'kind (obligatoria)'
Ensure-Tool 'kind' $true

Start-Step 5 $TotalSteps 'git (recomendada)'
Ensure-Tool 'git' $false

# --- 6. Verificación ---------------------------------------------------------
Start-Step 6 $TotalSteps 'Verificación'
$MissingOpt = ''
foreach ($row in $script:ToolStatus) {
    $c = $row -split '\|'
    if ($c[1] -eq 'instalado') { Write-Ok ('{0,-8} {1}' -f $c[0], $c[2]) }
    else { $MissingOpt += ' ' + $c[0] }
}

$DaemonOk = $false
if (Test-Command 'docker') {
    $info = Invoke-Tool 'docker' @('info', '--format', '{{.ServerVersion}}')
    if ($info.ExitCode -eq 0) {
        $DaemonOk = $true
        Write-Note ("daemon de Docker activo ({0})" -f $info.Text.Trim())
    } else {
        Write-Warn 'el daemon de Docker no responde: iniciar Docker Desktop / systemctl start docker'
    }
}

# Las obligatorias ya frenaron el script en su propio paso: acá solo quedan
# las recomendadas que falten.
if ($MissingOpt) {
    $DoneMsg = "obligatorias instaladas; falta:$MissingOpt (recomendado)"
} elseif ($DaemonOk) {
    $DoneMsg = 'todas las herramientas instaladas y verificadas'
} else {
    $DoneMsg = 'herramientas instaladas (daemon de Docker apagado)'
}
Complete-Step $DoneMsg

$StatusLine = ''
foreach ($row in $script:ToolStatus) {
    $c = $row -split '\|'
    $StatusLine += ' {0}={1}' -f $c[0], $c[1]
}

Write-Summary 'Instalación verificada' -Rows @(
    'herramientas: docker, kubectl, kind (obligatorias) + git (recomendada)',
    "estado      :$StatusLine",
    "modo        : $Mode"
) -Next @(
    'scripts\kind-up.ps1  (o scripts/kind-up.sh) para levantar el entorno',
    'scripts\deploy.ps1 k8s/overlays/local  (o deploy.sh) para aplicar manifests'
)
