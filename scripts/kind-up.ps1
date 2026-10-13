<#
.SYNOPSIS
    Levanta el entorno local completo de La Tiendita sobre kind.

.DESCRIPTION
    1. Verifica docker, kubectl y kind.
    2. Crea (o reutiliza) el clúster kind y activa su contexto.
    3. Crea k8s/overlays/local/secrets.env desde el example si no existe.
    4. Construye las imágenes y las carga en el clúster (kind load).
    5. Aplica k8s/overlays/local y espera StatefulSets y Deployments.
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
. (Join-Path $PSScriptRoot 'lib/console.ps1')

$Namespace      = 'latiendita'
$Overlay        = 'k8s/overlays/local'
$SecretsFile    = 'k8s/overlays/local/secrets.env'
$SecretsExample = 'k8s/overlays/local/secrets.env.example'
$Context        = 'kind-{0}' -f $ClusterName
$TotalSteps     = 8

$Apps          = @('auth-service', 'user-service', 'product-service', 'order-service', 'payment-service', 'gateway')
$StatefulSets  = @('auth-db', 'users-db', 'products-db', 'orders-db', 'payments-db', 'kafka')

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
    Stop-Script -Message 'El daemon de Docker no está corriendo.' -Hint 'Iniciar Docker Desktop y reintentar.'
}

# kubectl --short fue removido en 1.28: se intenta y se cae a --client.
$kVersion = Invoke-Tool 'kubectl' @('version', '--client', '--short')
if ($kVersion.ExitCode -ne 0) { $kVersion = Invoke-Tool 'kubectl' @('version', '--client') }
$kText = ($kVersion.Lines | Where-Object { $_ -match 'Client Version' } | Select-Object -First 1)
if (-not $kText) { $kText = ($kVersion.Lines | Select-Object -First 1) }
$versions = @(
    ('docker  {0}' -f $daemon.Text.Trim()),
    ('kubectl {0}' -f $kText.Trim()),
    ('kind    {0}' -f ((Invoke-Tool 'kind' @('version')).Text -split "`n" | Select-Object -First 1).Trim())
)
foreach ($v in $versions) { Write-Note $v }
Complete-Step 'herramientas listas'

# --- 2. Clúster --------------------------------------------------------------
Start-Step 2 $TotalSteps 'Clúster kind'
$existing = Invoke-Tool 'kubectl' @('config', 'get-contexts', '-o', 'name')
$hasContext = @($existing.Lines) -contains $Context

if (-not $hasContext) {
    Write-Note ("creando clúster '{0}' (puede tardar varios minutos la primera vez)..." -f $ClusterName)
    $create = Invoke-Tool 'kind' @('create', 'cluster', '--name', $ClusterName)
    if ($create.ExitCode -ne 0) {
        Stop-Script -Message ("No se pudo crear el clúster '{0}'." -f $ClusterName) `
                    -Hint 'Revisar el log de kind arriba.'
    }
} else {
    Write-Note ("reutilizando clúster existente '{0}'" -f $ClusterName)
    $info = Invoke-Tool 'kubectl' @('cluster-info', '--context', $Context)
    if ($info.ExitCode -ne 0) {
        Stop-Script -Message ("El clúster '{0}' existe pero no responde." -f $ClusterName) `
                    -Hint ("Borrarlo y volver a empezar: kind delete cluster --name {0}" -f $ClusterName)
    }
}

$use = Invoke-Tool 'kubectl' @('config', 'use-context', $Context)
if ($use.ExitCode -ne 0) {
    Stop-Script -Message ("No se pudo activar el contexto '{0}'." -f $Context)
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
        Stop-Script -Message ("No existe {0} ni su example." -f $SecretsFile)
    }
    Copy-Item $SecretsExample $SecretsFile
    Write-Note ("{0}  <-  {1}" -f $SecretsFile, $SecretsExample)
    Write-Warn 'secrets.env creado con valores de desarrollo: no usar fuera de este clúster.'
    Complete-Step 'creado desde el example'
}

