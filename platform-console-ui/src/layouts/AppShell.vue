<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAppStore } from '@/stores/app'
import { ElMessage } from 'element-plus'
import { listSessionApprovals, listInbox } from '@/api'
import ConsoleBot from '@/components/bot/ConsoleBot.vue'

const store = useAppStore()
const route = useRoute()
const router = useRouter()

const collapsed = ref(false)
const identityOpen = ref(false)
const notifyOpen = ref(false)
const commandQuery = ref('')
const commandInput = ref(null)

const navGroups = [
  {
    label: '工作台',
    items: [
      { path: '/workbench/chat', name: 'chat', icon: 'ChatDotRound', label: '智能对话' },
      { path: '/workbench/approvals', name: 'approvals', icon: 'Bell', label: '审批中心' },
      { path: '/workbench/knowledge', name: 'knowledge', icon: 'Collection', label: '知识库' },
    ],
  },
  {
    label: 'Agent 中心',
    items: [
      { path: '/agents', name: 'agents', icon: 'UserFilled', label: 'Agent 列表' },
      { path: '/skills', name: 'skills', icon: 'Grid', label: '技能市场' },
      { path: '/workflows', name: 'workflows', icon: 'Operation', label: '编排 Workflow' },
      { path: '/teams', name: 'teams', icon: 'Connection', label: 'Team 协作' },
    ],
  },
  {
    label: '治理中心',
    items: [
      { path: '/governance/permissions', name: 'permissions', icon: 'Lock', label: '权限与模式' },
      { path: '/governance/tools', name: 'tools', icon: 'Tools', label: 'MCP 与工具' },
      { path: '/governance/channels', name: 'channels', icon: 'Promotion', label: '渠道 Channel' },
      { path: '/governance/external', name: 'external', icon: 'Link', label: 'External Agent' },
      { path: '/governance/iam', name: 'iam', icon: 'Avatar', label: 'IAM / RBAC' },
    ],
  },
  {
    label: '运营中心',
    items: [
      { path: '/ops/overview', name: 'ops-overview', icon: 'Odometer', label: '运行概览' },
      { path: '/ops/sessions', name: 'ops-sessions', icon: 'Document', label: '会话与日志' },
      { path: '/ops/billing', name: 'ops-billing', icon: 'Wallet', label: '配额与用量' },
      { path: '/ops/audit', name: 'ops-audit', icon: 'Tickets', label: '审计' },
      { path: '/ops/sandbox', name: 'ops-sandbox', icon: 'Monitor', label: '沙箱 / FS' },
      { path: '/ops/credentials', name: 'ops-credentials', icon: 'Key', label: '模型凭证' },
    ],
  },
  {
    label: '系统',
    items: [
      { path: '/system/health', name: 'system-health', icon: 'CircleCheck', label: '环境与就绪' },
      { path: '/system/config', name: 'system-config', icon: 'Setting', label: '配置热更' },
      { path: '/system/openai', name: 'system-openai', icon: 'MagicStick', label: 'OpenAI 调试' },
    ],
  },
]

const flatNav = navGroups.flatMap((g) => g.items)

const readyOk = computed(() => {
  const r = store.ready
  return r?.status === 'UP' || r?.ready === true || r?.status === 'ready'
})

const unread = computed(() => store.unreadCount())

const filteredCommands = computed(() => {
  const q = commandQuery.value.trim().toLowerCase()
  const list = flatNav.map((n) => ({ type: '页面', label: n.label, path: n.path, icon: n.icon }))
  if (!q) return list.slice(0, 12)
  return list.filter((n) => n.label.toLowerCase().includes(q) || n.path.includes(q)).slice(0, 12)
})

function go(path) {
  store.commandOpen = false
  commandQuery.value = ''
  if (route.path === path) {
    // 同路径强制重载当前页，避免像「点了没反应」
    router.replace({ path, query: { _t: Date.now() } })
    return
  }
  router.push(path)
}

