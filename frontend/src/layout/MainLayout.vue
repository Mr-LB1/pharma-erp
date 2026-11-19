<template>
  <el-container class="layout">
    <el-aside :width="collapsed ? '64px' : '220px'" class="aside">
      <div class="brand" @click="router.push('/')">
        <div class="brand-logo">药</div>
        <span v-show="!collapsed" class="brand-name">医药 B2B ERP</span>
      </div>
      <el-menu
        :default-active="route.path"
        :collapse="collapsed"
        :collapse-transition="false"
        router
        background-color="#001529"
        text-color="rgba(255,255,255,0.7)"
        active-text-color="#409eff"
        class="menu"
      >
        <template v-for="node in visibleMenus" :key="isGroup(node) ? node.key : node.path">
          <el-sub-menu v-if="isGroup(node)" :index="node.key">
            <template #title>
              <el-icon><component :is="node.icon" /></el-icon>
              <span>{{ node.title }}</span>
            </template>
            <el-menu-item v-for="child in node.children" :key="child.path" :index="child.path">
              <el-icon><component :is="child.icon" /></el-icon>
              <template #title>{{ child.title }}</template>
            </el-menu-item>
          </el-sub-menu>
          <el-menu-item v-else :index="node.path">
            <el-icon><component :is="node.icon" /></el-icon>
            <template #title>{{ node.title }}</template>
          </el-menu-item>
        </template>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div class="header-left">
          <el-icon class="collapse-btn" @click="collapsed = !collapsed">
            <Fold v-if="!collapsed" />
            <Expand v-else />
          </el-icon>
          <span class="page-title">{{ route.meta.title }}</span>
        </div>
        <el-dropdown @command="handleCommand">
          <span class="user-info">
            <el-avatar :size="28" class="user-avatar">
              {{ authStore.realName.charAt(0) }}
            </el-avatar>
            <span class="user-name">{{ authStore.realName }}</span>
            <el-icon><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="logout">
                <el-icon><SwitchButton /></el-icon>退出登录
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>

      <div class="tabbar">
        <div
          v-for="tab in tabsStore.tabs"
          :key="tab.path"
          class="tab-item"
          :class="{ active: tab.path === route.path }"
          @click="activateTab(tab.path)"
        >
          <span class="tab-title">{{ tab.title }}</span>
          <el-icon
            v-if="tab.closable"
            class="tab-close"
            @click.stop="closeTab(tab.path)"
          >
            <Close />
          </el-icon>
        </div>
      </div>

      <el-main class="main">
        <router-view v-slot="{ Component }">
          <keep-alive :include="cachedNames">
            <component
              :is="resolveTabWrapper(route.path, Component)"
              v-if="Component"
              :key="route.path"
            />
          </keep-alive>
        </router-view>
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { ref, computed, defineComponent, h, onMounted, onBeforeUnmount, watch, type Component, type VNode } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { Fold, Expand, ArrowDown, SwitchButton, Close } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { useTabsStore } from '@/stores/tabs'
import { filterMenus, isMenuGroup, type MenuNode } from './menus'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const tabsStore = useTabsStore()
const collapsed = ref(false)

// 响应式（交互 9）：视口不足 1280 时自动收起侧边栏，给表格腾出横向空间
const NARROW_WIDTH = 1280
function syncCollapsedByWidth() {
  if (window.innerWidth < NARROW_WIDTH) collapsed.value = true
}
onMounted(() => {
  syncCollapsedByWidth()
  window.addEventListener('resize', syncCollapsedByWidth)
})
onBeforeUnmount(() => window.removeEventListener('resize', syncCollapsedByWidth))

// 菜单按权限过滤：无权限的叶子隐藏，组内全部无权限则整组隐藏
const visibleMenus = computed<MenuNode[]>(() =>
  filterMenus((code) => authStore.hasPermission(code))
)

const isGroup = isMenuGroup

// ===== 多标签页 =====
// 每次路由变化都打开/激活对应标签（刷新后仅保留工作台 + 当前路由）
watch(
  () => route.path,
  () => tabsStore.openTab(route),
  { immediate: true }
)

function activateTab(path: string) {
  if (path !== route.path) router.push(path)
}

function closeTab(path: string) {
  const next = tabsStore.closeTab(path)
  // 仅当关闭的是当前页时才需要跳转；关闭后台标签不影响当前页
  if (path === route.path) router.push(next)
}

