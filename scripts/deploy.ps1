<#
.SYNOPSIS
    Deploy idempotente de Kubernetes para La Tiendita (PowerShell).

.DESCRIPTION
    Renderiza los manifests indicados (kustomize o YAML planos), valida que
    cada clave secretKeyRef esté presente en el archivo de secrets, crea los
    namespaces que falten, aplica, espera cada workload y reporta estado.

    Par nativo de scripts/deploy.sh: mismas opciones, misma salida y mismo
    contrato de códigos de salida. No depende de bash ni de ningún otro
    script del repo.

.PARAMETER Rest
    Todas las opciones y manifests de deploy.sh, sin cambios:
      -s|--secrets <archivo>  -n|--namespace <ns>   -t|--timeout <seg>
      -l|--logs <nombre>      --log-tail <n>         -d|--dry-run   -h|--help
    Ver: scripts/deploy.ps1 -h

.EXAMPLE
    ./deploy.ps1 k8s/overlays/local

.EXAMPLE
    ./deploy.ps1 -s k8s/overlays/local/secrets.env --dry-run k8s/overlays/local

.NOTES
    No declara param(): los argumentos se leen crudos de $args para replicar
    exactamente la CLI de deploy.sh (incluidas opciones con guiones dobles).

    Script autocontenido: no dot-sourcea librerías.

    Códigos de salida (idénticos a deploy.sh):
      1 uso incorrecta  2 secrets faltantes/inválidos  3 kubectl apply falló
      4 workload no listo dentro del timeout  5 faltan claves requeridas
#>

# Los comandos nativos se validan por $LASTEXITCODE; EAP Stop rompe el
# streaming de docker/kubectl cuando escriben en stderr.
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

# --- Funciones de deploy -----------------------------------------------------
# Diagnósticos de kubectl van a stderr, como en deploy.sh.
function Write-Diag {
    param([string]$Text, $Color)
    $old = $null
    try { $old = [Console]::ForegroundColor } catch { }
    try {
        if ($script:UseColor -and $Color) { [Console]::ForegroundColor = $Color }
        [Console]::Error.WriteLine($Text)
    } catch {
        try { [Console]::Error.WriteLine($Text) } catch { Write-Host $Text }
    } finally {
        if ($null -ne $old) { try { [Console]::ForegroundColor = $old } catch { } }
    }
}

# Indentación de 2 espacios: tablas de estado e informe final (igual que `note`
# de deploy.sh, que sangra 2 espacios en esas secciones).
function Write-Note2 { param([string]$Text = '') Write-Line "  $Text" DarkGray }

function Show-Usage {
    $text = @'
Uso: scripts/deploy.ps1 [opciones] <manifest> [<manifest> ...]

  <manifest>  Archivo o directorio. Un directorio con kustomization.yaml se
              renderiza con `kubectl kustomize`; si no, se aplica cada
              *.yaml / *.yml de primer nivel.

Opciones:
  -s, --secrets <archivo>   Archivo .env a leer y validar
                            (default: ./secrets.env, o $SECRETS_FILE)
  -n, --namespace <ns>      Garantiza este namespace antes de aplicar
  -t, --timeout <seg>       Espera máxima por workload (default: 300)
  -l, --logs <nombre>       Tail de logs de ese Deployment/StatefulSet al final
      --log-tail <n>        Líneas a mostrar con --logs (default: 100)
  -d, --dry-run             Renderiza y valida, pero no aplica nada
  -h, --help                Esta ayuda

Códigos de salida: 1 uso, 2 secrets, 3 apply, 4 no ready, 5 claves faltantes
'@
    Write-Host $text
}

# Lee un .env (KEY=VALUE, # comentarios, export opcional, comillas simples o
# dobles) en un hashtable. Devuelve $null si el archivo no se pudo leer.
# A diferencia de `set -a; . archivo` no ejecuta shell: solo parsea asignaciones.
function Import-EnvFile {
    param([string]$Path)
    $map = @{}
    try {
        $resolved = (Convert-Path -LiteralPath $Path)
        $lines = [System.IO.File]::ReadAllLines($resolved)
    } catch {
        return $null
    }
    foreach ($raw in $lines) {
        $line = $raw.Trim()
        if (-not $line -or $line.StartsWith('#')) { continue }
        if ($line -match '^(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
            $key = $Matches[1]
            $val = $Matches[2].Trim()
            if ($val.Length -ge 2) {
                $first = $val[0]
                $last = $val[$val.Length - 1]
                if (($first -eq '"' -and $last -eq '"') -or ($first -eq "'" -and $last -eq "'")) {
                    $val = $val.Substring(1, $val.Length - 2)
                }
            }
            $map[$key] = $val
        }
    }
    return $map
}

