# 注册登录平台（pc_platform）首批只读业务工具到 AI 中台
# 用法：
#   $env:AMP_BASE = "http://127.0.0.1:18081"
#   $env:AMP_ADMIN_KEY = "s0-admin-key"
#   $env:JEECG_BASE = "http://127.0.0.1:8080"   # 登录平台网关/系统服务
#   powershell -File scripts/register-pc-platform-tools.ps1
# 也可选 $env:AGENT_ID 后补权限规则（默认 biz-assist）。

$ErrorActionPreference = "Stop"

$base = if ($env:AMP_BASE) { $env:AMP_BASE.TrimEnd('/') } else { "http://127.0.0.1:18081" }
$adminKey = $env:AMP_ADMIN_KEY
if (-not $adminKey) { throw "set AMP_ADMIN_API_KEY / AMP_ADMIN_KEY first" }
$jeecg = if ($env:JEECG_BASE) { $env:JEECG_BASE.TrimEnd('/') } else { throw "set JEECG_BASE (pc_platform base url)" }
$agentId = if ($env:AGENT_ID) { $env:AGENT_ID } else { "biz-assist" }

$headers = @{
  Authorization  = "Bearer $adminKey"
  "Content-Type" = "application/json"
}

function Register-Tool([string]$name, [string]$desc, [string]$configJson) {
  $body = @{
    toolName    = $name
    description = $desc
    kind        = "HTTP"
    config      = $configJson
  } | ConvertTo-Json -Depth 8
  $uri = "$base/api/v1/admin/governance/business-tools"
  try {
    $r = Invoke-RestMethod -Method Post -Uri $uri -Headers $headers -Body $body
    Write-Host "OK  $name -> $($r.status)"
  } catch {
    Write-Host "FAIL $name : $($_.Exception.Message)"
  }
}

function Set-Rule([string]$tool, [string]$behavior = "ALLOW") {
  $body = @{
    agentId     = $agentId
    toolName    = $tool
    behavior    = $behavior
    ruleContent = "*"
    source      = "script"
  } | ConvertTo-Json
  $uri = "$base/api/v1/admin/governance/permissions"
  try {
    Invoke-RestMethod -Method Post -Uri $uri -Headers $headers -Body $body | Out-Null
    Write-Host "RULE $tool $behavior"
  } catch {
    Write-Host "RULE FAIL $tool : $($_.Exception.Message)"
  }
}

function Http-Get([string]$path, [string]$propsJson = "{}") {
  @{
    url         = "$jeecg$path"
    method      = "GET"
    readOnly    = $true
    risk        = "low"
    authMode    = "user_token"
    ownerSystem = "pc_platform"
    parameters  = @{ type = "object"; properties = ($propsJson | ConvertFrom-Json) }
  } | ConvertTo-Json -Depth 8 -Compress
}

Write-Host "AMP=$base  JEECG=$jeecg  AGENT=$agentId"

Register-Tool "query_my_info" "查询当前登录用户信息（透传用户票）" (Http-Get "/sys/user/getUserInfo")
Register-Tool "query_user_by_name" "按姓名/账号查询用户列表" (Http-Get "/sys/user/list" (@{
  realname = @{ type = "string"; description = "姓名" }
  username = @{ type = "string"; description = "账号" }
  pageNo   = @{ type = "integer"; description = "页码" }
  pageSize = @{ type = "integer"; description = "每页条数" }
} | ConvertTo-Json -Depth 5 -Compress))
Register-Tool "query_dept_tree" "查询我的部门树" (Http-Get "/sys/sysDepart/queryMyDeptTreeList")
Register-Tool "query_dept_users" "按部门 ID 查询用户" (Http-Get "/sys/sysDepart/getUsersByDepartId" (@{
  id = @{ type = "string"; description = "部门 ID" }
} | ConvertTo-Json -Depth 5 -Compress))
Register-Tool "query_roles" "查询当前用户角色" (Http-Get "/sys/role/getRolesByToken")
Register-Tool "query_my_menus" "查询当前用户菜单/权限" (Http-Get "/sys/permission/getUserPermissionByToken")
Register-Tool "query_dict" "查询字典项（dictCode 路径参数）" (Http-Get "/sys/dict/getDictItems/{dictCode}" (@{
  dictCode = @{ type = "string"; description = "字典编码" }
} | ConvertTo-Json -Depth 5 -Compress))
Register-Tool "query_announcements" "查询我的公告列表" (Http-Get "/sys/annountCement/listByUser")

foreach ($t in @(
    "query_my_info", "query_user_by_name", "query_dept_tree", "query_dept_users",
    "query_roles", "query_my_menus", "query_dict", "query_announcements"
  )) {
  Set-Rule $t "ALLOW"
}

Write-Host "done. list: GET $base/api/v1/admin/governance/business-tools"
