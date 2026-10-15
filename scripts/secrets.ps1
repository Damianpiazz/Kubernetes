<#
.SYNOPSIS
    Genera los archivos .env / secrets.env del repo desde sus plantillas .example.

.DESCRIPTION
    Par nativo de scripts/secrets.sh: mismos destinos, mismas opciones, mismos
    pasos y mismo contrato de códigos de salida.

      compose        .env.example                                                  -> .env
      local          k8s/apps/environments/local/secrets.env.example               -> secrets.env
      observability-local  k8s/observability/environments/local/secrets.env.example -> secrets.env
      apps           k8s/apps/environments/production/secrets.env.example          -> secrets.env
      observability  k8s/observability/environments/production/secrets.env.example -> secrets.env

    Reglas:
      - Nunca se pisa un archivo que ya existe, salvo -Force (misma regla que kind-up).
      - Se parte del .example: comentarios, orden y claves no secretas se conservan;
        solo se generan valores para claves secretas (JWT_SECRET, *_DB_PASSWORD,
        *_ADMIN_PASSWORD).
      - Las claves que la plantilla deja vacías (Mercado Pago) quedan vacías.
      - Con -Check no se escribe nada: reporta faltantes y valores de plantilla.

.PARAMETER Target
    Qué destinos generar. Por defecto: todo.
    Valores: todo, compose, local, observability-local, production, apps, observability.

.PARAMETER Force
    Regenerar también los destinos que ya existen.

.PARAMETER Check
    Solo verifica; no escribe nada (sale con código 4 si hay faltantes o
    valores de plantilla).

.PARAMETER Help
    Muestra esta ayuda.

.EXAMPLE
    ./secrets.ps1

.EXAMPLE
    ./secrets.ps1 -Target production -Force

.EXAMPLE
    ./secrets.ps1 -Check

.NOTES
    Script autocontenido: no dot-sourcea librerías ni invoca a otros scripts
    del repo. Correr desde la raíz del repo.

    Los archivos generados son gitignored: no se commitean.

    Códigos de salida (idénticos a secrets.sh):
      1 uso incorrecta / opción o target desconocido
      2 falta una plantilla .example
      3 no se pudo escribir el destino
      4 -Check encontró destinos faltantes o con valores de plantilla
#>
[CmdletBinding()]
param(
    [string]$Target = 'todo',
    [switch]$Force,
    [switch]$Check,
    [Alias('h')]
    [switch]$Help
)

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
Uso: scripts/secrets.ps1 [opciones]

Genera los archivos .env / secrets.env del repo desde sus plantillas .example,
con valores aleatorios fuertes para las claves secretas.

Destinos:
  compose        .env.example  -> .env                                     (Docker Compose)
  local          k8s/apps/environments/local/secrets.env.example           -> secrets.env
  observability-local  k8s/observability/environments/local/secrets.env.example -> secrets.env
  apps           k8s/apps/environments/production/secrets.env.example      -> secrets.env
  observability  k8s/observability/environments/production/secrets.env...  -> secrets.env

Opciones:
  -Target <id>   Qué destinos generar (por defecto: todo)
                 Valores: todo, compose, local, observability-local, production,
                 apps, observability
  -Force         Regenerar también los destinos que ya existen
  -Check         Solo verifica; no escribe nada (salta con código 4 si hay
                 faltantes o valores de plantilla)
  -Help          Esta ayuda

Comportamiento:
  - Nunca se pisa un archivo que ya existe (salvo -Force).
  - Se conservan comentarios y claves no secretas de la plantilla; se generan
    valores solo para JWT_SECRET, *_DB_PASSWORD y *_ADMIN_PASSWORD.
  - Las claves vacías en la plantilla (Mercado Pago) quedan vacías.
  - Los archivos generados son gitignored: no se commitean.

Códigos de salida: 1 uso · 2 falta una plantilla · 3 escritura falló · 4 -Check con problemas
'@ | Write-Host
}

if ($Help) { Show-Usage; exit 0 }

# --- Destinos ----------------------------------------------------------------
# id | plantilla | destino
$Targets = @(
    [pscustomobject]@{ Id = 'compose';              Template = '.env.example';                                                  Dest = '.env' }
    [pscustomobject]@{ Id = 'local';                Template = 'k8s/apps/environments/local/secrets.env.example';               Dest = 'k8s/apps/environments/local/secrets.env' }
    [pscustomobject]@{ Id = 'observability-local';  Template = 'k8s/observability/environments/local/secrets.env.example';      Dest = 'k8s/observability/environments/local/secrets.env' }
    [pscustomobject]@{ Id = 'apps';                 Template = 'k8s/apps/environments/production/secrets.env.example';          Dest = 'k8s/apps/environments/production/secrets.env' }
    [pscustomobject]@{ Id = 'observability';        Template = 'k8s/observability/environments/production/secrets.env.example'; Dest = 'k8s/observability/environments/production/secrets.env' }
)

