<#
.SYNOPSIS
    Ejecuta scripts/deploy.sh desde PowerShell.

.DESCRIPTION
    Wrapper delgado: no reimplementa el deploy, solo localiza un intérprete
    bash (Git for Windows o WSL) y le pasa los argumentos tal cual. Así hay
    una sola implementación y una sola contract de códigos de salida.

    Sin bash disponible el script falla con instrucciones de instalación.

.PARAMETER Rest
    Todas las opciones y manifests de deploy.sh, sin cambios.
    Ver: scripts/deploy.sh --help

.EXAMPLE
    ./deploy.ps1 --help

.EXAMPLE
    ./deploy.ps1 k8s/overlays/local

.EXAMPLE
    ./deploy.ps1 -s k8s/overlays/local/secrets.env --dry-run k8s/overlays/local
#>
[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Rest
)

$ErrorActionPreference = 'Continue'
. (Join-Path $PSScriptRoot 'lib/console.ps1')

$script:UseColor = $false   # el color lo decide deploy.sh (dueno de la salida)
$DeployScript = Join-Path $PSScriptRoot 'deploy.sh'
if (-not (Test-Path $DeployScript)) {
    Stop-Script -Message "No existe $DeployScript"
}
if ($null -eq $Rest) { $Rest = @() }

# --- Buscar un bash usable ---------------------------------------------------
# System32\bash.exe es el launcher de WSL, no un bash real: se ignora.
$gitBash = @(
    (Join-Path $env:ProgramFiles 'Git\bin\bash.exe'),
    (Join-Path ${env:ProgramFiles(x86)} 'Git\bin\bash.exe'),
    (Join-Path $env:LOCALAPPDATA 'Programs\Git\bin\bash.exe')
) | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1

$wsl = Get-Command 'wsl.exe' -ErrorAction SilentlyContinue
$wslOk = $false
if ($wsl) {
    $null = & wsl.exe -e bash -c 'exit 0' 2>&1
    if ($LASTEXITCODE -eq 0) { $wslOk = $true }
}

if ($gitBash) {
    $shellKind = "Git Bash ($gitBash)"
    $runner = { & $gitBash $DeployScript @Rest }
} elseif ($wslOk) {
    $shellKind = 'WSL'
    # wslpath resuelve /mnt/c/...; los argumentos viajan como argv, sin quoting.
    $wslScript = "$((& wsl.exe wslpath -a "$DeployScript" 2>&1 | Select-Object -First 1))".Trim()
    if (-not $wslScript -or $wslScript -match ' ') {
        Stop-Script -Message 'No se pudo convertir la ruta del script para WSL.' `
                    -Hint 'Correr desde Git Bash o instalar Git for Windows.'
    }
    $runner = { & wsl.exe -e bash $wslScript @Rest }
} else {
    Stop-Script -Message 'No hay un bash disponible para correr deploy.sh.' `
                -Hint 'Instalar Git for Windows (winget install Git.Git) o habilitar WSL (wsl --install).'
}

Write-Note $shellKind
& $runner
exit $LASTEXITCODE
