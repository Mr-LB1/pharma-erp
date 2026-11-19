import { request } from './request'
import type { PageResult, ApprovalPendingItem } from '@/types'

// 待审批列表（SUBMITTED 单据）；silent=true 用于工作台降级探测，不弹错误提示
export function listPending(
  page = 1,
  size = 20,
  silent = false
): Promise<PageResult<ApprovalPendingItem>> {
  return request<PageResult<ApprovalPendingItem>>({
    url: '/api/approvals/pending',
    method: 'get',
    params: { page, size },
    silent
  })
}

export function approve(bizType: string, bizId: number, comment?: string): Promise<void> {
  return request<null>({
    url: `/api/approvals/${bizType}/${bizId}/approve`,
    method: 'post',
    data: { comment: comment || null }
  }).then(() => undefined)
}

export function reject(bizType: string, bizId: number, comment?: string): Promise<void> {
  return request<null>({
    url: `/api/approvals/${bizType}/${bizId}/reject`,
    method: 'post',
    data: { comment: comment || null }
  }).then(() => undefined)
}
