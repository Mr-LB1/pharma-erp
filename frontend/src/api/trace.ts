import { request } from './request'
import type { TraceResult } from '@/types'

// 正向：批次 → 采购入库 → 销售出库 → 客户
export function traceForward(productId: number, batchNo: string): Promise<TraceResult> {
  return request<TraceResult>({
    url: '/api/trace/forward',
    method: 'get',
    params: { productId, batchNo }
  })
}

// 逆向：批次 → 销售出库 → 采购入库 → 供应商
export function traceBackward(productId: number, batchNo: string): Promise<TraceResult> {
  return request<TraceResult>({
    url: '/api/trace/backward',
    method: 'get',
    params: { productId, batchNo }
  })
}
