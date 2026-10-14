<#
.SYNOPSIS
    Construye las imágenes Docker de los servicios de La Tiendita.

.DESCRIPTION
    Construye "latiendita/<servicio>:<tag>" para cada servicio de services/.
    Muestra el avance por servicio (tiempo y tamaño) y cierra con un resumen.
    Si algún build falla, continúa con el resto y termina con error al final.

    Par nativo de scripts/build-images.sh: mismos objetivos, mismos pasos y
    mismo contrato de códigos de salida.

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

.NOTES
    Script autocontenido: no dot-sourcea librerías ni invoca a otros scripts
    del repo. Correr desde la raíz del repo.

    Códigos de salida (idénticos a build-images.sh):
      1 uso incorrecta / servicio desconocido / repo sin services/
      2 docker ausente o daemon de Docker caído
      3 falló el build de al menos un servicio
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

$AllServices = @(
    'auth-service',
    'user-service',
    'product-service',
    'order-service',
    'payment-service',
    'gateway'
)

if (-not (Test-Path 'services')) {
    Stop-Script -Message 'No se encontró el directorio services/: correr desde la raíz del repo.' -ExitCode 1
}

Write-Header 'La Tiendita · build de imágenes' @(
    "tag        : $Tag",
    "servicios  : services/"
)

# --- 1. Preflight ------------------------------------------------------------
Start-Step 1 3 'Preflight'
Assert-Tool -Name 'docker' -Hint 'Instalar Docker Desktop y dejarlo corriendo antes de continuar.'

$daemon = Invoke-Tool 'docker' @('info', '--format', '{{.ServerVersion}}')
if ($daemon.ExitCode -ne 0) {
    Stop-Script -Message 'El daemon de Docker no está corriendo.' -Hint 'Iniciar Docker Desktop y reintentar.' -ExitCode 2
}
$client = Invoke-Tool 'docker' @('version', '--format', '{{.Client.Version}}')
Complete-Step ('Docker {0} (cliente {1})' -f $daemon.Text.Trim(), $client.Text.Trim())

# --- 2. Selección de servicios ----------------------------------------------
Start-Step 2 3 'Selección de servicios'
$selected = if ($Services.Count -gt 0) { @($Services) } else { @($AllServices) }

$unknown = @($selected | Where-Object { $AllServices -notcontains $_ })
if ($unknown.Count -gt 0) {
    Stop-Script -Message ("Servicio(s) desconocido(s): {0}" -f ($unknown -join ', ')) `
                -Hint ("Válidos: {0}" -f ($AllServices -join ', ')) -ExitCode 1
}

# Orden fijo de construcción, independientemente del orden de entrada.
$selected = @($AllServices | Where-Object { $selected -contains $_ })
if ($selected.Count -eq 0) {
    Stop-Script -Message 'No hay servicios para construir.' -Hint 'Revisar el parámetro -Services.' -ExitCode 1
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
if ($results.Count -gt 0) {
    Write-Line ('      {0,-28} {1,10} {2,10}' -f 'Imagen', 'Tamaño', 'Tiempo') DarkGray
    Write-Line ('      {0,-28} {1,10} {2,10}' -f ('-' * 28), ('-' * 10), ('-' * 10)) DarkGray
    foreach ($r in $results) {
        Write-Line ('      {0,-28} {1,10} {2,10}' -f $r.Image, (Format-Bytes $r.Bytes), (Format-Duration $r.Seconds))
    }
}

if ($failed.Count -gt 0) {
    Stop-Script -Message ("Falló el build de {0} servicio(s): {1}" -f $failed.Count, ($failed -join ', ')) `
                -Hint 'Revisar el log de cada build arriba. -NoCache puede ayudar si la caché está corrupta.' -ExitCode 3
}

$totalBytes = 0L
if ($results.Count -gt 0) { $totalBytes = [long]($results | Measure-Object -Property Bytes -Sum).Sum }

Write-Summary 'Build finalizado' -Rows @(
    ('imágenes   : {0} con tag {1}' -f $results.Count, $Tag),
    ('tamaño     : {0}' -f (Format-Bytes $totalBytes)),
    ('servicios  : {0}' -f (($results | ForEach-Object { $_.Service }) -join ', '))
)
