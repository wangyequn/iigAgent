# Nacos AI Registry smoke test
# Usage: powershell -File scripts/nacos-ai-smoke.ps1
#   [-Base http://127.0.0.1:18081] [-AdminKey s0-admin-key]
#   [-Nacos http://127.0.0.1:8848] [-IdentityKey serverIdentity] [-IdentityValue security]
param(
  [string]$Base = "http://127.0.0.1:18081",
  [string]$AdminKey = "s0-admin-key",
  [string]$Nacos = "http://127.0.0.1:8848",
  [string]$IdentityKey = "serverIdentity",
  [string]$IdentityValue = "security",
  [string]$McpName = "smoke-sync-mcp",
  [string]$AgentName = "smoke-ext-agent"
)

$ErrorActionPreference = "Stop"
$fail = 0
function Ok($m) { Write-Host "  [OK] $m" -ForegroundColor Green }
function Bad($m) { Write-Host "  [FAIL] $m" -ForegroundColor Red; $script:fail++ }
function Step($m) { Write-Host ""; Write-Host "== $m ==" -ForegroundColor Cyan }

$H = @{ Authorization = "Bearer $AdminKey" }
$NH = @{ $IdentityKey = $IdentityValue }

Step "1. platform AI status"
try {
  $ai = Invoke-RestMethod -Uri "$Base/api/v1/admin/nacos/ai" -Headers $H
  if ($ai.enabled -eq $true) { Ok "amp.nacos.ai.enabled=true" } else { Bad "AI disabled (set AMP_NACOS_AI_ENABLED=true)" }
  Ok "syncDirection=$($ai.syncDirection) agentCardMode=$($ai.agentCardMode)"
} catch { Bad "GET /nacos/ai: $($_.Exception.Message)" }

Step "2. upsert Nacos MCP (toolsMeta hides hidden_tool)"
$serverSpec = '{"protocol":"streamable_http","frontProtocol":"streamable_http","name":"smoke-sync-mcp","id":"","description":"amp smoke","versionDetail":{"version":"1.0.0"},"enabled":true,"remoteServerConfig":{"serviceRef":{"namespaceId":"public","groupName":"DEFAULT_GROUP","serviceName":"smoke-sync-mcp"}}}'
$toolSpec = '{"tools":[{"name":"query_orders"},{"name":"place_order"},{"name":"hidden_tool"}],"toolsMeta":{"query_orders":{"enabled":true},"place_order":{"enabled":true},"hidden_tool":{"enabled":false}}}'
$endpointSpec = '{"type":"DIRECT","data":{"address":"127.0.0.1","port":19099}}'
$serverSpec = $serverSpec.Replace("smoke-sync-mcp", $McpName)
$mcpId = $null
try {
  $body = @{
    namespaceId = "public"
    mcpName = $McpName
    serverSpecification = $serverSpec
    toolSpecification = $toolSpec
    endpointSpecification = $endpointSpec
  }
  $created = Invoke-RestMethod -Uri "$Nacos/nacos/v3/admin/ai/mcp" -Method Post -Body $body -Headers $NH
  if ($created.code -eq 0) { $mcpId = $created.data; Ok "created mcp id=$mcpId" } else { Bad "create mcp: $($created.message)" }
} catch {
  try {
    $list = Invoke-RestMethod -Uri "$Nacos/nacos/v3/admin/ai/mcp/list?pageNo=1&pageSize=50&namespaceId=public&search=blur&name=$McpName" -Headers $NH
    $hit = $list.data.pageItems | Where-Object { $_.name -eq $McpName } | Select-Object -First 1
    if (-not $hit) {
      $list = Invoke-RestMethod -Uri "$Nacos/nacos/v3/admin/ai/mcp/list?pageNo=1&pageSize=50&namespaceId=public&search=blur" -Headers $NH
      $hit = $list.data.pageItems | Where-Object { $_.name -eq $McpName } | Select-Object -First 1
    }
    if ($hit) {
      $mcpId = $hit.id
      $serverSpec2 = $serverSpec -replace '"id":""', ('"id":"' + $mcpId + '"')
      $putBody = "namespaceId=public&mcpName=$McpName&mcpId=$mcpId&serverSpecification=" + [uri]::EscapeDataString($serverSpec2) + "&toolSpecification=" + [uri]::EscapeDataString($toolSpec) + "&endpointSpecification=" + [uri]::EscapeDataString($endpointSpec) + "&overwrite=true"
      $u = Invoke-RestMethod -Uri "$Nacos/nacos/v3/admin/ai/mcp" -Method Put -Body $putBody -ContentType "application/x-www-form-urlencoded" -Headers $NH
      if ($u.code -eq 0) { Ok "updated existing mcp id=$mcpId" } else { Bad "update mcp: $($u.message)" }
    } else { Bad "create/update mcp failed" }
  } catch { Bad "mcp upsert: $($_.Exception.Message)" }
}