# --- Opciones ----------------------------------------------------------------
$SecretsFile = './secrets.env'
if ($env:SECRETS_FILE) { $SecretsFile = $env:SECRETS_FILE }
$NamespaceFlag = ''
$Timeout = 300
$LogsTarget = ''
$LogTail = 100
$DryRun = $false
$Manifests = @()

$i = 0
while ($i -lt $args.Count) {
    $a = [string]$args[$i]
    $hasValue = ($i + 1) -lt $args.Count
    if ($a -eq '-s' -or $a -eq '--secrets') {
        if (-not $hasValue) { Show-Usage; Stop-Script -Message "valor faltante para $a" -ExitCode 1 }
        $SecretsFile = [string]$args[$i + 1]; $i += 2
    } elseif ($a -eq '-n' -or $a -eq '--namespace') {
        if (-not $hasValue) { Show-Usage; Stop-Script -Message "valor faltante para $a" -ExitCode 1 }
        $NamespaceFlag = [string]$args[$i + 1]; $i += 2
    } elseif ($a -eq '-t' -or $a -eq '--timeout') {
        if (-not $hasValue) { Show-Usage; Stop-Script -Message "valor faltante para $a" -ExitCode 1 }
        $Timeout = $args[$i + 1]; $i += 2
    } elseif ($a -eq '-l' -or $a -eq '--logs') {
        if (-not $hasValue) { Show-Usage; Stop-Script -Message "valor faltante para $a" -ExitCode 1 }
        $LogsTarget = [string]$args[$i + 1]; $i += 2
    } elseif ($a -eq '--log-tail') {
        if (-not $hasValue) { Show-Usage; Stop-Script -Message "valor faltante para $a" -ExitCode 1 }
        $LogTail = $args[$i + 1]; $i += 2
    } elseif ($a -eq '-d' -or $a -eq '--dry-run') {
        $DryRun = $true; $i++
    } elseif ($a -eq '-h' -or $a -eq '--help') {
        Show-Usage; exit 0
    } elseif ($a -eq '--') {
        $i++; break
    } elseif ($a -like '-*') {
        Show-Usage; Stop-Script -Message "opción desconocida: $a" -ExitCode 1
    } else {
        break
    }
}
if ($i -lt $args.Count) { $Manifests = @($args[$i..($args.Count - 1)]) }

$TimeoutNum = 0
if (-not [int]::TryParse([string]$Timeout, [ref]$TimeoutNum) -or $TimeoutNum -lt 0) {
    Show-Usage
    Stop-Script -Message '--timeout debe ser un número' -ExitCode 1
}
$Timeout = $TimeoutNum
$LogTailNum = 0
if (-not [int]::TryParse([string]$LogTail, [ref]$LogTailNum) -or $LogTailNum -lt 0) {
    Show-Usage
    Stop-Script -Message '--log-tail debe ser un número' -ExitCode 1
}
$LogTail = $LogTailNum

if ($Manifests.Count -eq 0) {
    Show-Usage
    Stop-Script -Message 'no se indicaron manifests' -ExitCode 1
}
if (-not (Test-Command 'kubectl')) {
    Stop-Script -Message 'kubectl no está en el PATH' -ExitCode 1
}

$TotalSteps = 6
if ($DryRun) { $TotalSteps = 5 }

$CurrentContext = ''
$ctxTool = Invoke-Tool 'kubectl' @('config', 'current-context')
if ($ctxTool.ExitCode -eq 0) { $CurrentContext = $ctxTool.Text.Trim() }
if (-not $CurrentContext) { $CurrentContext = '<desconocido>' }

$Title = 'La Tiendita · deploy Kubernetes'
if ($DryRun) { $Title += ' (dry-run)' }
Write-Header $Title @(
    "contexto  : $CurrentContext",
    "secrets   : $SecretsFile",
    "timeout   : ${Timeout}s por workload"
)

$Rendered = [System.IO.Path]::GetTempFileName()
$NsTmp = [System.IO.Path]::GetTempFileName()
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$Workloads = New-Object System.Collections.Generic.List[string]