function onKeydown(e) {
  if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
    e.preventDefault()
    store.commandOpen = !store.commandOpen
    if (store.commandOpen) {
      setTimeout(() => commandInput.value?.focus(), 30)
    }
  }
  if (e.key === 'Escape') {
    store.commandOpen = false
  }
}

async function refreshPending() {
  try {
    const inbox = await listInbox()
    const items = inbox.items || inbox.approvals || []
    const pending = items.filter((x) => !x.status || x.status === 'PENDING' || x.status === 'WAITING')
    if (pending.length) {
      store.notify({
        type: 'warning',
        title: `${pending.length} 条待审批`,
        message: pending[0]?.toolName || '工具调用等待人工确认',
      })
    }
  } catch {
    /* admin optional */
  }
}

onMounted(async () => {
  window.addEventListener('keydown', onKeydown)
  await store.refreshHealth()
  refreshPending()
})

onUnmounted(() => window.removeEventListener('keydown', onKeydown))
</script>

<template>
  <div class="shell" :class="{ collapsed }">
    <aside class="side">
      <div class="brand" @click="go('/workbench/chat')">
        <div class="brand-mark">AMP</div>
        <div v-if="!collapsed" class="brand-text">
          <div class="brand-title">AI 中台</div>
          <div class="brand-sub">Agent Console</div>
        </div>
      </div>

      <nav class="nav">
        <div v-for="g in navGroups" :key="g.label" class="nav-group">
          <div v-if="!collapsed" class="nav-group-label">{{ g.label }}</div>
          <a
            v-for="item in g.items"
            :key="item.name"
            href="javascript:void(0)"
            class="nav-item"
            :class="{ active: route.path === item.path || route.path.startsWith(item.path + '/') }"
            :title="item.label"
            @click.prevent="go(item.path)"
          >
            <el-icon :size="18"><component :is="item.icon" /></el-icon>
            <span v-if="!collapsed" class="nav-label">{{ item.label }}</span>
          </a>
        </div>
      </nav>

      <button class="collapse-btn" type="button" @click="collapsed = !collapsed">
        <el-icon><component :is="collapsed ? 'Expand' : 'Fold'" /></el-icon>
        <span v-if="!collapsed">收起导航</span>
      </button>
    </aside>

    <div class="main">
      <header class="top">
        <div class="top-left">
          <el-breadcrumb separator="/">
            <el-breadcrumb-item :to="{ path: '/workbench/chat' }">{{ route.meta?.group || '工作台' }}</el-breadcrumb-item>
            <el-breadcrumb-item>{{ route.meta?.title || 'Console' }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>

        <div class="top-right">
          <button class="ghost-btn command-trigger" type="button" @click="store.commandOpen = true">
            <el-icon><Search /></el-icon>
            <span>搜索页面…</span>
            <kbd>Ctrl K</kbd>
          </button>

          <el-tag :type="readyOk ? 'success' : 'danger'" size="small" effect="light" round>
            {{ readyOk ? '服务就绪' : '未就绪' }}
          </el-tag>

          <el-badge :value="unread" :hidden="!unread" :max="9">
            <button class="icon-btn" type="button" @click="notifyOpen = true" title="通知">
              <el-icon :size="18"><Bell /></el-icon>
            </button>
          </el-badge>

          <button class="icon-btn" type="button" :title="store.theme === 'dark' ? '切换浅色' : '切换深色'" @click="store.theme = store.theme === 'dark' ? 'light' : 'dark'">
            <el-icon :size="18"><component :is="store.theme === 'dark' ? 'Sunny' : 'Moon'" /></el-icon>
          </button>

          <button class="icon-btn" type="button" title="身份与密钥" @click="identityOpen = true">
            <el-icon :size="18"><Key /></el-icon>
          </button>
        </div>
      </header>

      <main class="content">
        <router-view v-slot="{ Component }">
          <component :is="Component" v-if="Component" :key="route.fullPath" />
          <div v-else class="route-loading">
            <el-icon class="is-loading"><Loading /></el-icon>
            <span>正在加载页面…</span>
          </div>
        </router-view>
      </main>
    </div>

    <!-- Command palette -->
    <el-dialog
      v-model="store.commandOpen"
      width="560px"
      :show-close="false"
      class="command-dialog"
      append-to-body
    >
      <div class="command-box">
        <div class="command-input-row">
          <el-icon><Search /></el-icon>
          <input
            ref="commandInput"
            v-model="commandQuery"
            class="command-input"
            placeholder="跳转到页面、功能…"
            @keydown.enter="filteredCommands[0] && go(filteredCommands[0].path)"
          />
          <kbd>ESC</kbd>
        </div>
        <div class="command-list">
          <button
            v-for="c in filteredCommands"
            :key="c.path"
            type="button"
            class="command-item"
            @click="go(c.path)"
          >
            <el-icon><component :is="c.icon" /></el-icon>
            <span>{{ c.label }}</span>
            <span class="path">{{ c.path }}</span>
          </button>
          <div v-if="!filteredCommands.length" class="command-empty">无匹配页面</div>
        </div>
      </div>
    </el-dialog>

    <!-- Identity drawer -->
    <el-drawer v-model="identityOpen" title="身份与上下文" size="380px">
      <el-form label-position="top">
        <el-form-item label="Open API Key">
          <el-input v-model="store.auth.openKey" placeholder="s0-demo-key" show-password />
          <div class="t-faint" style="margin-top:6px">业务对话 / 会话接口（open scope）</div>
        </el-form-item>
        <el-form-item label="Admin API Key">
          <el-input v-model="store.auth.adminKey" placeholder="s0-admin-key" show-password />
          <div class="t-faint" style="margin-top:6px">治理 / 运维 / 审计 / 配额（admin scope）</div>
        </el-form-item>
        <el-divider />
        <el-form-item label="当前 Agent">
          <el-input v-model="store.agentId" placeholder="demo" />
        </el-form-item>
        <el-form-item label="userId">
          <el-input v-model="store.userId" placeholder="u1" />
        </el-form-item>
        <el-form-item label="sessionId">
          <el-input v-model="store.sessionId" placeholder="agt_ui_demo1" />
          <div class="t-faint" style="margin-top:6px">需匹配 <span class="mono">[A-Za-z0-9_-]{1,128}</span></div>
        </el-form-item>
        <el-alert type="info" :closable="false" show-icon>
          密钥仅保存在本机 localStorage，不会上传。
        </el-alert>
      </el-form>
    </el-drawer>

    <!-- Notifications -->
    <el-drawer v-model="notifyOpen" title="通知中心" size="380px">
      <div class="flex-between" style="margin-bottom:12px">
        <span class="t-muted">{{ store.notifications.length }} 条</span>
        <el-button size="small" text type="primary" @click="store.markAllRead()">全部已读</el-button>
      </div>
      <el-empty v-if="!store.notifications.length" description="暂无通知" :image-size="72" />
      <div v-for="n in store.notifications" :key="n.id" class="notify-item" :class="{ unread: !n.read }">
        <el-tag size="small" :type="n.type === 'warning' ? 'warning' : n.type === 'error' ? 'danger' : 'info'">
          {{ n.title }}
        </el-tag>
        <div class="t-body" style="margin-top:6px">{{ n.message }}</div>
        <div class="t-faint mono" style="margin-top:4px">{{ n.at }}</div>
      </div>
    </el-drawer>

    <!-- 右下角智能机器人 -->
    <ConsoleBot />
  </div>
</template>

<style scoped>
.shell {
  display: grid;
  grid-template-columns: var(--sidebar-w) 1fr;
  height: 100vh;
  transition: grid-template-columns 0.2s ease;
}
.shell.collapsed {
  grid-template-columns: var(--sidebar-w-collapsed) 1fr;
}

.side {
  background: var(--bg-surface);
  border-right: 1px solid var(--line);
  display: flex;
  flex-direction: column;
  min-height: 0;
  padding: 14px 10px 12px;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 4px 8px 14px;
  cursor: pointer;
}
.brand-mark {
  width: 36px;
  height: 36px;
  border-radius: 11px;
  background: linear-gradient(135deg, var(--brand), #6366f1);
  color: #fff;
  display: grid;
  place-items: center;
  font-weight: 750;
  font-size: 12px;
  letter-spacing: 0.04em;
  flex-shrink: 0;
}
.brand-title {
  font-weight: 700;
  font-size: 14px;
  line-height: 1.2;
}
.brand-sub {
  font-size: 11px;
  color: var(--ink-3);
}

.nav {
  flex: 1;
  overflow: auto;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.nav-group-label {
  font-size: 11px;
  font-weight: 600;
  color: var(--ink-3);
  letter-spacing: 0.04em;
  padding: 6px 10px 4px;
}
.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 10px;
  border-radius: 9px;
  color: var(--ink-2);
  text-decoration: none;
  font-size: 13px;
  transition: background 0.15s, color 0.15s;
}
.nav-item:hover {
  background: var(--bg-hover);
  color: var(--ink-1);
}
.nav-item.active {
  background: var(--brand-soft);
  color: var(--brand);
  font-weight: 600;
}
.nav-label {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.collapse-btn {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
  padding: 8px 10px;
  border: 1px solid var(--line);
  background: var(--bg-surface);
  border-radius: 9px;
  color: var(--ink-2);
  cursor: pointer;
  font-size: 12px;
}
.collapse-btn:hover {
  background: var(--bg-hover);
}

.main {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}

.top {
  height: var(--topbar-h);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 0 18px;
  border-bottom: 1px solid var(--line);
  background: color-mix(in srgb, var(--bg-surface) 92%, transparent);
  backdrop-filter: blur(8px);
}

.top-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.content {
  flex: 1;
  overflow: auto;
  min-height: 0;
  position: relative;
}

.route-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 64px;
  color: var(--ink-2);
  font-size: 14px;
}

.ghost-btn {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  height: 32px;
  padding: 0 10px;
  border-radius: 8px;
  border: 1px solid var(--line);
  background: var(--bg-surface);
  color: var(--ink-2);
  cursor: pointer;
  font-size: 12px;
}
.ghost-btn:hover {
  background: var(--bg-hover);
}
.command-trigger kbd,
.command-input-row kbd {
  font-family: var(--font-mono);
  font-size: 10px;
  padding: 2px 6px;
  border-radius: 5px;
  border: 1px solid var(--line);
  background: var(--bg-subtle);
  color: var(--ink-3);
}

.icon-btn {
  width: 34px;
  height: 34px;
  border-radius: 9px;
  border: 1px solid transparent;
  background: transparent;
  color: var(--ink-2);
  display: grid;
  place-items: center;
  cursor: pointer;
}
.icon-btn:hover {
  background: var(--bg-hover);
  color: var(--ink-1);
}

.command-box {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.command-input-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--bg-subtle);
}
.command-input {
  flex: 1;
  border: 0;
  outline: none;
  background: transparent;
  font-size: 14px;
  color: var(--ink-1);
}
.command-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 320px;
  overflow: auto;
}
.command-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border: 0;
  border-radius: 9px;
  background: transparent;
  cursor: pointer;
  color: var(--ink-1);
  text-align: left;
}
.command-item:hover {
  background: var(--brand-soft);
  color: var(--brand);
}
.command-item .path {
  margin-left: auto;
  color: var(--ink-3);
  font-family: var(--font-mono);
  font-size: 11px;
}
.command-empty {
  padding: 18px;
  text-align: center;
  color: var(--ink-3);
  font-size: 13px;
}

.notify-item {
  padding: 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  margin-bottom: 8px;
  background: var(--bg-surface);
}
.notify-item.unread {
  border-color: color-mix(in srgb, var(--brand) 35%, var(--line));
  background: var(--brand-softer);
}

.flex-between {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
</style>
