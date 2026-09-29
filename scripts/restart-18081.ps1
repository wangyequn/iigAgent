$ErrorActionPreference = 'Continue'
Set-Location "D:\sinosoft\江苏二期\基于JAVA的智能体应用平台"
Write-Host "PWD=$((Get-Location).Path)"
$localEnv = Join-Path (Get-Location) 'scripts\local-env.ps1'
if (Test-Path $localEnv) { . $localEnv }
$env:AMP_SERVER_PORT = '18081'
$env:AMP_REDIS_URI = 'redis://127.0.0.1:6379'
$env:AMP_PG_URI = 'jdbc:postgresql://127.0.0.1:15432/amp'
$env:AMP_PG_USER = 'amp'
$env:AMP_PG_PASSWORD = 'amp'
$env:AMP_MODEL_ID = 'deepseek:deepseek-flash'
$env:AMP_API_KEY = 's0-demo-key'
$env:AMP_TENANT_ID = 'acme'
New-Item -ItemType Directory -Force -Path ".\logs" | Out-Null
$mvn = "D:\Program Files (x86)\apache-maven-3.8.8\bin\mvn.cmd"
Write-Host "MVN=$mvn exists=$(Test-Path $mvn)"
Write-Host "POM=$(Test-Path .\pom.xml)"
$pom = (Resolve-Path .\pom.xml).Path
$job = Start-Job -ScriptBlock {
  param($root, $pom, $mvn)
  Set-Location $root
  $localEnv = Join-Path $root 'scripts\local-env.ps1'
  if (Test-Path $localEnv) { . $localEnv }
  $env:AMP_SERVER_PORT = '18081'
  $env:AMP_REDIS_URI = 'redis://127.0.0.1:6379'
  $env:AMP_PG_URI = 'jdbc:postgresql://127.0.0.1:15432/amp'
  $env:AMP_PG_USER = 'amp'
  $env:AMP_PG_PASSWORD = 'amp'
  $env:AMP_MODEL_ID = 'deepseek:deepseek-flash'
  $env:AMP_API_KEY = 's0-demo-key'
  $env:AMP_TENANT_ID = 'acme'
  & $mvn -f $pom -pl platform-open-api spring-boot:run 2>&1
} -ArgumentList (Get-Location).Path, $pom, $mvn
Write-Host "JOB=$($job.Id)"
Start-Sleep -Seconds 5
for ($i = 0; $i -lt 25; $i++) {
  try {
    $r = Invoke-WebRequest -Uri 'http://127.0.0.1:18081/api/v1/health/demo' -UseBasicParsing -TimeoutSec 3
    if ($r.StatusCode -eq 200) { Write-Host "HEALTH_OK $($r.Content)"; exit 0 }
  } catch { }
  Start-Sleep -Seconds 3
  if ($i % 5 -eq 0) {
    Receive-Job -Job $job -Keep | Select-Object -Last 8
    Write-Host "wait $i"
  }
}
Write-Host 'HEALTH_FAIL'
Receive-Job -Job $job | Select-Object -Last 40
exit 1