// keep-alive 按组件 name 精确缓存：给每个标签页包一层以标签路径命名的壳组件
// （不侵入各业务页，无需逐页声明 defineOptions）
const tabWrappers = new Map<string, { source: unknown; wrapper: Component }>()

function tabWrapperName(path: string) {
  return `TabPage${path.replace(/[^\w]/g, '_')}`
}

function resolveTabWrapper(path: string, vnode: VNode): Component {
  const source = vnode.type
  const cached = tabWrappers.get(path)
  if (cached && cached.source === source) return cached.wrapper

  const wrapper = defineComponent({
    name: tabWrapperName(path),
    setup: () => () => h('div', { class: 'tab-page' }, [h(source as Component)])
  })
  tabWrappers.set(path, { source, wrapper })
  return wrapper
}

/** 当前打开标签对应的缓存名单；关闭标签后 keep-alive 会自动销毁其缓存 */
const cachedNames = computed(() => tabsStore.tabs.map((tab) => tabWrapperName(tab.path)))

onMounted(() => {
  if (!authStore.user) {
    authStore.fetchMe().catch(() => {})
  }
})

async function handleCommand(cmd: string) {
  if (cmd === 'logout') {
    try {
      await ElMessageBox.confirm('确定退出登录？', '退出登录', {
        type: 'warning',
        confirmButtonText: '退出',
        cancelButtonText: '取消'
      })
    } catch {
      return
    }
    await authStore.logout()
    tabsStore.reset()
    router.push('/login')
  }
}
</script>

<style scoped>
.layout {
  height: 100%;
}

.aside {
  background: #001529;
  transition: width 0.2s;
  overflow-x: hidden;
}

.brand {
  height: 56px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 16px;
  cursor: pointer;
  color: #fff;
}

.brand-logo {
  width: 32px;
  height: 32px;
  flex-shrink: 0;
  border-radius: 8px;
  background: #409eff;
  color: #fff;
  text-align: center;
  line-height: 32px;
  font-size: 16px;
}

.brand-name {
  font-size: 15px;
  font-weight: 600;
  white-space: nowrap;
}

.menu {
  border-right: none;
}

.menu :deep(.el-menu-item-group__title) {
  padding-top: 12px;
  color: rgba(255, 255, 255, 0.35);
  font-size: 12px;
}

.header {
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  padding: 0 16px;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.collapse-btn {
  font-size: 18px;
  cursor: pointer;
  color: #606266;
}

.page-title {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
}

.user-info {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  color: #303133;
}

.user-avatar {
  background: #409eff;
  color: #fff;
  font-size: 13px;
}

.user-name {
  font-size: 14px;
}

.main {
  background: #f0f2f5;
  padding: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.tabbar {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
  height: 40px;
  padding: 0 12px;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  overflow-x: auto;
  overflow-y: hidden;
}

.tab-item {
  display: flex;
  align-items: center;
  gap: 6px;
  height: 28px;
  padding: 0 10px;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  background: #f5f7fa;
  color: #606266;
  font-size: 13px;
  line-height: 1;
  white-space: nowrap;
  cursor: pointer;
  user-select: none;
  transition: color 0.15s, border-color 0.15s, background-color 0.15s;
}

.tab-item:hover {
  color: #409eff;
  border-color: #c6e2ff;
  background: #ecf5ff;
}

.tab-item.active {
  color: #fff;
  background: #409eff;
  border-color: #409eff;
}

.tab-close {
  font-size: 12px;
  border-radius: 50%;
  transition: background-color 0.15s;
}

.tab-close:hover {
  background: rgba(0, 0, 0, 0.08);
}

.tab-item.active .tab-close:hover {
  background: rgba(255, 255, 255, 0.3);
}

.main :deep(.tab-page) {
  flex: 1 1 auto;
  min-height: 0;
  min-width: 0;
  padding: 16px;
  overflow: auto;
  box-sizing: border-box;
}

/* 筛选栏在窄屏时换行而不是横向溢出 */
.main :deep(.toolbar) {
  flex-wrap: wrap;
  row-gap: 8px;
}

.main :deep(.toolbar-left) {
  flex-wrap: wrap;
  row-gap: 8px;
}
</style>
