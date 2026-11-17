# Generic elevation shim used by the per-user NSIS installer/uninstaller:
# runs a sibling .ps1 elevated (one UAC prompt) and propagates its exit code.
#   powershell -File elevate.ps1 -Target install-driver.ps1
param([Parameter(Mandatory = $true)][string]$Target)
$script = Join-Path $PSScriptRoot $Target
if (-not (Test-Path $script)) { Write-Host "elevate: target not found: $script"; exit 20 }
try {
    $p = Start-Process powershell -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$script`"") -Verb RunAs -Wait -PassThru
    exit $p.ExitCode
} catch {
    Write-Host "elevate: UAC declined or failed: $($_.Exception.Message)"
    exit 21
}
