import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [
    vue(),
    // 按需引入（决策 8）：Element Plus 组件与函数式 API 只在被使用时进入产物，
    // 替代 main.ts 里的全量 app.use(ElementPlus) + dist/index.css。
    AutoImport({
      resolvers: [ElementPlusResolver({ importStyle: 'css' })],
      dts: 'src/auto-imports.d.ts'
    }),
    Components({
      resolvers: [ElementPlusResolver({ importStyle: 'css' })],
      dts: 'src/components.d.ts'
    })
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5173,
    // 允许内网穿透域名访问（cpolar 隧道 Host 校验白名单）
    allowedHosts: ['.cpolar.top'],
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
