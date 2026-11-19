// 单据/库存状态中文标签与 tag 类型（与后端枚举约定一致）

const LABELS: Record<string, string> = {
  DRAFT: '草稿',
  SUBMITTED: '待审批',
  PENDING: '待审批',
  APPROVED: '已审核',
  PUSHED: '已下发WMS',
  REJECTED: '已驳回',
  PARTIAL_RECEIVED: '部分入库',
  PARTIAL_DELIVERED: '部分出库',
  COMPLETED: '已完成',
  ACTIVE: '正常',
  INACTIVE: '停用',
  VOID: '已作废',
  DISPOSED: '已销毁',
  // 财务结算状态（批次四）
  UNSETTLED: '未结算',
  PARTIAL: '部分结算',
  SETTLED: '已结算',
  // 退货验收状态（批次四）
  RECEIVED: '已验收'
}

// 审批流单据状态筛选项（采购入库/销售出库/采购退货/仓库调拨共用）
export const DOC_STATUS_OPTIONS = [
  'DRAFT',
  'SUBMITTED',
  'APPROVED',
  'PUSHED',
  'REJECTED',
  'COMPLETED',
  'VOID'
] as const

// 已下发 WMS、等待回传：不可作废
export function isAwaitingWms(status?: string): boolean {
  return status === 'PUSHED'
}

// 可作废的状态：草稿 / 已驳回（后端对 SUBMITTED 直接作废返回 400，须先驳回）
export function canVoid(status?: string): boolean {
  return status === 'DRAFT' || status === 'REJECTED'
}

export type TagType = 'primary' | 'success' | 'warning' | 'danger' | 'info'

const TAGS: Record<string, TagType> = {
  DRAFT: 'info',
  SUBMITTED: 'primary',
  PENDING: 'warning',
  APPROVED: 'success',
  PUSHED: 'primary',
  REJECTED: 'danger',
  PARTIAL_RECEIVED: 'warning',
  PARTIAL_DELIVERED: 'warning',
  COMPLETED: 'success',
  ACTIVE: 'success',
  INACTIVE: 'info',
  VOID: 'danger',
  DISPOSED: 'info',
  UNSETTLED: 'warning',
  PARTIAL: 'warning',
  SETTLED: 'success',
  RECEIVED: 'success'
}

export function statusLabel(v?: string): string {
  return LABELS[v || ''] || v || '-'
}

export function statusTag(v?: string): TagType {
  return TAGS[v || ''] || 'info'
}

// 审批流单据状态：历史数据中入库/出库的 ACTIVE 等价于已完成
export function docStatusLabel(v?: string): string {
  return v === 'ACTIVE' ? '已完成' : statusLabel(v)
}

export function docStatusTag(v?: string): TagType {
  return v === 'ACTIVE' ? 'success' : statusTag(v)
}
