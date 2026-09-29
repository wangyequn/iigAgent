# S9 External 流式透传演示
#
# 前置:
#   1) 平台后端已在 http://127.0.0.1:18081
#   2) External SSE mock 已启动:  python scripts/external-sse-mock.py   # :19099
#      （若只想看 mock 本身: curl -N -X POST http://127.0.0.1:19099/api/v1/messages:stream ...）
#   3) 管理 Key 具备 admin scope（默认 s0-admin-key）
#
# 流程:
#   注册 External Agent → 心跳 → 用 Open Key 走平台 messages:stream →
#   平台托管目录无此 agent 时透传 mock 的 SSE
#
# 用法:
#   powershell -File scripts/demo-external-stream.ps1
#   powershell -File scripts/demo-external-stream.ps1 -AgentName ext-demo -SkipRegister
param(
  [string]$Base = "http://127.0.0.1:18081",
  [string]$OpenKey = "s0-demo-key",
  [string]$AdminKey = "s0-admin-key",
  [string]$AgentName = "ext-demo",
  [string]$ExternalBase = "http://127.0.0.1:19099",
  [string]$CallbackKey = "",
  [switch]$SkipRegister
)

$ErrorActionPreference = "Stop"
$adminH = @{ Authorization = "Bearer $AdminKey"; "Content-Type" = "application/json" }
$openH  = @{ Authorization = "Bearer $OpenKey";  "Content-Type" = "application/json" }

function Write-Step([string]$msg) {
  Write-Host "`n=== $msg ===" -ForegroundColor Cyan
}

if (-not $SkipRegister) {
  Write-Step "1) 注册 External Agent: $AgentName -> $ExternalBase"
  $regBody = @{
    name         = $AgentName
    baseUrl      = $ExternalBase
    capabilities = "chat,stream"
  }
  if ($CallbackKey) { $regBody.callbackKey = $CallbackKey }
  $reg = Invoke-RestMethod -Method Post -Uri "$Base/api/v1/admin/external-agents" `
    -Headers $adminH -Body ($regBody | ConvertTo-Json) -TimeoutSec 15
  $reg | ConvertTo-Json -Depth 6
  $agentId = $reg.id
  if (-not $agentId) { throw "register response missing id" }

  Write-Step "2) 心跳（保持 HEALTHY）"
  Invoke-RestMethod -Method Post -Uri "$Base/api/v1/admin/external-agents/$agentId/heartbeat" `
    -Headers $adminH -TimeoutSec 10 | ConvertTo-Json -Depth 6
}

Write-Step "3) 平台流式（托管无此 agent -> 透传 mock SSE）"
$sid = "ext_sse_$([guid]::NewGuid().ToString('N').Substring(0,8))"
$chatBody = @{
  userId    = "demo-user"
  sessionId = $sid
  message   = "请流式自我介绍"
} | ConvertTo-Json

Write-Host "POST $Base/api/v1/agents/$AgentName/messages:stream  sessionId=$sid"
Write-Host "（EventSource 不支持 POST，此处用 curl.exe / Invoke 调 SSE）`n"

# 优先 curl.exe（Win10+ 自带），便于 -N 真流式打印
$curl = Get-Command curl.exe -ErrorAction SilentlyContinue
if ($curl) {
  & curl.exe -sN -X POST "$Base/api/v1/agents/$AgentName/messages:stream" `
    -H "Authorization: Bearer $OpenKey" `
    -H "Content-Type: application/json" `
    -d $chatBody
  if ($LASTEXITCODE -ne 0) { Write-Host "curl exit=$LASTEXITCODE" -ForegroundColor Yellow }
} else {
  # 回退：一次性读完 SSE 文本（仍可见帧内容）
  $resp = Invoke-WebRequest -Method Post -Uri "$Base/api/v1/agents/$AgentName/messages:stream" `
    -Headers $openH -Body $chatBody -TimeoutSec 60
  $resp.Content
}

Write-Step "4) 对照：mock 本机直连（不经平台）"
if ($curl) {
  Write-Host "（可选）curl -sN -X POST $ExternalBase/api/v1/messages:stream -H 'Content-Type: application/json' -d '{\"sessionId\":\"direct\",\"message\":\"hi\"}'"
}

Write-Host "`nDone. 若看到 data: 逐帧 JSON（type=token/done），External 流式透传即为通路。" -ForegroundColor Green
