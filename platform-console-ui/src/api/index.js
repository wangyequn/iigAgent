import { http, getOpenKey, getAdminKey, headers } from './http'

const open = () => ({ apiKey: getOpenKey() })
const admin = () => ({ apiKey: getAdminKey() })
const withKey = (apiKey) => ({ apiKey: apiKey || getOpenKey() })

/* ───────── Health ───────── */
export const healthReady = () => http.get('/api/v1/health/ready')
export const replicaInfo = () => http.get('/api/v1/health/replica')

/* ───────── Agents / Catalog ───────── */
export const listAgents = (apiKey) => http.get('/api/v1/agents', withKey(apiKey))
export const createAgent = (body) => http.post('/api/v1/admin/agents', body, admin())
export const updateAgent = (agentId, body) =>
  http.put(`/api/v1/admin/agents/${encodeURIComponent(agentId)}`, body, admin())
export const deleteAgent = (agentId) =>
  http.del(`/api/v1/admin/agents/${encodeURIComponent(agentId)}`, admin())
export const listVersions = (agentId) =>
  http.get(`/api/v1/agents/${agentId}/versions`, admin())
export const createVersion = (agentId, body) =>
  http.post(`/api/v1/admin/agents/${agentId}/versions`, body, admin())
export const publishVersion = (agentId, version) =>
  http.post(`/api/v1/admin/agents/${agentId}/versions/${version}/publish`, {}, admin())
export const runtimeInstances = () => http.get('/api/v1/admin/runtime/instances', admin())

/* ───────── Chat / Session ───────── */
export const chatOnce = (agentId, body, apiKey) =>
  http.post(`/api/v1/agents/${agentId}/messages`, body, withKey(apiKey))
export const sessionHistory = (agentId, sessionId, userId, apiKey) =>
  http.get(
    `/api/v1/agents/${agentId}/sessions/${sessionId}/history`,
    { ...withKey(apiKey), query: { userId } },
  )
export const interruptSession = (agentId, sessionId, userId) =>
  http.post(`/api/v1/agents/${agentId}/sessions/${sessionId}/interrupt`, { userId }, open())
export const clearContext = (agentId, sessionId, userId) =>
  http.post(`/api/v1/agents/${agentId}/sessions/${sessionId}/clear-context`, { userId }, open())
export const listSessionApprovals = (agentId, sessionId, userId) =>
  http.get(`/api/v1/agents/${agentId}/sessions/${sessionId}/approvals`, {
    ...open(),
    query: { userId },
  })
export const confirmSession = (agentId, sessionId, body) =>
  http.post(`/api/v1/agents/${agentId}/sessions/${sessionId}/confirm`, body, open())

/** POST SSE stream; onEvent(name, data, id) */
export async function streamChat(agentId, body, onEvent, lastEventId, apiKey) {
  const headersIn = headers(apiKey)
  if (lastEventId) headersIn['Last-Event-ID'] = lastEventId
  const res = await fetch(`/api/v1/agents/${agentId}/messages:stream`, {
    method: 'POST',
    headers: headersIn,
    body: JSON.stringify(body),
  })
  if (!res.ok) {
    const text = await res.text()
    const err = new Error(`stream failed: ${res.status}`)
    err.status = res.status
    err.body = text
    throw err
  }
  const reader = res.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buf = ''
  let lastId = lastEventId || null
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buf += decoder.decode(value, { stream: true })
    let idx
    while ((idx = buf.indexOf('\n\n')) >= 0) {
      const frame = buf.slice(0, idx)
      buf = buf.slice(idx + 2)
      let eventName = 'message'
      let id = null
      const dataLines = []
      for (const line of frame.split('\n')) {
        if (line.startsWith('id:')) id = line.slice(3).trim()
        else if (line.startsWith('event:')) eventName = line.slice(6).trim()
        else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
      }
      if (id) lastId = id
      if (dataLines.length) onEvent(eventName, dataLines.join('\n'), id)
    }
  }
  return lastId
}

