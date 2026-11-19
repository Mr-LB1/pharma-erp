import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { batchOperate } from '@/api/batch'
import type { BatchAction, BatchResource, BatchResult } from '@/types'

// 表格行最小约束：批量操作依赖 id
export interface BatchSelectableRow {
  id?: number
  [key: string]: unknown
}

const ACTION_TEXT: Record<BatchAction, string> = {
  ENABLE: '启用',
  DISABLE: '停用',
  DELETE: '删除'
}

function escapeHtml(text: string): string {
  return text.replace(/[&<>"']/g, (ch) => {
    const map: Record<string, string> = {
      '&': '&amp;',
      '<': '&lt;',
      '>': '&gt;',
      '"': '&quot;',
      "'": '&#39;'
    }
    return map[ch] ?? ch
  })
}

/**
 * 批量启用 / 停用 / 删除的通用逻辑：
 * 选择状态、二次确认、结果明细弹窗、操作后刷新列表
 */
export function useBatchOperation(options: {
  resource: BatchResource
  // 资源中文名，用于确认与提示文案，如「商品」
  label: string
  // 操作成功后刷新列表
  reload: () => void | Promise<void>
  // 清空表格勾选（由页面传入 tableRef.clearSelection）
  clearSelection?: () => void
}) {
  const selectedRows = ref<BatchSelectableRow[]>([])
  const batchLoading = ref(false)

  const selectedIds = computed(() =>
    selectedRows.value
      .map((row) => row.id)
      .filter((id): id is number => typeof id === 'number')
  )
  const selectedCount = computed(() => selectedIds.value.length)

  function handleSelectionChange(rows: BatchSelectableRow[]) {
    selectedRows.value = rows
  }

  function showResult(result: BatchResult, actionText: string) {
    const successCount = result.successCount ?? 0
    const failedCount = result.failedCount ?? 0
    const failedItems = (result.results || []).filter((item) => !item.success).slice(0, 20)

    if (failedCount <= 0) {
      ElMessage.success(`批量${actionText}成功 ${successCount} 条`)
      return
    }

    const listHtml = failedItems.length
      ? `<ul style="margin:8px 0 0;padding-left:18px;max-height:240px;overflow:auto;line-height:1.8">${failedItems
          .map(
            (item) =>
              `<li>${item.id != null ? `ID ${item.id}：` : ''}${escapeHtml(item.message || '操作失败')}</li>`
          )
          .join('')}</ul>`
      : ''
    const moreHtml =
      failedCount > failedItems.length
        ? `<p style="margin:8px 0 0">… 另有 ${failedCount - failedItems.length} 条失败</p>`
        : ''

    ElMessageBox.alert(
      `<p style="margin:0">成功 <b>${successCount}</b> 条，失败 <b style="color:var(--el-color-danger)">${failedCount}</b> 条</p>${listHtml}${moreHtml}`,
      `批量${actionText}结果`,
      {
        dangerouslyUseHTMLString: true,
        confirmButtonText: '知道了',
        type: 'warning'
      }
    ).catch(() => {})
  }

  async function handleBatchAction(action: BatchAction) {
    const ids = selectedIds.value
    if (!ids.length) {
      ElMessage.warning('请先勾选要操作的数据')
      return
    }

    const actionText = ACTION_TEXT[action]
    const suffix = action === 'DELETE' ? '，删除后不可在列表查看' : ''
    try {
      await ElMessageBox.confirm(
        `确认${actionText}已选中的 ${ids.length} 条${options.label}吗${suffix}？`,
        `批量${actionText}`,
        {
          type: 'warning',
          confirmButtonText: `确认${actionText}`,
          cancelButtonText: '取消'
        }
      )
    } catch {
      return
    }

    batchLoading.value = true
    try {
      const result = await batchOperate(options.resource, action, ids)
      showResult(result, actionText)
      options.clearSelection?.()
      selectedRows.value = []
      await options.reload()
    } catch {
      // 错误提示由 request 拦截器统一处理
    } finally {
      batchLoading.value = false
    }
  }

  return {
    selectedRows,
    selectedIds,
    selectedCount,
    batchLoading,
    handleSelectionChange,
    handleBatchAction
  }
}

/** 单据批量操作（批量提交 / 批量作废）：逐条独立事务，结果含失败明细 */
export interface DocumentBatchOptions {
  /** 资源中文名，如「采购订单」 */
  label: string
  /** 操作文案，如「提交」「作废」 */
  actionText: string
  /** 作废原因：required 必填 / optional 选填 / 不传则不询问 */
  reason?: 'required' | 'optional'
  /** 执行批量请求（ids + 作废原因） */
  execute: (ids: number[], reason?: string) => Promise<BatchResult>
  /** 操作完成后刷新列表 */
  reload: () => void | Promise<void>
  /** 清空表格勾选 */
  clearSelection?: () => void
  /** 自定义确认文案 */
  confirmText?: (count: number) => string
  /** 取行展示标识（如单据号），用于失败明细更易读 */
  labelOf?: (row: BatchSelectableRow) => string | undefined
}

