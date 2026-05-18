@echo off
REM Thin wrapper - all the real work is in auto-build.ps1.
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0auto-build.ps1" %*
exit /b %ERRORLEVEL%
