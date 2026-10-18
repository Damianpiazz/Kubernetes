<#
.SYNOPSIS
    Limpia el entorno local de La Tiendita sobre kind.

.DESCRIPTION
    Borra el clúster kind, sus contenedores de nodo, su contexto de kubeconfig
    y las imágenes construidas por kind-up / build-images.

      1. Verifica docker y kind, y que el daemon responda.
      2. Inventario de lo que se va a borrar (clúster, contexto, nodos, imágenes).
      3. Aviso de pérdida de datos y confirmación (o -DryRun / -Yes).
      4. Borra el clúster kind, sus contenedores de nodo y su contexto.
      5. Borra las imágenes latiendita/<servicio>:<tag> (salvo -KeepImages).
      6. Limpia la build cache (solo con -Cache).
      7. Verifica que no quede nada y reporta el espacio recuperado.

    ADVERTENCIA: los datos de las bases (Postgres/Kafka) viven en volúmenes
    dentro del nodo de kind. Borrar el clúster los destruye de forma permanente.

    Par nativo de scripts/kind-down.sh: mismos objetivos, mismas opciones,
    mismos pasos y mismo contrato de códigos de salida.

.PARAMETER ClusterName
    Nombre del clúster kind. Por defecto: latiendita-dev.

.PARAMETER Tag
    Tag de las imágenes a borrar. Por defecto: dev.

.PARAMETER KeepImages
    No borra las imágenes, solo el clúster.

.PARAMETER Cache
    También limpia la build cache (docker builder prune).

.PARAMETER Yes
    No pide confirmación (para CI).

.PARAMETER DryRun
    Solo muestra el inventario; no borra nada.

.EXAMPLE
    ./kind-down.ps1 -DryRun

.EXAMPLE
    ./kind-down.ps1 -Yes -Cache

.NOTES
    Script autocontenido: no dot-sourcea librerías ni invoca a otros scripts
    del repo. No requiere estar en la raíz del repo: no toca archivos locales.

    Códigos de salida (idénticos a kind-down.sh):
      1 uso incorrecta
      2 herramientas faltantes o daemon de Docker caído
      3 confirmación rechazada / abortado
      4 clúster o nodo no borrado
      5 imágenes no borradas
      6 build cache no limpiada
      7 quedaron recursos tras la limpieza
#>
[CmdletBinding()]
param(
    [string]$ClusterName = 'latiendita-dev',
    [string]$Tag = 'dev',
    [switch]$KeepImages,
    [switch]$Cache,
    [switch]$Yes,
    [switch]$DryRun
)

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

$TotalSteps   = 7
$ContextName  = 'kind-{0}' -f $ClusterName
$NodePrefix   = '{0}-' -f $ClusterName

$ImagesPolicy = "latiendita/*:$Tag"
if ($KeepImages) { $ImagesPolicy = 'conservadas (-KeepImages)' }
$CachePolicy = 'conservada'
if ($Cache) { $CachePolicy = 'se limpiará (-Cache)' }

$ModeLabel = 'limpieza real'
if ($DryRun) { $ModeLabel = 'dry-run (no borra nada)' }

Write-Header 'La Tiendita · limpieza del entorno local (kind)' @(
    "clúster    : $ClusterName",
    "contexto   : $ContextName",
    "tag        : $Tag",
    "imágenes   : $ImagesPolicy",
    "build cache: $CachePolicy",
    "modo       : $ModeLabel"
)

# --- 1. Preflight ------------------------------------------------------------
Start-Step 1 $TotalSteps 'Preflight'
Assert-Tool -Name 'docker' -Hint 'Instalar Docker Desktop y dejarlo corriendo antes de continuar.'
Assert-Tool -Name 'kind' -Hint 'Instalar kind (winget install Kubernetes.kind).'

$daemon = Invoke-Tool 'docker' @('info', '--format', '{{.ServerVersion}}')
if ($daemon.ExitCode -ne 0) {
    Stop-Script -Message 'El daemon de Docker no está corriendo.' -Hint 'Iniciar Docker Desktop y reintentar.' -ExitCode 2
}
Write-Note ("docker  {0}" -f $daemon.Text.Trim())

