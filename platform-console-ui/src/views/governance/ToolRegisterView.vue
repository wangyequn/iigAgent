<script setup>
import { computed, reactive, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import PageHeader from '@/components/ui/PageHeader.vue'
import { registerBusinessTool, registerMcpServer, listBusinessSystems } from '@/api'
import { ElMessage } from 'element-plus'

const router = useRouter()
const route = useRoute()
const step = ref(0)
const kind = ref(route.query.type === 'mcp' ? 'MCP' : 'HTTP')
const systems = ref([])
const saving = ref(false)

listBusinessSystems()
  .then((r) => {
    systems.value = r.systems || []
  })
  .catch(() => {})

const form = reactive({
  toolName: '',
  description: '',
  ownerSystem: '',
  owner: '',
  url: '',
  method: 'GET',
  headersJson: '{}',
  risk: 'low',
  authMode: 'user_token',
  permCode: '',
  readOnly: true,
  paramsJson: '{\n  "type": "object",\n  "properties": {}\n}',
  lifecycle: 'ENABLED',
  env: 'prod',
  timeoutMs: 30000,
  maxRetries: 0,
  rateLimitQps: null,
  tags: '',
  secretRef: '',
  systemCode: '',
  name: '',
  transport: 'stdio',
  command: '',
  args: '',
  mcpUrl: '',
  enableTools: '',
  mcpHeadersJson: '{}',
})

const isHttp = computed(() => kind.value === 'HTTP')
const isMutating = computed(() => !['GET', 'HEAD'].includes(form.method))

const steps = computed(() =>
  isHttp.value
    ? ['类型', '连接', '安全与韧性', '预检提交']
    : ['类型', '连接', '工具白名单', '预检提交'],
)

const riskOptions = [
  { value: 'low', label: 'low · 低', hint: '只读查询' },
  { value: 'medium', label: 'medium · 中', hint: '默认写接口' },
  { value: 'high', label: 'high · 高', hint: '需 ASK 确认' },
  { value: 'forbidden', label: 'forbidden · 禁止', hint: '不可注册' },
]

const authOptions = [
  { value: 'user_token', label: '用户票', hint: '推荐 · 透传 X-Access-Token' },
  { value: 'client', label: '客户端凭证', hint: '仅只读 · 禁写' },
  { value: 'none', label: '无鉴权', hint: '不推荐' },
]

const canNext = computed(() => {
  if (step.value === 0) return !!kind.value
  if (step.value === 1) {
    if (isHttp.value) return !!form.toolName && !!form.url
    return !!form.name
  }
  if (step.value === 2) {
    if (!isHttp.value) return !!form.enableTools.trim()
    return form.risk !== 'forbidden'
  }
  return true
})

function next() {
  if (step.value < 3) step.value += 1
}
function prev() {
  if (step.value > 0) step.value -= 1
}

function validateClientWrite() {
  if (isHttp.value && form.authMode === 'client' && isMutating.value) {
    ElMessage.error('客户端凭证不能调用写接口')
    return false
  }
  if (isHttp.value && form.risk === 'high' && form.authMode === 'client') {
    ElMessage.error('高风险工具禁止使用客户端凭证')
    return false
  }
  return true
}

async function submit() {
  if (!validateClientWrite()) return
  saving.value = true
  try {
    if (isHttp.value) {
      let headers = {}
      let params = {}
      try {
        headers = JSON.parse(form.headersJson || '{}')
        params = JSON.parse(form.paramsJson || '{}')
      } catch {
        ElMessage.error('headers / parameters 必须是合法 JSON')
        saving.value = false
        return
      }
      const config = {
        url: form.url,
        method: form.method,
        headers,
        readOnly: form.readOnly,
        risk: form.risk,
        authMode: form.authMode,
        permCode: form.permCode,
        ownerSystem: form.ownerSystem,
        parameters: params,
      }
      await registerBusinessTool({
        toolName: form.toolName,
        description: form.description || form.toolName,
        kind: 'HTTP',
        config: JSON.stringify(config),
        lifecycle: form.lifecycle,
        env: form.env,
        owner: form.owner || form.ownerSystem,
        secretRef: form.secretRef || null,
        timeoutMs: Number(form.timeoutMs) || 30000,
        maxRetries: Number(form.maxRetries) || 0,
        rateLimitQps: form.rateLimitQps == null || form.rateLimitQps === '' ? null : Number(form.rateLimitQps),
        tags: form.tags,
        updatedBy: 'console',
        systemCode: form.systemCode || null,
      })
      ElMessage.success('工具已登记')
    } else {
      await registerMcpServer({
        name: form.name,
        transport: form.transport,
        command: form.command,
        args: form.args,
        url: form.mcpUrl,
        enableTools: form.enableTools,
        headers: JSON.parse(form.mcpHeadersJson || '{}'),
      })
      ElMessage.success('MCP 已登记')
    }
    router.push('/governance/tools')
  } catch (e) {
    ElMessage.error(e.message || '提交失败')
  } finally {
    saving.value = false
  }
}

const summaryRows = computed(() => {
  if (isHttp.value) {
    return [
      { k: '类型', v: 'HTTP 业务工具' },
      { k: '工具名', v: form.toolName || '—' },
      { k: '连接', v: `${form.method} ${form.url || '—'}` },
      { k: '风险 / 鉴权', v: `${form.risk} / ${form.authMode}` },
      { k: '只读', v: form.readOnly ? '是' : '否' },
      { k: '生命周期 / 环境', v: `${form.lifecycle} / ${form.env}` },
      { k: '超时 / 重试', v: `${form.timeoutMs}ms / ${form.maxRetries}` },
      { k: '业务系统', v: form.systemCode || '—' },
      { k: '责任人', v: form.owner || form.ownerSystem || '—' },
      { k: '密钥引用', v: form.secretRef || '—' },
    ]
  }
  return [
    { k: '类型', v: 'MCP Server' },
    { k: '名称', v: form.name || '—' },
    { k: '传输', v: form.transport },
    { k: '地址', v: form.transport === 'stdio' ? `${form.command} ${form.args}` : form.mcpUrl },
    { k: 'enableTools', v: form.enableTools || '—' },
  ]
})
</script>

<template>
  <PageHeader
    title="接入工具"
    back="/governance/tools"
    back-label="工具中心"
    desc="HTTP 业务接口或 MCP Server 一次登记。授权在中台，业务鉴权仍在所属系统。"
  />

  <!-- 步骤条 -->
  <div class="card steps-bar">
    <div
      v-for="(s, i) in steps"
      :key="s"
      class="step-item"
      :class="{ on: i === step, done: i < step }"
      @click="i < step && (step = i)"
    >
      <div class="step-no">
        <span v-if="i < step">✓</span>
        <span v-else>{{ i + 1 }}</span>
      </div>
      <div class="step-txt">{{ s }}</div>
      <div v-if="i < steps.length - 1" class="step-line" />
    </div>
  </div>

  <!-- Step 0 类型 -->
  <div v-if="step === 0" class="card step-panel">
    <div class="kind-grid">
      <button
        type="button"
        class="kind-card"
        :class="{ on: kind === 'HTTP' }"
        @click="kind = 'HTTP'"
      >
        <div class="kind-icon">HTTP</div>
        <div class="kind-title">HTTP 业务工具</div>
        <div class="kind-desc">按 URL / Method 调用业务 API，支持用户票透传、风险分级、超时重试</div>
        <ul class="kind-list">
          <li>适合订单 / 用户 / 字典等 REST</li>
          <li>可配置 secretRef，禁止明文密钥</li>
        </ul>
      </button>
      <button
        type="button"
        class="kind-card"
        :class="{ on: kind === 'MCP' }"
        @click="kind = 'MCP'"
      >
        <div class="kind-icon">MCP</div>
        <div class="kind-title">MCP Server</div>
        <div class="kind-desc">注册 stdio / HTTP 传输的 MCP，并显式声明 enableTools 白名单</div>
        <ul class="kind-list">
          <li>未列出的工具不会注入 Toolkit</li>
          <li>登记后建议先做连通测试</li>
        </ul>
      </button>
    </div>
  </div>

  <!-- Step 1 连接 -->
  <div v-if="step === 1" class="card step-panel">
    <template v-if="isHttp">
      <div class="form-section">
        <div class="form-section-title">基本</div>
        <el-form label-width="110px" class="reg-form">
          <div class="col-2">
            <el-form-item label="工具名" required>
              <el-input v-model="form.toolName" placeholder="order_query" />
            </el-form-item>
            <el-form-item label="所属系统">
              <el-select v-model="form.systemCode" clearable placeholder="选择业务系统" style="width: 100%">
                <el-option
                  v-for="s in systems"
                  :key="s.systemCode"
                  :label="`${s.name}（${s.systemCode}）`"
                  :value="s.systemCode"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="系统备注">
              <el-input v-model="form.ownerSystem" placeholder="可选，补充说明" />
            </el-form-item>
          </div>
          <el-form-item label="描述">
            <el-input v-model="form.description" type="textarea" :rows="2" placeholder="一句话说明能力边界" />
          </el-form-item>
        </el-form>
      </div>
      <div class="form-section">
        <div class="form-section-title">连接</div>
        <el-form label-width="110px" class="reg-form">
          <div class="col-2">
            <el-form-item label="Method">
              <el-select v-model="form.method" style="width: 100%">
                <el-option v-for="m in ['GET', 'POST', 'PUT', 'DELETE', 'HEAD']" :key="m" :label="m" :value="m" />
              </el-select>
            </el-form-item>
            <el-form-item label="URL" required>
              <el-input v-model="form.url" placeholder="http://order-svc/api/orders" />
            </el-form-item>
          </div>
          <el-form-item label="静态 Headers">
            <el-input v-model="form.headersJson" type="textarea" :rows="3" class="mono-input" />
            <div class="field-hint">user_token 自动注入；Authorization / X-Api-Key 请用 secretRef，勿写明文</div>
          </el-form-item>
        </el-form>
      </div>
    </template>
    <template v-else>
      <div class="form-section">
        <div class="form-section-title">连接</div>
        <el-form label-width="110px" class="reg-form">
          <div class="col-2">
            <el-form-item label="名称" required>
              <el-input v-model="form.name" placeholder="demo-mcp" />
            </el-form-item>
            <el-form-item label="传输">
              <el-select v-model="form.transport" style="width: 100%">
                <el-option label="stdio" value="stdio" />
                <el-option label="sse" value="sse" />
                <el-option label="streamable_http" value="streamable_http" />
              </el-select>
            </el-form-item>
          </div>
          <div v-if="form.transport === 'stdio'" class="col-2">
            <el-form-item label="command">
              <el-input v-model="form.command" placeholder="python" />
            </el-form-item>
            <el-form-item label="args">
              <el-input v-model="form.args" placeholder="scripts/mcp-demo-server.py" />
            </el-form-item>
          </div>
          <el-form-item v-else label="url">
            <el-input v-model="form.mcpUrl" placeholder="http://127.0.0.1:19099/mcp" />
          </el-form-item>
          <el-form-item label="静态 Headers">
            <el-input v-model="form.mcpHeadersJson" type="textarea" :rows="3" class="mono-input" />
          </el-form-item>
        </el-form>
      </div>
    </template>
  </div>

  <!-- Step 2 安全 / 白名单 -->
  <div v-if="step === 2" class="card step-panel">
    <template v-if="isHttp">
      <div class="form-section">
        <div class="form-section-title">安全</div>
        <div class="pick-label">风险等级</div>
        <div class="pick-row">
          <button
            v-for="o in riskOptions"
            :key="o.value"
            type="button"
            class="pick"
            :class="{ on: form.risk === o.value, danger: o.value === 'high' || o.value === 'forbidden' }"
            @click="form.risk = o.value"
          >
            <div class="pick-t">{{ o.label }}</div>
            <div class="pick-h">{{ o.hint }}</div>
          </button>
        </div>
        <div class="pick-label" style="margin-top: 16px">鉴权方式</div>
        <div class="pick-row">
          <button
            v-for="o in authOptions"
            :key="o.value"
            type="button"
            class="pick"
            :class="{ on: form.authMode === o.value }"
            @click="form.authMode = o.value"
          >
            <div class="pick-t">{{ o.label }}</div>
            <div class="pick-h">{{ o.hint }}</div>
          </button>
        </div>
        <el-alert
          v-if="form.risk === 'high'"
          type="warning"
          :closable="false"
          title="高风险工具默认 ASK，且禁止 authMode=client"
          style="margin-top: 14px"
        />
      </div>
      <div class="form-section">
        <div class="form-section-title">韧性与生产化</div>
        <el-form label-width="110px" class="reg-form">
          <div class="col-3">
            <el-form-item label="生命周期">
              <el-select v-model="form.lifecycle" style="width: 100%">
                <el-option label="启用 ENABLED" value="ENABLED" />
                <el-option label="草稿 DRAFT" value="DRAFT" />
                <el-option label="停用 DISABLED" value="DISABLED" />
              </el-select>
            </el-form-item>
            <el-form-item label="环境">
              <el-select v-model="form.env" style="width: 100%">
                <el-option label="prod" value="prod" />
                <el-option label="test" value="test" />
                <el-option label="dev" value="dev" />
              </el-select>
            </el-form-item>
            <el-form-item label="只读">
              <el-switch v-model="form.readOnly" />
            </el-form-item>
          </div>
          <div class="col-3">
            <el-form-item label="超时 ms">
              <el-input-number v-model="form.timeoutMs" :min="1000" :max="120000" :step="1000" style="width: 100%" />
            </el-form-item>
            <el-form-item label="只读重试">
              <el-input-number v-model="form.maxRetries" :min="0" :max="3" style="width: 100%" />
            </el-form-item>
            <el-form-item label="限流 QPS">
              <el-input-number v-model="form.rateLimitQps" :min="0" :max="10000" style="width: 100%" />
            </el-form-item>
          </div>
          <div class="col-2">
            <el-form-item label="责任人">
              <el-input v-model="form.owner" placeholder="姓名 / 团队" />
            </el-form-item>
            <el-form-item label="标签">
              <el-input v-model="form.tags" placeholder="order, readonly" />
            </el-form-item>
          </div>
          <div class="col-2">
            <el-form-item label="permCode">
              <el-input v-model="form.permCode" placeholder="order:query（可选）" />
            </el-form-item>
            <el-form-item label="密钥引用">
              <el-input v-model="form.secretRef" placeholder="secretRef（禁明文密钥）" />
            </el-form-item>
          </div>
          <el-form-item label="参数 Schema">
            <el-input v-model="form.paramsJson" type="textarea" :rows="5" class="mono-input" />
          </el-form-item>
        </el-form>
      </div>
    </template>
    <template v-else>
      <div class="form-section">
        <div class="form-section-title">工具白名单（必填）</div>
        <el-input
          v-model="form.enableTools"
          type="textarea"
          :rows="4"
          placeholder="echo_time, list_files"
          class="mono-input"
        />
        <div class="field-hint">
          用逗号分隔。<b>未列出的工具不会注入 Agent Toolkit</b>，登记后可在工具中心做连通测试。
        </div>
      </div>
    </template>
  </div>

  <!-- Step 3 预检 -->
  <div v-if="step === 3" class="card step-panel">
    <div class="form-section-title" style="margin-bottom: 12px">确认信息</div>
    <div class="summary-grid">
      <div v-for="r in summaryRows" :key="r.k" class="summary-item">
        <div class="summary-k">{{ r.k }}</div>
        <div class="summary-v">{{ r.v }}</div>
      </div>
    </div>
    <el-alert
      type="info"
      :closable="false"
      title="提交后会立即写入目录；HTTP 工具可回工具中心试调用，MCP 建议先连通测试。"
      style="margin-top: 16px"
    />
  </div>

  <!-- 底栏 -->
  <div class="card footer-bar">
    <el-button v-if="step > 0" @click="prev">上一步</el-button>
    <div class="view-spacer" />
    <el-button @click="router.push('/governance/tools')">取消</el-button>
    <el-button v-if="step < 3" type="primary" :disabled="!canNext" @click="next">
      下一步
    </el-button>
    <el-button v-else type="primary" :loading="saving" @click="submit">
      {{ isHttp ? '登记工具' : '登记 MCP' }}
    </el-button>
  </div>
</template>

<style scoped>
.steps-bar {
  display: flex;
  align-items: center;
  gap: 0;
  padding: 18px 24px;
  margin-bottom: 12px;
}
.step-item {
  display: flex;
  align-items: center;
  gap: 10px;
  cursor: default;
  flex: 1;
}
.step-item.done {
  cursor: pointer;
}
.step-no {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 600;
  background: var(--bg-subtle);
  color: var(--ink-3);
  flex-shrink: 0;
}
.step-item.on .step-no {
  background: var(--brand);
  color: #fff;
  box-shadow: var(--shadow-focus);
}
.step-item.done .step-no {
  background: var(--brand-soft);
  color: var(--brand);
}
.step-txt {
  font-size: 13px;
  color: var(--ink-3);
  white-space: nowrap;
}
.step-item.on .step-txt {
  color: var(--ink-1);
  font-weight: 600;
}
.step-item.done .step-txt {
  color: var(--ink-2);
}
.step-line {
  flex: 1;
  height: 2px;
  background: var(--line);
  margin: 0 12px;
  border-radius: 2px;
}
.step-item.done .step-line {
  background: color-mix(in srgb, var(--brand) 35%, var(--line));
}

.step-panel {
  padding: 20px 24px 24px;
}
.kind-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
.kind-card {
  text-align: left;
  padding: 22px;
  border-radius: var(--radius-lg);
  border: 2px solid var(--line);
  background: var(--bg-surface);
  cursor: pointer;
  transition: all 0.15s;
}
.kind-card:hover {
  border-color: color-mix(in srgb, var(--brand) 40%, var(--line));
  background: var(--brand-softer);
}
.kind-card.on {
  border-color: var(--brand);
  background: var(--brand-soft);
  box-shadow: var(--shadow-focus);
}
.kind-icon {
  display: inline-flex;
  align-items: center;
  height: 24px;
  padding: 0 10px;
  border-radius: 999px;
  background: var(--brand);
  color: #fff;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.04em;
  margin-bottom: 12px;
}
.kind-title {
  font-size: 17px;
  font-weight: 700;
  color: var(--ink-1);
  margin-bottom: 6px;
}
.kind-desc {
  font-size: 13px;
  color: var(--ink-2);
  line-height: 1.55;
  margin-bottom: 12px;
}
.kind-list {
  margin: 0;
  padding-left: 18px;
  color: var(--ink-3);
  font-size: 12px;
  line-height: 1.7;
}

.form-section + .form-section {
  margin-top: 22px;
  padding-top: 18px;
  border-top: 1px solid var(--line);
}
.form-section-title {
  font-size: 13px;
  font-weight: 700;
  color: var(--brand);
  margin-bottom: 14px;
  letter-spacing: 0.02em;
}
.col-2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 16px;
}
.col-3 {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr;
  gap: 0 16px;
}
.mono-input :deep(textarea),
.mono-input :deep(input) {
  font-family: var(--font-mono);
  font-size: 12px;
}
.field-hint {
  font-size: 12px;
  color: var(--ink-3);
  line-height: 1.5;
  margin-top: 4px;
}
.field-hint b {
  color: var(--warn);
}

