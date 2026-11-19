import { createApp } from 'vue'
import { createPinia } from 'pinia'
// 按需引入（决策 8）：不再全量 app.use(ElementPlus) / 不再引入 dist/index.css，
// 组件与指令由 unplugin-vue-components 在编译期按需注入样式。
// 但 ElMessage / ElMessageBox 属于函数式 API，不经过模板解析，样式需显式引入。
import 'element-plus/theme-chalk/el-message.css'
import 'element-plus/theme-chalk/el-message-box.css'
import {
  ArrowDown,
  Back,
  Box,
  CircleClose,
  Connection,
  CreditCard,
  DataLine,
  Document,
  DocumentChecked,
  Download,
  Expand,
  Files,
  Fold,
  FolderOpened,
  Goods,
  House,
  Lock,
  Money,
  Odometer,
  OfficeBuilding,
  Plus,
  Refresh,
  RefreshLeft,
  Right,
  Search,
  Sell,
  Setting,
  Shop,
  ShoppingCart,
  Sort,
  Stamp,
  SwitchButton,
  Tickets,
  Timer,
  TrendCharts,
  Upload,
  UploadFilled,
  User,
  UserFilled,
  Van,
  Wallet,
  Warning
} from '@element-plus/icons-vue'
import App from './App.vue'
import router from './router'
import permission from './directives/permission'
import { scheduleTokenRefresh } from './api/request'
import { useAuthStore } from './stores/auth'

const app = createApp(App)

app.use(createPinia())
app.use(router)

// token 静默续期（交互 7）：按 JWT exp 在到期前主动换发；跨组件同步 store 内 token
window.addEventListener('pharma:token-refreshed', (event) => {
  useAuthStore().token = (event as CustomEvent<string>).detail
})
scheduleTokenRefresh()

// 按钮/元素级权限指令 v-permission
app.directive('permission', permission)

// 图标按需注册（#34）：全量注册约 290 个图标是首屏体积大头，此处仅注册项目实际使用的图标。
// 页面内图标基本已在各自 <script setup> 中局部引入，这份列表作为全局兜底，避免遗漏。
const GLOBAL_ICONS = {
  ArrowDown,
  Back,
  Box,
  CircleClose,
  Connection,
  CreditCard,
  DataLine,
  Document,
  DocumentChecked,
  Download,
  Expand,
  Files,
  Fold,
  FolderOpened,
  Goods,
  House,
  Lock,
  Money,
  Odometer,
  OfficeBuilding,
  Plus,
  Refresh,
  RefreshLeft,
  Right,
  Search,
  Sell,
  Setting,
  Shop,
  ShoppingCart,
  Sort,
  Stamp,
  SwitchButton,
  Tickets,
  Timer,
  TrendCharts,
  Upload,
  UploadFilled,
  User,
  UserFilled,
  Van,
  Wallet,
  Warning
}

for (const [key, component] of Object.entries(GLOBAL_ICONS)) {
  app.component(key, component)
}

app.mount('#app')
