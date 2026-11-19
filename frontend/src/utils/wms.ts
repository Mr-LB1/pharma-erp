import { formatQty } from './format'

/** WMS 回传差异展示（4 类单据共用）：实际量为空表示尚未回传 */
type WmsRow = Record<string, unknown>

function isBlank(v: unknown): boolean {
  return v === null || v === undefined || v === ''
}

/** 实际量：未回传显示 '-' */
export function wmsActualText(row: WmsRow): string {
  return formatQty(row.actualQty as number | string | null | undefined)
}

/**
 * 差异 = 实际量 - 单据量；未回传显示 '-'，超出/短少带正负号。
 * 后端已算 qtyDiff 时优先用后端值（口径/精度一致）。
 */
export function wmsDiffText(row: WmsRow): string {
  if (isBlank(row.actualQty)) return '-'
  const diff = isBlank(row.qtyDiff) ? Number(row.actualQty) - Number(row.quantity ?? 0) : Number(row.qtyDiff)
  if (!Number.isFinite(diff)) return '-'
  if (diff === 0) return '0'
  return `${diff > 0 ? '+' : '-'}${formatQty(Math.abs(diff))}`
}
