import { defineStore } from 'pinia'

/** 工作台固定标签：始终存在且不可关闭 */
export const HOME_PATH = '/dashboard'
export const HOME_TITLE = '工作台'

export interface TabItem {
  path: string
  title: string
  closable: boolean
}

/** 仅依赖 path / meta.title，避免与 vue-router 类型强耦合 */
export interface TabRouteLike {
  path: string
  meta?: { title?: unknown }
}

interface TabsState {
  tabs: TabItem[]
  activePath: string
}

export const useTabsStore = defineStore('tabs', {
  state: (): TabsState => ({
    tabs: [],
    activePath: ''
  }),
  actions: {
    /** 打开（或激活）标签：去重、补工作台、同步标题 */
    openTab(route: TabRouteLike) {
      const path = route.path || HOME_PATH
      const title =
        typeof route.meta?.title === 'string' && route.meta.title ? route.meta.title : path

      if (!this.tabs.some((t) => t.path === HOME_PATH)) {
        this.tabs.unshift({ path: HOME_PATH, title: HOME_TITLE, closable: false })
      }

      const existing = this.tabs.find((t) => t.path === path)
      if (existing) {
        if (existing.title !== title) existing.title = title
      } else {
        this.tabs.push({ path, title, closable: path !== HOME_PATH })
      }
      this.activePath = path
    },

    /** 关闭标签，返回应激活的 path；固定标签关闭无效 */
    closeTab(path: string): string {
      const index = this.tabs.findIndex((t) => t.path === path)
      if (index === -1) return this.activePath
      if (!this.tabs[index].closable) return this.activePath

      this.tabs.splice(index, 1)

      if (this.activePath === path) {
        // 优先激活右侧相邻标签，其次左侧，兜底工作台
        const next = this.tabs[index] ?? this.tabs[index - 1]
        this.activePath = next ? next.path : HOME_PATH
      }
      return this.activePath
    },

    /** 退出登录 / 切换账号时清空 */
    reset() {
      this.tabs = []
      this.activePath = ''
    }
  }
})
