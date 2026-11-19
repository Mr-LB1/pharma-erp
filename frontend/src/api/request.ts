import axios, {
  type AxiosHeaders,
  type AxiosRequestConfig,
  type AxiosResponse,
  type InternalAxiosRequestConfig
} from 'axios'
import { ElMessage } from 'element-plus'
import type { ApiResponse } from '@/types'

export const TOKEN_KEY = 'pharma_token'

// 扩展请求配置：silent=true 时不弹出错误提示（用于可降级的探测型接口）
export interface RequestOptions extends AxiosRequestConfig {
  silent?: boolean
  /** 内部标记：刷新 token 的请求本身不再触发刷新重放，避免递归 */
  skipAuthRefresh?: boolean
  /** 内部标记：原请求已重放过一次 */
  retried?: boolean
}

interface InternalRequestConfig extends InternalAxiosRequestConfig {
  silent?: boolean
  skipAuthRefresh?: boolean
  retried?: boolean
}

const isSilent = (config?: AxiosRequestConfig) => !!(config as RequestOptions | undefined)?.silent

/** 字段级校验错误（后端 400 时 data.errors = [{field,message}]） */
export interface FieldError {
  field: string
  message: string
}

/** 从响应体提取字段级错误文案，优先展示具体字段原因 */
function fieldErrorText(payload: unknown): string {
  const data = (payload as { data?: { errors?: FieldError[] } } | undefined)?.data
  const errors = data?.errors
  if (Array.isArray(errors) && errors.length > 0) {
    return errors.map((e) => e?.message).filter(Boolean).join('；')
  }
  return ''
}

// baseURL 留空走相对路径：api 文件 url 均带 /api 前缀，由 Vite 代理转发到后端，避免直连与重复前缀
const instance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '',
  timeout: 15000
})

// 请求拦截器：注入 Bearer token
instance.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// ===== 静默续期（交互 7） =====
// 401 时先尝试换发一次 token 并重放原请求，避免 2h 过期把正在编辑的表单整页踢回登录。
// 并发请求 401 时共用同一个刷新 Promise（单飞），刷新失败才跳登录。

/** 正在进行的刷新请求（单飞） */
let refreshing: Promise<string | null> | null = null

function setToken(token: string) {
  localStorage.setItem(TOKEN_KEY, token)
}

/** 唤醒 token 变化监听（Pinia store / 跨标签页同步用） */
function notifyTokenChanged(token: string) {
  window.dispatchEvent(new CustomEvent('pharma:token-refreshed', { detail: token }))
}

/**
 * 换发 token：成功返回新 token 并落库，失败返回 null（不抛错、不弹提示）。
 * 使用独立请求 + skipAuthRefresh 标记，避免刷新失败再次递归触发刷新。
 */
export function refreshAccessToken(): Promise<string | null> {
  if (refreshing) return refreshing
  refreshing = instance
    .request({
      url: '/api/auth/refresh',
      method: 'post',
      skipAuthRefresh: true
    } as RequestOptions)
    .then((resp) => {
      const token = (resp as unknown as ApiResponse<{ token?: string }>)?.data?.token
      if (!token) return null
      setToken(token)
      notifyTokenChanged(token)
      scheduleTokenRefresh()
      return token
    })
    .catch(() => null)
    .finally(() => {
      refreshing = null
    })
  return refreshing
}

/** 解析 JWT exp（毫秒）；非 JWT / 无 exp 返回 null */
export function getTokenExpiry(token = localStorage.getItem(TOKEN_KEY)): number | null {
  if (!token || token.split('.').length !== 3) return null
  try {
    const payload = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')
    const binary = window.atob(payload)
    const bytes = Uint8Array.from(binary, (c) => c.charCodeAt(0))
    const json = JSON.parse(new TextDecoder().decode(bytes)) as { exp?: number }
    return typeof json.exp === 'number' ? json.exp * 1000 : null
  } catch {
    return null
  }
}

let refreshTimer: ReturnType<typeof setTimeout> | null = null
/** 提前续期的余量：到期前 5 分钟主动换发，进一步降低编辑中被踢的概率 */
const REFRESH_AHEAD_MS = 5 * 60 * 1000

