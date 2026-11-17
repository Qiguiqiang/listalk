@echo off
REM Phone Audio Bridge - PC side launcher (Windows)
REM First run creates .venv and installs dependencies.
setlocal
cd /d %~dp0

where py >nul 2>nul
if %ERRORLEVEL%==0 (
    set PY=py -3
) else (
    set PY=python
)

if not exist .venv (
    echo [setup] Creating virtual environment...
    %PY% -m venv .venv
    if errorlevel 1 (
        echo [error] venv creation failed. Install Python 3.10+ from python.org and tick "Add to PATH".
        pause
        exit /b 1
    )
)

call .venv\Scripts\activate.bat
python -m pip install --upgrade pip >nul
pip install -r requirements.txt
if errorlevel 1 (
    echo [error] Dependency install failed. Check your network and retry.
    pause
    exit /b 1
)

echo.
echo ============================================================
echo  Phone Audio Bridge - PC side
echo  Tip: install VB-CABLE (free, https://vb-audio.com/Cable/)
echo  so WeChat / Zoom / OBS / games can use the phone as a mic.
echo  This app auto-detects CABLE Input / CABLE Output.
echo ============================================================
echo.
python phone_audio_pc.py %*
pause
