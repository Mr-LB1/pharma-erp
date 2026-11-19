import type { UserInfo } from '@/types'

/** 超级权限通配（后端 ADMIN 可能下发 '*'，或前端本地约定） */
export const WILDCARD_PERMISSIONS = ['*', '*:*:*']

/**
 * 归一化用户权限码：
 * - 返回 string[]：后端已下发权限列表（空数组表示确实无权限，需严格拦截）
 * - 返回 null：后端尚未下发该字段（兼容旧后端），此时放行，避免整站不可用
 */
export function normalizePermissions(user: UserInfo | null | undefined): string[] | null {
  const perms = user?.permissions
  return Array.isArray(perms) ? perms : null
}

/**
 * 权限判定（任一命中即可，语义与 v-permission 数组一致）
 * @param permissions 归一化后的权限列表，null 表示未知（放行）
 * @param code 单个权限码或权限码数组；空值表示无需权限
 */
export function hasPermission(
  permissions: string[] | null,
  code?: string | string[]
): boolean {
  if (!code || (Array.isArray(code) && code.length === 0)) return true
  if (permissions === null) return true
  if (WILDCARD_PERMISSIONS.some((p) => permissions.includes(p))) return true
  const codes = Array.isArray(code) ? code : [code]
  return codes.some((c) => permissions.includes(c))
}
