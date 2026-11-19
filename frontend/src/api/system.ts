import { request } from './request'
import {
  isMock,
  mockUserList,
  mockUserCreate,
  mockUserUpdate,
  mockUserDelete,
  mockUserResetPassword,
  mockUserToggle,
  mockUserAssignRoles,
  mockRoleList,
  mockPermissionList,
  mockRolePermissions,
  mockUpdateRolePermissions
} from './mock'
import type {
  PageResult,
  AuditLog,
  SysUser,
  SysUserCreateRequest,
  SysUserUpdateRequest,
  SysRole,
  SysPermission,
  SysRolePermissions
} from '@/types'

// ===== 审计日志 =====
export function listAuditLogs(page = 1, size = 20): Promise<PageResult<AuditLog>> {
  return request<PageResult<AuditLog>>({
    url: '/api/system/audit-logs',
    method: 'get',
    params: { page, size }
  })
}

// ===== 用户管理（批次三 P0-6） =====
export function listUsers(
  keyword?: string,
  page = 1,
  size = 20
): Promise<PageResult<SysUser>> {
  if (isMock) return mockUserList(keyword, page, size)
  return request<PageResult<SysUser>>({
    url: '/api/system/users',
    method: 'get',
    params: { keyword, page, size }
  })
}

export function createUser(payload: SysUserCreateRequest): Promise<SysUser> {
  if (isMock) return mockUserCreate(payload)
  return request<SysUser>({ url: '/api/system/users', method: 'post', data: payload })
}

export function updateUser(id: number, payload: SysUserUpdateRequest): Promise<SysUser> {
  if (isMock) return mockUserUpdate(id, payload)
  return request<SysUser>({ url: `/api/system/users/${id}`, method: 'put', data: payload })
}

export function deleteUser(id: number): Promise<void> {
  if (isMock) return mockUserDelete(id)
  return request<null>({ url: `/api/system/users/${id}`, method: 'delete' }).then(() => undefined)
}

/** 重置密码 */
export function resetUserPassword(id: number, password: string): Promise<void> {
  if (isMock) return mockUserResetPassword(id, password)
  return request<null>({
    url: `/api/system/users/${id}/password`,
    method: 'post',
    data: { password }
  }).then(() => undefined)
}

/** 启用 / 停用（后端切换状态并令旧 token 失效） */
export function toggleUser(id: number): Promise<SysUser> {
  if (isMock) return mockUserToggle(id)
  return request<SysUser>({ url: `/api/system/users/${id}/toggle`, method: 'post' })
}

/** 分配角色（create/update 已带 roles，此处为兼容仅支持独立分配接口的后端，失败静默） */
export function assignUserRoles(id: number, roles: string[]): Promise<SysUser> {
  if (isMock) return mockUserAssignRoles(id, roles)
  return request<SysUser>({
    url: `/api/system/users/${id}/roles`,
    method: 'post',
    data: { roles },
    silent: true
  })
}

/** 角色列表（用于用户角色分配下拉；后端未就绪时静默失败，页面回退内置角色） */
export function listRoles(silent = true): Promise<SysRole[]> {
  if (isMock) return mockRoleList()
  return request<SysRole[]>({ url: '/api/system/roles', method: 'get', silent })
}

// ===== 角色权限配置（批次六） =====

/** 全部权限码（用于角色授权面板分组展示） */
export function listPermissions(): Promise<SysPermission[]> {
  if (isMock) return mockPermissionList()
  return request<SysPermission[]>({ url: '/api/system/permissions', method: 'get' })
}

/** 某角色已勾选的权限码；兼容后端返回 string[] 或 { permissionCodes } 两种结构 */
export async function getRolePermissions(roleId: number): Promise<string[]> {
  if (isMock) return mockRolePermissions(roleId)
  const data = await request<string[] | SysRolePermissions>({
    url: `/api/system/roles/${roleId}/permissions`,
    method: 'get'
  })
  if (Array.isArray(data)) return data
  return data?.permissionCodes ?? []
}

/** 覆盖式保存角色权限（后端对 ADMIN 关键权限做 400 兜底），返回保存后的权限码 */
export async function updateRolePermissions(
  roleId: number,
  permissionCodes: string[]
): Promise<string[]> {
  if (isMock) return mockUpdateRolePermissions(roleId, permissionCodes)
  const data = await request<string[] | SysRolePermissions | null>({
    url: `/api/system/roles/${roleId}/permissions`,
    method: 'put',
    data: { permissionCodes }
  })
  if (Array.isArray(data)) return data
  return data?.permissionCodes ?? permissionCodes
}
