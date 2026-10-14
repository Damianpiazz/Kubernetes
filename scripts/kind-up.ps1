<#
.SYNOPSIS
    Levanta el entorno local completo de La Tiendita sobre kind.

.DESCRIPTION
    1. Verifica docker, kubectl y kind.
    2. Crea (o reutiliza) el clúster kind y activa su contexto.
    3. Crea k8s/apps/environments/local/secrets.env desde el example si no existe.
    4. Construye las imágenes y las carga en el clúster (kind load).
    5. Aplica k8s/apps/environments/local y espera StatefulSets y Deployments.
    6. Verifica que todos los pods queden listos.

.PARAMETER ClusterName
    Nombre del clúster kind. Por defecto: latiendita-dev.

.PARAMETER Tag
    Tag de las imágenes a construir y cargar. Por defecto: dev.

.PARAMETER SkipBuild
    Omite el build y usa las imágenes ya presentes en el daemon de Docker.

.PARAMETER Timeout
    Segundos máximo de espera por cada rollout. Por defecto: 300.

.EXAMPLE
    ./kind-up.ps1

.EXAMPLE
    ./kind-up.ps1 -Tag dev -SkipBuild

.NOTES
    Script autocontenido: no dot-sourcea librerías. Correr desde la raíz del
    repo.

    Códigos de salida (idénticos a kind-up.sh):
      1 uso incorrecta / repo sin k8s/apps/environments/local
      2 herramientas faltantes o daemon de Docker caído
      3 clúster no creado / contexto no activado
      4 secrets locales (falta el example o no se pudo copiar)
      5 build fallido / imagen ausente / kind load fallido
      6 kubectl apply falló
      7 algún StatefulSet o Deployment no quedó listo
      8 pods del namespace no listos
