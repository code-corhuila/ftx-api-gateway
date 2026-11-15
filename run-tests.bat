@echo off
REM ftx-api-gateway - run the tests locally with Docker (no Java or Maven needed).
setlocal
cd /d "%~dp0"
docker run --rm -v "%CD%":/work -v ftx-m2:/root/.m2 -w /work maven:3.9.9-eclipse-temurin-21 mvn -B verify
if %ERRORLEVEL%==0 (echo. & echo ===== TODAS LAS PRUEBAS PASARON =====) else (echo. & echo ===== HAY PRUEBAS FALLANDO: copia el error y envialo =====)
pause