# --- 4. Imágenes -------------------------------------------------------------
Start-Step 4 $TotalSteps 'Imágenes'
if ($SkipBuild) {
    Write-Note 'build omitido (-SkipBuild): se usan las imágenes ya presentes en Docker.'
} else {
    $buildWatch = [System.Diagnostics.Stopwatch]::StartNew()
    & ('{0}/build-images.ps1' -f $PSScriptRoot) -Tag $Tag
    $buildCode = $LASTEXITCODE
    $buildWatch.Stop()
    if ($buildCode -ne 0) {
        Stop-Script -Message 'El build de imágenes falló.' -Hint 'Correr .\scripts\build-images.ps1 para ver el detalle.'
    }
    Write-Note ('build: {0}' -f (Format-Duration $buildWatch.Elapsed.TotalSeconds))
}

foreach ($s in $Apps) {
    $image = 'latiendita/{0}:{1}' -f $s, $Tag
    $check = Invoke-Tool 'docker' @('image', 'inspect', '-f', '{{.Id}}', $image)
    if ($check.ExitCode -ne 0) {
        Stop-Script -Message ("No existe la imagen '{0}'." -f $image) `
                    -Hint ('Correr .\scripts\build-images.ps1 -Tag {0} o sacar -SkipBuild.' -f $Tag)
    }
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $load = Invoke-Tool 'kind' @('load', 'docker-image', $image, '--name', $ClusterName)
    $sw.Stop()
    if ($load.ExitCode -ne 0) {
        Stop-Script -Message ("No se pudo cargar '{0}' en el clúster." -f $image) -Hint 'Revisar el log de kind arriba.'
    }
    Write-Ok ('{0,-26} {1}' -f $image, (Format-Duration $sw.Elapsed.TotalSeconds))
}
Complete-Step ('{0} imágenes cargadas en el clúster' -f $Apps.Count)

# --- 5. Manifests ------------------------------------------------------------
Start-Step 5 $TotalSteps "Aplicar manifests ($Overlay)"
$apply = Invoke-Tool 'kubectl' @('apply', '-k', $Overlay)
foreach ($line in $apply.Lines) { if ($line.Trim()) { Write-Note $line } }
if ($apply.ExitCode -ne 0) {
    Stop-Script -Message 'kubectl apply falló.' -Hint 'Revisar el render: kubectl kustomize k8s/overlays/local'
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
                    -Hint ("kubectl describe statefulset {0} -n {1}; kubectl get pods -n {1}" -f $d, $Namespace)
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
                    -Hint ("kubectl describe deployment {0} -n {1}; kubectl logs deploy/{0} -n {1} --tail=50" -f $s, $Namespace)
    }
    Write-Ok ('{0,-22} {1}' -f $s, (Format-Duration $sw.Elapsed.TotalSeconds))
}
Complete-Step ('{0}/{0} listos' -f $Apps.Count)

# --- 8. Verificación ---------------------------------------------------------
Start-Step 8 $TotalSteps 'Verificación'
$pods = Invoke-Tool 'kubectl' @('get', 'pods', '-n', $Namespace, '--no-headers')
if ($pods.ExitCode -ne 0) {
    Stop-Script -Message ("No se pudieron listar los pods del namespace '{0}'." -f $Namespace)
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
    Stop-Script -Message ("El namespace '{0}' no tiene pods." -f $Namespace) -Hint ("kubectl get all -n {0}" -f $Namespace)
}
foreach ($p in $notReady) { Write-Warn $p }
if ($notReady.Count -gt 0) {
    Stop-Script -Message ('{0} de {1} pods no están listos.' -f $notReady.Count, $total) `
                -Hint ("kubectl get pods -n {0}; kubectl describe pod <pod> -n {0}" -f $Namespace)
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
