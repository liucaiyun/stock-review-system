@echo off
setlocal EnableExtensions
cd /d "%~dp0"

echo ============================================
echo  Stock Review System - Start
echo ============================================
echo.

set "JAVA_EXE="
where java >nul 2>&1 && for /f "delims=" %%i in ('where java') do (
  if not defined JAVA_EXE set "JAVA_EXE=%%i"
)
if not defined JAVA_EXE if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE if exist "D:\Program Files\Microsoft\jdk-21.0.7.6-hotspot\bin\java.exe" set "JAVA_EXE=D:\Program Files\Microsoft\jdk-21.0.7.6-hotspot\bin\java.exe"
if not defined JAVA_EXE if exist "D:\Program Files\Java\jdk-21\bin\java.exe" set "JAVA_EXE=D:\Program Files\Java\jdk-21\bin\java.exe"
if not defined JAVA_EXE if exist "D:\Program Files\Java\jdk-17\bin\java.exe" set "JAVA_EXE=D:\Program Files\Java\jdk-17\bin\java.exe"

set "MVN_CMD="
where mvn >nul 2>&1 && for /f "delims=" %%i in ('where mvn') do (
  if not defined MVN_CMD set "MVN_CMD=%%i"
)
if not defined MVN_CMD if defined MAVEN_HOME if exist "%MAVEN_HOME%\bin\mvn.cmd" set "MVN_CMD=%MAVEN_HOME%\bin\mvn.cmd"
if not defined MVN_CMD if exist "D:\Program Files\maven-mvnd-1.0.2-windows-amd64\mvn\bin\mvn.cmd" set "MVN_CMD=D:\Program Files\maven-mvnd-1.0.2-windows-amd64\mvn\bin\mvn.cmd"
if not defined MVN_CMD if exist "D:\apache-maven-3.9.6\bin\mvn.cmd" set "MVN_CMD=D:\apache-maven-3.9.6\bin\mvn.cmd"
if not defined MVN_CMD if exist "D:\maven\bin\mvn.cmd" set "MVN_CMD=D:\maven\bin\mvn.cmd"

if not defined JAVA_EXE (
  echo [ERROR] java.exe not found.
  pause
  exit /b 1
)
if not defined MVN_CMD (
  echo [ERROR] mvn.cmd not found.
  pause
  exit /b 1
)

for %%I in ("%JAVA_EXE%") do set "JAVA_BIN=%%~dpI"
set "JAVA_BIN=%JAVA_BIN:~0,-1%"
for %%I in ("%JAVA_BIN%") do set "JAVA_HOME=%%~dpI"
set "JAVA_HOME=%JAVA_HOME:~0,-1%"
set "PATH=%JAVA_BIN%;%PATH%"

set "JAR=target\stock-review-system-1.0.0.jar"

echo Java : %JAVA_EXE%
echo Maven: %MVN_CMD%
echo Port : 8088
echo.

if not exist "%JAR%" (
  echo JAR not found, building...
  call "%MVN_CMD%" -DskipTests package
  if errorlevel 1 (
    echo [ERROR] Build failed.
    pause
    exit /b 1
  )
)

for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":8088.*LISTENING"') do (
  if not "%%a"=="" (
    echo Port 8088 in use, stopping PID %%a ...
    taskkill /F /PID %%a >nul 2>&1
  )
)
timeout /t 1 >nul

echo Starting...
start "stock-review-system" "%JAVA_EXE%" -jar "%JAR%" --server.port=8088
echo.
echo   Open http://localhost:8088
echo.
timeout /t 3 >nul
exit /b 0
