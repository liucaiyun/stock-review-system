@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"

echo ============================================
echo  Stock Review System - Restart
echo ============================================
echo.

set "SKIP_BUILD=0"
if /I "%~1"=="/nobuild" set "SKIP_BUILD=1"
if /I "%~1"=="-nobuild" set "SKIP_BUILD=1"
if /I "%~1"=="nobuild" set "SKIP_BUILD=1"

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
set "PORT=8088"

echo Java : %JAVA_EXE%
echo Maven: %MVN_CMD%
echo Port : %PORT%
echo.

echo [1/3] Stopping existing instance...
taskkill /FI "WINDOWTITLE eq stock-review-system*" /F >nul 2>&1

set "KILLED="
for /f "tokens=5" %%a in ('netstat -ano 2^>nul ^| findstr ":%PORT%" ^| findstr /I "LISTENING 侦听"') do (
  echo !KILLED! | findstr /C:"[%%a]" >nul 2>&1
  if errorlevel 1 (
    echo   stopping PID %%a
    taskkill /F /PID %%a >nul 2>&1
    set "KILLED=!KILLED![%%a]"
  )
)

set "WAIT=0"
:wait_free
netstat -ano 2>nul | findstr ":%PORT%" | findstr /I "LISTENING 侦听" >nul
if not errorlevel 1 (
  set /a WAIT+=1
  if !WAIT! GEQ 20 (
    echo [ERROR] Port %PORT% is still in use, cannot restart.
    pause
    exit /b 1
  )
  timeout /t 1 >nul
  goto wait_free
)
if defined KILLED (
  echo   port %PORT% released.
) else (
  echo   no running instance.
)
echo.

if "%SKIP_BUILD%"=="1" (
  echo [2/3] Skip rebuild.
  if not exist "%JAR%" (
    echo [ERROR] %JAR% not found. Run without /nobuild, or run build.bat first.
    pause
    exit /b 1
  )
) else (
  echo [2/3] Rebuilding...
  call "%MVN_CMD%" -DskipTests package
  if errorlevel 1 (
    echo [ERROR] Build failed.
    pause
    exit /b 1
  )
)
echo.

echo [3/3] Starting...
start "stock-review-system" "%JAVA_EXE%" -jar "%JAR%" --server.port=%PORT%

set "WAIT=0"
:wait_up
timeout /t 1 >nul
netstat -ano 2>nul | findstr ":%PORT%" | findstr /I "LISTENING 侦听" >nul
if errorlevel 1 (
  set /a WAIT+=1
  if !WAIT! GEQ 40 (
    echo [WARN] Started, but port %PORT% not listening yet. Check the new window.
    goto done
  )
  goto wait_up
)

:done
echo.
echo   Restarted. Open http://localhost:%PORT%
echo   Skip rebuild next time: restart.bat /nobuild
echo.
timeout /t 3 >nul
exit /b 0
