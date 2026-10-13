# Console formatting helpers shared by the La Tiendita scripts.
# Dot-source before using any helper:
#   . (Join-Path $PSScriptRoot 'lib/console.ps1')
#
# Honors NO_COLOR and TERM=dumb. Colors go through Write-Host
# -ForegroundColor so the scripts stay compatible with Windows
# PowerShell 5.1 (no VT/ANSI dependency). Files must be saved as
# UTF-8 with BOM, otherwise PS 5.1 mangles the symbols and accents.

$script:UseColor = -not [bool]$env:NO_COLOR
if ($env:TERM -eq 'dumb') { $script:UseColor = $false }

# Sin esto, PowerShell 5.1 escribe la salida redirigida con la codepage de la
# consola (OEM) y los símbolos/acentos quedan como '?' en pipes y logs de CI.
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

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
    param([string]$Name, [string]$Hint)
    if (Test-Command $Name) { return }
    if (-not $Hint) { $Hint = "Instalar '$Name' y reintentar." }
    Stop-Script -Message "Falta la herramienta '$Name'." -Hint $Hint
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
    try { $Host.UI.WriteErrorLine($text) } catch { Write-Host $text -ForegroundColor Red }
    if ($Hint) { Write-Host "         → $Hint" -ForegroundColor Yellow }
    if ($null -ne $script:ScriptWatch) {
        $script:ScriptWatch.Stop()
        Write-Host ('  Tiempo total: {0}' -f (Format-Duration $script:ScriptWatch.Elapsed.TotalSeconds)) -ForegroundColor DarkGray
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
