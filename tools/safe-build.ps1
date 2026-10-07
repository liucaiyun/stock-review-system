# Safe build: never overwrite the jar that a running instance is using.
#
# Why this exists (a real incident):
#   A Spring Boot fat jar is loaded LAZILY - JarLauncher keeps a handle on the outer jar and
#   reads a class from it the first time that class is needed. Classes already loaded stay in
#   memory, so the app keeps looking healthy after the jar is replaced. But any class that has
#   not been loaded yet fails with ClassNotFoundException. The classic symptom is that
#   everything works until the app tries to log one stack trace, and then it dies on:
#     java.lang.NoClassDefFoundError: ch/qos/logback/classic/spi/ThrowableProxy
#   (ThrowableProxy is loaded by logback only the first time it logs a throwable.)
#   Restoring a complete jar afterwards does NOT help: the zip offsets inside the file changed.
#
# Rule: if port 8088 is listening -> build only the side artifact target\srs-fat.jar.
#       Otherwise -> build the normal artifact.
#
# Usage: powershell -ExecutionPolicy Bypass -File tools\safe-build.ps1 [-Verify]
# NOTE: keep this file ASCII-only. Windows PowerShell 5.1 decodes a BOM-less UTF-8 script as
#       ANSI, and non-ASCII characters can break the parser.

param([switch]$Verify)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$mvn = "D:\Program Files\maven-mvnd-1.0.2-windows-amd64\mvn\bin\mvn.cmd"
if (-not (Test-Path $mvn)) { $mvn = "D:\apache-maven-3.9.6\bin\mvn.cmd" }
if (-not (Test-Path $mvn)) { $mvn = "mvn" }

$listening = (netstat -ano | Select-String ":8088\s.*LISTENING")
$side = $false
if ($listening) {
  $ownerPid = ($listening | Select-Object -First 1).Line.Trim().Split(' ')[-1]
  Write-Host "[WARN] port 8088 is in use by PID $ownerPid (probably the running app)." -ForegroundColor Yellow
  Write-Host "       Building the side artifact target\srs-fat.jar only; the live jar is untouched." -ForegroundColor Yellow
  & $mvn -o -q -DskipTests package "-Dfinal-name=srs-fat"
  if ($LASTEXITCODE -ne 0) { Write-Host "BUILD FAILED" -ForegroundColor Red; exit 1 }
  $side = $true
  Write-Host "Output: target\srs-fat.jar (close the app and re-run to build the normal name)" -ForegroundColor Green
} else {
  & $mvn -o -q -DskipTests package
  if ($LASTEXITCODE -ne 0) { Write-Host "BUILD FAILED" -ForegroundColor Red; exit 1 }
  Write-Host "Output: target\stock-review-system-1.0.0.jar" -ForegroundColor Green
}

if ($Verify) {
  Write-Host "Verifying artifact integrity (manifest + BOOT-INF + logback ThrowableProxy)..."
  $pick = if ($side) { "target\srs-fat.jar" } else { "target\stock-review-system-1.0.0.jar" }
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $z = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path $pick))
  $libs = ($z.Entries | Where-Object { $_.FullName -like 'BOOT-INF/lib/*' }).Count
  $mf = $z.Entries | Where-Object { $_.FullName -eq 'META-INF/MANIFEST.MF' }
  $sr = New-Object System.IO.StreamReader($mf.Open()); $txt = $sr.ReadToEnd(); $sr.Close()
  $hasLoader = $txt -match 'JarLauncher'
  $lb = $z.Entries | Where-Object { $_.FullName -like 'BOOT-INF/lib/logback-classic*' }
  $tmp = Join-Path $env:TEMP "lb-verify.jar"
  if ($lb) { $fs = [System.IO.File]::Create($tmp); $lb.Open().CopyTo($fs); $fs.Close() }
  $hasTp = $false
  if (Test-Path $tmp) {
    $z2 = [System.IO.Compression.ZipFile]::OpenRead($tmp)
    $hasTp = ($z2.Entries | Where-Object { $_.FullName -eq 'ch/qos/logback/classic/spi/ThrowableProxy.class' }).Count -gt 0
    $z2.Dispose()
    Remove-Item $tmp -Force
  }
  $z.Dispose()
  Write-Host ("  {0}: deps={1} JarLauncher={2} ThrowableProxy={3}" -f $pick, $libs, $hasLoader, $hasTp)
  if ($libs -lt 40 -or -not $hasLoader -or -not $hasTp) {
    Write-Host "  [ERROR] artifact is INCOMPLETE - do not launch with it!" -ForegroundColor Red
    exit 1
  }
  Write-Host "  artifact OK" -ForegroundColor Green
}
