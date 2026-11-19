import { request } from './request'
import { requestExcel } from './excel'
import type {
  BatchResult,
  PageResult,
  PurchaseOrder,
  PurchaseOrderCreateRequest,
  PurchaseReceive,
  PurchaseReceiveCreateRequest,
  PurchaseReturn,
  PurchaseReturnCreateRequest
} from '@/types'

export function listPurchaseOrders(
  keyword?: string,
  status?: string,
  page = 1,
  size = 20
): Promise<PageResult<PurchaseOrder>> {
  return request<PageResult<PurchaseOrder>>({
    url: '/api/purchase/orders',
    method: 'get',
    params: { keyword, status, page, size }
  })
}

export function createPurchaseOrder(payload: PurchaseOrderCreateRequest): Promise<PurchaseOrder> {
  return request<PurchaseOrder>({ url: '/api/purchase/orders', method: 'post', data: payload })
}

// 单据详情（含明细）
export function getPurchaseOrder(id: number): Promise<PurchaseOrder> {
  return request<PurchaseOrder>({ url: `/api/purchase/orders/${id}`, method: 'get', silent: true })
}

// 采购订单导出（按查询条件，xlsx 二进制流）
export function exportPurchaseOrders(keyword?: string, status?: string): Promise<Blob> {
  return requestExcel('/api/purchase/orders/export', { keyword, status })
}

// 批量提交：逐条独立事务，返回逐条结果
export function batchSubmitPurchaseOrders(ids: number[]): Promise<BatchResult> {
  return request<BatchResult>({
    url: '/api/purchase/orders/batch-submit',
    method: 'post',
    data: { ids },
    timeout: 60000
  })
}

// 批量作废（可带作废原因）
export function batchVoidPurchaseOrders(ids: number[], reason?: string): Promise<BatchResult> {
  return request<BatchResult>({
    url: '/api/purchase/orders/batch-void',
    method: 'post',
    data: { ids, reason: reason || null },
    timeout: 60000
  })
}

export function submitPurchaseOrder(id: number): Promise<PurchaseOrder> {
  return request<PurchaseOrder>({ url: `/api/purchase/orders/${id}/submit`, method: 'post' })
}

// 编辑草稿订单（仅 DRAFT 可编辑，后端重建明细）
export function updatePurchaseOrder(
  id: number,
  payload: PurchaseOrderCreateRequest
): Promise<PurchaseOrder> {
  return request<PurchaseOrder>({ url: `/api/purchase/orders/${id}`, method: 'put', data: payload })
}

// 驳回后重新提交（REJECTED → DRAFT → SUBMITTED）
export function resubmitPurchaseOrder(id: number): Promise<PurchaseOrder> {
  return request<PurchaseOrder>({ url: `/api/purchase/orders/${id}/resubmit`, method: 'post' })
}

// 作废订单（DRAFT/SUBMITTED → VOID 终态）
export function voidPurchaseOrder(id: number, reason?: string): Promise<PurchaseOrder> {
  return request<PurchaseOrder>({
    url: `/api/purchase/orders/${id}/void`,
    method: 'post',
    data: { reason: reason || null }
  })
}

export function listPurchaseReceives(
  keyword?: string,
  status?: string,
  page = 1,
  size = 20
): Promise<PageResult<PurchaseReceive>> {
  return request<PageResult<PurchaseReceive>>({
    url: '/api/purchase/receives',
    method: 'get',
    params: { keyword, status, page, size }
  })
}

export function createPurchaseReceive(
  payload: PurchaseReceiveCreateRequest
): Promise<PurchaseReceive> {
  return request<PurchaseReceive>({ url: '/api/purchase/receives', method: 'post', data: payload })
}

// 提交审批（DRAFT 首提 / REJECTED 重提，后端同一接口）
export function submitPurchaseReceive(id: number): Promise<PurchaseReceive> {
  return request<PurchaseReceive>({ url: `/api/purchase/receives/${id}/submit`, method: 'post' })
}

// 批量提交审批（逐条独立事务，返回逐条结果）
export function batchSubmitPurchaseReceives(ids: number[]): Promise<BatchResult> {
  return request<BatchResult>({
    url: '/api/purchase/receives/batch-submit',
    method: 'post',
    data: { ids },
    timeout: 60000
  })
}

// 入库单详情（含明细）
export function getPurchaseReceive(id: number): Promise<PurchaseReceive> {
  return request<PurchaseReceive>({ url: `/api/purchase/receives/${id}`, method: 'get', silent: true })
}

// 入库单导出
export function exportPurchaseReceives(keyword?: string, status?: string): Promise<Blob> {
  return requestExcel('/api/purchase/receives/export', { keyword, status })
}

// 批量作废入库单（回冲库存 + 重算订单状态）
export function batchVoidPurchaseReceives(ids: number[], reason?: string): Promise<BatchResult> {
  return request<BatchResult>({
    url: '/api/purchase/receives/batch-void',
    method: 'post',
    data: { ids, reason: reason || null },
    timeout: 60000
  })
}

// 作废入库单（回冲库存 + 重算订单状态）
export function voidPurchaseReceive(id: number, reason?: string): Promise<PurchaseReceive> {
  return request<PurchaseReceive>({
    url: `/api/purchase/receives/${id}/void`,
    method: 'post',
    data: { reason: reason || null }
  })
}

// ===== 采购退货 =====
export function listPurchaseReturns(
  keyword?: string,
  supplierId?: number,
  status?: string,
  page = 1,
  size = 20
): Promise<PageResult<PurchaseReturn>> {
  return request<PageResult<PurchaseReturn>>({
    url: '/api/purchase/returns',
    method: 'get',
    params: { keyword, supplierId, status, page, size }
  })
}

export function createPurchaseReturn(
  payload: PurchaseReturnCreateRequest
): Promise<PurchaseReturn> {
  return request<PurchaseReturn>({ url: '/api/purchase/returns', method: 'post', data: payload })
}

// 提交审批（DRAFT 首提 / REJECTED 重提）
export function submitPurchaseReturn(id: number): Promise<PurchaseReturn> {
  return request<PurchaseReturn>({ url: `/api/purchase/returns/${id}/submit`, method: 'post' })
}

// 批量提交审批
export function batchSubmitPurchaseReturns(ids: number[]): Promise<BatchResult> {
  return request<BatchResult>({
    url: '/api/purchase/returns/batch-submit',
    method: 'post',
    data: { ids },
    timeout: 60000
  })
}

// 退货单详情（含明细）
export function getPurchaseReturn(id: number): Promise<PurchaseReturn> {
  return request<PurchaseReturn>({ url: `/api/purchase/returns/${id}`, method: 'get', silent: true })
}

// 退货单导出
export function exportPurchaseReturns(
  keyword?: string,
  supplierId?: number,
  status?: string
): Promise<Blob> {
  return requestExcel('/api/purchase/returns/export', { keyword, supplierId, status })
}

// 批量作废采购退货（库存回补 + 红字应付联动作废）
export function batchVoidPurchaseReturns(ids: number[], reason?: string): Promise<BatchResult> {
  return request<BatchResult>({
    url: '/api/purchase/returns/batch-void',
    method: 'post',
    data: { ids, reason: reason || null },
    timeout: 60000
  })
}

// 作废采购退货（库存回补 + 红字应付联动作废）
export function voidPurchaseReturn(id: number, reason?: string): Promise<PurchaseReturn> {
  return request<PurchaseReturn>({
    url: `/api/purchase/returns/${id}/void`,
    method: 'post',
    data: { reason: reason || null }
  })
}