/** Read-only session event stream (GET SSE). Returns abort fn. */
export function openSessionStream(agentId, sessionId, userId, onEvent, lastEventId) {
  const ctrl = new AbortController()
  const url =
    `/api/v1/agents/${agentId}/sessions/${sessionId}/stream` +
    `?userId=${encodeURIComponent(userId || '')}`

  ;(async () => {
    try {
      const headersIn = headers()
      if (lastEventId) headersIn['Last-Event-ID'] = lastEventId
      const res = await fetch(url, { headers: headersIn, signal: ctrl.signal })
      if (!res.ok) throw new Error(`session stream ${res.status}`)
      const reader = res.body.getReader()
      const decoder = new TextDecoder('utf-8')
      let buf = ''
      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        buf += decoder.decode(value, { stream: true })
        let idx
        while ((idx = buf.indexOf('\n\n')) >= 0) {
          const frame = buf.slice(0, idx)
          buf = buf.slice(idx + 2)
          let eventName = 'message'
          let id = null
          const dataLines = []
          for (const line of frame.split('\n')) {
            if (line.startsWith('id:')) id = line.slice(3).trim()
            else if (line.startsWith('event:')) eventName = line.slice(6).trim()
            else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
          }
          if (dataLines.length) onEvent(eventName, dataLines.join('\n'), id)
        }
      }
    } catch (e) {
      if (e.name !== 'AbortError') onEvent('ERROR', JSON.stringify({ message: e.message }), null)
    }
  })()

  return () => ctrl.abort()
}

/* ───────── HITL / External ───────── */
export const listInbox = () => http.get('/api/v1/admin/inbox', admin())
export const decideApproval = (id, confirmed, note) =>
  http.post(`/api/v1/admin/approvals/${id}/decide`, { confirmed, note }, admin())
export const listExternalAgents = () => http.get('/api/v1/admin/external-agents', admin())
export const registerExternalAgent = (body) =>
  http.post('/api/v1/admin/external-agents', body, admin())
export const heartbeatExternal = (id) =>
  http.post(`/api/v1/admin/external-agents/${id}/heartbeat`, {}, admin())

/* ───────── Skills ───────── */
export const listSkills = () => http.get('/api/v1/admin/skills', admin())
export const listVisibleSkills = () => http.get('/api/v1/admin/skills/visible', admin())
export const uploadSkill = (body) => http.post('/api/v1/admin/skills', body, admin())
export const approveSkill = (id, note) =>
  http.post(`/api/v1/admin/skills/${id}/approve`, { reviewer: 'console', note }, admin())
export const rejectSkill = (id, note) =>
  http.post(`/api/v1/admin/skills/${id}/reject`, { reviewer: 'console', note }, admin())
export const archiveSkill = (id) => http.post(`/api/v1/admin/skills/${id}/archive`, {}, admin())
export const setSkillVisible = (id, visible) =>
  http.post(`/api/v1/admin/skills/${id}/visible`, { visible }, admin())

/* ───────── Knowledge ───────── */
export const knowledgeStatus = () => http.get('/api/v1/admin/knowledge/status', admin())
export const listKnowledgeDocs = () => http.get('/api/v1/admin/knowledge/documents', admin())
export const uploadKnowledge = (title, content) =>
  http.post('/api/v1/admin/knowledge/documents', { title, content, source: 'console' }, admin())

/** 多文件上传（Dify 支持 PDF/DOCX/PPTX/XLSX/图片等；Simple 仅文本类） */
export async function uploadKnowledgeFiles(files) {
  const form = new FormData()
  for (const f of files) form.append('files', f)
  form.append('source', 'console')
  const uploadHeaders = headers()
  delete uploadHeaders['Content-Type']
  const res = await fetch('/api/v1/admin/knowledge/documents/file', {
    method: 'POST',
    headers: uploadHeaders,
    body: form,
  })
  const text = await res.text()
  if (!res.ok) {
    let code = ''
    try {
      code = JSON.parse(text).code || ''
    } catch {
      /* raw */
    }
    const err = new Error(code ? `${code} · upload failed` : `upload failed: ${res.status}`)
    err.status = res.status
    err.body = text
    throw err
  }
  return JSON.parse(text || '{}')
}
export const searchKnowledge = (query, topK = 5) =>
  http.get('/api/v1/admin/knowledge/search', { ...admin(), query: { query, topK } })