/** 单次批量执行的覆盖项（用于同页多个批量动作：提交 / 作废） */
export interface DocumentBatchRunOptions {
  actionText?: string
  reason?: 'required' | 'optional'
  execute?: (ids: number[], reason?: string) => Promise<BatchResult>
  confirmText?: (count: number) => string
}

/**
 * 单据批量操作封装：选择状态 → 二次确认（作废可要求填原因）→ 调用批量接口 →
 * 结果弹窗（全成功 / 部分成功列失败单号+原因 / 全失败）→ 清空勾选并刷新。
 */
export function useDocumentBatch(options: DocumentBatchOptions) {
  const selectedRows = ref<BatchSelectableRow[]>([])
  const batchLoading = ref(false)

  const selectedIds = computed(() =>
    selectedRows.value
      .map((row) => row.id)
      .filter((id): id is number => typeof id === 'number')
  )
  const selectedCount = computed(() => selectedIds.value.length)

  function handleSelectionChange(rows: BatchSelectableRow[]) {
    selectedRows.value = rows
  }

  function showResult(result: BatchResult, ids: number[], actionText: string, labelMap: Map<number, string>) {
    const rows = result.results || result.items || []
    const successCount = result.successCount ?? result.success ?? rows.filter((r) => r.success).length
    const failedCount = result.failedCount ?? result.failed ?? rows.filter((r) => !r.success).length

    if (failedCount <= 0) {
      ElMessage.success(`批量${actionText}成功 ${successCount} 条`)
      return
    }

    const failedItems = rows
      .filter((item) => !item.success)
      .slice(0, 20)
      .map((item) => {
        // 后端失败项可能不带 id，用请求下标回填选中记录
        const id = item.id ?? (typeof item.index === 'number' ? ids[item.index] : undefined)
        const label = id != null ? labelMap.get(id) ?? `#${id}` : ''
        return { label, message: item.message || '操作失败' }
      })

    const listHtml = failedItems.length
      ? `<ul style="margin:8px 0 0;padding-left:18px;max-height:240px;overflow:auto;line-height:1.8">${failedItems
          .map((item) => `<li>${item.label ? `${escapeHtml(item.label)}：` : ''}${escapeHtml(item.message)}</li>`)
          .join('')}</ul>`
      : ''
    const moreHtml =
      failedCount > failedItems.length
        ? `<p style="margin:8px 0 0">… 另有 ${failedCount - failedItems.length} 条失败</p>`
        : ''
    const title = successCount > 0 ? `批量${actionText}部分成功` : `批量${actionText}失败`

    ElMessageBox.alert(
      `<p style="margin:0">成功 <b>${successCount}</b> 条，失败 <b style="color:var(--el-color-danger)">${failedCount}</b> 条</p>${listHtml}${moreHtml}`,
      title,
      {
        dangerouslyUseHTMLString: true,
        confirmButtonText: '知道了',
        type: successCount > 0 ? 'warning' : 'error'
      }
    ).catch(() => {})
  }

  async function handleBatchRun(runOptions?: DocumentBatchRunOptions) {
    const ids = selectedIds.value
    if (!ids.length) {
      ElMessage.warning('请先勾选要操作的数据')
      return
    }

    const actionText = runOptions?.actionText ?? options.actionText
    const reasonMode = runOptions?.reason ?? options.reason
    const execute = runOptions?.execute ?? options.execute
    const confirmText = runOptions?.confirmText ?? options.confirmText

    let reason: string | undefined
    try {
      if (reasonMode) {
        const { value } = await ElMessageBox.prompt(
          `确认${actionText}已选中的 ${ids.length} 条${options.label}吗？${reasonMode === 'required' ? '作废后不可恢复。' : ''}`,
          `批量${actionText}`,
          {
            type: 'warning',
            confirmButtonText: `确认${actionText}`,
            cancelButtonText: '取消',
            inputType: 'textarea',
            inputPlaceholder: reasonMode === 'required' ? '请填写原因' : '原因（选填）',
            inputValidator:
              reasonMode === 'required'
                ? (v: string) => (v && v.trim() ? true : '原因不能为空')
                : () => true
          }
        )
        reason = (value || '').trim() || undefined
      } else {
        await ElMessageBox.confirm(
          confirmText?.(ids.length) ??
            `确认${actionText}已选中的 ${ids.length} 条${options.label}吗？`,
          `批量${actionText}`,
          { type: 'warning', confirmButtonText: `确认${actionText}`, cancelButtonText: '取消' }
        )
      }
    } catch {
      return
    }

    batchLoading.value = true
    try {
      // 失败明细用单号而非主键 id 展示，更易定位
      const labelMap = new Map<number, string>()
      for (const row of selectedRows.value) {
        if (typeof row.id !== 'number') continue
        const label = options.labelOf?.(row)
        if (label) labelMap.set(row.id, label)
      }
      const result = await execute(ids, reason)
      showResult(result, ids, actionText, labelMap)
      options.clearSelection?.()
      selectedRows.value = []
      await options.reload()
    } catch {
      // 错误提示由 request 拦截器统一处理
    } finally {
      batchLoading.value = false
    }
  }

  return {
    selectedRows,
    selectedIds,
    selectedCount,
    batchLoading,
    handleSelectionChange,
    handleBatchRun,
    clearSelected: () => {
      selectedRows.value = []
    }
  }
}
