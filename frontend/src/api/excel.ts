import instance from './request'

/**
 * 请求 Excel 二进制流（GET，responseType=blob）。
 * 后端鉴权失败/异常时可能返回 JSON 错误体，需识别后抛出可读原因。
 */
export async function requestExcel(
  url: string,
  params?: Record<string, unknown>
): Promise<Blob> {
  const resp = (await instance.request({
    url,
    method: 'get',
    params,
    responseType: 'blob'
  })) as unknown as Blob | { message?: string }

  if (!(resp instanceof Blob)) {
    throw new Error(resp?.message || '导出失败')
  }
  if ((resp.type || '').includes('application/json')) {
    const text = await resp.text()
    let message = '导出失败'
    try {
      message = JSON.parse(text)?.message || message
    } catch {
      /* 保留默认提示 */
    }
    throw new Error(message)
  }
  return resp
}
