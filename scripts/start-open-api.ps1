$ErrorActionPreference = 'Stop'
$root = Join-Path 'D:\sinosoft' ([string]::Concat([char]0x6C5F,[char]0x82CF,[char]0x4E8C,[char]0x671F) + '\' + [string]::Concat([char]0x57FA,[char]0x4E8E,'JAVA',[char]0x7684,[char]0x667A,[char]0x80FD,[char]0x4F53,[char]0x5E94,[char]0x7528,[char]0x5E73,[char]0x53F0))
if (-not (Test-Path $root)) { throw "root missing: $root" }
Set-Location $root
Write-Host "ROOT=$root"

$localEnv = Join-Path $root 'scripts\local-env.ps1'
if (Test-Path $localEnv) { . $localEnv }
if (-not $env:DEEPSEEK_API_KEY) { Write-Host 'WARN: DEEPSEEK_API_KEY not set (scripts/local-env.ps1)'; }
$env:AMP_MODEL_ID = 'deepseek:deepseek-flash'
$env:AMP_SERVER_PORT = '18081'
$env:AMP_REDIS_URI = 'redis://127.0.0.1:6379'
$env:AMP_API_KEY = 's0-demo-key'
$env:AMP_TENANT_ID = 'acme'
$env:AMP_PG_URI = 'jdbc:postgresql://127.0.0.1:15432/amp'
$env:AMP_PG_USER = 'amp'
$env:AMP_PG_PASSWORD = 'amp'

$logDir = Join-Path $root 'logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$log = Join-Path $logDir 'amp-open-api.log'

$c = Get-NetTCPConnection -State Listen -LocalPort 18081 -ErrorAction SilentlyContinue
if ($c) {
  $procId = @($c)[0].OwningProcess
  Write-Host "Stopping pid $procId on 18081"
  Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
  Start-Sleep -Seconds 1
}

$cmd = @(
  "Set-Location '$root'",
  "if (Test-Path 'scripts\\local-env.ps1') { . 'scripts\\local-env.ps1' }",
  "`$env:AMP_MODEL_ID='deepseek:deepseek-flash'",
  "`$env:AMP_SERVER_PORT='18081'",
  "`$env:AMP_REDIS_URI='redis://127.0.0.1:6379'",
  "`$env:AMP_API_KEY='s0-demo-key'",
  "`$env:AMP_TENANT_ID='acme'",
  "`$env:AMP_PG_URI='jdbc:postgresql://127.0.0.1:15432/amp'",
  "`$env:AMP_PG_USER='amp'",
  "`$env:AMP_PG_PASSWORD='amp'",
  "mvn -pl platform-open-api spring-boot:run 1> '$log' 2>&1"
) -join '; '

$proc = Start-Process -FilePath 'powershell.exe' -ArgumentList @('-NoProfile','-Command', $cmd) -WorkingDirectory $root -WindowStyle Hidden -PassThru
Write-Host "PID=$($proc.Id)"
$proc.Id | Out-File -FilePath (Join-Path $logDir 'amp-open-api.pid') -Encoding ascii

$ok = $false
for ($i = 0; $i -lt 50; $i++) {
  Start-Sleep -Seconds 3
  try {
    $r = Invoke-WebRequest -Uri 'http://127.0.0.1:18081/api/v1/health/demo' -UseBasicParsing -TimeoutSec 3
    if ($r.StatusCode -eq 200) { $ok = $true; Write-Host "HEALTH_OK $($r.Content)"; break }
  } catch {
    Write-Host "wait $i"
  }
}
if (-not $ok) {
  Write-Host "HEALTH_FAIL"
  if (Test-Path $log) { Get-Content $log -Tail 100 | ForEach-Object { Write-Host $_ } }
  exit 1
}