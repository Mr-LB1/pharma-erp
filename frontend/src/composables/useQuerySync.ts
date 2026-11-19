import { onBeforeUnmount, watch } from 'vue'
import { useRoute, useRouter, type LocationQueryRaw } from 'vue-router'

export interface QuerySyncOptions {
  /** 需要按数字还原的字段（如 page/size/productId） */
  numeric?: string[]
  /**
   * 不写入 URL 的默认值。不传时自动取调用瞬间 query 的初值，
   * 例如 page=1 / size=20 不会出现在地址栏，保持链接干净。
   */
  defaults?: Record<string, unknown>
  /** 输入防抖（毫秒），避免逐字打字刷屏 replace */
  debounce?: number
}

/**
 * 列表筛选条件 ⇄ 路由 query 双向同步（交互 4）。
 * - setup 阶段先把 URL 参数还原进 query 对象，随后页面 onMounted 的 load() 即可带上筛选
 * - query 变化后防抖写回 URL，使用 router.replace 避免污染浏览器历史
 * - 关闭标签页（keep-alive 销毁）时清理定时器与监听
 */
export function useQuerySync<T extends Record<string, unknown>>(
  query: T,
  keys: (keyof T & string)[],
  options: QuerySyncOptions = {}
) {
  const route = useRoute()
  const router = useRouter()
  // keep-alive 下页面可能处于后台：记录自身路径，仅在当前路由就是本页时才写 URL
  const ownPath = route.path
  const numeric = new Set(options.numeric || [])
  const defaults: Record<string, unknown> =
    options.defaults ??
    keys.reduce<Record<string, unknown>>((acc, key) => {
      acc[key] = query[key]
      return acc
    }, {})

  // 1) URL → query（仅覆盖 URL 中确实存在的非空值，避免清掉页面默认值）
  for (const key of keys) {
    const raw = route.query[key]
    const value = Array.isArray(raw) ? raw[0] : raw
    if (value === undefined || value === null || value === '') continue
    if (numeric.has(key) || typeof query[key] === 'number') {
      const n = Number(value)
      if (Number.isFinite(n)) (query as Record<string, unknown>)[key] = n
    } else if (typeof query[key] === 'boolean') {
      (query as Record<string, unknown>)[key] = value === 'true'
    } else {
      (query as Record<string, unknown>)[key] = value
    }
  }

  // 2) query → URL
  let timer: ReturnType<typeof setTimeout> | null = null

  function buildQuery(): LocationQueryRaw {
    const next: LocationQueryRaw = { ...route.query }
    for (const key of keys) {
      const value = query[key]
      if (
        value === undefined ||
        value === null ||
        value === '' ||
        value === defaults[key]
      ) {
        delete next[key]
        continue
      }
      next[key] = String(value)
    }
    return next
  }

  function flush() {
    if (route.path !== ownPath) return
    const next = buildQuery()
    const current = route.query
    const same =
      Object.keys(next).length === Object.keys(current).length &&
      Object.entries(next).every(([k, v]) => String(current[k] ?? '') === String(v ?? ''))
    if (same) return
    // replace 不产生历史记录；路由重复跳转的 reject 静默忽略
    router.replace({ query: next }).catch(() => {})
  }

  const stop = watch(
    () => keys.map((key) => query[key]),
    () => {
      if (timer) clearTimeout(timer)
      timer = setTimeout(() => {
        timer = null
        flush()
      }, options.debounce ?? 250)
    },
    { deep: true }
  )

  onBeforeUnmount(() => {
    if (timer) clearTimeout(timer)
    stop()
  })

  return { flush }
}
