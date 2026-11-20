import { request } from './request'
import { requestExcel } from './excel'
import type {
  BatchResult,
  PageResult,
  SalesOrder,
  SalesOrderCreateRequest,
  SalesDelivery,
  SalesDeliveryCreateRequest,
  SalesReturn,
  SalesReturnCreateRequest,
  SalesReturnReceiveRequest
} from '@/types'

export function listSalesOrders(
  keyword?: string,
  status?: string,
  page = 1,
  size = 20
): Promise<PageResult<SalesOrder>> {
  return request<PageResult<SalesOrder>>({
    url: '/api/sales/orders',
    method: 'get',
    params: { keyword, status, page, size }
  })
}

export function createSalesOrder(payload: SalesOrderCreateRequest): Promise<SalesOrder> {
  return request<SalesOrder>({ url: '/api/sales/orders', method: 'post', data: payload })
}

// 单据详情（含明细）
export function getSalesOrder(id: number): Promise<SalesOrder> {
  return request<SalesOrder>({ url: `/api/sales/orders/${id}`, method: 'get', silent: true })
}

// 销售订单导出
export function exportSalesOrders(keyword?: string, status?: string): Promise<Blob> {
  return requestExcel('/api/sales/orders/export', { keyword, status })
}

// 批量提交：逐条独立事务
export function batchSubmitSalesOrders(ids: number[]): Promise<BatchResult> {
  return request<BatchResult>({
    url: '/api/sales/orders/batch-submit',
    method: 'post',
    data: { ids },
    timeout: 60000
  })
}

// 批量作废
export function batchVoidSalesOrders(ids: number[], reason?: string): Promise<BatchResult> {
  return request<BatchResult>({
    url: '/api/sales/orders/batch-void',
    method: 'post',
    data: { ids, reason: reason || null },
    timeout: 60000
  })
}

export function submitSalesOrder(id: number): Promise<SalesOrder> {
  return request<SalesOrder>({ url: `/api/sales/orders/${id}/submit`, method: 'post' })
}

// 编辑草稿订单（仅 DRAFT 可编辑，后端重建明细）
export function updateSalesOrder(
  id: number,
  payload: SalesOrderCreateRequest
): Promise<SalesOrder> {
  return request<SalesOrder>({ url: `/api/sales/orders/${id}`, method: 'put', data: payload })
}

// 驳回后重新提交（REJECTED → DRAFT → SUBMITTED）
export function resubmitSalesOrder(id: number): Promise<SalesOrder> {
  return request<SalesOrder>({ url: `/api/sales/orders/${id}/resubmit`, method: 'post' })
}

// 作废订单（DRAFT/SUBMITTED → VOID 终态）
export function voidSalesOrder(id: number, reason?: string): Promise<SalesOrder> {
  return request<SalesOrder>({
    url: `/api/sales/orders/${id}/void`,
    method: 'post',
    data: { reason: reason || null }
  })
}

export function listSalesDeliveries(
  keyword?: string,
  status?: string,
  page = 1,
  size = 20
): Promise<PageResult<SalesDelivery>> {
  return request<PageResult<SalesDelivery>>({
    url: '/api/sales/deliveries',
    method: 'get',
    params: { keyword, status, page, size }
  })
}

export function createSalesDelivery(
  payload: SalesDeliveryCreateRequest
): Promise<SalesDelivery> {
  return request<SalesDelivery>({ url: '/api/sales/deliveries', method: 'post', data: payload })
}

// 提交审批（DRAFT 首提 / REJECTED 重提）
export function submitSalesDelivery(id: number): Promise<SalesDelivery> {
  return request<SalesDelivery>({ url: `/api/sales/deliveries/${id}/submit`, method: 'post' })
}

// 批量提交审批
export function batchSubmitSalesDeliveries(ids: number[]): Promise<BatchResult> {
  return request<BatchResult>({
    url: '/api/sales/deliveries/batch-submit',
    method: 'post',
    data: { ids },
    timeout: 60000
  })
}

