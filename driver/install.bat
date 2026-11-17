@echo off
setlocal EnableExtensions EnableDelayedExpansion
rem ============================================================================
rem  AirBridge Virtual Audio Driver - INSTALL (Route A, TEST ONLY)
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

echo === AirBridge virtual audio driver install (TEST-SIGNED, self-use only) ===
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

rem --- locate devcon (ships with the WDK) ---
set "DEVCON="
for /f "delims=" %%d in ('dir /b /ad /o-n "%ProgramFiles(x86)%\Windows Kits\10\Tools" 2^>nul') do (
  if not defined DEVCON if exist "%ProgramFiles(x86)%\Windows Kits\10\Tools\%%d\x64\devcon.exe" set "DEVCON=%ProgramFiles(x86)%\Windows Kits\10\Tools\%%d\x64\devcon.exe"
)
for /f "delims=" %%d in ('dir /b /ad /o-n "%ProgramFiles(x86)%\Windows Kits\10\bin" 2^>nul') do (
  if not defined DEVCON if exist "%ProgramFiles(x86)%\Windows Kits\10\bin\%%d\x64\devcon.exe" set "DEVCON=%ProgramFiles(x86)%\Windows Kits\10\bin\%%d\x64\devcon.exe"
)
if not defined DEVCON (
  echo devcon.exe not found in the Windows Kit folders.
  echo Staging the package with pnputil only - the device node still needs devcon:
  pnputil /add-driver "%PKG%\SimpleAudioSample.inf"
  echo.
  echo Then create the device manually with the WDK devcon:
  echo     devcon install "%PKG%\SimpleAudioSample.inf" %HWID%
  pause & exit /b 1
)
echo     devcon: %DEVCON%

"%DEVCON%" remove "%HWID%" >nul 2>&1
"%DEVCON%" install "%PKG%\SimpleAudioSample.inf" "%HWID%"
if errorlevel 1 ( echo ERROR: devcon install failed. & pause & exit /b 1 )

echo.
echo --- Device status ---
"%DEVCON%" status "%HWID%"
echo.
echo Done. Check Settings ^> Sound - you should now see:
echo     Output: Speakers (AirBridge virtual audio device, shown in Chinese as
echo             the speaker endpoint of the branded device)
echo     Input : Microphone (same branded device)
echo Device Manager should list the adapter under "Sound, video and game
echo controllers" with the AirBridge branded name.
pause
exit /b 0