.pick-label {
  font-size: 12px;
  color: var(--ink-2);
  font-weight: 600;
  margin-bottom: 8px;
}
.pick-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
}
.pick {
  text-align: left;
  padding: 12px 14px;
  border-radius: var(--radius-md);
  border: 1.5px solid var(--line);
  background: var(--bg-surface);
  cursor: pointer;
  transition: all 0.15s;
}
.pick:hover {
  border-color: color-mix(in srgb, var(--brand) 35%, var(--line));
}
.pick.on {
  border-color: var(--brand);
  background: var(--brand-soft);
}
.pick.danger.on {
  border-color: var(--danger);
  background: var(--danger-soft);
}
.pick-t {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-1);
}
.pick-h {
  font-size: 11px;
  color: var(--ink-3);
  margin-top: 2px;
}

.summary-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px 20px;
}
.summary-item {
  display: flex;
  gap: 12px;
  padding: 10px 12px;
  border-radius: var(--radius-sm);
  background: var(--bg-subtle);
}
.summary-k {
  width: 110px;
  flex-shrink: 0;
  font-size: 12px;
  color: var(--ink-3);
}
.summary-v {
  font-size: 13px;
  color: var(--ink-1);
  font-weight: 500;
  word-break: break-all;
  font-family: var(--font-mono);
}

.footer-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
  padding: 12px 16px;
}
.view-spacer {
  flex: 1;
}

@media (max-width: 960px) {
  .kind-grid,
  .col-2,
  .col-3,
  .pick-row,
  .summary-grid {
    grid-template-columns: 1fr;
  }
}
</style>