# --- Generación --------------------------------------------------------------
function New-RandomToken {
    # Token base64url sin padding (sin +, / ni =): seguro para cualquier parser .env.
    param([int]$Bytes = 48)
    $buf = New-Object byte[] $Bytes
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($buf) } finally { $rng.Dispose() }
    return ([Convert]::ToBase64String($buf).TrimEnd('=').Replace('+', '-').Replace('/', '_'))
}

function New-RandomPassword {
    # Caracteres [A-Za-z0-9] solos: sin signos, seguro para JDBC URLs.
    param([int]$Length = 24)
    $abc = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789'
    $buf = New-Object byte[] $Length
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($buf) } finally { $rng.Dispose() }
    $sb = New-Object System.Text.StringBuilder
    foreach ($b in $buf) { [void]$sb.Append($abc[$b % $abc.Length]) }
    return $sb.ToString()
}

function Test-SecretKey {
    # Las claves cuyo valor se genera; el resto se copia tal cual de la plantilla.
    param([string]$Key)
    if ($Key -eq 'JWT_SECRET') { return $true }
    if ($Key -like '*_DB_PASSWORD') { return $true }
    if ($Key -like '*_ADMIN_PASSWORD') { return $true }
    return $false
}

function Test-TemplateValue {
    # Marca de plantilla que deploy.* rechaza en targets de producción.
    param([string]$Value)
    $lc = $Value.ToLowerInvariant()
    return ($lc.Contains('changeme') -or $lc.Contains('change-me') -or $lc.Contains('dev-only'))
}

function Generate-EnvFile {
    # Devuelve @{ Secrets = n; Empty = n }. Lanza si no se pudo escribir.
    param([string]$Template, [string]$Destination)
    $lines = @(Get-Content -LiteralPath $Template -Encoding UTF8)
    $out = New-Object System.Collections.Generic.List[string]
    $secrets = 0
    $empty = 0
    $re = '^[ \t]*(export[ \t]+)?([A-Za-z_][A-Za-z0-9_]*)=(.*)$'
    foreach ($line in $lines) {
        if ($line -match $re) {
            $kw = $Matches[1]; $key = $Matches[2]; $val = $Matches[3]
            if (Test-SecretKey $key) {
                if ($key -eq 'JWT_SECRET') { $val = New-RandomToken -Bytes 48 }
                else { $val = New-RandomPassword -Length 24 }
                $secrets++
            } elseif ($val -eq '') {
                $empty++
            }
            $out.Add(('{0}{1}={2}' -f $kw, $key, $val))
        } else {
            $out.Add($line)
        }
    }
    # UTF-8 sin BOM, finales de línea LF: igual que los .example y que el .sh.
    $enc = New-Object System.Text.UTF8Encoding($false)
    $full = [System.IO.Path]::GetFullPath($Destination)
    [System.IO.File]::WriteAllText($full, (($out -join "`n") + "`n"), $enc)
    return @{ Secrets = $secrets; Empty = $empty }
}

function Get-EnvIssues {
    # Valores de plantilla y claves vacías de un archivo .env / secrets.env.
    param([string]$Path)
    $weak = New-Object System.Collections.Generic.List[string]
    $empty = New-Object System.Collections.Generic.List[string]
    $re = '^[ \t]*(export[ \t]+)?([A-Za-z_][A-Za-z0-9_]*)=(.*)$'
    foreach ($line in @(Get-Content -LiteralPath $Path -Encoding UTF8)) {
        if ($line -match $re) {
            $key = $Matches[2]; $val = $Matches[3]
            if ($val -eq '') { $empty.Add($key) }
            elseif (Test-TemplateValue $val) { $weak.Add($key) }
        }
    }
    [pscustomobject]@{ Weak = @($weak); Empty = @($empty) }
}

# --- Opciones ----------------------------------------------------------------
switch ($Target) {
    'todo'         { $SelectedIds = @('compose', 'local', 'observability-local', 'apps', 'observability') }
    'production'   { $SelectedIds = @('apps', 'observability') }
    'compose'      { $SelectedIds = @('compose') }
    'local'        { $SelectedIds = @('local') }
    'observability-local'{ $SelectedIds = @('observability-local') }
    'apps'         { $SelectedIds = @('apps') }
    'observability'{ $SelectedIds = @('observability') }
    default {
        Show-Usage
        Stop-Script -Message "Target desconocido: $Target" `
                    -Hint 'Targets válidos: todo, compose, local, observability-local, production, apps, observability' -ExitCode 1
    }
}

$TotalSteps = 3
if ($Check) {
    $Mode = 'verificación (-Check)'
    $WorkStep = 'Verificación (-Check)'
} else {
    $Mode = 'generar'
    if ($Force) { $Mode = 'generar (-Force)' }
    $WorkStep = 'Generación'
}

Write-Header 'La Tiendita · generación de secretos' @(
    "modo       : $Mode",
    "targets    : $($SelectedIds -join ' ')"
)

# --- 1. Plantillas -----------------------------------------------------------
Start-Step 1 $TotalSteps 'Plantillas'
foreach ($id in $SelectedIds) {
    $t = ($Targets | Where-Object { $_.Id -eq $id })
    if (-not (Test-Path -LiteralPath $t.Template)) {
        Stop-Script -Message "No existe la plantilla '$($t.Template)'." `
                    -Hint "Correr desde la raíz del repo (falta $($t.Template))." -ExitCode 2
    }
    Write-Ok $t.Template
}
Complete-Step ('{0} plantilla(s) encontrada(s)' -f $SelectedIds.Count)

