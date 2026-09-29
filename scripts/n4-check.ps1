# N4 上线检查单（脚本化）
#
# 检查项: health/ready、Open Key 列表、Open Key 被 admin 拒绝、Admin Key 技能/审计、
#         模型凭证打码、默认 demo Key 是否已轮换（生产门禁）。
# 用法: powershell -File scripts/n4-check.ps1 [-Base http://127.0.0.1:18081] [-OpenKey s0-demo-key] [-AdminKey s0-admin-key]
# 前置: 后端已在 18081；Open Key=open scope，Admin Key=open+admin scope。
# 退出码: 0=全过, 1=有 FAIL。
param(
  [string]$Base = "http://127.0.0.1:18081",
  [string]$OpenKey = "s0-demo-key",
  [string]$AdminKey = "s0-admin-key"
)

$ErrorActionPreference = "Continue"
$pass = 0
$fail = 0

function Check([string]$name, [bool]$ok, [string]$detail = "") {
  if ($ok) {
    $script:pass++
    Write-Host "[PASS] $name $detail" -ForegroundColor Green
  } else {
    $script:fail++
    Write-Host "[FAIL] $name $detail" -ForegroundColor Red
  }
}

function Get-Json([string]$url, [string]$token) {
  $h = @{ Authorization = "Bearer $token" }
  try {
    return Invoke-RestMethod -Uri $url -Headers $h -TimeoutSec 8
  } catch {
    return $null
  }
}

Write-Host "=== N4 Check: $Base ==="

# 1) ready / replica（免 Key）
$ready = Get-Json "$Base/api/v1/health/ready" ""
Check "health/ready" ($ready -ne $null) $(if ($ready) { $ready | ConvertTo-Json -Compress } else { "unreachable" })
$replica = Get-Json "$Base/api/v1/health/replica" ""
Check "health/replica" ($replica -ne $null) $(if ($replica) { $replica.replica } else { "" })

# 2) Open Key 可用
$agents = Get-Json "$Base/api/v1/agents" $OpenKey
Check "open key list agents" ($agents -ne $null) ""

# 3) Open Key 不能调 admin（应 403）
try {
  Invoke-RestMethod -Uri "$Base/api/v1/admin/skills" -Headers @{ Authorization = "Bearer $OpenKey" } -TimeoutSec 8 | Out-Null
  Check "open key blocked from admin" $false "unexpected 200"
} catch {
  $code = 0
  if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
  Check "open key blocked from admin" ($code -eq 403 -or $code -eq 401) "status=$code"
}

# 4) Admin Key 可用 + 审计可查
$skills = Get-Json "$Base/api/v1/admin/skills/visible" $AdminKey
Check "admin skills/visible" ($skills -ne $null) ""
$audit = Get-Json "$Base/api/v1/admin/audit?limit=5" $AdminKey
Check "admin audit" ($audit -ne $null) ""

# 5) 模型凭证打码（不要求已配置）
$creds = Get-Json "$Base/api/v1/admin/model-credentials" $AdminKey
$maskedOk = $true
if ($creds -and $creds.items) {
  foreach ($c in $creds.items) {
    $k = [string]$c.apiKeyMasked
    if ($k -and $k.Length -gt 0 -and -not $k.StartsWith("****")) { $maskedOk = $false }
  }
}
Check "model credentials masked" $maskedOk ""

# 6) 默认 Key 非 demo（生产门禁：未改则 FAIL）
Check "default demo key rotated (warn)" ($OpenKey -ne "s0-demo-key") "current open key is demo default"

Write-Host ""
Write-Host "=== Result: pass=$pass fail=$fail ==="
if ($fail -gt 0) { exit 1 } else { exit 0 }
