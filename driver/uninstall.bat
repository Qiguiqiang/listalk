@echo off
setlocal EnableExtensions EnableDelayedExpansion
rem ============================================================================
rem  AirBridge Virtual Audio Driver - UNINSTALL (Route A)
rem  MUST be run as Administrator.
rem  Removes the device + driver package. Optionally also removes the test
rem  certificate and turns Test Mode back OFF (you will be asked).
rem ============================================================================
cd /d "%~dp0"

net session >nul 2>&1
if errorlevel 1 (
  echo Requesting Administrator rights ...
  powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b
)

set "HWID=ROOT\SimpleAudioSample"

echo === AirBridge virtual audio driver uninstall ===
echo.
echo [1/3] Removing device node ...
set "DEVCON="
for /f "delims=" %%d in ('dir /b /ad /o-n "%ProgramFiles(x86)%\Windows Kits\10\Tools" 2^>nul') do (
  if not defined DEVCON if exist "%ProgramFiles(x86)%\Windows Kits\10\Tools\%%d\x64\devcon.exe" set "DEVCON=%ProgramFiles(x86)%\Windows Kits\10\Tools\%%d\x64\devcon.exe"
)
for /f "delims=" %%d in ('dir /b /ad /o-n "%ProgramFiles(x86)%\Windows Kits\10\bin" 2^>nul') do (
  if not defined DEVCON if exist "%ProgramFiles(x86)%\Windows Kits\10\bin\%%d\x64\devcon.exe" set "DEVCON=%ProgramFiles(x86)%\Windows Kits\10\bin\%%d\x64\devcon.exe"
)
if defined DEVCON (
  "%DEVCON%" remove "%HWID%"
) else (
  echo devcon not found - remove the device in Device Manager instead:
  echo   Sound, video and game controllers ^> AirBridge device ^> Uninstall device.
)

echo [2/3] Removing driver package from the driver store ...
powershell -NoProfile -Command "$lines = pnputil /enum-drivers; $oem = $null; foreach ($l in $lines) { if ($l -match 'Published Name:\s*(oem\d+\.inf)') { $oem = $Matches[1] } elseif ($l -match 'Original Name:\s*SimpleAudioSample\.inf' -and $oem) { Write-Host ('Deleting ' + $oem); pnputil /delete-driver $oem /uninstall; $oem = $null } }"
echo.
echo [3/3] Optional cleanup ...
choice /c YN /m "Also remove the AirBridge TEST certificate from Root/TrustedPublisher stores"
if not errorlevel 2 (
  certutil -delstore Root "AirBridge Test Driver Signing" >nul 2>&1
  certutil -delstore TrustedPublisher "AirBridge Test Driver Signing" >nul 2>&1
  echo Certificate removed (if it was present).
)
choice /c YN /m "Also turn Test Mode OFF (other test-signed drivers will stop working)"
if not errorlevel 2 (
  bcdedit /set testsigning off
  echo Test Mode will be off after the next REBOOT.
)
echo.
echo Uninstall finished. A reboot is recommended.
pause
exit /b 0
