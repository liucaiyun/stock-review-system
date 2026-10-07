@echo off
setlocal EnableExtensions
cd /d "%~dp0"

set "JAVA_EXE="
where java >nul 2>&1 && for /f "delims=" %%i in ('where java') do (
  if not defined JAVA_EXE set "JAVA_EXE=%%i"
)
if not defined JAVA_EXE if exist "D:\Program Files\Microsoft\jdk-21.0.7.6-hotspot\bin\java.exe" set "JAVA_EXE=D:\Program Files\Microsoft\jdk-21.0.7.6-hotspot\bin\java.exe"

set "MVN_CMD="
where mvn >nul 2>&1 && for /f "delims=" %%i in ('where mvn') do (
  if not defined MVN_CMD set "MVN_CMD=%%i"
)
if not defined MVN_CMD if exist "D:\Program Files\maven-mvnd-1.0.2-windows-amd64\mvn\bin\mvn.cmd" set "MVN_CMD=D:\Program Files\maven-mvnd-1.0.2-windows-amd64\mvn\bin\mvn.cmd"
if not defined MVN_CMD if exist "D:\apache-maven-3.9.6\bin\mvn.cmd" set "MVN_CMD=D:\apache-maven-3.9.6\bin\mvn.cmd"

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

echo Java : %JAVA_EXE%
echo Maven: %MVN_CMD%
echo.

rem ---------------------------------------------------------------------------
rem 关键：绝对不能覆盖「正在运行的那个 jar」。
rem
rem 血的教训：应用从 fat jar 启动后，Spring Boot 的 JarLauncher 是**按需**从 jar 里读类的，
rem 已经加载过的类留在内存里没事，但任何「第一次要用」的类都要回jar读。
rem 如果这时把 jar 覆盖掉（哪怕后来换回一个完整的新 jar，zip 内的偏移也全变了），
rem 运行中的进程就会出现 ClassNotFoundException / NoClassDefFoundError。
rem 典型症状：平时一切正常，一旦要打一条带异常的日志就崩在
rem   NoClassDefFoundError: ch/qos/logback/classic/spi/ThrowableProxy
rem （ThrowableProxy 正是 logback 第一次记录异常时才加载的类）。
rem
rem 所以：检测到 8088 在监听时，只生成旁路产物 target\srs-fat.jar，不碰正式名。
rem ---------------------------------------------------------------------------
set "RUNNING_PID="
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":8088.*LISTENING"') do (
  if not defined RUNNING_PID set "RUNNING_PID=%%a"
)

if defined RUNNING_PID (
  echo [WARN] 股票复盘系统正在运行（PID %RUNNING_PID%），jar 正在被它使用。
  echo        本次改成生成旁路产物 target\srs-fat.jar，不会影响正在运行的实例。
  echo        想更新正式 jar：先关掉应用，再运行一次 build.bat。
  echo.
  call "%MVN_CMD%" -DskipTests package -Dfinal-name=srs-fat
  if errorlevel 1 (
    echo [ERROR] Build failed.
    pause
    exit /b 1
  )
  echo Output: target\srs-fat.jar
  pause
  exit /b 0
)

call "%MVN_CMD%" -DskipTests package
if errorlevel 1 (
  echo [ERROR] Build failed.
  pause
  exit /b 1
)
echo Output: target\stock-review-system-1.0.0.jar
pause
