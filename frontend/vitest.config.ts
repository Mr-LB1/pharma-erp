import { defineConfig } from 'vitest/config'
import { fileURLToPath, URL } from 'node:url'

// 单元测试配置：只覆盖 utils / composables 等纯逻辑，无需 DOM 环境
export default defineConfig({
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  test: {
    environment: 'node',
    include: ['src/**/*.spec.ts', 'tests/**/*.spec.ts'],
    // 纯函数测试体量很小，串行执行更稳定（也避开部分 CI/沙箱环境的 worker 临时目录权限问题）
    fileParallelism: false
  }
})
