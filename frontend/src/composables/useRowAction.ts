import { ref } from 'vue'

/**
 * 表格行级操作的防连点状态（交互优化）：
 * - isActing(id, action) 用于按钮 :loading
 * - isBlocked() 用于「有任意行操作进行中」时禁用其它行按钮
 * - run(id, task, action) 包裹异步操作，自动置位/复位
 */
export function useRowAction() {
  const actingKey = ref<string | null>(null)

  function keyOf(id: number | string, action?: string) {
    return action ? `${id}:${action}` : String(id)
  }

  function isActing(id: number | string, action?: string) {
    return actingKey.value === keyOf(id, action)
  }

  function isBlocked() {
    return actingKey.value !== null
  }

  async function run<T>(
    id: number | string,
    task: () => Promise<T>,
    action?: string
  ): Promise<T | undefined> {
    if (actingKey.value !== null) return undefined
    actingKey.value = keyOf(id, action)
    try {
      return await task()
    } finally {
      actingKey.value = null
    }
  }

  return { actingKey, isActing, isBlocked, run }
}
