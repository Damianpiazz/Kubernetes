<#
.SYNOPSIS
    Consulta el API de productos de La Tiendita a través del gateway.

.DESCRIPTION
    Par nativo de scripts/api-products.sh: mismas consultas, mismas opciones,
    mismos pasos y mismo contrato de códigos de salida.

    Solo lectura: no escribe nada en el clúster ni en la base de datos.

      1. Preflight  GET {base}/actuator/health/readiness
      2. Productos  GET {base}/api/products?page=&size=&sort=
      3. Detalle    GET {base}/api/products/{id}
      4. Categorías GET {base}/api/categories

    Los GET de /api/products y /api/categories son públicos en el gateway
    (SecurityConfig del gateway los deja en permitAll), así que no hace falta
    token. Escribir (POST/PUT/DELETE) sí lo requiere: ver
    docs/kubernetes/comandos.md sección 9.

.PARAMETER Base
    URL base del gateway. Por defecto: http://localhost:8080
    (kubectl port-forward svc/gateway 8080:80 -n latiendita).

.PARAMETER Page
    Página a consultar (0-based). Por defecto: 0.

.PARAMETER Size
    Tamaño de página (1-500). Por defecto: 10.

.PARAMETER Sort
    Orden opcional: <campo>,asc|desc. Campos válidos: productId, title, sku,
    price, quantity, categoryId.

.PARAMETER Id
    productId a consultar en el paso 3. Si se omite, se usa el primero de la
    página devuelta.

.PARAMETER Help
    Muestra esta ayuda.

.EXAMPLE
    ./api-products.ps1

.EXAMPLE
    ./api-products.ps1 -Page 1 -Size 20 -Sort 'price,desc'

.EXAMPLE
    ./api-products.ps1 -Id 3 -Base http://localhost:8080

.NOTES
    Script autocontenido: no dot-sourcea librerías ni invoca a otros scripts
    del repo. No requiere estar en la raíz del repo.

    Códigos de salida (idénticos a api-products.sh):
      1 uso incorrecta / opción inválida
      2 el gateway no responde
      3 GET /api/products falló
      4 GET /api/products/{id} falló
      5 GET /api/categories falló
