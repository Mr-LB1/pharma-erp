import instance, { request } from './request'
import type {
  BatchAction,
  BatchRequest,
  BatchResource,
  BatchResult,
  DuplicateStrategy,
  ImportExecuteResult,
  ImportPreviewResult
} from '@/types'

// 批量启用 / 停用 / 删除：POST /api/{resource}/batch
// ids 与 items 二选一，按后端契约只传有值的那个
export function batchOperate<T = Record<string, unknown>>(
  resource: BatchResource,
  action: BatchAction,
  ids?: number[],
  items?: T[]
): Promise<BatchResult> {
  const payload: BatchRequest<T> = { action }
  if (ids && ids.length) payload.ids = ids
  if (items && items.length) payload.items = items
  return request<BatchResult>({
    url: `/api/${resource}/batch`,
    method: 'post',
    data: payload,
    // 批量逐条独立事务执行，条数多时耗时较长，放宽默认 15s 超时
    timeout: 60000
  })
}

// 触发浏览器下载
function saveBlob(blob: Blob, filename: string) {
  const url = window.URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  window.URL.revokeObjectURL(url)
}

// 下载导入模板：GET /api/{resource}/import/template（二进制流，直接触发下载）
export async function downloadTemplate(resource: BatchResource): Promise<void> {
  const resp = (await instance.request({
    url: `/api/${resource}/import/template`,
    method: 'get',
    responseType: 'blob'
  })) as unknown as Blob | { message?: string }

  if (!(resp instanceof Blob)) {
    throw new Error(resp?.message || '模板下载失败')
  }
  // 后端未就绪或鉴权失败时可能返回 JSON 错误体，需要识别后抛错
  if ((resp.type || '').includes('application/json')) {
    const text = await resp.text()
    let message = '模板下载失败'
    try {
      message = JSON.parse(text)?.message || message
    } catch {
      /* 保留默认提示 */
    }
    throw new Error(message)
  }
  saveBlob(resp, `${resource}-import-template.xlsx`)
}

function buildFormData(file: File): FormData {
  const formData = new FormData()
  formData.append('file', file)
  return formData
}

// 导入预览（解析 + 校验，不落库）：POST /api/{resource}/import/preview
export function importPreview(
  resource: BatchResource,
  file: File
): Promise<ImportPreviewResult> {
  return request<ImportPreviewResult>({
    url: `/api/${resource}/import/preview`,
    method: 'post',
    data: buildFormData(file),
    // 解析 + 校验 5000 行可能较慢
    timeout: 120000
  })
}

// 执行导入：POST /api/{resource}/import?duplicateStrategy=SKIP|UPDATE|ERROR
export function importExecute(
  resource: BatchResource,
  file: File,
  strategy: DuplicateStrategy
): Promise<ImportExecuteResult> {
  return request<ImportExecuteResult>({
    url: `/api/${resource}/import`,
    method: 'post',
    params: { duplicateStrategy: strategy },
    data: buildFormData(file),
    // 逐行独立事务写入，放宽超时
    timeout: 180000
  })
}
