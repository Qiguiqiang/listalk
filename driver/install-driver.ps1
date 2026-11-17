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
$errFile = Join-Path $dir 'install-driver-error.txt'
$script:LastToolOutput = ''
function Log($m) { $line = "[{0}] {1}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $m; Add-Content -Path $log -Value $line -Encoding UTF8; Write-Host $line }
function Fail($code, $what) {
    Log "FAILED: $what"
    # Hand the failing step's own words to the installer's failure dialog
    # (installer-v2.2.nsi reads $errFile): the step description plus the
    # last lines of the most recent tool output — or, when the tool never
    # ran, the exception text carried in $what. Capped (step line + last
    # 4 lines x 80 chars) so the NSIS dialog string cannot overflow.
    # Written as UTF-16LE ('Unicode') because the Unicode NSIS build reads
    # it with FileRead as wide text.
    try {
        $detail = @($what)
        $lines = @($script:LastToolOutput -split "`r?`n" | Where-Object { $_.Trim() -ne '' } | Select-Object -Last 4)
        foreach ($l in $lines) { if ($l.Length -gt 80) { $l = $l.Substring(0, 80) }; $detail += $l }
        Set-Content -Path $errFile -Value ($detail -join "`r`n") -Encoding Unicode
    } catch { }
    Write-Host "SoundRelay driver setup failed: $what" -ForegroundColor Red
    exit $code
}
# --- Native tool resolution (FB-1 root cause, 2026-11-17) ------------------
# This script is reached through a 32-bit launch chain (the NSIS installer
# is a 32-bit process and spawns a 32-bit PowerShell; elevate.ps1 then
# re-launches PowerShell elevated, still 32-bit). A 32-bit process on
# 64-bit Windows sees C:\Windows\System32 transparently redirected to
# C:\Windows\SysWOW64 — and bcdedit.exe has NO build in SysWOW64 (it is a
# 64-bit-only tool), so a bare 'bcdedit' died with CommandNotFoundException
# -> exit 13 on every machine, while certutil/reg (which DO ship 32-bit
# builds in SysWOW64) worked and masked the pattern. Rule: native tools
# are invoked ONLY by absolute path; from a 32-bit process on 64-bit
# Windows the Sysnative alias reaches the real 64-bit System32.
function NativeTool([string]$name) {
    $base = Join-Path $env:SystemRoot 'System32'
    if ([Environment]::Is64BitOperatingSystem -and -not [Environment]::Is64BitProcess) {
        $base = Join-Path $env:SystemRoot 'Sysnative'
    }
    Join-Path $base $name
}
$bcdedit  = NativeTool 'bcdedit.exe'
$certutil = NativeTool 'certutil.exe'
$reg      = NativeTool 'reg.exe'
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
    $script:LastToolOutput = $out
    foreach ($l in ($out -split "`r?`n")) { if ($l.Trim() -ne '') { Log "   $l" } }
    return $LASTEXITCODE
}

$cer = Join-Path $dir 'driver-test-cert.cer'
if (-not (Test-Path $cer)) { Fail 10 "certificate file missing: $cer" }
Log "driver dir: $dir"
Log "native tools: bcdedit=$bcdedit certutil=$certutil reg=$reg (process 64-bit: $([Environment]::Is64BitProcess), OS 64-bit: $([Environment]::Is64BitOperatingSystem))"
# A failure-detail file from a PREVIOUS run must never surface in this
# run's installer dialog.
if (Test-Path $errFile) { Remove-Item $errFile -Force -ErrorAction SilentlyContinue }

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
    if ((RunLogged $certutil @('-addstore', '-f', 'Root', $cer)) -ne 0) { Fail 11 "certutil -addstore Root failed (see log above)" }
    Log 'Step 1/3: importing test certificate into TrustedPublisher store...'
    if ((RunLogged $certutil @('-addstore', '-f', 'TrustedPublisher', $cer)) -ne 0) { Fail 12 "certutil -addstore TrustedPublisher failed (see log above)" }
} catch { Fail 11 "certificate import error: $($_.Exception.Message)" }

try {
    # Read the CURRENT state first: if Test Mode is already ON (the user
    # enabled it by hand, or an earlier attempt got this far before the
    # launch-chain defect was fixed), this step is already satisfied —
    # skip the /set and count it as success instead of failing against a
    # state that is already correct.
    Log 'Step 2/3: reading current Test Mode state (bcdedit /enum {current})...'
    $null = RunLogged $bcdedit @('/enum', '{current}')
    if ($script:LastToolOutput -match '(?im)^\s*testsigning\s+Yes\s*$') {
        Log 'Test Mode is already ON — skipping bcdedit /set, step counts as done.'
    } else {
        Log 'Step 2/3: turning Windows Test Mode ON (bcdedit /set testsigning on)...'
        if ((RunLogged $bcdedit @('/set', 'testsigning', 'on')) -ne 0) {
            Log 'bcdedit refused to enable test signing. Most common causes, in order:'
            Log '  1) Secure Boot is still ON (double-check in BIOS/UEFI — some machines re-enable it after a BIOS update).'
            Log '  2) This bcdedit output above names a policy/lock (e.g. an organization-managed PC).'
            Log 'Retry: disable Secure Boot, reboot, then re-run the installer and select only the driver component.'
            Fail 13 'bcdedit /set testsigning on failed — see the full bcdedit output and guidance in this log'
        }
        # Verify, don't trust: re-read the state so a "successful" /set
        # that did not actually land is caught here, with evidence.
        $null = RunLogged $bcdedit @('/enum', '{current}')
        if ($script:LastToolOutput -notmatch '(?im)^\s*testsigning\s+Yes\s*$') {
            Fail 13 'bcdedit /set reported success but Test Mode still does not read as ON — see the bcdedit /enum output in this log'
        }
        Log 'Test Mode confirmed ON.'
    }
} catch { Fail 13 "bcdedit error: $($_.Exception.Message)" }

try {
    Log 'Step 3/3: registering post-reboot resume (HKLM RunOnce AirBridgeDriverSetup)...'
    $bat = Join-Path $dir '一键安装.bat'
    if ((RunLogged $reg @('add', 'HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\RunOnce', '/v', 'AirBridgeDriverSetup', '/t', 'REG_SZ', '/d', "`"$bat`"", '/f')) -ne 0) { Fail 14 "writing HKLM RunOnce failed (see log above)" }
} catch { Fail 14 "RunOnce registration error: $($_.Exception.Message)" }

Log 'All privileged steps done. A reboot is required; setup resumes automatically after logon.'
exit 0