#>
[CmdletBinding()]
param(
    [string]$Base = 'http://localhost:8080',
    [int]$Page = 0,
    [int]$Size = 10,
    [string]$Sort,
    [string]$Id,
    [Alias('h')]
    [switch]$Help
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

function Show-Usage {
    @'
Uso: scripts/api-products.ps1 [opciones]

Consulta el API de productos de La Tiendita a través del gateway (solo lectura).

Pasos:
  1. Preflight  GET {base}/actuator/health/readiness
  2. Productos  GET {base}/api/products?page=&size=&sort=
  3. Detalle    GET {base}/api/products/{id}
  4. Categorías GET {base}/api/categories

Opciones:
  -Base <url>    URL base del gateway (por defecto: http://localhost:8080)
  -Page <n>      Página a consultar, 0-based (por defecto: 0)
  -Size <n>      Tamaño de página, 1-500 (por defecto: 10)
  -Sort <campo[,asc|desc]>
                 Orden; campos: productId, title, sku, price, quantity, categoryId
  -Id <id>       productId del paso 3 (por defecto: el primero de la página)
  -Help          Esta ayuda

Comportamiento:
  - Solo GET: no escribe en el clúster ni en la base de datos.
  - Los GET de /api/products y /api/categories son públicos en el gateway.
  - El port-forward debe estar activo: kubectl port-forward svc/gateway 8080:80 -n latiendita

Códigos de salida: 1 uso · 2 gateway sin respuesta · 3 products · 4 detalle · 5 categorías
'@ | Write-Host
}

if ($Help) { Show-Usage; exit 0 }

# --- Validación de opciones ---------------------------------------------------
$Base = "$Base".Trim().TrimEnd('/')
if (-not $Base -or $Base -notmatch '^https?://') {
    Show-Usage
    Stop-Script -Message ("-Base debe ser una URL http(s) (recibido: '{0}')." -f $Base) -ExitCode 1
}
if ($Page -lt 0) {
    Stop-Script -Message ('-Page debe ser mayor o igual a 0 (recibido: {0}).' -f $Page) -ExitCode 1
}
if ($Size -lt 1 -or $Size -gt 500) {
    Stop-Script -Message ('-Size debe estar entre 1 y 500 (recibido: {0}).' -f $Size) -ExitCode 1
}
$ValidSort = @('productId', 'title', 'sku', 'price', 'quantity', 'categoryId')
if ("$Sort".Trim()) {
    $Sort = "$Sort".Trim()
    $sortField = ($Sort -split ',')[0]
    if ($ValidSort -notcontains $sortField) {
        Show-Usage
        Stop-Script -Message ("Campo de orden inválido: '{0}'." -f $sortField) `
                    -Hint ('campos válidos: {0}' -f ($ValidSort -join ', ')) -ExitCode 1
    }
} else {
    $Sort = ''
}

$TotalSteps = 4
$SortLabel  = '(default del backend)'
if ($Sort) { $SortLabel = $Sort }
$DetailLabel = '(auto: primer producto de la página)'
if ($Id) { $DetailLabel = "$Id" }

Write-Header 'La Tiendita · API de productos (solo lectura)' @(
    "gateway    : $Base",
    "página     : $Page · tamaño $Size",
    "orden      : $SortLabel",
    "detalle    : $DetailLabel"
)

# --- HTTP ---------------------------------------------------------------------
function Invoke-ApiGet {
    # Devuelve @{ Ok; Status; Body; Ms }. Status 0 = sin respuesta (red/puerto).
    param([string]$Url)
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $resp = Invoke-WebRequest -Uri $Url -Method Get -UseBasicParsing -TimeoutSec 30
        $sw.Stop()
        # PS 5.1 puede devolver Content como byte[]; sin esto el body se
        # imprime como "123 34 115 ..." (decimales separados por espacio).
        $content = $resp.Content
        if ($content -is [byte[]]) { $content = [System.Text.Encoding]::UTF8.GetString($content) }
        return [pscustomobject]@{
            Ok     = $true
            Status = [int]$resp.StatusCode
            Body   = [string]$content
            Ms     = $sw.ElapsedMilliseconds
        }
    } catch {
        $sw.Stop()
        $status = 0
        $body = ''
        $ex = $_.Exception
        while ($null -ne $ex) {
            if ($ex.Response -is [System.Net.WebResponse]) {
                $status = [int]$ex.Response.StatusCode
                try {
                    $stream = $ex.Response.GetResponseStream()
                    if ($null -ne $stream) {
                        $reader = New-Object System.IO.StreamReader($stream)
                        $body = $reader.ReadToEnd()
                        $reader.Dispose()
                    }
                } catch { }
                break
            }
            $ex = $ex.InnerException
        }
        return [pscustomobject]@{
            Ok     = $false
            Status = $status
            Body   = $body
            Ms     = $sw.ElapsedMilliseconds
        }
    }
}

function Format-Body {
    param([string]$Body)
    if (-not "$Body".Trim()) { return '(sin cuerpo)' }
    try { return ($Body | ConvertFrom-Json | ConvertTo-Json -Depth 10) } catch { return $Body }
}

function Write-Body {
    param([string]$Body)
    foreach ($line in ((Format-Body $Body) -split "`r?`n")) { Write-Note $line }
}

function Get-ResponseLabel {
    param($Response)
    if ($Response.Status) { return ('HTTP {0}' -f $Response.Status) }
    return 'sin respuesta'
}

# --- 1. Preflight -------------------------------------------------------------
Start-Step 1 $TotalSteps 'Preflight (gateway)'
$health = Invoke-ApiGet -Url "$Base/actuator/health/readiness"
if (-not $health.Ok -or $health.Status -ne 200) {
    Stop-Script -Message ("El gateway no responde en {0} ({1})." -f $Base, (Get-ResponseLabel $health)) `
                -Hint 'Levantar el port-forward: kubectl port-forward svc/gateway 8080:80 -n latiendita' -ExitCode 2
}
Write-Note ("GET /actuator/health/readiness  {0}  {1} ms" -f $health.Status, $health.Ms)
Write-Body $health.Body
Complete-Step 'gateway accesible'

# --- 2. Productos -------------------------------------------------------------
Start-Step 2 $TotalSteps 'Productos'
$productsUrl = '{0}/api/products?page={1}&size={2}' -f $Base, $Page, $Size
if ($Sort) { $productsUrl += '&sort={0}' -f [uri]::EscapeDataString($Sort) }
$products = Invoke-ApiGet -Url $productsUrl
if (-not $products.Ok -or $products.Status -ne 200) {
    Stop-Script -Message ("GET /api/products falló ({0})." -f (Get-ResponseLabel $products)) `
                -Hint 'Verificar que product-service esté listo: kubectl get pods -n latiendita' -ExitCode 3
}
Write-Note ("GET {0}  {1}  {2} ms" -f $productsUrl, $products.Status, $products.Ms)
Write-Body $products.Body

$ProductsPage = $null
try { $ProductsPage = $products.Body | ConvertFrom-Json } catch { }
$TotalElements = $null
$TotalPages    = $null
$FirstId       = $null
if ($null -ne $ProductsPage) {
    if ($ProductsPage.PSObject.Properties['totalElements']) { $TotalElements = $ProductsPage.totalElements }
    if ($ProductsPage.PSObject.Properties['totalPages'])    { $TotalPages    = $ProductsPage.totalPages }
    $content = @($ProductsPage.content)
    if ($content.Count -gt 0 -and $content[0].PSObject.Properties['productId']) {
        $FirstId = $content[0].productId
    }
}
if ($null -ne $TotalElements) {
    Write-Note ('totalElements={0} · totalPages={1} · elementos en esta página={2}' -f $TotalElements, $TotalPages, @($ProductsPage.content).Count)
}
Complete-Step ('{0} elemento(s) en la página' -f @($ProductsPage.content).Count)

# --- 3. Detalle ---------------------------------------------------------------
Start-Step 3 $TotalSteps 'Detalle de producto'
$DetailId = $Id
if (-not $DetailId) { $DetailId = $FirstId }
if (-not "$DetailId") {
    Write-Note 'sin id para el detalle (página vacía o respuesta sin productId); pasar -Id <productId>'
    Complete-Step 'omitido'
} else {
    $detailUrl = '{0}/api/products/{1}' -f $Base, $DetailId
    $detail = Invoke-ApiGet -Url $detailUrl
    if (-not $detail.Ok -or $detail.Status -ne 200) {
        Stop-Script -Message ("GET /api/products/{0} falló ({1})." -f $DetailId, (Get-ResponseLabel $detail)) `
                    -Hint 'Verificar que el id exista (paso 2) o que product-service esté listo.' -ExitCode 4
    }
    Write-Note ("GET {0}  {1}  {2} ms" -f $detailUrl, $detail.Status, $detail.Ms)
    Write-Body $detail.Body
    Complete-Step ('producto {0} encontrado' -f $DetailId)
}

# --- 4. Categorías ------------------------------------------------------------
Start-Step 4 $TotalSteps 'Categorías'
$categoriesUrl = "$Base/api/categories"
$categories = Invoke-ApiGet -Url $categoriesUrl
if (-not $categories.Ok -or $categories.Status -ne 200) {
    Stop-Script -Message ("GET /api/categories falló ({0})." -f (Get-ResponseLabel $categories)) `
                -Hint 'Verificar que product-service esté listo: kubectl get pods -n latiendita' -ExitCode 5
}
Write-Note ("GET {0}  {1}  {2} ms" -f $categoriesUrl, $categories.Status, $categories.Ms)
Write-Body $categories.Body

$CategoriesList = @()
try { $CategoriesList = @($categories.Body | ConvertFrom-Json) } catch { }
Complete-Step ('{0} categoría(s)' -f $CategoriesList.Count)

# --- Resumen ------------------------------------------------------------------
$TotalMs = $health.Ms + $products.Ms + $categories.Ms
$DetailMs = 0
if ($DetailId -and $null -ne $detail) { $DetailMs = $detail.Ms }
$TotalMs += $DetailMs

$ElementsRow = 'no disponible'
if ($null -ne $TotalElements) { $ElementsRow = "$TotalElements" }

Write-Summary 'Consulta del API de productos' -Rows @(
    ('gateway    : {0}' -f $Base),
    ('página     : {0} · {1} elemento(s) en total' -f $Page, $ElementsRow),
    ('peticiones : 4/4 OK · {0}' -f (Format-Duration ($TotalMs / 1000.0)))
) -Next @(
    'kubectl port-forward svc/product-service 8082:80 -n latiendita   (directo, sin gateway)',
    'docs/kubernetes/comandos.md  sección 6 y 9 (endpoints y escritura con JWT)'
)
