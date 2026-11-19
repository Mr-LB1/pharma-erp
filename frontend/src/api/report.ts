import instance, { request } from './request'
import type {
  StockSummaryItem,
  NearExpiryItem,
  InventoryBalanceItem,
  GrossProfitItem,
  DashboardData,
  PageResult
} from '@/types'

export interface StockSummaryParams {
  productId?: number
  startDate?: string
  endDate?: string
  /** 仓库筛选（为空表示全部仓库） */
  warehouseId?: number
  /** 分页（P2-7），默认 1/20 */
  page?: number
  size?: number
}

export interface InventoryBalanceParams {
  productId?: number
  warehouseId?: number
  page: number
  size: number
}

// 进销存汇总（分页，P2-7）
export function listStockSummary(
  params: StockSummaryParams
): Promise<PageResult<StockSummaryItem>> {
  return request<PageResult<StockSummaryItem>>({
    url: '/api/reports/stock-summary',
    method: 'get',
    params
  })
}

// 近效期清单（全量，保留给旧调用方）
export function listNearExpiryList(days = 30, silent = false): Promise<NearExpiryItem[]> {
  return request<NearExpiryItem[]>({
    url: '/api/reports/near-expiry-list',
    method: 'get',
    params: { days },
    silent
  })
}

// 近效期清单（分页，P1-4）
export function nearExpiryPage(
  days = 30,
  page = 1,
  size = 20
): Promise<PageResult<NearExpiryItem>> {
  return request<PageResult<NearExpiryItem>>({
    url: '/api/reports/near-expiry',
    method: 'get',
    params: { days, page, size }
  })
}

// 近效期清单导出（EasyExcel xlsx 二进制流，P1-4）
export function exportNearExpiry(days = 30): Promise<Blob> {
  return requestExcel('/api/reports/near-expiry/export', { days })
}

// 进销存汇总导出（P2-7，导出全量不受分页限制）
export function exportStockSummary(params: Omit<StockSummaryParams, 'page' | 'size'>): Promise<Blob> {
  return requestExcel('/api/reports/stock-summary/export', params)
}

// 毛利报表导出（P2-7）
export function exportGrossProfit(
  productId?: number,
  startDate?: string,
  endDate?: string
): Promise<Blob> {
  return requestExcel('/api/reports/gross-profit/export', { productId, startDate, endDate })
}

async function requestExcel(url: string, params: Record<string, unknown>): Promise<Blob> {
  const resp = (await instance.request({
    url,
    method: 'get',
    params,
    responseType: 'blob'
  })) as unknown as Blob | { message?: string }

  if (!(resp instanceof Blob)) {
    throw new Error(resp?.message || '导出失败')
  }
  // 后端异常时可能返回 JSON 错误体，识别后抛错提示
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

// 库存结存（分页）
export function listInventoryBalance(
  params: InventoryBalanceParams
): Promise<PageResult<InventoryBalanceItem>> {
  return request<PageResult<InventoryBalanceItem>>({
    url: '/api/reports/inventory-balance',
    method: 'get',
    params
  })
}

// 工作台聚合数据（批次四）；接口未就绪时静默失败，由页面降级拼装
export function getDashboard(): Promise<DashboardData> {
  return request<DashboardData>({ url: '/api/reports/dashboard', method: 'get', silent: true })
}

// 毛利报表（区间按商品汇总），工作台商品排行降级使用
export function listGrossProfit(
  productId?: number,
  startDate?: string,
  endDate?: string,
  silent = false
): Promise<GrossProfitItem[]> {
  return request<GrossProfitItem[]>({
    url: '/api/reports/gross-profit',
    method: 'get',
    params: { productId, startDate, endDate },
    silent
  })
}
