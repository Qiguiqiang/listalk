@echo off
setlocal EnableExtensions EnableDelayedExpansion
rem ============================================================================
rem  devnode.exe - STANDALONE BUILD (helper only, no driver build / signing)
rem  Run on the Windows build machine. The normal path is build-and-sign.bat,
rem  which builds this helper as one of its steps; this script exists for
rem  rebuilding just the helper while working on it.
rem  Output: devnode.exe next to this script.
rem ============================================================================
cd /d "%~dp0"

echo [1/2] Locating Visual Studio 2022 ...
set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
set "VSINSTALL="
if exist "%VSWHERE%" (
  for /f "usebackq tokens=*" %%i in (`"%VSWHERE%" -latest -products * -version "[17.0,18.0)" -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set "VSINSTALL=%%i"
)
if not defined VSINSTALL (
  echo ERROR: Visual Studio 2022 with C++ workload not found.
  echo Install VS2022 + "Desktop development with C++" + matching Windows SDK.
  goto :fail
)
echo     VS: %VSINSTALL%

echo [2/2] Compiling devnode.cpp ...
call "%VSINSTALL%\Common7\Tools\VsDevCmd.bat" -arch=x64
if errorlevel 1 ( echo ERROR: VsDevCmd.bat failed. & goto :fail )
cl /nologo /O2 /W4 /EHsc /MT /DUNICODE /D_UNICODE devnode.cpp /Fe:devnode.exe
if errorlevel 1 ( echo ERROR: compile failed. See output above. & goto :fail )
if not exist "%~dp0devnode.exe" ( echo ERROR: devnode.exe was not produced. & goto :fail )

echo.
echo SUCCESS. Helper is at:
echo     %~dp0devnode.exe
exit /b 0

:fail
echo.
echo BUILD FAILED.
exit /b 1
