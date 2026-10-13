# 登录平台挂载 AiBot（ant-design-vue-jeecg）

将 `AiBot.vue` 拷入前端工程，例如：

```text
src/components/aibot/AiBot.vue
```

## 1. 全局挂载（推荐：BasicLayout / TabLayout）

```vue
<!-- src/components/layouts/TabLayout.vue 或全局 BasicLayout 根节点末尾 -->
<template>
  <div>
    <!-- 原有布局 ... -->
    <AiBot
      :ai-base="aiBase"
      agent-id="biz-assist"
    />
  </div>
</template>

<script>
import AiBot from '@/components/aibot/AiBot.vue'

export default {
  components: { AiBot },
  data() {
    return {
      // 生产请走网关同域或环境变量注入
      aiBase: process.env.VUE_APP_AI_BASE || 'http://127.0.0.1:18081'
    }
  }
}
</script>
```

## 2. 仅在业务页挂载

```vue
<template>
  <div class="order-page">
    <!-- 业务内容 -->
    <AiBot :ai-base="aiBase" agent-id="biz-assist" />
  </div>
</template>
```

## 3. Token

组件默认读 `Vue.ls.get('ACCESS_TOKEN')`（与业务请求一致），**禁止**在前端写 AI 平台 API Key。

## 4. 构建环境变量

`.env.production` / `.env.development`：

```env
VUE_APP_AI_BASE=http://127.0.0.1:18081
```

同域网关部署时改为相对路径，由网关把 `/api/v1/**` 路由到 AI 中台。

## 5. 简易联调

1. 启动 Redis + PG + AI 中台（18081）  
2. 配置 `AMP_PC_PLATFORM_BASE_URL` / `AMP_JEECG_*`，重启使工具播种  
   或执行 `scripts/register-pc-platform-tools.ps1`  
3. 登录平台登录后打开挂载页，点悬浮球提问：`查我的信息`  
4. 期望：流式回复；无票 401；工具结果与页面手工查询一致  