/* ───────── Governance ───────── */
export const listPermissions = (agentId) =>
  http.get('/api/v1/admin/governance/permissions', { ...admin(), query: { agentId } })
export const upsertPermission = (body) =>
  http.post('/api/v1/admin/governance/permissions', body, admin())
export const upsertPermissionsBatch = (items) =>
  http.post('/api/v1/admin/governance/permissions/batch', { items }, admin())
export const deletePermission = (body) =>
  http.del('/api/v1/admin/governance/permissions', { ...admin(), body })
export const setPermissionMode = (body) =>
  http.post('/api/v1/admin/governance/permission-mode', body, admin())
export const listMcpServers = () => http.get('/api/v1/admin/mcp-servers', admin())
export const registerMcpServer = (body) => http.post('/api/v1/admin/mcp-servers', body, admin())
export const mcpTest = (name) =>
  http.post(`/api/v1/admin/mcp-servers/${encodeURIComponent(name)}/test`, {}, admin())
export const listBusinessSystems = () => http.get('/api/v1/admin/systems', admin())
export const createBusinessSystem = (body) => http.post('/api/v1/admin/systems', body, admin())
export const updateBusinessSystem = (code, body) =>
  http.put(`/api/v1/admin/systems/${encodeURIComponent(code)}`, body, admin())
export const deleteBusinessSystem = (code) =>
  http.del(`/api/v1/admin/systems/${encodeURIComponent(code)}`, admin())
export const listBusinessTools = () => http.get('/api/v1/admin/business-tools', admin())
export const getBusinessTool = (toolName) =>
  http.get(`/api/v1/admin/business-tools/${encodeURIComponent(toolName)}`, admin())
export const registerBusinessTool = (body) =>
  http.post('/api/v1/admin/business-tools', body, admin())
export const patchBusinessTool = (toolName, body) =>
  http.patch(`/api/v1/admin/business-tools/${encodeURIComponent(toolName)}`, body, admin())
export const toolLifecycle = (toolName, body) =>
  http.post(`/api/v1/admin/business-tools/${encodeURIComponent(toolName)}/lifecycle`, body, admin())
export const testBusinessTool = (toolName) =>
  http.post(`/api/v1/admin/business-tools/${encodeURIComponent(toolName)}/test`, {}, admin())
export const cloneBusinessTool = (toolName, body) =>
  http.post(`/api/v1/admin/business-tools/${encodeURIComponent(toolName)}/clone`, body || {}, admin())
export const deleteBusinessTool = (toolName, hard = false) =>
  http.del(`/api/v1/admin/business-tools/${encodeURIComponent(toolName)}${hard ? '?hard=true' : ''}`, admin())
export const listToolBindings = (agentId) =>
  http.get(`/api/v1/admin/agents/${encodeURIComponent(agentId)}/tool-bindings`, admin())
export const upsertToolBinding = (agentId, body) =>
  http.post(`/api/v1/admin/agents/${encodeURIComponent(agentId)}/tool-bindings`, body, admin())
export const deleteToolBinding = (agentId, toolName) =>
  http.del(
    `/api/v1/admin/agents/${encodeURIComponent(agentId)}/tool-bindings/${encodeURIComponent(toolName)}`,
    admin(),
  )
export const putToolSecret = (refName, body) =>
  http.put(`/api/v1/admin/tool-credentials/${encodeURIComponent(refName)}`, body, admin())
export const deleteToolSecret = (refName) =>
  http.del(`/api/v1/admin/tool-credentials/${encodeURIComponent(refName)}`, admin())

/* ───────── IAM ───────── */
export const listIamRoles = () => http.get('/api/v1/admin/iam/roles', admin())
export const listIamUsers = () => http.get('/api/v1/admin/iam/users', admin())
export const assignIamRole = (userId, roleCode) =>
  http.post(`/api/v1/admin/iam/users/${encodeURIComponent(userId)}/roles/${roleCode}`, {}, admin())