#>
[CmdletBinding()]
param(
    [string]$ClusterName = 'latiendita-dev',
    [string]$Tag = 'dev',
    [switch]$SkipBuild,
    [int]$Timeout = 300
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

$Namespace      = 'latiendita'
$Overlay        = 'k8s/apps/environments/local'
$SecretsFile    = 'k8s/apps/environments/local/secrets.env'
$SecretsExample = 'k8s/apps/environments/local/secrets.env.example'
$Context        = 'kind-{0}' -f $ClusterName
$TotalSteps     = 8

$Apps          = @('auth-service', 'user-service', 'product-service', 'order-service', 'payment-service', 'gateway')
$StatefulSets  = @('auth-db', 'users-db', 'products-db', 'orders-db', 'payments-db', 'kafka')

if (-not (Test-Path $Overlay)) {
    Stop-Script -Message "No existe ${Overlay}: correr desde la raíz del repo." -ExitCode 1
}

Write-Header 'La Tiendita · entorno local (kind)' @(
    "clúster    : $ClusterName",
    "contexto   : $Context",
    "tag        : $Tag",
    "namespace  : $Namespace"
)

# --- 1. Preflight ------------------------------------------------------------
Start-Step 1 $TotalSteps 'Preflight'
Assert-Tool -Name 'docker' -Hint 'Instalar Docker Desktop y dejarlo corriendo antes de continuar.'
Assert-Tool -Name 'kubectl' -Hint 'Instalar kubectl (winget install Kubernetes.kubectl).'
Assert-Tool -Name 'kind' -Hint 'Instalar kind (winget install Kubernetes.kind).'

$daemon = Invoke-Tool 'docker' @('info', '--format', '{{.ServerVersion}}')
if ($daemon.ExitCode -ne 0) {
    Stop-Script -Message 'El daemon de Docker no está corriendo.' -Hint 'Iniciar Docker Desktop y reintentar.' -ExitCode 2
}

# kubectl --short fue removido en 1.28: se intenta y se cae a --client.
$kVersion = Invoke-Tool 'kubectl' @('version', '--client', '--short')
if ($kVersion.ExitCode -ne 0) { $kVersion = Invoke-Tool 'kubectl' @('version', '--client') }
$kText = ($kVersion.Lines | Where-Object { $_ -match 'Client Version' } | Select-Object -First 1)
if (-not $kText) { $kText = ($kVersion.Lines | Select-Object -First 1) }
if (-not $kText) { $kText = 'desconocida' }
$kindText = ((Invoke-Tool 'kind' @('version')).Lines | Select-Object -First 1)
if (-not $kindText) { $kindText = 'desconocida' }

$versions = @(
    ('docker  {0}' -f $daemon.Text.Trim()),
    ('kubectl {0}' -f $kText.Trim()),
    ('kind    {0}' -f $kindText.Trim())
)
foreach ($v in $versions) { Write-Note $v }
Complete-Step 'herramientas listas'

# --- 2. Clúster --------------------------------------------------------------
Start-Step 2 $TotalSteps 'Clúster kind'
$existing = Invoke-Tool 'kubectl' @('config', 'get-contexts', '-o', 'name')
$hasContext = @($existing.Lines) -contains $Context

if (-not $hasContext) {
    Write-Note ("creando clúster '{0}' (puede tardar varios minutos la primera vez)..." -f $ClusterName)
    & kind create cluster --name $ClusterName
    if ($LASTEXITCODE -ne 0) {
        Stop-Script -Message ("No se pudo crear el clúster '{0}'." -f $ClusterName) `
                    -Hint 'Revisar el log de kind arriba.' -ExitCode 3
    }
} else {
    Write-Note ("reutilizando clúster existente '{0}'" -f $ClusterName)
    $info = Invoke-Tool 'kubectl' @('cluster-info', '--context', $Context)
    if ($info.ExitCode -ne 0) {
        Stop-Script -Message ("El clúster '{0}' existe pero no responde." -f $ClusterName) `
                    -Hint ("Borrarlo y volver a empezar: kind delete cluster --name {0}" -f $ClusterName) -ExitCode 3
    }
}

$use = Invoke-Tool 'kubectl' @('config', 'use-context', $Context)
if ($use.ExitCode -ne 0) {
    Stop-Script -Message ("No se pudo activar el contexto '{0}'." -f $Context) -ExitCode 3
}

$serverVersion = Invoke-Tool 'kubectl' @('version', '-o', 'jsonpath={.serverVersion.gitVersion}')
if ($serverVersion.ExitCode -eq 0 -and $serverVersion.Text.Trim()) {
    Write-Note ("contexto activo: {0} · k8s {1}" -f (Invoke-Tool 'kubectl' @('config', 'current-context')).Text.Trim(), $serverVersion.Text.Trim())
} else {
    Write-Note ("contexto activo: {0}" -f $Context)
}
Complete-Step ("clúster '{0}' disponible" -f $ClusterName)

# --- 3. Secrets locales ------------------------------------------------------
Start-Step 3 $TotalSteps 'Secrets locales'
if (Test-Path $SecretsFile) {
    Write-Note $SecretsFile
    Complete-Step 'ya existe, no se toca'
} else {
    if (-not (Test-Path $SecretsExample)) {
        Stop-Script -Message ("No existe {0} ni su example." -f $SecretsFile) -ExitCode 4
    }
    try {
        Copy-Item $SecretsExample $SecretsFile -ErrorAction Stop
    } catch {
        Stop-Script -Message ("No se pudo crear {0}." -f $SecretsFile) -ExitCode 4
    }
    Write-Note ("{0}  <-  {1}" -f $SecretsFile, $SecretsExample)
    Write-Warn 'secrets.env creado con valores de desarrollo: no usar fuera de este clúster.'
    Complete-Step 'creado desde el example'
}

# --- 4. Imágenes -------------------------------------------------------------
Start-Step 4 $TotalSteps 'Imágenes'
$failed = New-Object System.Collections.Generic.List[string]
if ($SkipBuild) {
    Write-Note 'build omitido (-SkipBuild): se usan las imágenes ya presentes en Docker.'
} else {
    $buildWatch = [System.Diagnostics.Stopwatch]::StartNew()
    foreach ($s in $Apps) {
        $image = 'latiendita/{0}:{1}' -f $s, $Tag
        Write-Host ''
        Write-Info $image
        & docker build -t $image "./services/$s"
        if ($LASTEXITCODE -ne 0) {
            $failed.Add($s)
            Write-Warn ("build falló para '{0}' (exit code {1})" -f $s, $LASTEXITCODE)
        }
    }
    $buildWatch.Stop()
    if ($failed.Count -gt 0) {
        Stop-Script -Message ("Falló el build de {0} servicio(s): {1}" -f $failed.Count, ($failed -join ', ')) `
                    -Hint 'Correr .\scripts\build-images.ps1 para ver el detalle.' -ExitCode 5
    }
    Write-Note ('build: {0}' -f (Format-Duration $buildWatch.Elapsed.TotalSeconds))
}

foreach ($s in $Apps) {
    $image = 'latiendita/{0}:{1}' -f $s, $Tag
    $check = Invoke-Tool 'docker' @('image', 'inspect', '-f', '{{.Id}}', $image)
    if ($check.ExitCode -ne 0) {
        Stop-Script -Message ("No existe la imagen '{0}'." -f $image) `
                    -Hint ('Correr .\scripts\build-images.ps1 -Tag {0} o sacar -SkipBuild.' -f $Tag) -ExitCode 5
    }
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $load = Invoke-Tool 'kind' @('load', 'docker-image', $image, '--name', $ClusterName)
    $sw.Stop()
    if ($load.ExitCode -ne 0) {
        Stop-Script -Message ("No se pudo cargar '{0}' en el clúster." -f $image) -Hint 'Revisar el log de kind arriba.' -ExitCode 5
    }
    Write-Ok ('{0,-26} {1}' -f $image, (Format-Duration $sw.Elapsed.TotalSeconds))
}
Complete-Step ('{0} imágenes cargadas en el clúster' -f $Apps.Count)

# --- 5. Manifests ------------------------------------------------------------
Start-Step 5 $TotalSteps "Aplicar manifests ($Overlay)"
$apply = Invoke-Tool 'kubectl' @('apply', '-k', $Overlay)
foreach ($line in $apply.Lines) { if ($line.Trim()) { Write-Note $line } }
if ($apply.ExitCode -ne 0) {
    Stop-Script -Message 'kubectl apply falló.' -Hint 'Revisar el render: kubectl kustomize k8s/apps/environments/local' -ExitCode 6
}
Complete-Step ('{0} recursos aplicados' -f @($apply.Lines | Where-Object { $_ -match 'configured|created|unchanged' }).Count)

# --- 6. Infraestructura ------------------------------------------------------
Start-Step 6 $TotalSteps 'Esperar infraestructura (StatefulSets)'
foreach ($d in $StatefulSets) {
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $wait = Invoke-Tool 'kubectl' @('rollout', 'status', "statefulset/$d", '-n', $Namespace, "--timeout=${Timeout}s")
    $sw.Stop()
    if ($wait.ExitCode -ne 0) {
        Stop-Script -Message ("statefulset/{0} no quedó listo en {1}s." -f $d, $Timeout) `
                    -Hint ("kubectl describe statefulset {0} -n {1}; kubectl get pods -n {1}" -f $d, $Namespace) -ExitCode 7
    }
    Write-Ok ('{0,-16} {1}' -f $d, (Format-Duration $sw.Elapsed.TotalSeconds))
}
Complete-Step ('{0}/{0} listos' -f $StatefulSets.Count)

# --- 7. Servicios ------------------------------------------------------------
Start-Step 7 $TotalSteps 'Esperar servicios (Deployments)'
foreach ($s in $Apps) {
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $wait = Invoke-Tool 'kubectl' @('rollout', 'status', "deployment/$s", '-n', $Namespace, "--timeout=${Timeout}s")
    $sw.Stop()
    if ($wait.ExitCode -ne 0) {
        Stop-Script -Message ("deployment/{0} no quedó listo en {1}s." -f $s, $Timeout) `
                    -Hint ("kubectl describe deployment {0} -n {1}; kubectl logs deploy/{0} -n {1} --tail=50" -f $s, $Namespace) -ExitCode 7
    }
    Write-Ok ('{0,-22} {1}' -f $s, (Format-Duration $sw.Elapsed.TotalSeconds))
}
Complete-Step ('{0}/{0} listos' -f $Apps.Count)

# --- 8. Verificación ---------------------------------------------------------
Start-Step 8 $TotalSteps 'Verificación'
$pods = Invoke-Tool 'kubectl' @('get', 'pods', '-n', $Namespace, '--no-headers')
if ($pods.ExitCode -ne 0) {
    Stop-Script -Message ("No se pudieron listar los pods del namespace '{0}'." -f $Namespace) -ExitCode 8
}

$total = 0
$notReady = @()
foreach ($line in $pods.Lines) {
    if (-not $line.Trim()) { continue }
    $cols = @($line.Trim() -split '\s+')
    if ($cols.Count -lt 3) { continue }
    $total++
    $counts = @($cols[1] -split '/')
    $status = $cols[2]
    $isReady = ($counts.Count -eq 2 -and $counts[0] -eq $counts[1] -and $status -match '^(Running|Completed)$')
    if (-not $isReady) { $notReady += $line.Trim() }
}

if ($total -eq 0) {
    Stop-Script -Message ("El namespace '{0}' no tiene pods." -f $Namespace) -Hint ("kubectl get all -n {0}" -f $Namespace) -ExitCode 8
}
foreach ($p in $notReady) { Write-Warn $p }
if ($notReady.Count -gt 0) {
    Stop-Script -Message ('{0} de {1} pods no están listos.' -f $notReady.Count, $total) `
                -Hint ("kubectl get pods -n {0}; kubectl describe pod <pod> -n {0}" -f $Namespace) -ExitCode 8
}
Complete-Step ('{0}/{1} pods listos' -f $total, $total)

Write-Summary 'Entorno local desplegado' -Rows @(
    ('clúster    : {0} (contexto {1})' -f $ClusterName, $Context),
    ('namespace  : {0}' -f $Namespace),
    ('pods       : {0} listos' -f $total),
    ('workloads  : {0} statefulsets + {1} deployments' -f $StatefulSets.Count, $Apps.Count)
) -Next @(
    ('kubectl port-forward svc/gateway 8080:80 -n {0}' -f $Namespace),
    'curl http://localhost:8080/actuator/health/readiness',
    ('kubectl get pods -n {0} -w' -f $Namespace)
)
