@echo off
setlocal
title Airtel 5G Real-Time Bandwidth Monitor
cd /d "%~dp0"

echo =======================================================
echo   Airtel 5G Router Real-Time Bandwidth Monitor
echo   ODU: ZLT X17M  ^|  IDU: ZLT W304VA PRO
echo =======================================================
echo.
echo Starting local monitor server at http://127.0.0.1:8080...
echo.

REM Prefer the official Python launcher (py). It runs in THIS window and
REM avoids the Microsoft Store "python.exe" alias, which relaunches the
REM interpreter in a second console window.
where py >nul 2>nul
if %errorlevel%==0 (
    py -3 server.py
) else (
    python server.py
)

echo.
echo Server stopped.
pause
endlocal
