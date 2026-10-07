# Download daily bars for a large stock pool, for the standalone RSI mean-reversion test.
#
# Pool construction (deterministic rule, no cherry-picking):
#   take every 25th code inside 7 code blocks (SH main 600/601/603, STAR 688,
#   SZ main 000, SZ SME 002, SZ ChiNext 300) -> about 280 candidates; keep the ones that return data.
#   Codes that are delisted/invalid simply return nothing, so the pool has SURVIVORSHIP BIAS.
#
# Two sources:
#   tx_<sym>.json   : Tencent, qfq (adjusted), 640 bars (~2.5y)  -> primary, clean
#   sina_<sym>.json : Sina, unadjusted, 1000 bars (~4.1y)        -> secondary, longer but noisy
#
# Usage: powershell -ExecutionPolicy Bypass -File tools\pool-download.ps1

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$dir = "tools\pool-data"
New-Item -ItemType Directory -Force -Path $dir | Out-Null

$blocks = @(
  @{ p = 'sh'; lo = 600000; hi = 600999 },
  @{ p = 'sh'; lo = 601000; hi = 601999 },
  @{ p = 'sh'; lo = 603000; hi = 603999 },
  @{ p = 'sh'; lo = 688001; hi = 688999 },
  @{ p = 'sz'; lo = 1;      hi = 999 },
  @{ p = 'sz'; lo = 2001;   hi = 2999 },
  @{ p = 'sz'; lo = 300001; hi = 300999 }
)
$syms = New-Object System.Collections.Generic.List[string]
foreach ($b in $blocks) {
  for ($c = $b.lo; $c -le $b.hi; $c += 25) {
    $syms.Add(('{0}{1:d6}' -f $b.p, $c))
  }
}
Write-Host "candidates: $($syms.Count)"

$txOk = New-Object System.Collections.Generic.List[string]
$sinaOk = New-Object System.Collections.Generic.List[string]

$i = 0
foreach ($sym in $syms) {
  $i++
  if ($i % 25 -eq 0) { Write-Host "  progress $i / $($syms.Count) (tx $($txOk.Count) / sina $($sinaOk.Count))" }

  $f1 = Join-Path $dir "tx_$sym.json"
  $txUrl = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=$sym,day,,,640,qfq"
  $ok = $false
  for ($r = 0; $r -lt 2 -and -not $ok; $r++) {
    curl.exe --noproxy "*" -sS -m 30 $txUrl -o $f1 2>$null | Out-Null
    if ((Test-Path $f1) -and (Get-Item $f1).Length -gt 5000) { $ok = $true } else { Start-Sleep -Milliseconds 400 }
  }
  if ($ok) { $txOk.Add($sym) }

  $f2 = Join-Path $dir "sina_$sym.json"
  $sinaUrl = "https://money.finance.sina.com.cn/quotes_service/api/json_v2.php/CN_MarketData.getKLineData?symbol=$sym&scale=240&ma=no&datalen=1000"
  $ok2 = $false
  for ($r = 0; $r -lt 2 -and -not $ok2; $r++) {
    curl.exe --noproxy "*" -sS -m 30 $sinaUrl -o $f2 2>$null | Out-Null
    if ((Test-Path $f2) -and (Get-Item $f2).Length -gt 5000) { $ok2 = $true } else { Start-Sleep -Milliseconds 400 }
  }
  if ($ok2) { $sinaOk.Add($sym) }

  Start-Sleep -Milliseconds 250
}

$txOk | Set-Content -Encoding utf8 (Join-Path $dir "symbols_tx.txt")
$sinaOk | Set-Content -Encoding utf8 (Join-Path $dir "symbols_sina.txt")
Write-Host "done: tencent $($txOk.Count), sina $($sinaOk.Count)"
