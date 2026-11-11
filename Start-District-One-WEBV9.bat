@echo off
setlocal

REM District One WEBV9 automatic startup
REM Starts the existing approved startup script:
REM MySQL84: 3306 | Backend: 8081 | Frontend: 8080

powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "D:\WEB_V9\START-WEBV9-AUTO.ps1"

endlocal
exit /b %errorlevel%
