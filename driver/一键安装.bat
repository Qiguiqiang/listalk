@echo off
setlocal EnableExtensions EnableDelayedExpansion
rem ============================================================================
rem  SoundRelay Virtual Audio Driver - ONE-CLICK SETUP (Route A, TEST ONLY)
rem
rem  Double-click this file. It does EVERYTHING by itself:
rem    check Secure Boot -> build + sign driver -> import test certificate ->
rem    turn on Test Mode -> (ONE restart) -> install device -> verify.
rem  The script is STATE-DRIVEN: every run looks at what is already done
rem  and continues from there. If the auto-continue after the restart does
rem  not pop up for any reason, just double-click this file again.
rem
rem  WHY PLAIN ENGLISH TEXT: cmd.exe parses .bat files in the legacy OEM
rem  code page; embedding UTF-8 Chinese text can corrupt parsing. This is
rem  the same convention as the other scripts in this package. README.md
rem  explains every screen of this script in Chinese, line by line.
rem ============================================================================
title SoundRelay Driver Setup
cd /d "%~dp0"

rem --- ask for Administrator rights (a UAC prompt appears: click YES) ---
net session >nul 2>&1
if errorlevel 1 (
  echo Asking Windows for Administrator rights - click YES on the prompt...
  powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b
)

set "CER=%~dp0cert\driver-test-cert.cer"
set "PKG=%~dp0build-output"
set "INF=%PKG%\SimpleAudioSample.inf"
set "SYS=%PKG%\simpleaudiosample.sys"
set "CAT=%PKG%\SimpleAudioSample.cat"
set "HWID=ROOT\SimpleAudioSample"
set "RO_KEY=HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\RunOnce"
set "RO_NAME=AirBridgeDriverSetup"

echo ================================================================
echo  SoundRelay virtual audio driver - one-click setup
echo  Everything is automatic. Do NOT close this window.
echo ================================================================
echo.

rem ---------------------------------------------------------------------------
rem  Check: Secure Boot must be OFF. Windows refuses test-signed drivers
rem  and blocks TESTSIGNING while Secure Boot policy is active.
rem ---------------------------------------------------------------------------
set "SB="
for /f "usebackq tokens=*" %%i in (`powershell -NoProfile -Command "try { Confirm-SecureBootUEFI } catch { 'UNKNOWN' }"`) do set "SB=%%i"
if /i "%SB%"=="True" (
  echo ****************************************************************
  echo  STOP: Secure Boot is ON on this computer.
  echo  Windows cannot load a test-signed driver while it is on.
  echo.
  echo  Do this ONE thing, then double-click this file again:
  echo    1. Restart and enter UEFI/BIOS setup.
  echo    2. Set  Secure Boot = Disabled.
  echo    3. Save, exit, boot back into Windows.
  echo  Full Chinese steps: see README.md.
  echo ****************************************************************
  pause
  exit /b 1
)
if /i "%SB%"=="UNKNOWN" echo NOTE: Secure Boot state unreadable (legacy BIOS?). Continuing.

rem ---------------------------------------------------------------------------
rem  Step 1: build + test-sign the driver, only if the signed package is
rem  not there yet. Reuses build-and-sign.bat; its "pause" prompts are
rem  neutralised by feeding it an empty stdin.
rem ---------------------------------------------------------------------------
if exist "%INF%" if exist "%SYS%" if exist "%CAT%" goto :pkg_ready
echo [Step 1 of 4] Building the driver - first run only, a few minutes...
echo             (Any line below about "install.bat" can be ignored.)
call "%~dp0build-and-sign.bat" <nul
if errorlevel 1 (
  echo.
  echo ERROR: build failed. Most common cause: Visual Studio 2022 with
  echo the C++ workload, or the WDK, is not installed on this computer.
  echo Scroll up for the exact error, fix it, then run this file again.
  pause
  exit /b 1
)
if not exist "%INF%" (
  echo ERROR: build finished but the driver package is incomplete.
  pause
  exit /b 1
)
if not exist "%SYS%" (
  echo ERROR: build finished but the driver package is incomplete.
  pause
  exit /b 1
)
if not exist "%CAT%" (
  echo ERROR: build finished but the driver package is incomplete.
  pause
  exit /b 1
)
:pkg_ready
echo [Step 1 of 4] Driver package ready.

