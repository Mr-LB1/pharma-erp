import type { Directive, DirectiveBinding } from 'vue'
import { useAuthStore } from '@/stores/auth'

type PermissionValue = string | string[] | undefined

/**
 * v-permission 按钮/元素级权限指令（P1-21）
 *
 * 用法：
 *   <el-button v-permission="'purchase:order:void'">作废</el-button>
 *   <el-button v-permission="['purchase:order:update', 'purchase:order:create']">保存</el-button>
 *
 * 无权限时直接从 DOM 移除元素；数组为「任一命中」即显示。
 * 依赖路由守卫在进入页面前已拉取用户信息，故挂载时权限已就绪。
 */
function allowed(binding: DirectiveBinding<PermissionValue>): boolean {
  const authStore = useAuthStore()
  return authStore.hasPermission(binding.value)
}

function removeEl(el: HTMLElement) {
  el.parentNode?.removeChild(el)
}

export const permission: Directive<HTMLElement, PermissionValue> = {
  mounted(el, binding) {
    if (!allowed(binding)) removeEl(el)
  },
  updated(el, binding) {
    // 权限变化（如切换账号）后补移除；已移除的元素不会再次进入 updated
    if (!allowed(binding)) removeEl(el)
  }
}

export default permission
