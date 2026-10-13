<#
.SYNOPSIS
    Construye las imágenes Docker de los servicios de La Tiendita.

.DESCRIPTION
    Construye "latiendita/<servicio>:<tag>" para cada servicio de services/.
    Muestra el avance por servicio (tiempo y tamaño) y cierra con un resumen.
    Si algún build falla, continúa con el resto y termina con error al final.

.PARAMETER Tag
    Tag de las imágenes. Por defecto: dev.

.PARAMETER Services
    Subconjunto de servicios a construir. Por defecto: todos.

.PARAMETER NoCache
    Fuerza el build sin caché de capas (--no-cache).

.PARAMETER Pull
    Fuerza la actualización de la imagen base (--pull).

.EXAMPLE
    ./build-images.ps1

.EXAMPLE
    ./build-images.ps1 -Tag dev -Services gateway,auth-service -NoCache
#>
[CmdletBinding()]
param(
    [string]$Tag = 'dev',
    [string[]]$Services = @(),
    [switch]$NoCache,
    [switch]$Pull
)

# Los comandos nativos se validan por $LASTEXITCODE; EAP Stop rompe el
# streaming de docker/kubectl cuando escriben en stderr.
$ErrorActionPreference = 'Continue'
. (Join-Path $PSScriptRoot 'lib/console.ps1')

$AllServices = @(
    'auth-service',
    'user-service',
    'product-service',
    'order-service',
    'payment-service',
    'gateway'
)

Write-Header 'La Tiendita · build de imágenes' @(
    "tag        : $Tag",
    "servicios  : services/"
)

# --- 1. Preflight ------------------------------------------------------------
Start-Step 1 3 'Preflight'
Assert-Tool -Name 'docker' -Hint 'Instalar Docker Desktop y dejarlo corriendo antes de continuar.'

$daemon = Invoke-Tool 'docker' @('info', '--format', '{{.ServerVersion}}')
if ($daemon.ExitCode -ne 0) {
    Stop-Script -Message 'El daemon de Docker no está corriendo.' -Hint 'Iniciar Docker Desktop y reintentar.'
}
$client = Invoke-Tool 'docker' @('version', '--format', '{{.Client.Version}}')
Complete-Step ('Docker {0} (cliente {1})' -f $daemon.Text.Trim(), $client.Text.Trim())

# --- 2. Selección de servicios ----------------------------------------------
Start-Step 2 3 'Selección de servicios'
$selected = if ($Services.Count -gt 0) { @($Services) } else { @($AllServices) }

$unknown = @($selected | Where-Object { $AllServices -notcontains $_ })
if ($unknown.Count -gt 0) {
    Stop-Script -Message ("Servicio(s) desconocido(s): {0}" -f ($unknown -join ', ')) `
                -Hint ("Válidos: {0}" -f ($AllServices -join ', '))
}

# Orden fijo de construcción, independientemente del orden de entrada.
$selected = @($AllServices | Where-Object { $selected -contains $_ })
if ($selected.Count -eq 0) {
    Stop-Script -Message 'No hay servicios para construir.' -Hint 'Revisar el parámetro -Services.'
}
Write-Note ("{0} de {1} servicio(s): {2}" -f $selected.Count, $AllServices.Count, ($selected -join ', '))
Complete-Step ('{0} imagen(es) a construir' -f $selected.Count)

# --- 3. Build ----------------------------------------------------------------
Start-Step 3 3 'Build de imágenes'
$results = New-Object System.Collections.Generic.List[object]
$failed  = New-Object System.Collections.Generic.List[string]

foreach ($s in $selected) {
    $image = 'latiendita/{0}:{1}' -f $s, $Tag
    $dockerArgs = @('build', '-t', $image)
    if ($NoCache) { $dockerArgs += '--no-cache' }
    if ($Pull)    { $dockerArgs += '--pull' }
    $dockerArgs += ('./services/{0}' -f $s)

    Write-Host ''
    Write-Info $image

    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    & docker @dockerArgs
    $code = $LASTEXITCODE
    $sw.Stop()

    if ($code -ne 0) {
        $failed.Add($s)
        Write-Warn ("build falló para '{0}' (exit code {1})" -f $s, $code)
        continue
    }

    $bytes = 0L
    $inspect = Invoke-Tool 'docker' @('image', 'inspect', '-f', '{{.Size}}', $image)
    if ($inspect.ExitCode -eq 0) {
        $parsed = 0L
        if ([long]::TryParse($inspect.Text.Trim(), [ref]$parsed)) { $bytes = $parsed }
    }

    $results.Add([pscustomobject]@{
        Service  = $s
        Image    = $image
        Bytes    = $bytes
        Seconds  = $sw.Elapsed.TotalSeconds
    })
    Write-Ok ('{0} · {1} · {2}' -f $image, (Format-Bytes $bytes), (Format-Duration $sw.Elapsed.TotalSeconds))
}

Write-Host ''
Write-Line ('      {0,-28} {1,10} {2,10}' -f 'Imagen', 'Tamaño', 'Tiempo') DarkGray
Write-Line ('      {0,-28} {1,10} {2,10}' -f ('-' * 28), ('-' * 10), ('-' * 10)) DarkGray
foreach ($r in $results) {
    Write-Line ('      {0,-28} {1,10} {2,10}' -f $r.Image, (Format-Bytes $r.Bytes), (Format-Duration $r.Seconds))
}

if ($failed.Count -gt 0) {
    Stop-Script -Message ("Falló el build de {0} servicio(s): {1}" -f $failed.Count, ($failed -join ', ')) `
                -Hint 'Revisar el log de cada build arriba. -NoCache puede ayudar si la caché está corrupta.'
}

$totalBytes = 0L
if ($results.Count -gt 0) { $totalBytes = [long]($results | Measure-Object -Property Bytes -Sum).Sum }

Write-Summary 'Build finalizado' -Rows @(
    ('imágenes   : {0} con tag {1}' -f $results.Count, $Tag),
    ('tamaño     : {0}' -f (Format-Bytes $totalBytes)),
    ('servicios  : {0}' -f (($results | ForEach-Object { $_.Service }) -join ', '))
)
