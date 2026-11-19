import { request } from './request'
import type {
  PageResult,
  FirstAuditType,
  FirstAuditPendingItem,
  ExpiringLicense,
  Unqualified,
  UnqualifiedCreateRequest,
  UnqualifiedDisposeRequest
} from '@/types'

// 待审核列表（PENDING），type ∈ product / supplier / customer
export function listFirstAuditsPending(
  type: FirstAuditType,
  page = 1,
  size = 20
): Promise<PageResult<FirstAuditPendingItem>> {
  return request<PageResult<FirstAuditPendingItem>>({
    url: '/api/quality/first-audits/pending',
    method: 'get',
    params: { type, page, size }
  })
}

export function firstAuditApprove(
  type: FirstAuditType,
  id: number,
  comment?: string
): Promise<void> {
  return request<null>({
    url: `/api/quality/first-audits/${type}/${id}/approve`,
    method: 'post',
    data: { comment }
  }).then(() => undefined)
}

export function firstAuditReject(
  type: FirstAuditType,
  id: number,
  comment: string
): Promise<void> {
  return request<null>({
    url: `/api/quality/first-audits/${type}/${id}/reject`,
    method: 'post',
    data: { comment }
  }).then(() => undefined)
}

// 证照到期前 N 天预警（供应商 + 客户）
export function listExpiringLicenses(days = 90, silent = false): Promise<ExpiringLicense[]> {
  return request<ExpiringLicense[]>({
    url: '/api/quality/expiring-licenses',
    method: 'get',
    params: { days },
    silent
  })
}

// ===== 不合格品 =====
export function listUnqualified(page = 1, size = 20): Promise<PageResult<Unqualified>> {
  return request<PageResult<Unqualified>>({
    url: '/api/quality/unqualified',
    method: 'get',
    params: { page, size }
  })
}

export function createUnqualified(payload: UnqualifiedCreateRequest): Promise<Unqualified> {
  return request<Unqualified>({ url: '/api/quality/unqualified', method: 'post', data: payload })
}

export function disposeUnqualified(
  id: number,
  payload: UnqualifiedDisposeRequest
): Promise<void> {
  return request<null>({
    url: `/api/quality/unqualified/${id}/dispose`,
    method: 'post',
    data: payload
  }).then(() => undefined)
}