rem ---------------------------------------------------------------------------
rem  Step 2: import the TEST certificate (safe to repeat).
rem ---------------------------------------------------------------------------
echo [Step 2 of 4] Installing the test certificate...
certutil -addstore -f Root "%CER%" >nul
if errorlevel 1 ( echo ERROR: certificate import failed - Root store. & pause & exit /b 1 )
certutil -addstore -f TrustedPublisher "%CER%" >nul
if errorlevel 1 ( echo ERROR: certificate import failed - TrustedPublisher store. & pause & exit /b 1 )

rem ---------------------------------------------------------------------------
rem  Step 3: Test Mode. If it is not active yet: arm it, register an
rem  auto-continue entry for the next logon, then restart.
rem ---------------------------------------------------------------------------
set "TS="
for /f "tokens=1,2" %%a in ('bcdedit /enum {current} 2^>nul ^| findstr /i "testsigning"') do set "TS=%%b"
if /i "%TS%"=="Yes" goto :install_device

echo [Step 3 of 4] Turning ON Test Mode...
bcdedit /set testsigning on
if errorlevel 1 (
  echo ERROR: could not turn on Test Mode.
  echo Secure Boot may still be on, or BitLocker protection is in the way.
  echo See README.md, then run this file again.
  pause
  exit /b 1
)
reg add "%RO_KEY%" /v "%RO_NAME%" /t REG_SZ /d "\"%~f0\"" /f >nul
if errorlevel 1 (
  echo NOTE: the auto-continue entry could not be registered.
  echo No problem: after the restart, double-click this file once more.
)
echo.
echo ****************************************************************
echo  NOW THE COMPUTER MUST RESTART - this is the only restart.
echo  After the restart, log in and this setup continues BY ITSELF.
echo  (If nothing pops up, just double-click this file again.)
echo  A "Test Mode" watermark at the desktop corner is expected.
echo  If Windows asks for a BitLocker recovery key, type it in.
echo ****************************************************************
choice /c YN /m "Restart now"
if errorlevel 2 goto :restart_later
shutdown /r /t 5 /c "SoundRelay driver setup"
exit /b 0
:restart_later
echo.
echo OK - restart by hand whenever ready; setup continues after logon.
pause
exit /b 0

rem ---------------------------------------------------------------------------
rem  Step 4: Test Mode is active - create the device, install the driver,
rem  then verify it is actually present AND running.
rem ---------------------------------------------------------------------------
:install_device
echo [Step 4 of 4] Installing the virtual audio device...

rem --- the device node is created by devnode.exe, a small SetupAPI helper
rem --- bundled in the driver package. It replaces the WDK devcon.exe,
rem --- which user machines do not have - that is why this step used to
rem --- stage the package and give up without creating any device.
set "HELPER=%PKG%\devnode.exe"
if not exist "%HELPER%" (
  echo devnode.exe is missing from the driver package - staging the driver only.
  pnputil /add-driver "%INF%"
  echo.
  echo ERROR: the bundled device helper (devnode.exe) is missing, so the
  echo device node could not be created. Reinstall the driver package,
  echo then run this file again.
  pause
  exit /b 1
)

"%HELPER%" remove "%HWID%" >nul 2>&1
"%HELPER%" install "%INF%" "%HWID%"
if errorlevel 1 (
  echo ERROR: device install failed. See the devnode output above.
  pause
  exit /b 1
)

rem --- verify: device present AND its driver running ---
set "STAT=%TEMP%\airbridge-devstatus.txt"
"%HELPER%" status "%HWID%" > "%STAT%" 2>&1
type "%STAT%"
findstr /i /c:"Driver is running" "%STAT%" >nul
if errorlevel 1 (
  echo.
  echo ERROR: the device exists but its driver is NOT running.
  echo Usual causes: Test Mode did not really activate - check for
  echo the watermark on the desktop - or the signature is wrong.
  echo See the troubleshooting section in README.md.
  pause
  exit /b 1
)
del "%STAT%" >nul 2>&1
reg delete "%RO_KEY%" /v "%RO_NAME%" /f >nul 2>&1

echo.
echo ================================================================
echo  SUCCESS - all done, nothing else to do.
echo.
echo  Open:  Settings ^> System ^> Sound
echo  Two NEW devices are now in the lists:
echo    Output:  Speakers    ( AirBridge Wave Speaker )
echo    Input :  Microphone  ( AirBridge Wave Microphone )
echo  README.md shows where to find them - compare there.
echo ================================================================
pause
exit /b 0