try {
    # --- 1. Secrets -----------------------------------------------------------
    Start-Step 1 $TotalSteps 'Cargar secrets'
    if (-not (Test-Path -LiteralPath $SecretsFile -PathType Leaf)) {
        Stop-Script -Message "no existe el archivo de secrets: $SecretsFile" -ExitCode 2
    }
    $envMap = Import-EnvFile -Path $SecretsFile
    if ($null -eq $envMap) {
        Stop-Script -Message "no se pudo leer: $SecretsFile" -ExitCode 2
    }
    Complete-Step $SecretsFile

    # --- 2. Render manifests --------------------------------------------------
    Start-Step 2 $TotalSteps 'Renderizar manifests'
    [System.IO.File]::WriteAllText($Rendered, '', $Utf8NoBom)

    foreach ($path in $Manifests) {
        $path = [string]$path
        if (-not (Test-Path -LiteralPath $path)) {
            Stop-Script -Message "no existe el path: $path" -ExitCode 1
        }
        $item = Get-Item -LiteralPath $path
        if ($item.PSIsContainer -and (Test-Path -LiteralPath (Join-Path $path 'kustomization.yaml'))) {
            Write-Note "kustomize: $path"
            $k = Invoke-Tool 'kubectl' @('kustomize', $path)
            if ($k.ExitCode -ne 0) {
                Stop-Script -Message "kustomize falló: $path" -ExitCode 3
            }
            [System.IO.File]::AppendAllText($Rendered, ($k.Text + "`n---`n"), $Utf8NoBom)
        } elseif ($item.PSIsContainer) {
            Write-Note "directorio: $path"
            $files = @(Get-ChildItem -LiteralPath $path -File |
                Where-Object { $_.Extension -eq '.yaml' -or $_.Extension -eq '.yml' } |
                Sort-Object Name)
            if ($files.Count -eq 0) {
                Stop-Script -Message "sin *.yaml de primer nivel en: $path" -ExitCode 1
            }
            foreach ($f in $files) {
                Write-Note ("  {0}" -f $f.FullName)
                $text = [System.IO.File]::ReadAllText($f.FullName)
                [System.IO.File]::AppendAllText($Rendered, ("---`n# Source: {0}`n{1}" -f $f.FullName, $text), $Utf8NoBom)
            }
        } else {
            Write-Note "archivo: $path"
            $text = [System.IO.File]::ReadAllText((Convert-Path -LiteralPath $path))
            [System.IO.File]::AppendAllText($Rendered, ("---`n# Source: {0}`n{1}" -f $path, $text), $Utf8NoBom)
        }
    }

    $renderedInfo = Get-Item -LiteralPath $Rendered
    if ($renderedInfo.Length -eq 0) {
        Stop-Script -Message 'el render quedó vacío' -ExitCode 1
    }
    $RenderedLines = [System.IO.File]::ReadAllLines($Rendered)
    $ResourceCount = @($RenderedLines | Where-Object { $_ -match '^kind:' }).Count
    Complete-Step "$ResourceCount recursos renderizados"

    # --- 3. Validate secret keys required by the manifests --------------------
    Start-Step 3 $TotalSteps 'Validar claves de secrets'
    # Every `secretKeyRef.key` in the rendered output must exist in the secrets
    # file, otherwise pods would mount an incomplete Secret.
    $want = $false
    $required = New-Object System.Collections.Generic.List[string]
    foreach ($line in $RenderedLines) {
        if ($line -match 'secretKeyRef:') { $want = $true; continue }
        if ($want -and $line -match '^\s*key:\s*(\S+)') {
            $required.Add($Matches[1])
            $want = $false
        }
    }

    if ($required.Count -gt 0) {
        $requiredKeys = @($required | Sort-Object -Unique)
        $missing = @()
        $empty = @()
        foreach ($key in $requiredKeys) {
            if (-not $envMap.ContainsKey($key)) { $missing += $key }
            elseif ([string]::IsNullOrEmpty([string]$envMap[$key])) { $empty += $key }
        }
        if ($missing.Count -gt 0) {
            Stop-Script -Message ("el archivo '{0}' no define claves requeridas por los manifests: {1}" -f $SecretsFile, ($missing -join ' ')) -ExitCode 5
        }
        if ($empty.Count -gt 0) {
            Write-Warn ("claves definidas pero vacías (features opcionales deshabilitadas): {0}" -f ($empty -join ' '))
        }
        Complete-Step ("{0} validadas" -f (($requiredKeys -join ' ').Trim()))
    } else {
        Complete-Step 'los manifests no referencian secretKeyRef'
    }

    # --- 4. Namespaces --------------------------------------------------------
    Start-Step 4 $TotalSteps 'Namespaces'
    $kind = ''; $docName = ''; $docNs = ''; $inMeta = $false
    foreach ($line in $RenderedLines) {
        if ($line -match '^---') {
            if ($kind -and $docName -and $kind -match '^(Deployment|StatefulSet|DaemonSet|Job)$') {
                $ns = $docNs; if (-not $ns) { $ns = '-' }
                $Workloads.Add(('{0}|{1}|{2}' -f $kind.ToLower(), $docName, $ns))
            }
            $kind = ''; $docName = ''; $docNs = ''; $inMeta = $false
            continue
        }
        if ($line -match '^kind:\s*(\S+)') { $kind = $Matches[1]; continue }
        if ($line -match '^metadata:') { $inMeta = $true; continue }
        if ($inMeta -and $line -match '^  name:\s*(\S+)') { $docName = $Matches[1]; continue }
        if ($inMeta -and $line -match '^  namespace:\s*(\S+)') { $docNs = $Matches[1]; continue }
        if ($inMeta -and $line -match '^[A-Za-z]') { $inMeta = $false }
    }
    if ($kind -and $docName -and $kind -match '^(Deployment|StatefulSet|DaemonSet|Job)$') {
        $ns = $docNs; if (-not $ns) { $ns = '-' }
        $Workloads.Add(('{0}|{1}|{2}' -f $kind.ToLower(), $docName, $ns))
    }

    # Namespaces referenced by manifests: explicit Namespace resources plus
    # every metadata.namespace (exactly 2-space indent, so nested pod metadata
    # is skipped).
    $neededNs = New-Object System.Collections.Generic.List[string]
    $seenNs = @{}
    $pendingNs = ''
    $kind2 = ''
    foreach ($line in $RenderedLines) {
        if ($line -match '^---') {
            if ($pendingNs -and -not $seenNs.ContainsKey($pendingNs)) { $seenNs[$pendingNs] = $true; $neededNs.Add($pendingNs) }
            $pendingNs = ''
            $kind2 = ''
            continue
        }
        if ($line -match '^kind:\s*(\S+)') { $kind2 = $Matches[1]; continue }
        if ($kind2 -eq 'Namespace' -and $line -match '^  name:\s*(\S+)') { $pendingNs = $Matches[1]; continue }
        if ($line -match '^  namespace:\s*(\S+)') {
            $n = $Matches[1]
            if (-not $seenNs.ContainsKey($n)) { $seenNs[$n] = $true; $neededNs.Add($n) }
        }
    }
    if ($pendingNs -and -not $seenNs.ContainsKey($pendingNs)) { $seenNs[$pendingNs] = $true; $neededNs.Add($pendingNs) }
    if ($NamespaceFlag -and -not $seenNs.ContainsKey($NamespaceFlag)) {
        $neededNs.Add($NamespaceFlag)
    }

    if ($neededNs.Count -gt 0) {
        foreach ($ns in $neededNs) {
            if ($DryRun) {
                Write-Note "dry-run: se usaría el namespace '$ns'"
                continue
            }
            Write-Note "garantizando namespace '$ns'"
            $mk = Invoke-Tool 'kubectl' @('create', 'namespace', $ns, '--dry-run=client', '-o', 'yaml')
            if ($mk.ExitCode -ne 0) {
                Stop-Script -Message "no se pudo crear el namespace: $ns" -ExitCode 3
            }
            [System.IO.File]::WriteAllText($NsTmp, ($mk.Text + "`n"), $Utf8NoBom)
            $ap = Invoke-Tool 'kubectl' @('apply', '-f', $NsTmp)
            if ($ap.ExitCode -ne 0) {
                Stop-Script -Message "no se pudo crear el namespace: $ns" -Hint (($ap.Lines | Select-Object -Last 1)) -ExitCode 3
            }
        }
    } else {
        Write-Note 'los manifests no referencian namespaces explícitos'
    }

    $StatusNs = $NamespaceFlag
    if (-not $StatusNs) {
        $withNs = @($Workloads | Where-Object { ($_ -split '\|')[2] -ne '-' } | Select-Object -First 1)
        if ($withNs.Count -gt 0) { $StatusNs = ($withNs[0] -split '\|')[2] }
    }
    if (-not $StatusNs -and $neededNs.Count -gt 0) { $StatusNs = $neededNs[0] }
    if (-not $StatusNs) { $StatusNs = 'default' }
    Complete-Step "namespace de estado: $StatusNs"

    # --- 5. Apply -------------------------------------------------------------
    if ($DryRun) {
        Start-Step 5 $TotalSteps 'Dry-run de aplicación'
        $dry = Invoke-Tool 'kubectl' @('apply', '--dry-run=server', '-f', $Rendered)
        if ($dry.ExitCode -ne 0) {
            foreach ($l in $dry.Lines) { if ($l.Trim()) { Write-Diag $l } }
            Stop-Script -Message 'el dry-run del server falló' -ExitCode 3
        }
        $changed = 0
        $unchanged = 0
        foreach ($l in $dry.Lines) {
            if ($l -match 'unchanged') {
                $unchanged++
            } else {
                $changed++
                $clean = ($l -replace ' *\(server dry run\)', '').TrimEnd()
                if ($clean.Trim()) { Write-Info $clean }
            }
        }
        Complete-Step "$changed cambiarían, $unchanged sin cambios; no se aplicó nada"
    } else {
        Start-Step 5 $TotalSteps 'Aplicar manifests'
        $apply = Invoke-Tool 'kubectl' @('apply', '-f', $Rendered)
        if ($apply.ExitCode -ne 0) {
            foreach ($l in $apply.Lines) { if ($l.Trim()) { Write-Diag $l } }
            Stop-Script -Message 'kubectl apply falló' -ExitCode 3
        }
        $applied = 0
        foreach ($l in $apply.Lines) {
            if ($l -match 'created|configured|unchanged') { $applied++ }
            if ($l.Trim()) { Write-Info $l }
        }
        Complete-Step "$applied recursos aplicados"
    }

    # --- 6. Wait for workloads ------------------------------------------------
    if (-not $DryRun) {
        Start-Step 6 $TotalSteps "Esperar workloads (hasta ${Timeout}s)"
        if ($Workloads.Count -gt 0) {
            $notReady = 0
            foreach ($w in $Workloads) {
                $parts = $w -split '\|'
                $wKind = $parts[0]
                $wName = $parts[1]
                $wNs = $parts[2]
                if ($wNs -eq '-') { $wNs = $StatusNs }
                $rollout = Invoke-Tool 'kubectl' @('rollout', 'status', "$wKind/$wName", '-n', $wNs, "--timeout=${Timeout}s")
                if ($rollout.ExitCode -eq 0) {
                    Write-Ok ('{0,-28} {1}' -f "$wKind/$wName", "ns $wNs")
                } else {
                    Write-Diag ('    ✗ {0}/{1} (namespace {2})' -f $wKind, $wName, $wNs) 'Red'
                    foreach ($l in $rollout.Lines) { if ($l.Trim()) { Write-Diag ('      {0}' -f $l) } }
                    Write-Diag '      eventos recientes:' 'DarkGray'
                    $ev = Invoke-Tool 'kubectl' @('get', 'events', '-n', $wNs, '--field-selector', "involvedObject.name=$wName", '--sort-by=.lastTimestamp')
                    if ($ev.ExitCode -eq 0) {
                        $last = @($ev.Lines | Select-Object -Last 5)
                        foreach ($l in $last) { if ($l.Trim()) { Write-Diag ('      {0}' -f $l) } }
                    }
                    $notReady++
                }
            }
            if ($notReady -gt 0) {
                Stop-Script -Message "$notReady workload(s) no quedaron listos dentro de ${Timeout}s" -ExitCode 4
            }
            Complete-Step ("{0}/{0} workloads listos" -f $Workloads.Count)
        } else {
            Complete-Step 'sin Deployments/StatefulSets/DaemonSets/Jobs: nada que esperar'
        }
    }

    # --- 7. Status tables -----------------------------------------------------
    if (-not $DryRun) {
        Write-Host ''
        Write-Line ("[informe] Estado en el namespace {0}" -f $StatusNs) Cyan

        Write-Host ''
        Write-Note2 'workloads'
        $t = Invoke-Tool 'kubectl' @('get', 'deploy,statefulset,daemonset', '-n', $StatusNs, '-o', 'wide')
        if ($t.ExitCode -eq 0) { foreach ($l in $t.Lines) { if ($l.Trim()) { Write-Note2 $l } } }

        Write-Host ''
        Write-Note2 'services'
        $t = Invoke-Tool 'kubectl' @('get', 'svc', '-n', $StatusNs)
        if ($t.ExitCode -eq 0) { foreach ($l in $t.Lines) { if ($l.Trim()) { Write-Note2 $l } } }

        Write-Host ''
        Write-Note2 'pods'
        $t = Invoke-Tool 'kubectl' @('get', 'pods', '-n', $StatusNs)
        if ($t.ExitCode -eq 0) { foreach ($l in $t.Lines) { if ($l.Trim()) { Write-Note2 $l } } }

        # --- 8. Service access URLs ------------------------------------------
        Write-Host ''
        Write-Note2 'URLs de acceso'
        $node = Invoke-Tool 'kubectl' @('get', 'nodes', '-o', 'jsonpath={.items[0].status.addresses[?(@.type=="InternalIP")].address}')
        $NodeIp = ''
        if ($node.ExitCode -eq 0) { $NodeIp = $node.Text.Trim() }
        $svc = Invoke-Tool 'kubectl' @('get', 'svc', '-n', $StatusNs, '-o', 'jsonpath={range .items[*]}{.metadata.name}{"|"}{.spec.type}{"|"}{.spec.ports[0].port}{"|"}{.spec.ports[0].nodePort}{"\n"}{end}')
        foreach ($l in @($svc.Lines)) {
            if (-not $l.Trim() -or $svc.ExitCode -ne 0) { continue }
            $c = $l -split '\|'
            if ($c.Count -lt 4) { continue }
            $name = $c[0]; $type = $c[1]; $port = $c[2]; $nodePort = $c[3]
            switch ($type) {
                'NodePort' {
                    $ip = $NodeIp; if (-not $ip) { $ip = '<node-ip>' }
                    $np = $nodePort; if (-not $np) { $np = '?' }
                    Write-Info ('{0,-22} NodePort    http://{1}:{2}  (service port {3})' -f $name, $ip, $np, $port)
                }
                'LoadBalancer' {
                    Write-Info ('{0,-22} LoadBalancer service port {1} (external IP pendiente)' -f $name, $port)
                }
                default {
                    Write-Info ('{0,-22} ClusterIP   http://{0}.{1}.svc.cluster.local:{2}' -f $name, $StatusNs, $port)
                }
            }
        }

        # --- 9. Optional logs -------------------------------------------------
        if ($LogsTarget) {
            Write-Host ''
            Write-Note2 ("logs: {0} (últimas {1} líneas)" -f $LogsTarget, $LogTail)
            $lg = Invoke-Tool 'kubectl' @('logs', "deploy/$LogsTarget", '-n', $StatusNs, "--tail=$LogTail", '--timestamps')
            if ($lg.ExitCode -ne 0) {
                $lg = Invoke-Tool 'kubectl' @('logs', "statefulset/$LogsTarget", '-n', $StatusNs, "--tail=$LogTail", '--timestamps')
                if ($lg.ExitCode -ne 0) {
                    Stop-Script -Message "no se pudieron obtener logs de '$LogsTarget'" -ExitCode 1
                }
            }
            foreach ($l in $lg.Lines) { Write-Host $l }
        }
    }

    # --- 10. Summary ----------------------------------------------------------
    $elapsed = [int][Math]::Round($script:ScriptWatch.Elapsed.TotalSeconds)
    Write-Host ''
    Write-Line $script:Rule DarkGray
    if ($DryRun) {
        Write-Line ('  ✓ Dry-run validado · {0}s' -f $elapsed) Green
    } else {
        Write-Line ('  ✓ Deploy finalizado · {0}s · namespace {1}' -f $elapsed, $StatusNs) Green
    }
    Write-Line $script:Rule DarkGray
    Write-Host ''
} catch {
    Stop-Script -Message ("Error inesperado: {0}" -f $_.Exception.Message) -ExitCode 1
} finally {
    Remove-Item -LiteralPath $Rendered -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $NsTmp -Force -ErrorAction SilentlyContinue
}
