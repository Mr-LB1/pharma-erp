import { describe, expect, it } from 'vitest'
import { hasPermission, normalizePermissions, WILDCARD_PERMISSIONS } from '../permission'
import type { UserInfo } from '@/types'

function user(permissions?: string[]): UserInfo {
  return {
    id: 1,
    username: 'tester',
    realName: '测试',
    roles: ['ADMIN'],
    ...(permissions === undefined ? {} : { permissions })
  } as UserInfo
}

describe('normalizePermissions', () => {
  it('后端下发数组时原样返回（空数组代表确实无权限）', () => {
    expect(normalizePermissions(user(['a']))).toEqual(['a'])
    expect(normalizePermissions(user([]))).toEqual([])
  })

  it('未下发权限字段时返回 null（兼容放行）', () => {
    expect(normalizePermissions(user())).toBeNull()
    expect(normalizePermissions(null)).toBeNull()
    expect(normalizePermissions(undefined)).toBeNull()
  })
})

describe('hasPermission', () => {
  it('权限未知（null）时放行', () => {
    expect(hasPermission(null, 'sales:order:create')).toBe(true)
  })

  it('空权限数组严格拦截', () => {
    expect(hasPermission([], 'sales:order:create')).toBe(false)
    expect(hasPermission([], ['sales:order:create'])).toBe(false)
  })

  it('空权限码表示无需权限', () => {
    expect(hasPermission([], undefined)).toBe(true)
    expect(hasPermission([], '')).toBe(true)
    expect(hasPermission([], [])).toBe(true)
  })

  it('通配权限放行任意操作', () => {
    for (const wildcard of WILDCARD_PERMISSIONS) {
      expect(hasPermission([wildcard], 'system:user:delete')).toBe(true)
    }
  })

  it('数组权限码任一命中即放行', () => {
    expect(hasPermission(['a'], ['b', 'a'])).toBe(true)
    expect(hasPermission(['a'], ['b', 'c'])).toBe(false)
  })

  it('精确匹配，不做前缀/通配推断', () => {
    expect(hasPermission(['sales:order'], 'sales:order:create')).toBe(false)
    expect(hasPermission(['sales:order:create'], 'sales:order:create')).toBe(true)
  })
})