$HaveKubectl = $false
if (Test-Command 'kubectl') {
    $HaveKubectl = $true
    Write-Note 'kubectl presente (se usa para limpiar el contexto de kubeconfig)'
} else {
    Write-Warn ("kubectl ausente: el contexto '{0}' no se podrá limpiar desde acá" -f $ContextName)
}
Complete-Step 'herramientas listas'

# --- 2. Inventario -----------------------------------------------------------
Start-Step 2 $TotalSteps 'Inventario (qué se va a borrar)'

$clusters = Invoke-Tool 'kind' @('get', 'clusters')
$ClusterExists = @($clusters.Lines) -contains $ClusterName

$ContextExists = $false
if ($HaveKubectl) {
    $ctxList = Invoke-Tool 'kubectl' @('config', 'get-contexts', '-o', 'name')
    $ContextExists = @($ctxList.Lines) -contains $ContextName
}

$NodeContainers = @()
$ps = Invoke-Tool 'docker' @('ps', '-a', '--filter', "name=$NodePrefix", '--format', '{{.Names}}')
foreach ($n in $ps.Lines) { if ("$n".Trim()) { $NodeContainers += "$n".Trim() } }

$ImageRefs = @()
if (-not $KeepImages) {
    $imgs = Invoke-Tool 'docker' @('images', '--format', '{{.Repository}}:{{.Tag}}', 'latiendita/*')
    foreach ($r in $imgs.Lines) {
        $ref = "$r".Trim()
        if ($ref -and $ref -like "*:$Tag") { $ImageRefs += $ref }
    }
}

# Las imágenes pueden compartir ID (varios tags, un solo disco): se suman por ID
# único para no inflar el total.
$ImageBytes = 0L
$uniqueIds = @()
foreach ($ref in $ImageRefs) {
    $t = Invoke-Tool 'docker' @('image', 'inspect', '-f', '{{.Id}}', $ref)
    if ($t.ExitCode -eq 0 -and $t.Text.Trim()) { $uniqueIds += $t.Text.Trim() }
}
foreach ($id in ($uniqueIds | Sort-Object -Unique)) {
    $t = Invoke-Tool 'docker' @('image', 'inspect', '-f', '{{.Size}}', $id)
    if ($t.ExitCode -eq 0) {
        $parsed = 0L
        if ([long]::TryParse($t.Text.Trim(), [ref]$parsed)) { $ImageBytes += $parsed }
    }
}

if ($ClusterExists) {
    Write-Info ('{0,-14} {1}' -f 'clúster kind', $ClusterName)
} else {
    Write-Note ("clúster kind  {0} (no existe)" -f $ClusterName)
}
if ($NodeContainers.Count -gt 0) {
    foreach ($n in $NodeContainers) { Write-Info ('{0,-14} {1}' -f 'contenedor', $n) }
} else {
    Write-Note 'contenedores  ninguno'
}
if ($ContextExists) {
    Write-Info ('{0,-14} {1}' -f 'contexto', $ContextName)
} else {
    Write-Note ("contexto      {0} (no existe)" -f $ContextName)
}
if ($ImageRefs.Count -gt 0) {
    foreach ($r in $ImageRefs) { Write-Info ('{0,-14} {1}' -f 'imagen', $r) }
    Write-Note ("tamaño de imágenes: {0}" -f (Format-Bytes $ImageBytes))
} else {
    Write-Note ("imágenes      ninguna latiendita/*:$Tag")
}

$resourceCount = @($ClusterExists, $ContextExists).Where({ $_ }).Count + $NodeContainers.Count + $ImageRefs.Count
if ($resourceCount -eq 0) {
    Complete-Step 'nada que limpiar'
    Write-Summary 'Entorno local ya limpio' -Rows @(
        ("clúster    : {0} (no existía)" -f $ClusterName),
        ("imágenes   : 0 latiendita/*:$Tag"),
        "build cache: $CachePolicy"
    ) -Next @(
        'scripts\kind-up.ps1  para volver a levantar el entorno'
    )
    exit 0
}
Complete-Step ('{0} recurso(s) por limpiar' -f $resourceCount)

