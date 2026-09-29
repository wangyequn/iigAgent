# 压测冒烟：并发非流式对话，输出延迟分位（非全量 NFR；只作冒烟）
#
# 对端: POST {Base}/api/v1/agents/{AgentId}/messages（Open Key）
# 输出: ok/err、avg、p50/p95/p99；p95>5s 警告。
# 用法: powershell -File scripts/smoke-load.ps1 [-N 20] [-Concurrency 4] [-Base http://127.0.0.1:18081] [-ApiKey s0-demo-key]
# 前置: 后端已在 18081；AgentId 默认 demo（或已注册的 External 名）。
# 退出码: 0=ok, 1=无成功样本, 2=p95 超标。
param(
  [int]$N = 20,
  [int]$Concurrency = 4,
  [string]$Base = "http://127.0.0.1:18081",
  [string]$ApiKey = "s0-demo-key",
  [string]$AgentId = "demo"
)

$ErrorActionPreference = "Stop"
$headers = @{ Authorization = "Bearer $ApiKey"; "Content-Type" = "application/json" }
$results = New-Object System.Collections.Generic.List[double]
$errors = 0

$jobs = @()
for ($i = 0; $i -lt $N; $i++) {
  while (@(Get-Job -State Running | Where-Object { $_.Name -like "smoke-*" }).Count -ge $Concurrency) {
    Start-Sleep -Milliseconds 50
    Get-Job -State Completed -Name "smoke-*" | ForEach-Object {
      $r = Receive-Job $_
      if ($r -is [double]) { $results.Add($r) } else { $script:errors++ }
      Remove-Job $_
    }
  }
  $sid = "smoke_$([guid]::NewGuid().ToString('N').Substring(0,10))"
  $body = @{ userId = "load"; sessionId = $sid; message = "ping $i" } | ConvertTo-Json
  $jobs += Start-Job -Name "smoke-$i" -ScriptBlock {
    param($Base, $AgentId, $headers, $body)
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
      Invoke-RestMethod -Method Post -Uri "$Base/api/v1/agents/$AgentId/messages" -Headers $headers -Body $body -TimeoutSec 60 | Out-Null
      $sw.Stop()
      return $sw.Elapsed.TotalMilliseconds
    } catch {
      $sw.Stop()
      return -1.0
    }
  } -ArgumentList $Base, $AgentId, $headers, $body
}

Get-Job -Name "smoke-*" | Wait-Job | Out-Null
Get-Job -Name "smoke-*" | ForEach-Object {
  $r = Receive-Job $_
  if ($r -is [double] -and $r -ge 0) { $results.Add($r) } else { $errors++ }
  Remove-Job $_
}

if ($results.Count -eq 0) {
  Write-Host "no successful samples" -ForegroundColor Red
  exit 1
}

$sorted = $results | Sort-Object
function Pct([double[]]$arr, [double]$p) {
  $idx = [Math]::Max(0, [Math]::Min($arr.Length - 1, [int][Math]::Floor($arr.Length * $p) - 1))
  if ($idx -lt 0) { $idx = 0 }
  return [Math]::Round($arr[$idx], 1)
}

$p50 = Pct $sorted 0.50
$p95 = Pct $sorted 0.95
$p99 = Pct $sorted 0.99
$avg = [Math]::Round(($sorted | Measure-Object -Average).Average, 1)

Write-Host "=== Smoke Load ==="
Write-Host "ok=$($results.Count) err=$errors N=$N concurrency=$Concurrency"
Write-Host "avg=${avg}ms p50=${p50}ms p95=${p95}ms p99=${p99}ms"
Write-Host "NFR 参考: 首 Token P95 < 2000ms（本脚本为整轮 messages 延迟，偏严）"
if ($p95 -gt 5000) {
  Write-Host "WARN: p95 > 5s" -ForegroundColor Yellow
  exit 2
}
exit 0
