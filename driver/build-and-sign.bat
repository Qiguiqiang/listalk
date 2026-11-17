@echo off
setlocal EnableExtensions EnableDelayedExpansion
rem ============================================================================
rem  AirBridge Virtual Audio Driver - BUILD + TEST-SIGN (Route A, TEST ONLY)
rem  Run on the Windows build machine (VS2022 + WDK installed). No admin needed.
rem  Output: signed driver package copied to  build-output\
rem ============================================================================
cd /d "%~dp0"

set "PFX=%~dp0cert\driver-test-cert.pfx"
rem TEST-ONLY throwaway certificate password (cert is in this package, self-signed)
set "PFXPASS=AirBridge-Test-2026"
set "SLN=%~dp0src\SimpleAudioSample\SimpleAudioSample.sln"
set "OUT=%~dp0build-output"

echo [1/5] Locating Visual Studio 2022 ...
set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
set "VSINSTALL="
if exist "%VSWHERE%" (
  for /f "usebackq tokens=*" %%i in (`"%VSWHERE%" -latest -products * -version "[17.0,18.0)" -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set "VSINSTALL=%%i"
)
if not defined VSINSTALL (
  echo ERROR: Visual Studio 2022 with C++ workload not found.
  echo Install VS2022 + "Desktop development with C++" + matching Windows SDK and WDK.
  goto :fail
)
echo     VS: %VSINSTALL%
set "MSBUILD=%VSINSTALL%\MSBuild\Current\Bin\MSBuild.exe"
if not exist "%MSBUILD%" ( echo ERROR: MSBuild.exe not found. & goto :fail )

echo [2/5] Locating Windows Kit tools (signtool / inf2cat) ...
set "KITBIN=%ProgramFiles(x86)%\Windows Kits\10\bin"
set "SIGNTOOL=" & set "INF2CAT="
for /f "delims=" %%d in ('dir /b /ad /o-n "%KITBIN%" 2^>nul') do (
  if not defined SIGNTOOL if exist "%KITBIN%\%%d\x64\signtool.exe" set "SIGNTOOL=%KITBIN%\%%d\x64\signtool.exe"
  if not defined INF2CAT  if exist "%KITBIN%\%%d\x86\inf2cat.exe"  set "INF2CAT=%KITBIN%\%%d\x86\inf2cat.exe"
)
if not defined SIGNTOOL ( echo ERROR: signtool.exe not found - is the WDK installed? & goto :fail )
if not defined INF2CAT  ( echo ERROR: inf2cat.exe not found - is the WDK installed? & goto :fail )
echo     signtool: %SIGNTOOL%
echo     inf2cat : %INF2CAT%

echo [3/5] Building Release x64 ...
"%MSBUILD%" "%SLN%" /t:Rebuild /p:Configuration=Release /p:Platform=x64 /m /nologo /v:minimal
if errorlevel 1 ( echo ERROR: build failed. See output above. & goto :fail )

echo [4/5] Locating built driver package ...
set "PKGDIR="
for /r "%~dp0src\SimpleAudioSample" %%f in (SimpleAudioSample.inf) do (
  echo %%f | findstr /i "\\x64\\Release\\" >nul && set "PKGDIR=%%~dpf"
)
if not defined PKGDIR (
  echo ERROR: SimpleAudioSample.inf not found under an x64\Release folder.
  echo Check the build output layout and adjust this script.
  goto :fail
)
echo     package: %PKGDIR%
if not exist "%PKGDIR%simpleaudiosample.sys" ( echo ERROR: .sys missing in package dir. & goto :fail )

echo [5/5] Test-signing .sys and catalog ...
"%SIGNTOOL%" sign /f "%PFX%" /p "%PFXPASS%" /fd sha256 "%PKGDIR%simpleaudiosample.sys"
if errorlevel 1 ( echo ERROR: signing .sys failed. & goto :fail )
"%INF2CAT%" /driver:"%PKGDIR%" /os:10_X64
if errorlevel 1 ( echo ERROR: inf2cat failed. & goto :fail )
"%SIGNTOOL%" sign /f "%PFX%" /p "%PFXPASS%" /fd sha256 "%PKGDIR%SimpleAudioSample.cat"
if errorlevel 1 ( echo ERROR: signing catalog failed. & goto :fail )

if exist "%OUT%" rmdir /s /q "%OUT%"
mkdir "%OUT%"
copy /y "%PKGDIR%SimpleAudioSample.inf" "%OUT%\" >nul
copy /y "%PKGDIR%simpleaudiosample.sys" "%OUT%\" >nul
copy /y "%PKGDIR%SimpleAudioSample.cat" "%OUT%\" >nul

echo.
echo SUCCESS. Signed package is in:
echo     %OUT%
echo Next: reboot into Test Mode if not already (install.bat handles it), then run install.bat as Administrator.
pause
exit /b 0

:fail
echo.
echo BUILD/SIGN FAILED.
pause
exit /b 1