# --- 3. Confirmación ---------------------------------------------------------
Start-Step 3 $TotalSteps 'Confirmación'
Write-Host ''
Write-Warn 'Los volúmenes de las bases (Postgres/Kafka) viven DENTRO del nodo de kind.'
Write-Warn 'Borrar el clúster destruye esos datos de forma permanente e irreversible.'
Write-Host ''

if ($DryRun) {
    Write-Note '-DryRun: no se borró nada.'
    Complete-Step 'dry-run, se omite la confirmación'
    Write-Summary 'Dry-run: nada borrado' -Rows @(
        ("clúster    : {0} ({1})" -f $ClusterName, $(if ($ClusterExists) { 'existente' } else { 'inexistente' })),
        ("imágenes   : {0} latiendita/*:$Tag ({1})" -f $ImageRefs.Count, (Format-Bytes $ImageBytes)),
        "build cache: $CachePolicy"
    ) -Next @(
        'scripts\kind-down.ps1  sin -DryRun para borrar de verdad'
    )
    exit 0
}

if ($Yes) {
    Write-Note '-Yes: se continúa sin pedir confirmación.'
} elseif ([Console]::IsInputRedirected) {
    Stop-Script -Message 'Se requiere confirmación y la entrada no es un TTY.' `
                -Hint 'Usar -Yes (CI) o -DryRun (solo inventario).' -ExitCode 3
} else {
    $answer = Read-Host '  ¿Borrar el entorno local? [s/N]'
    if ($answer -notmatch '^(s|si|y|yes)$') {
        Stop-Script -Message 'Confirmación rechazada: no se borró nada.' -ExitCode 3
    }
}
Complete-Step 'confirmado'

# --- 4. Clúster kind ---------------------------------------------------------
Start-Step 4 $TotalSteps 'Borrar clúster kind y su contexto'
if ($ClusterExists) {
    Write-Note ("kind delete cluster --name {0}" -f $ClusterName)
    & kind delete cluster --name $ClusterName
    if ($LASTEXITCODE -ne 0) {
        Stop-Script -Message ("No se pudo borrar el clúster '{0}'." -f $ClusterName) `
                    -Hint 'Revisar el log de kind arriba.' -ExitCode 4
    }
    Write-Ok ("clúster '{0}' eliminado" -f $ClusterName)
} else {
    Write-Note ("el clúster '{0}' no existía: se omite" -f $ClusterName)
}

foreach ($n in $NodeContainers) {
    $r = Invoke-Tool 'docker' @('rm', '-f', $n)
    if ($r.ExitCode -ne 0) {
        Stop-Script -Message ("No se pudo eliminar el contenedor de nodo '{0}'." -f $n) `
                    -Hint ("docker ps -a --filter name={0}  y revisar a mano." -f $n) -ExitCode 4
    }
    Write-Ok ("contenedor {0} eliminado" -f $n)
}

# kind ya borra el contexto al borrar el clúster; acá se limpian los restos de
# un clúster que se mató a mano (kill del contenedor, VM caída, etc.).
if ($HaveKubectl) {
    $ctxList = Invoke-Tool 'kubectl' @('config', 'get-contexts', '-o', 'name')
    if (@($ctxList.Lines) -contains $ContextName) {
        $dc = Invoke-Tool 'kubectl' @('config', 'delete-context', $ContextName)
        if ($dc.ExitCode -eq 0) { Write-Ok ("contexto {0} eliminado" -f $ContextName) }
        $dk = Invoke-Tool 'kubectl' @('config', 'delete-cluster', $ContextName)
        if ($dk.ExitCode -eq 0) { Write-Ok ("entrada de clúster {0} eliminada de kubeconfig" -f $ContextName) }
    } else {
        Write-Note ("el contexto {0} ya no quedó en kubeconfig" -f $ContextName)
    }
}
Complete-Step 'clúster y nodos eliminados'

# --- 5. Imágenes -------------------------------------------------------------
Start-Step 5 $TotalSteps "Borrar imágenes (latiendita/*:$Tag)"
if ($KeepImages) {
    Write-Note '-KeepImages: se conservan las imágenes locales.'
    Complete-Step 'imágenes conservadas'
} elseif ($ImageRefs.Count -eq 0) {
    Write-Note ("no hay imágenes latiendita/*:$Tag que borrar.")
    Complete-Step 'nada que borrar'
} else {
    $failedImages = New-Object System.Collections.Generic.List[string]
    foreach ($ref in $ImageRefs) {
        $r = Invoke-Tool 'docker' @('rmi', $ref)
        if ($r.ExitCode -ne 0) {
            $failedImages.Add($ref)
            Write-Warn ("no se pudo eliminar '{0}' (exit code {1})" -f $ref, $r.ExitCode)
            continue
        }
        Write-Ok $ref
    }
    if ($failedImages.Count -gt 0) {
        Stop-Script -Message ("No se pudieron eliminar {0} imagen(es): {1}" -f $failedImages.Count, ($failedImages -join ', ')) `
                    -Hint 'docker rmi <imagen> a mano, o revisar que ningún contenedor la esté usando.' -ExitCode 5
    }
    Complete-Step ('{0} imagen(es) eliminadas ({1})' -f $ImageRefs.Count, (Format-Bytes $ImageBytes))
}