export const revokeIamRole = (userId, roleCode) =>
  http.del(`/api/v1/admin/iam/users/${encodeURIComponent(userId)}/roles/${roleCode}`, admin())

/* ───────── Workflow / Team ───────── */
export const listWorkflows = () => http.get('/api/v1/workflows', admin())
export const upsertWorkflow = (workflowId, body) =>
  http.put(`/api/v1/workflows/${workflowId}`, body, admin())
export const publishWorkflowRevision = (workflowId) =>
  http.post(`/api/v1/workflows/${workflowId}/revisions`, {}, admin())
export const startWorkflowRun = (workflowId, body) =>
  http.post(`/api/v1/workflows/${workflowId}/runs`, body, admin())
export const listRuns = (limit = 50) => http.get(`/api/v1/runs`, { ...admin(), query: { limit } })
export const getRun = (runId) => http.get(`/api/v1/runs/${runId}`, admin())
export const getRunEvents = (runId) => http.get(`/api/v1/runs/${runId}/events`, admin())
export const confirmRun = (runId, approved, note) =>
  http.post(`/api/v1/runs/${runId}/confirm`, { approved, note }, admin())
export const cancelRun = (runId) => http.post(`/api/v1/runs/${runId}/cancel`, {}, admin())
export const listTeams = () => http.get('/api/v1/teams', admin())
export const upsertTeam = (teamId, body) => http.put(`/api/v1/teams/${teamId}`, body, admin())
export const createTeamTask = (teamId, goal) =>
  http.post(`/api/v1/teams/${teamId}/tasks`, { goal }, admin())

/* ───────── Ops / Billing ───────── */
export const metricsSummary = () => http.get('/api/v1/admin/metrics/summary', admin())
export const otelStatus = () => http.get('/api/v1/admin/otel', admin())
export const nacosStatus = () => http.get('/api/v1/admin/nacos', admin())
export const lokiStatus = () => http.get('/api/v1/admin/loki/status', admin())
export const lokiSearch = (sessionId, limit = 30) =>
  http.get('/api/v1/admin/loki/search', { ...admin(), query: { sessionId, limit } })
export const getConfig = () => http.get('/api/v1/admin/config', admin())
export const updateConfig = (patch) => http.post('/api/v1/admin/config', patch, admin())
export const listAudit = (limit = 50, filters = {}) =>
  http.get('/api/v1/admin/audit', {
    ...admin(),
    query: {
      limit,
      toolName: filters.toolName,
      resourceId: filters.resourceId,
      action: filters.action,
    },
  })
export const sandboxStatus = () => http.get('/api/v1/admin/sandbox/status', admin())
export const sandboxExec = (command) => http.post('/api/v1/admin/sandbox/exec', { command }, admin())
export const fsStatus = () => http.get('/api/v1/admin/fs/status', admin())
export const fsItems = (limit = 30) =>
  http.get('/api/v1/admin/fs/items', { ...admin(), query: { limit } })
export const listModelCredentials = () => http.get('/api/v1/admin/model-credentials', admin())
export const upsertModelCredential = (body) =>
  http.post('/api/v1/admin/model-credentials', body, admin())
export const deleteModelCredential = (provider) =>
  http.del(`/api/v1/admin/model-credentials/${encodeURIComponent(provider)}`, admin())
export const getQuotas = () => http.get('/api/v1/admin/quotas', admin())
export const upsertQuota = (body) => http.post('/api/v1/admin/quotas', body, admin())
export const getUsage = (limit = 50) =>
  http.get('/api/v1/admin/usage', { ...admin(), query: { limit } })
export const getUsageReport = (year, month) =>
  http.get('/api/v1/admin/usage/report', { ...admin(), query: { year, month } })
export async function exportUsageCsv(from, to) {
  const res = await fetch(
    `/api/v1/admin/usage/export?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`,
    { headers: headers(getAdminKey()) },
  )
  if (!res.ok) throw new Error(`export failed: ${res.status}`)
  return res.text()
}
