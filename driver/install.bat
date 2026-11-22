@echo off
setlocal EnableExtensions EnableDelayedExpansion
rem ============================================================================
rem  SoundRelay Virtual Audio Driver - INSTALL (Route A, TEST ONLY)
rem  MUST be run as Administrator.
rem  Pass 1 (test signing off): imports the test certificate, turns Test Mode
rem          ON, then tells you to REBOOT and run install.bat again.
rem  Pass 2 (test signing on) : creates the device and installs the driver.
rem ============================================================================
cd /d "%~dp0"

rem --- admin check / self-elevate ---
net session >nul 2>&1
if errorlevel 1 (
  echo Requesting Administrator rights ...
  powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b
)

set "CER=%~dp0cert\driver-test-cert.cer"
set "PKG=%~dp0build-output"
set "HWID=ROOT\SimpleAudioSample"

echo === SoundRelay virtual audio driver install (TEST-SIGNED, self-use only) ===
echo.

rem --- Secure Boot check (Test Mode cannot take effect with Secure Boot ON) ---
set "SB="
for /f "usebackq tokens=*" %%i in (`powershell -NoProfile -Command "try { Confirm-SecureBootUEFI } catch { 'UNKNOWN' }"`) do set "SB=%%i"
if /i "%SB%"=="True" (
  echo ****************************************************************************
  echo  WARNING: Secure Boot is ENABLED on this machine.
  echo  Windows will NOT load test-signed drivers and TESTSIGNING cannot be set
  echo  while Secure Boot policy protects it.
  echo.
  echo  Do this first: reboot into UEFI/BIOS setup, set Secure Boot = Disabled,
  echo  save, boot back into Windows, then run install.bat again.
  echo ****************************************************************************
  pause
  exit /b 1
)
if /i "%SB%"=="UNKNOWN" echo NOTE: Secure Boot state could not be read (legacy BIOS?). Continuing.

rem --- is test signing already on? ---
set "TS="
for /f "tokens=1,2" %%a in ('bcdedit /enum {current} 2^>nul ^| findstr /i "testsigning"') do set "TS=%%b"
if /i not "%TS%"=="Yes" (
  echo [Pass 1] Importing test certificate ...
  certutil -addstore -f Root "%CER%" >nul
  if errorlevel 1 ( echo ERROR: importing cert into Root store failed. & pause & exit /b 1 )
  certutil -addstore -f TrustedPublisher "%CER%" >nul
  if errorlevel 1 ( echo ERROR: importing cert into TrustedPublisher store failed. & pause & exit /b 1 )
  echo [Pass 1] Enabling Test Mode ...
  bcdedit /set testsigning on
  if errorlevel 1 (
    echo ERROR: bcdedit failed. Secure Boot may still be on, or BitLocker needs suspending.
    pause & exit /b 1
  )
  echo.
  echo ****************************************************************************
  echo  Test Mode is now armed. REBOOT the machine now.
  echo  After reboot you should see a "Test Mode" watermark at the bottom-right
  echo  of the desktop. Then run install.bat AGAIN to finish the installation.
  echo ****************************************************************************
  pause
  exit /b 0
)

echo [Pass 2] Test Mode is ON. Installing driver ...
if not exist "%PKG%\SimpleAudioSample.inf" (
  echo ERROR: signed package not found in build-output\.
  echo Run build-and-sign.bat first.
  pause & exit /b 1
)
certutil -addstore -f Root "%CER%" >nul
certutil -addstore -f TrustedPublisher "%CER%" >nul

rem --- the device node is created by devnode.exe, a small SetupAPI helper
rem --- bundled in the driver package (build-output). It replaces the WDK
rem --- devcon.exe, which user machines do not have.
set "HELPER=%PKG%\devnode.exe"
if not exist "%HELPER%" (
  echo devnode.exe not found in build-output\.
  echo Staging the package with pnputil only - the device node cannot
  echo be created without the bundled helper:
  pnputil /add-driver "%PKG%\SimpleAudioSample.inf"
  echo.
  echo Rebuild the package with build-and-sign.bat (it also produces
  echo devnode.exe), then run install.bat again.
  pause & exit /b 1
)
echo     helper: %HELPER%

"%HELPER%" remove "%HWID%" >nul 2>&1
"%HELPER%" install "%PKG%\SimpleAudioSample.inf" "%HWID%"
if errorlevel 1 ( echo ERROR: device install failed. See the devnode output above. & pause & exit /b 1 )

echo.
echo --- Device status ---
"%HELPER%" status "%HWID%"
echo.
echo Done. Check Settings ^> Sound - you should now see:
echo     Output: Speakers (AirBridge Wave Speaker)
echo     Input : Microphone (AirBridge Wave Microphone)
echo Device Manager should list the adapter under "Sound, video and game
echo controllers" with the branded device name (Chinese + AirBridge).
pause
exit /b 0