# --- 2. Destinos -------------------------------------------------------------
Start-Step 2 $TotalSteps 'Destinos'
$missingCount = 0
foreach ($id in $SelectedIds) {
    $t = ($Targets | Where-Object { $_.Id -eq $id })
    if (Test-Path -LiteralPath $t.Dest) {
        Write-Ok ('{0,-14} {1}' -f $id, $t.Dest)
    } else {
        $missingCount++
        Write-Info ('{0,-14} {1} (falta)' -f $id, $t.Dest)
    }
}
Complete-Step ('{0} destino(s) sin generar' -f $missingCount)

# --- 3. Generación / verificación --------------------------------------------
Start-Step 3 $TotalSteps $WorkStep
$generated = 0
$omitted = 0
$weakTotal = 0
$checkMissing = 0

foreach ($id in $SelectedIds) {
    $t = ($Targets | Where-Object { $_.Id -eq $id })

    if ($Check) {
        if (-not (Test-Path -LiteralPath $t.Dest)) {
            $checkMissing++
            Write-Warn ("{0}: falta {1} (generar con: .\scripts\secrets.ps1 -Target {0})" -f $id, $t.Dest)
            continue
        }
        $issues = Get-EnvIssues -Path $t.Dest
        if ($issues.Weak.Count -gt 0) {
            $weakTotal += $issues.Weak.Count
            Write-Warn ("{0}: valores de plantilla en {1} -> {2}" -f $id, $t.Dest, ($issues.Weak -join ', '))
        } else {
            Write-Ok ("{0}: {1} sin valores de plantilla" -f $id, $t.Dest)
        }
        if ($issues.Empty.Count -gt 0) {
            Write-Note ("{0}: claves vacías (features opcionales deshabilitadas): {1}" -f $id, ($issues.Empty -join ', '))
        }
        continue
    }

    if ((Test-Path -LiteralPath $t.Dest) -and -not $Force) {
        Write-Warn ("{0}: ya existe {1}, se omite (usar -Force para regenerar)" -f $id, $t.Dest)
        $omitted++
        continue
    }

    try {
        $stats = Generate-EnvFile -Template $t.Template -Destination $t.Dest
    } catch {
        Stop-Script -Message "No se pudo escribir '$($t.Dest)'." -Hint 'Verificar permisos del directorio.' -ExitCode 3
    }
    $issues = Get-EnvIssues -Path $t.Dest
    if ($issues.Weak.Count -gt 0) {
        Stop-Script -Message ("El archivo generado '{0}' quedó con valores de plantilla: {1}" -f $t.Dest, ($issues.Weak -join ', ')) -ExitCode 3
    }
    $generated++
    Write-Ok ("{0}: {1} · {2} secreto(s) generados" -f $id, $t.Dest, $stats.Secrets)
    if ($stats.Empty -gt 0) {
        Write-Note ("{0}: {1} clave(s) vacía(s) conservadas (features opcionales)" -f $id, $stats.Empty)
    }
}

if ($Check) {
    if ($checkMissing -gt 0 -or $weakTotal -gt 0) {
        Complete-Step ('{0} destino(s) faltantes · {1} valor(es) de plantilla' -f $checkMissing, $weakTotal)
        Stop-Script -Message 'Hay destinos sin generar o con valores de plantilla.' `
                    -Hint 'Generarlos con: .\scripts\secrets.ps1 -Target todo' -ExitCode 4
    }
    Complete-Step 'todos los destinos generados y sin valores de plantilla'
} else {
    Complete-Step ('{0} generado(s) · {1} omitido(s)' -f $generated, $omitted)
}

Write-Summary 'Secretos del repo' -Rows @(
    "modo        : $Mode",
    "targets     : $($SelectedIds -join ' ')",
    "resultados  : $generated generado(s), $omitted omitido(s), $checkMissing faltante(s)",
    'nota        : los archivos generados son gitignored, no se commitean'
) -Next @(
    '.\scripts\kind-up.ps1   para levantar el entorno local'
    '.\scripts\deploy.ps1 -s k8s/apps/environments/production/secrets.env k8s/apps/environments/production'
)
