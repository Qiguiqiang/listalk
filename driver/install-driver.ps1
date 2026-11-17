# SoundRelay virtual audio driver — privileged steps (test-signed route A).
# Invoked ELEVATED (via elevate.ps1 / Start-Process -Verb RunAs) by the main
# per-user NSIS installer, which cannot touch HKLM / cert stores / bcdedit
# itself. Every step checks its exit code; any failure exits non-zero with a
# clear message and a log line — never silent (INTEGRATION-IN-SETUP.md §3).
# Steps: import test cert (Root + TrustedPublisher) -> testsigning on ->
# write HKLM RunOnce so driver\一键安装.bat resumes the install after reboot.
$ErrorActionPreference = 'Stop'
$dir = $PSScriptRoot
$log = Join-Path $dir 'install-driver.log'
function Log($m) { $line = "[{0}] {1}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $m; Add-Content -Path $log -Value $line -Encoding UTF8; Write-Host $line }
function Fail($code, $what) { Log "FAILED: $what"; Write-Host "SoundRelay driver setup failed: $what" -ForegroundColor Red; exit $code }

$cer = Join-Path $dir 'driver-test-cert.cer'
if (-not (Test-Path $cer)) { Fail 10 "certificate file missing: $cer" }
Log "driver dir: $dir"

try {
    Log 'Step 1/3: importing test certificate into Root store...'
    & certutil -addstore -f Root $cer | Out-Null
    if ($LASTEXITCODE -ne 0) { Fail 11 "certutil -addstore Root failed (exit $LASTEXITCODE)" }
    Log 'Step 1/3: importing test certificate into TrustedPublisher store...'
    & certutil -addstore -f TrustedPublisher $cer | Out-Null
    if ($LASTEXITCODE -ne 0) { Fail 12 "certutil -addstore TrustedPublisher failed (exit $LASTEXITCODE)" }
} catch { Fail 11 "certificate import error: $($_.Exception.Message)" }

try {
    Log 'Step 2/3: turning Windows Test Mode ON (bcdedit /set testsigning on)...'
    & bcdedit /set testsigning on | Out-Null
    if ($LASTEXITCODE -ne 0) { Fail 13 "bcdedit /set testsigning on failed (exit $LASTEXITCODE) - Secure Boot may still be ON" }
} catch { Fail 13 "bcdedit error: $($_.Exception.Message)" }

try {
    Log 'Step 3/3: registering post-reboot resume (HKLM RunOnce AirBridgeDriverSetup)...'
    $bat = Join-Path $dir '一键安装.bat'
    & reg add 'HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\RunOnce' /v AirBridgeDriverSetup /t REG_SZ /d "`"$bat`"" /f | Out-Null
    if ($LASTEXITCODE -ne 0) { Fail 14 "writing HKLM RunOnce failed (exit $LASTEXITCODE)" }
} catch { Fail 14 "RunOnce registration error: $($_.Exception.Message)" }

Log 'All privileged steps done. A reboot is required; setup resumes automatically after logon.'
exit 0
