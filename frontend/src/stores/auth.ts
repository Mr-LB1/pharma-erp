import { defineStore } from 'pinia'
import { login as loginApi, getMe, logout as logoutApi } from '@/api/auth'
import { TOKEN_KEY, onTokenIssued, clearTokenRefresh } from '@/api/request'
import { hasPermission as checkPermission, normalizePermissions } from '@/utils/permission'
import { useTabsStore } from './tabs'
import type { UserInfo } from '@/types'

interface AuthState {
  token: string
  user: UserInfo | null
}

export const useAuthStore = defineStore('auth', {
  state: (): AuthState => ({
    token: localStorage.getItem(TOKEN_KEY) || '',
    user: null
  }),
  getters: {
    isLoggedIn: (state) => !!state.token,
    realName: (state) => state.user?.realName || state.user?.username || '',
    /** 归一化权限列表：null 表示后端未下发（兼容放行） */
    permissions: (state): string[] | null => normalizePermissions(state.user),
    /** 权限判定：v-permission / 菜单 / 路由守卫共用 */
    hasPermission: (state) => (code?: string | string[]) =>
      checkPermission(normalizePermissions(state.user), code)
  },
  actions: {
    async login(username: string, password: string) {
      const result = await loginApi(username, password)
      this.token = result.token
      this.user = result.user
      localStorage.setItem(TOKEN_KEY, result.token)
      // 按 JWT 到期时间安排一次主动续期（交互 7）
      onTokenIssued()
      // 重新登录（含 token 过期被动跳登录）时清空上次会话的标签页，避免残留
      useTabsStore().reset()
      // 兼容登录接口未下发 permissions 的后端：补拉 /me，确保菜单/按钮能按权限渲染
      if (!Array.isArray(result.user?.permissions)) {
        try {
          this.user = await getMe()
        } catch {
          // 拉取失败则按未知权限放行
        }
      }
    },
    async fetchMe() {
      this.user = await getMe()
    },
    async logout() {
      // 通知后端 token 失效（批次三忽略失败：旧后端无该接口时不影响本地登出）
      try {
        await logoutApi()
      } catch {
        // ignore
      }
      clearTokenRefresh()
      this.token = ''
      this.user = null
      localStorage.removeItem(TOKEN_KEY)
    }
  }
})
