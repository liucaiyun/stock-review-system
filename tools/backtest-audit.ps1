# 回测体检：用真实行情复算「扣成本 + 对比沪深300」后的策略表现
#
# 做三件事：
#   1. 下载 24 只样本股 + 沪深300 的前复权日线（腾讯接口，与东财 fqt=1 口径一致）
#   2. 编译 tools\BacktestAudit.java（只读探针，不改源码、不写数据库）
#   3. 调用项目自身的 StrategyEngine 产出信号，再按真实成本口径复算统计
#
# 用法：powershell -File tools\backtest-audit.ps1
# 输出：tools\backtest-audit-data\report.txt

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$dataDir = "tools\backtest-audit-data"
$outDir = "tools\out"
New-Item -ItemType Directory -Force -Path $dataDir, $outDir | Out-Null

$symbols = @(
  "sh000300", "sh600519", "sz000858", "sh601318", "sz000001", "sh600036",
  "sz300750", "sz002594", "sh600276", "sz000651", "sh601899", "sz002415",
  "sh600030", "sh601012", "sz300059", "sh600887", "sh601668", "sz000333",
  "sh688111", "sh603259", "sz002714", "sh600309", "sz000725", "sh601088", "sz002304"
)

Write-Host "1/3 下载日线（腾讯前复权）..."
$ok = 0
$bad = @()
foreach ($sym in $symbols) {
  $url = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=$sym,day,,,600,qfq"
  $out = Join-Path $dataDir "$sym.json"
  $done = $false
  for ($i = 0; $i -lt 3 -and -not $done; $i++) {
    curl.exe --noproxy "*" -sS -m 40 $url -o $out 2>$null | Out-Null
    if ((Test-Path $out) -and (Get-Item $out).Length -gt 3000) { $done = $true } else { Start-Sleep -Milliseconds 700 }
  }
  if ($done) { $ok++ } else { $bad += $sym }
}
Write-Host "    成功 $ok / $($symbols.Count)"
if ($bad.Count -gt 0) { Write-Host "    失败：$($bad -join ', ')" -ForegroundColor Yellow }

Write-Host "2/3 编译探针..."
& javac -encoding UTF-8 -cp target\classes -d $outDir tools\BacktestAudit.java
if ($LASTEXITCODE -ne 0) { Write-Host "编译失败" -ForegroundColor Red; exit 1 }

Write-Host "3/3 复算指标..."
& java -cp "target\classes;$outDir" BacktestAudit $dataDir "$dataDir\report.txt"
if ($LASTEXITCODE -ne 0) { Write-Host "运行失败" -ForegroundColor Red; exit 1 }

Write-Host ""
Write-Host "完成，报告：$dataDir\report.txt" -ForegroundColor Green
