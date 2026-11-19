import { request } from './request'
import { requestExcel } from './excel'
import type {
  BatchResult,
  FefoPreview,
  PageResult,
  Stock,
  Stocktake,
  StocktakeCreateRequest,
  StockTransfer,
  StockTransferCreateRequest
} from '@/types'

export interface StockQuery {
  productId?: number
  warehouseId?: number
  batchNo?: string
  expireBefore?: string
  page: number
  size: number
}

export function listStocks(params: StockQuery): Promise<PageResult<Stock>> {
  return request<PageResult<Stock>>({
    url: '/api/inventory/stocks',
    method: 'get',
    params
  })
}

export interface FefoPreviewParams {
  productId?: number
  warehouseId?: number
  quantity?: number
}

// FEFO 事前批次预览（只读）：模拟出库分配，不写库
export function fefoPreview(params: FefoPreviewParams): Promise<FefoPreview> {
  return request<FefoPreview>({
    url: '/api/inventory/fefo-preview',
    method: 'get',
    params,
    silent: true
  })
}

// ===== 盘点 =====
export function listStocktakes(page = 1, size = 20): Promise<PageResult<Stocktake>> {
  return request<PageResult<Stocktake>>({
    url: '/api/inventory/stocktakes',
    method: 'get',
    params: { page, size }
  })
}

export function createStocktake(payload: StocktakeCreateRequest): Promise<Stocktake> {
  return request<Stocktake>({ url: '/api/inventory/stocktakes', method: 'post', data: payload })
}

export function submitStocktake(id: number): Promise<Stocktake> {
  return request<Stocktake>({ url: `/api/inventory/stocktakes/${id}/submit`, method: 'post' })
}

// ===== 仓库调拨 =====
export function listStockTransfers(
  keyword?: string,
  status?: string,
  page = 1,
  size = 20
): Promise<PageResult<StockTransfer>> {
  return request<PageResult<StockTransfer>>({
    url: '/api/inventory/transfers',
    method: 'get',
    params: { keyword, status, page, size }
  })
}

export function createStockTransfer(payload: StockTransferCreateRequest): Promise<StockTransfer> {
  return request<StockTransfer>({ url: '/api/inventory/transfers', method: 'post', data: payload })
}

// 提交审批（DRAFT 首提 / REJECTED 重提）
export function submitStockTransfer(id: number): Promise<StockTransfer> {
  return request<StockTransfer>({ url: `/api/inventory/transfers/${id}/submit`, method: 'post' })
}

// 批量提交审批
export function batchSubmitStockTransfers(ids: number[]): Promise<BatchResult> {
  return request<BatchResult>({
    url: '/api/inventory/transfers/batch-submit',
    method: 'post',
    data: { ids },
    timeout: 60000
  })
}

// 调拨单详情（含明细）
export function getStockTransfer(id: number): Promise<StockTransfer> {
  return request<StockTransfer>({ url: `/api/inventory/transfers/${id}`, method: 'get', silent: true })
}

// 调拨单导出
export function exportStockTransfers(keyword?: string, status?: string): Promise<Blob> {
  return requestExcel('/api/inventory/transfers/export', { keyword, status })
}

// 批量作废调拨单（回冲两仓库存）
export function batchVoidStockTransfers(ids: number[], reason?: string): Promise<BatchResult> {
  return request<BatchResult>({
    url: '/api/inventory/transfers/batch-void',
    method: 'post',
    data: { ids, reason: reason || null },
    timeout: 60000
  })
}

// 作废调拨单（回冲两仓库存）
export function voidStockTransfer(id: number, reason?: string): Promise<StockTransfer> {
  return request<StockTransfer>({
    url: `/api/inventory/transfers/${id}/void`,
    method: 'post',
    data: { reason: reason || null }
  })
}
