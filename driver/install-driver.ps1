# SoundRelay virtual audio driver — privileged steps (test-signed route A).
# Invoked ELEVATED (via elevate.ps1 / Start-Process -Verb RunAs) by the main
# per-user NSIS installer, which cannot touch HKLM / cert stores / bcdedit
# itself. Every step checks its exit code; any failure exits non-zero with a
# clear message and a log line — never silent (INTEGRATION-IN-SETUP.md §3).
# Steps: Secure Boot pre-check -> import test cert (Root + TrustedPublisher)
# -> testsigning on -> write HKLM RunOnce so driver\一键安装.bat resumes the
# install after reboot.
# Exit codes: 10 missing cert file · 11/12 cert import · 13 testsigning ·
# 14 RunOnce · 42 Secure Boot is ON (installer shows the BIOS guidance page
# instead of a raw failure — same code as the installer's own pre-check).
$ErrorActionPreference = 'Stop'
$dir = $PSScriptRoot
$log = Join-Path $dir 'install-driver.log'
function Log($m) { $line = "[{0}] {1}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $m; Add-Content -Path $log -Value $line -Encoding UTF8; Write-Host $line }
function Fail($code, $what) { Log "FAILED: $what"; Write-Host "SoundRelay driver setup failed: $what" -ForegroundColor Red; exit $code }
# Run a native tool, keep its FULL output in the log (v2.2.1: previously the
# output was discarded with | Out-Null, so a failed step left no evidence),
# and return the tool's exit code.
function RunLogged($file, [string[]]$arguments) {
    Log ">> $file $($arguments -join ' ')"
    # PS 5.1 turns a native command's stderr into a terminating error while
    # $ErrorActionPreference is 'Stop' — relax it just for the call so the
    # exit code (our real success signal) survives to be checked.
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $out = & $file @arguments 2>&1 | Out-String } finally { $ErrorActionPreference = $prev }
    foreach ($l in ($out -split "`r?`n")) { if ($l.Trim() -ne '') { Log "   $l" } }
    return $LASTEXITCODE
}

$cer = Join-Path $dir 'driver-test-cert.cer'
if (-not (Test-Path $cer)) { Fail 10 "certificate file missing: $cer" }
Log "driver dir: $dir"

# --- Step 0/3: Secure Boot pre-check -------------------------------------
# FB-1 (v2.2 field report): the machine had Secure Boot ON; the installer's
# own pre-check ran Confirm-SecureBootUEFI UNelevated, where that cmdlet
# throws "access denied", the error was swallowed as "not enabled", and the
# install ran on into a bare bcdedit failure (old exit 13 with no guidance).
# Detect it HERE, elevated, before touching anything: registry state first
# (HKLM SecureBoot\State\UEFISecureBootEnabled — readable and reliable),
# Confirm-SecureBootUEFI as fallback. Enabled -> exit 42 with plain-language
# guidance in the log; the installer turns 42 into a "how to disable Secure
# Boot" message, not a failure code.
$sb = $null
try {
    $v = Get-ItemPropertyValue -Path 'HKLM:\SYSTEM\CurrentControlSet\Control\SecureBoot\State' -Name 'UEFISecureBootEnabled' -ErrorAction Stop
    $sb = ($v -eq 1)
    Log "Secure Boot state (registry UEFISecureBootEnabled=$v): $(if ($sb) { 'ENABLED' } else { 'disabled' })"
} catch {
    Log "Secure Boot registry state unreadable ($($_.Exception.Message)); falling back to Confirm-SecureBootUEFI..."
    try { $sb = [bool](Confirm-SecureBootUEFI); Log "Secure Boot state (Confirm-SecureBootUEFI): $sb" }
    catch { Log "Secure Boot state unknown (legacy BIOS?): $($_.Exception.Message) — continuing." }
}
if ($sb -eq $true) {
    Log 'Secure Boot is ON: Windows refuses to enable test signing, so the driver cannot be installed yet.'
    Log 'What to do: restart the PC, enter BIOS/UEFI setup (usually Del / F2 / F12 / Esc during power-on),'
    Log 'find "Secure Boot" (Security or Boot tab), set it to Disabled, save & exit (usually F10),'
    Log 'then run this installer again with the driver component selected.'
    Write-Host 'Secure Boot is ON. Disable it in BIOS/UEFI setup, then run the installer again.' -ForegroundColor Yellow
    exit 42
}

try {
    Log 'Step 1/3: importing test certificate into Root store...'
    if ((RunLogged 'certutil' @('-addstore', '-f', 'Root', $cer)) -ne 0) { Fail 11 "certutil -addstore Root failed (see log above)" }
    Log 'Step 1/3: importing test certificate into TrustedPublisher store...'
    if ((RunLogged 'certutil' @('-addstore', '-f', 'TrustedPublisher', $cer)) -ne 0) { Fail 12 "certutil -addstore TrustedPublisher failed (see log above)" }
} catch { Fail 11 "certificate import error: $($_.Exception.Message)" }

try {
    Log 'Step 2/3: turning Windows Test Mode ON (bcdedit /set testsigning on)...'
    if ((RunLogged 'bcdedit' @('/set', 'testsigning', 'on')) -ne 0) {
        Log 'bcdedit refused to enable test signing. Most common causes, in order:'
        Log '  1) Secure Boot is still ON (double-check in BIOS/UEFI — some machines re-enable it after a BIOS update).'
        Log '  2) This bcdedit output above names a policy/lock (e.g. an organization-managed PC).'
        Log 'Retry: disable Secure Boot, reboot, then re-run the installer and select only the driver component.'
        Fail 13 'bcdedit /set testsigning on failed — see the full bcdedit output and guidance in this log'
    }
} catch { Fail 13 "bcdedit error: $($_.Exception.Message)" }

try {
    Log 'Step 3/3: registering post-reboot resume (HKLM RunOnce AirBridgeDriverSetup)...'
    $bat = Join-Path $dir '一键安装.bat'
    if ((RunLogged 'reg' @('add', 'HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\RunOnce', '/v', 'AirBridgeDriverSetup', '/t', 'REG_SZ', '/d', "`"$bat`"", '/f')) -ne 0) { Fail 14 "writing HKLM RunOnce failed (see log above)" }
} catch { Fail 14 "RunOnce registration error: $($_.Exception.Message)" }

Log 'All privileged steps done. A reboot is required; setup resumes automatically after logon.'
exit 0