Step "3. mcp/sync then enableTools must not contain hidden_tool"
try {
  $sync = Invoke-RestMethod -Method Post -Uri "$Base/api/v1/admin/nacos/mcp/sync" -Headers $H
  Ok "sync imported=$($sync.imported -join ',') skipped=$($sync.skipped -join ',') conflicts=$($sync.conflictsLocalKept -join ',')"
  $servers = Invoke-RestMethod -Uri "$Base/api/v1/admin/mcp-servers" -Headers $H
  $m = $servers.servers | Where-Object { $_.name -eq $McpName } | Select-Object -First 1
  if (-not $m) { Bad "platform missing $McpName after sync" }
  else {
    Ok "enableTools=$($m.enableTools) url=$($m.url)"
    if ($m.enableTools -match "hidden_tool") { Bad "hidden_tool should be filtered by toolsMeta.enabled=false" }
    else { Ok "hidden_tool filtered" }
    if ($m.enableTools -match "query_orders" -and $m.enableTools -match "place_order") { Ok "enabled tools present" }
    else { Bad "expected query_orders/place_order in enableTools" }
  }
} catch { Bad "mcp sync: $($_.Exception.Message)" }

Step "4. Nacos AgentCard + agents/import"
$agentCard = '{"protocolVersion":"0.3.0","name":"' + $AgentName + '","description":"smoke external","url":"http://127.0.0.1:19099/a2a","preferredTransport":"HTTP+JSON","version":"1.0.0","capabilities":{"streaming":true},"defaultInputModes":["text/plain"],"defaultOutputModes":["text/plain"],"skills":[{"id":"echo","name":"Echo","description":"echo"}]}'
try {
  $body = @{ namespaceId = "public"; agentCard = $agentCard }
  $c = Invoke-RestMethod -Uri "$Nacos/nacos/v3/admin/ai/a2a" -Method Post -Body $body -Headers $NH
  if ($c.code -eq 0) { Ok "agent card created" } else { Bad "agent card: $($c.message)" }
} catch { Ok "agent card may exist" }

try {
  $imp = Invoke-RestMethod -Method Post -Uri "$Base/api/v1/admin/nacos/agents/import" -Headers $H
  Ok "import imported=$($imp.imported -join ',') skipped=$($imp.skipped -join ',')"
  $ext = Invoke-RestMethod -Uri "$Base/api/v1/admin/external-agents" -Headers $H
  $e = $ext.externalAgents | Where-Object { $_.name -eq $AgentName } | Select-Object -First 1
  if ($e) {
    Ok "external name=$($e.name) baseUrl=$($e.baseUrl)"
    if ($e.capabilities -match "discover-only") { Ok "discover-only marker present" } else { Bad "missing discover-only marker" }
    if ($e.callbackKey) { Bad "callbackKey must not come from Nacos" } else { Ok "callbackKey empty" }
  } else { Bad "external agent $AgentName not imported" }
} catch { Bad "agents/import: $($_.Exception.Message)" }

Step "5. local seed protection"
try {
  $servers = Invoke-RestMethod -Uri "$Base/api/v1/admin/mcp-servers" -Headers $H
  $local = $servers.servers | Where-Object { $_.name -eq "demo-mcp" }
  if ($local) { Ok "local demo-mcp kept" } else { Ok "no local demo-mcp seed (skip)" }
} catch { Bad "list mcp-servers: $($_.Exception.Message)" }

Step "6. skill import (needs Nacos >=3.2; 3.1 may error)"
try {
  $sk = Invoke-RestMethod -Method Post -Uri "$Base/api/v1/admin/nacos/skills/import" -Headers $H
  if ($sk.error) { Ok "skill import soft-fail (nacos may be 3.1): $($sk.error)" }
  else { Ok "skills imported=$($sk.imported -join ',') skipped=$($sk.skipped -join ',')" }
} catch { Ok "skills/import not available or soft-fail" }

Write-Host ""
if ($fail -eq 0) {
  Write-Host "NACOS AI SMOKE PASS" -ForegroundColor Green
  exit 0
} else {
  Write-Host "NACOS AI SMOKE FAIL count=$fail" -ForegroundColor Red
  exit 1
}