# --- 6. Build cache ----------------------------------------------------------
Start-Step 6 $TotalSteps 'Build cache'
if ($Cache) {
    $prune = Invoke-Tool 'docker' @('builder', 'prune', '-f')
    if ($prune.ExitCode -ne 0) {
        Stop-Script -Message 'No se pudo limpiar la build cache.' -Hint 'docker builder prune --force a mano.' -ExitCode 6
    }
    foreach ($line in $prune.Lines) { if ("$line".Trim()) { Write-Note "$line" } }
    Complete-Step 'build cache limpiada'
} else {
    Write-Note '-Cache no indicado: se conserva la build cache.'
    Complete-Step 'build cache conservada'
}

# --- 7. Verificación ---------------------------------------------------------
Start-Step 7 $TotalSteps 'Verificación'
$leftover = New-Object System.Collections.Generic.List[string]

$clusters = Invoke-Tool 'kind' @('get', 'clusters')
if (@($clusters.Lines) -contains $ClusterName) { $leftover.Add("clúster kind: $ClusterName") }

if ($HaveKubectl) {
    $ctxList = Invoke-Tool 'kubectl' @('config', 'get-contexts', '-o', 'name')
    if (@($ctxList.Lines) -contains $ContextName) { $leftover.Add("contexto kubeconfig: $ContextName") }
}

if (-not $KeepImages) {
    $imgs = Invoke-Tool 'docker' @('images', '--format', '{{.Repository}}:{{.Tag}}', 'latiendita/*')
    foreach ($r in $imgs.Lines) {
        $ref = "$r".Trim()
        if ($ref -and $ref -like "*:$Tag") { $leftover.Add("imagen: $ref") }
    }
}

if ($leftover.Count -gt 0) {
    foreach ($l in $leftover) { Write-Warn $l }
    Stop-Script -Message ("Quedaron {0} recurso(s) tras la limpieza." -f $leftover.Count) `
                -Hint ("kind delete cluster --name {0}; kubectl config delete-context {1}" -f $ClusterName, $ContextName) -ExitCode 7
}
Complete-Step 'sin restos'

Write-Summary 'Entorno local limpio' -Rows @(
    ("clúster    : {0} eliminado" -f $ClusterName),
    ("imágenes   : {0} latiendita/*:$Tag eliminadas" -f $ImageRefs.Count),
    ("espacio    : {0} liberado en imágenes" -f (Format-Bytes $ImageBytes)),
    "build cache: $CachePolicy"
) -Next @(
    'scripts\kind-up.ps1     para volver a levantar el entorno',
    'scripts\secrets.ps1     si también querés regenerar los secrets locales'
)
