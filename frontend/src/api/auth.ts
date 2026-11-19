import { request } from './request'
import { isMock, mockLogin, mockMe, mockLogout } from './mock'
import type { LoginResult, UserInfo } from '@/types'

export function login(username: string, password: string): Promise<LoginResult> {
  if (isMock) return mockLogin(username, password)
  return request<LoginResult>({
    url: '/api/auth/login',
    method: 'post',
    data: { username, password }
  })
}

export function getMe(): Promise<UserInfo> {
  if (isMock) return mockMe()
  return request<UserInfo>({
    url: '/api/auth/me',
    method: 'get'
  })
}

/** 登出：后端将该用户 tokenVersion +1，令旧 token 立即失效（失败不阻塞本地登出） */
export function logout(): Promise<void> {
  if (isMock) return mockLogout()
  return request<null>({ url: '/api/auth/logout', method: 'post' }).then(() => undefined)
}
