import { describe, expect, it, vi } from 'vitest'
import { useRowAction } from '../useRowAction'

describe('useRowAction', () => {
  it('执行期间置位，结束后复位', async () => {
    const { actingKey, run } = useRowAction()
    const task = vi.fn(async () => {
      expect(actingKey.value).toBe('1:void')
      return 'ok'
    })

    await expect(run(1, task, 'void')).resolves.toBe('ok')
    expect(task).toHaveBeenCalledTimes(1)
    expect(actingKey.value).toBeNull()
  })

  it('isActing / isBlocked 按行与动作精确判定', async () => {
    const { isActing, isBlocked, run } = useRowAction()
    let actingDuring = false
    let blockedDuring = false

    await run(
      7,
      async () => {
        actingDuring = isActing(7, 'submit')
        blockedDuring = isBlocked()
      },
      'submit'
    )

    expect(actingDuring).toBe(true)
    expect(blockedDuring).toBe(true)
    expect(isActing(7, 'submit')).toBe(false)
    expect(isBlocked()).toBe(false)
  })

  it('并发点击时后者直接放弃，不重复执行', async () => {
    const { run } = useRowAction()
    let release: (() => void) | null = null
    const first = run(
      1,
      () =>
        new Promise<string>((resolve) => {
          release = () => resolve('first')
        }),
      'void'
    )
    const second = run(2, async () => 'second', 'void')

    await expect(second).resolves.toBeUndefined()
    release!()
    await expect(first).resolves.toBe('first')
  })

  it('任务抛错后仍复位状态', async () => {
    const { actingKey, run } = useRowAction()
    await expect(
      run(1, async () => {
        throw new Error('boom')
      })
    ).rejects.toThrow('boom')
    expect(actingKey.value).toBeNull()
  })
})
