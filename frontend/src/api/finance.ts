import { request } from './request'
import type {
  PageResult,
  ArInvoice,
  ApInvoice,
  ArInvoiceCreateRequest,
  ApInvoiceCreateRequest,
  Receipt,
  ReceiptCreateRequest,
  Payment,
  PaymentCreateRequest,
  InvoiceRegister,
  InvoiceRegisterCreateRequest,
  ArStatement
} from '@/types'

export interface FinanceQuery {
  keyword?: string
  status?: string
  customerId?: number
  supplierId?: number
  page: number
  size: number
}

// ===== 应收单 =====
export function listArInvoices(params: FinanceQuery): Promise<PageResult<ArInvoice>> {
  return request<PageResult<ArInvoice>>({
    url: '/api/finance/ar-invoices',
    method: 'get',
    params
  })
}

// 手动补录应收（自动生成之外的兜底入口，后端固定 bizType=MANUAL）
export function createArInvoice(payload: ArInvoiceCreateRequest): Promise<ArInvoice> {
  return request<ArInvoice>({ url: '/api/finance/ar-invoices', method: 'post', data: payload })
}

// ===== 应付单 =====
export function listApInvoices(params: FinanceQuery): Promise<PageResult<ApInvoice>> {
  return request<PageResult<ApInvoice>>({
    url: '/api/finance/ap-invoices',
    method: 'get',
    params
  })
}

export function createApInvoice(payload: ApInvoiceCreateRequest): Promise<ApInvoice> {
  return request<ApInvoice>({ url: '/api/finance/ap-invoices', method: 'post', data: payload })
}

// ===== 收款单 =====
export function listReceipts(params: FinanceQuery): Promise<PageResult<Receipt>> {
  return request<PageResult<Receipt>>({
    url: '/api/finance/receipts',
    method: 'get',
    params
  })
}

export function createReceipt(payload: ReceiptCreateRequest): Promise<Receipt> {
  return request<Receipt>({ url: '/api/finance/receipts', method: 'post', data: payload })
}

// ===== 付款单 =====
export function listPayments(params: FinanceQuery): Promise<PageResult<Payment>> {
  return request<PageResult<Payment>>({
    url: '/api/finance/payments',
    method: 'get',
    params
  })
}

export function createPayment(payload: PaymentCreateRequest): Promise<Payment> {
  return request<Payment>({ url: '/api/finance/payments', method: 'post', data: payload })
}

// ===== 发票登记 =====
export function listInvoiceRegisters(
  keyword?: string,
  page = 1,
  size = 20,
  bizType?: string
): Promise<PageResult<InvoiceRegister>> {
  return request<PageResult<InvoiceRegister>>({
    url: '/api/finance/invoice-registers',
    method: 'get',
    params: { keyword, bizType, page, size }
  })
}

export function createInvoiceRegister(
  payload: InvoiceRegisterCreateRequest
): Promise<InvoiceRegister> {
  return request<InvoiceRegister>({
    url: '/api/finance/invoice-registers',
    method: 'post',
    data: payload
  })
}

// ===== 应收对账 =====
export function getArStatement(
  customerId: number,
  startDate?: string,
  endDate?: string
): Promise<ArStatement> {
  return request<ArStatement>({
    url: '/api/finance/statements/ar',
    method: 'get',
    params: { customerId, startDate, endDate }
  })
}
