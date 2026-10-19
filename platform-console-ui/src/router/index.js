import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/',
    component: () => import('@/layouts/AppShell.vue'),
    redirect: { name: 'chat' },
    children: [
      {
        path: 'workbench/chat',
        name: 'chat',
        component: () => import('@/views/workbench/ChatView.vue'),
        meta: { title: '智能对话', group: '工作台' },
      },
      {
        path: 'workbench/approvals',
        name: 'approvals',
        component: () => import('@/views/workbench/ApprovalsView.vue'),
        meta: { title: '审批中心', group: '工作台' },
      },
      {
        path: 'workbench/knowledge',
        name: 'knowledge',
        component: () => import('@/views/workbench/KnowledgeView.vue'),
        meta: { title: '知识库', group: '工作台' },
      },
      {
        path: 'agents',
        name: 'agents',
        component: () => import('@/views/agents/AgentListView.vue'),
        meta: { title: 'Agent 列表', group: 'Agent 中心' },
      },
      {
        path: 'agents/:agentId',
        name: 'agent-detail',
        component: () => import('@/views/agents/AgentDetailView.vue'),
        meta: { title: 'Agent 详情', group: 'Agent 中心' },
      },
      {
        path: 'agents/:agentId/versions',
        name: 'agent-versions',
        component: () => import('@/views/agents/AgentVersionsView.vue'),
        meta: { title: '版本与发布', group: 'Agent 中心' },
      },
      {
        path: 'skills',
        name: 'skills',
        component: () => import('@/views/agents/SkillsView.vue'),
        meta: { title: '技能市场', group: 'Agent 中心' },
      },
      {
        path: 'workflows',
        name: 'workflows',
        component: () => import('@/views/agents/WorkflowListView.vue'),
        meta: { title: '编排 Workflow（待开发）', group: 'Agent 中心' },
      },
      {
        path: 'workflows/:workflowId',
        name: 'workflow-editor',
        component: () => import('@/views/agents/WorkflowEditorView.vue'),
        meta: { title: 'Workflow 编辑器（待开发）', group: 'Agent 中心' },
      },
      {
        path: 'teams',
        name: 'teams',
        component: () => import('@/views/agents/TeamsView.vue'),
        meta: { title: 'Team 协作（待开发）', group: 'Agent 中心' },
      },
      {
        path: 'governance/permissions',
        name: 'permissions',
        component: () => import('@/views/governance/PermissionsView.vue'),
        meta: { title: '权限与模式', group: '治理中心' },
      },
      {
        path: 'governance/tools',
        name: 'tools',
        component: () => import('@/views/governance/ToolCatalogView.vue'),
        meta: { title: '工具目录', group: '治理中心' },
      },
      {
        path: 'governance/tools/grants',
        name: 'tool-grants',
        component: () => import('@/views/governance/ToolGrantsView.vue'),
        meta: { title: '工具授权', group: '治理中心' },
      },
      {
        path: 'governance/systems',
        name: 'governance-systems',
        component: () => import('@/views/governance/SystemsView.vue'),
        meta: { title: '业务系统', group: '治理中心' },
      },
      {
        path: 'governance/tools/register',
        name: 'tool-register',
        component: () => import('@/views/governance/ToolRegisterView.vue'),
        meta: { title: '接入与登记', group: '治理中心' },
      },
      {
        path: 'governance/tools/legacy',
        name: 'tools-legacy',
        component: () => import('@/views/governance/ToolsView.vue'),
        meta: { title: 'MCP 与工具（旧）', group: '治理中心' },
      },
      {
        path: 'governance/external',
        name: 'external',
        component: () => import('@/views/governance/ExternalAgentsView.vue'),
        meta: { title: 'External Agent', group: '治理中心' },
      },
      {
        path: 'governance/iam',
        name: 'iam',
        component: () => import('@/views/governance/IamView.vue'),
        meta: { title: 'IAM / RBAC', group: '治理中心' },
      },
      {
        path: 'ops/overview',
        name: 'ops-overview',
        component: () => import('@/views/ops/OpsOverviewView.vue'),
        meta: { title: '运行概览', group: '运营中心' },
      },
      {
        path: 'ops/sessions',
        name: 'ops-sessions',
        component: () => import('@/views/ops/SessionsView.vue'),
        meta: { title: '会话与日志', group: '运营中心' },
      },
      {
        path: 'ops/billing',
        name: 'ops-billing',
        component: () => import('@/views/ops/BillingView.vue'),
        meta: { title: '配额与用量', group: '运营中心' },
      },
      {
        path: 'ops/audit',
        name: 'ops-audit',
        component: () => import('@/views/ops/AuditView.vue'),
        meta: { title: '审计', group: '运营中心' },
      },
      {
        path: 'ops/sandbox',
        name: 'ops-sandbox',
        component: () => import('@/views/ops/SandboxView.vue'),
        meta: { title: '沙箱 / FS', group: '运营中心' },
      },
      {
        path: 'ops/credentials',
        name: 'ops-credentials',
        component: () => import('@/views/ops/CredentialsView.vue'),
        meta: { title: '模型凭证', group: '运营中心' },
      },
      {
        path: 'system/health',
        name: 'system-health',
        component: () => import('@/views/system/HealthView.vue'),
        meta: { title: '环境与就绪', group: '系统' },
      },
      {
        path: 'system/config',
        name: 'system-config',
        component: () => import('@/views/system/ConfigView.vue'),
        meta: { title: '配置热更', group: '系统' },
      },
      {
        path: 'system/openai',
        name: 'system-openai',
        component: () => import('@/views/system/OpenAiCompatView.vue'),
        meta: { title: 'OpenAI 兼容调试', group: '系统' },
      },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: { name: 'chat' } },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior() {
    return { top: 0 }
  },
})

router.onError((err, to) => {
  // 懒加载 chunk 失败时整页跳转，避免点菜单白屏
  console.error('[router]', err)
  if (to?.fullPath) {
    const target = location.origin + to.fullPath
    if (location.href !== target) {
      location.href = target
    }
  }
})

router.afterEach((to) => {
  const title = to.meta?.title
  document.title = title ? `${title} · AI 中台 Console` : 'AI 中台 Console'
})

export default router