/** 按 token 到期时间安排一次主动续期（编辑长表单时即使无请求也能续期） */
export function scheduleTokenRefresh() {
  if (refreshTimer) {
    clearTimeout(refreshTimer)
    refreshTimer = null
  }
  const exp = getTokenExpiry()
  if (!exp) return
  const remaining = exp - Date.now()
  // 已过期则不再排期（交给 401 分支）；仍在有效期内但已进入提前量时立即续期
  if (remaining <= 0) return
  const delay = Math.max(0, remaining - REFRESH_AHEAD_MS)
  refreshTimer = setTimeout(() => {
    refreshTimer = null
    refreshAccessToken().then((token) => {
      // 续期失败说明登录态已失效（禁用/登出），留给下一次请求的 401 处理
      if (!token) return
    })
  }, delay)
}

/** 登录成功后调用：立即安排主动续期 */
export function onTokenIssued() {
  scheduleTokenRefresh()
}

/** 登出/清会话时调用：取消已排期的续期 */
export function clearTokenRefresh() {
  if (refreshTimer) {
    clearTimeout(refreshTimer)
    refreshTimer = null
  }
}

function withNewToken(config: InternalRequestConfig, token: string) {
  const headers = config.headers as AxiosHeaders | undefined
  if (headers && typeof headers.set === 'function') {
    headers.set('Authorization', `Bearer ${token}`)
  } else {
    config.headers = {
      ...(config.headers || {}),
      Authorization: `Bearer ${token}`
    } as unknown as InternalRequestConfig['headers']
  }
}

const AUTH_ENDPOINTS = ['/api/auth/login', '/api/auth/refresh']

function isAuthEndpoint(url?: string) {
  const u = String(url || '')
  return AUTH_ENDPOINTS.some((p) => u.includes(p))
}

// 响应拦截器：按 {code,message,data} 解包，code!=200 抛错
// 返回解包后的完整响应体（ApiResponse），供 request<T> 取 data
instance.interceptors.response.use(
  (response: AxiosResponse<ApiResponse>) => {
    const body = response.data
    // 兼容非标准响应（如后端未就绪返回 HTML/空）
    if (body == null || typeof body.code === 'undefined') {
      return body as unknown as AxiosResponse
    }
    if (body.code !== 200) {
      const msg = fieldErrorText(body) || body.message || '请求失败'
      if (!isSilent(response.config)) ElMessage.error(msg)
      return Promise.reject(new Error(msg))
    }
    return body as unknown as AxiosResponse
  },
  async (error) => {
    const config = error.config as InternalRequestConfig | undefined
    const status = error.response?.status
    // 401 且非登录/刷新接口：先单飞换发一次 token，成功则重放原请求
    if (status === 401 && config && !isAuthEndpoint(config.url)) {
      if (!config.skipAuthRefresh && !config.retried) {
        const token = await refreshAccessToken()
        if (token) {
          config.retried = true
          withNewToken(config, token)
          return instance.request(config)
        }
      }
    }

    let msg = '网络错误'
    if (error.response) {
      const resp = error.response.data
      const isLoginApi = String(error.config?.url || '').includes('/api/auth/login')
      if (status === 401) {
        localStorage.removeItem(TOKEN_KEY)
        if (isLoginApi) {
          // 登录接口 401 = 账号或密码错误，不跳转
          msg = resp?.message || '用户名或密码错误'
        } else if (config?.skipAuthRefresh) {
          // 刷新接口自身失败：不重复提示，交由原请求的 401 分支处理
          msg = '登录已过期，请重新登录'
        } else {
          msg = '登录已过期，请重新登录'
          redirectToLogin()
        }
      } else if (status === 403) {
        msg = resp?.message || '无权限执行该操作'
      } else {
        // 400 优先展示字段级校验原因
        msg = fieldErrorText(resp) || resp?.message || `请求失败(${status})`
      }
    } else if (error.code === 'ECONNABORTED') {
      msg = '请求超时'
    }
    // 刷新请求自身的失败保持静默：提示与跳转由原请求的 401 分支统一处理
    if (!isSilent(error.config) && !config?.skipAuthRefresh) ElMessage.error(msg)
    return Promise.reject(error)
  }
)

/**
 * 401 统一跳登录页并携带 redirect（P1-31）。
 * 直接改 location 而非引入 router，避免与 router/index.ts 形成循环依赖。
 */
function redirectToLogin() {
  const { pathname, search, hash } = window.location
  if (pathname === '/login') return
  const redirect = encodeURIComponent(pathname + search + hash)
  window.location.replace(`/login?redirect=${redirect}`)
}

// 泛型封装：返回 data 字段
export async function request<T>(config: RequestOptions): Promise<T> {
  const resp = (await instance.request(config)) as unknown as ApiResponse<T>
  return resp.data as T
}

export default instance