// 出库单详情（含明细）
export function getSalesDelivery(id: number): Promise<SalesDelivery> {
  return request<SalesDelivery>({ url: `/api/sales/deliveries/${id}`, method: 'get', silent: true })
}

// 出库单导出
export function exportSalesDeliveries(keyword?: string, status?: string): Promise<Blob> {
  return requestExcel('/api/sales/deliveries/export', { keyword, status })
}

// 批量作废出库单（回冲库存 + 重算订单状态）
export function batchVoidSalesDeliveries(ids: number[], reason?: string): Promise<BatchResult> {
  return request<BatchResult>({
    url: '/api/sales/deliveries/batch-void',
    method: 'post',
    data: { ids, reason: reason || null },
    timeout: 60000
  })
}

// 作废出库单（回冲库存 + 重算订单状态）
export function voidSalesDelivery(id: number, reason?: string): Promise<SalesDelivery> {
  return request<SalesDelivery>({
    url: `/api/sales/deliveries/${id}/void`,
    method: 'post',
    data: { reason: reason || null }
  })
}

/** WMS 回传请求体（与后端 com.pharma.common.WmsCallbackRequest 对齐）：items 省略时后端按单据量回传。 */
export interface WmsCallbackPayload {
  /** WMS 单号（可空，回传时留痕） */
  wmsNo?: string | null
  /** WMS 回传时间（可空，默认取服务端当前时间） */
  callbackTime?: string | null
  /** 逐行实际数量；不传表示全部按单据量回传 */
  items?: { itemId: number; actualQty: number }[]
}

// WMS 回传实量：仅 PUSHED 可回传，按实量执行库存/财务并置 COMPLETED（重复回传后端 400）
export function wmsCallbackSalesDelivery(
  id: number,
  payload?: WmsCallbackPayload
): Promise<SalesDelivery> {
  return request<SalesDelivery>({
    url: `/api/sales/deliveries/${id}/wms-callback`,
    method: 'post',
    data: payload ?? {}
  })
}

// ===== 销售退货 =====
export function listSalesReturns(
  keyword?: string,
  status?: string,
  page = 1,
  size = 20
): Promise<PageResult<SalesReturn>> {
  return request<PageResult<SalesReturn>>({
    url: '/api/sales/returns',
    method: 'get',
    params: { keyword, status, page, size }
  })
}

export function createSalesReturn(payload: SalesReturnCreateRequest): Promise<SalesReturn> {
  return request<SalesReturn>({ url: '/api/sales/returns', method: 'post', data: payload })
}

// 退货单详情（含明细）
export function getSalesReturn(id: number): Promise<SalesReturn> {
  return request<SalesReturn>({ url: `/api/sales/returns/${id}`, method: 'get', silent: true })
}

// 退货单导出
export function exportSalesReturns(keyword?: string, status?: string): Promise<Blob> {
  return requestExcel('/api/sales/returns/export', { keyword, status })
}

// 批量作废销售退货（已验收的回冲库存 + 红字应收联动作废）
export function batchVoidSalesReturns(ids: number[], reason?: string): Promise<BatchResult> {
  return request<BatchResult>({
    url: '/api/sales/returns/batch-void',
    method: 'post',
    data: { ids, reason: reason || null },
    timeout: 60000
  })
}

export function submitSalesReturn(id: number): Promise<SalesReturn> {
  return request<SalesReturn>({ url: `/api/sales/returns/${id}/submit`, method: 'post' })
}

// 作废：RECEIVED 会回冲库存并作废红字应收；SUBMITTED 不可直接作废（后端 400）
export function voidSalesReturn(id: number, reason?: string): Promise<SalesReturn> {
  return request<SalesReturn>({
    url: `/api/sales/returns/${id}/void`,
    method: 'post',
    data: { reason }
  })
}

// 验收分流：合格回库存（按原成本回冲）、不合格转不合格品
export function receiveSalesReturn(
  id: number,
  payload: SalesReturnReceiveRequest
): Promise<SalesReturn> {
  return request<SalesReturn>({
    url: `/api/sales/returns/${id}/receive`,
    method: 'post',
    data: payload
  })
}
