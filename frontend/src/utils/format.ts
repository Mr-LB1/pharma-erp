// 统一格式化工具：金额、数量、日期

/**
 * 金额格式化：千分位 + ¥ + 两位小数（P1-32）
 * - null / undefined / '' / 非数字 → '-'
 * - 负数：-¥1,234.00
 * @param value 金额（number 或数字字符串，后端 BigDecimal 序列化为 number）
 * @param withSymbol 是否带 ¥ 符号，默认 true
 */
export function formatMoney(value?: number | string | null, withSymbol = true): string {
  if (value === null || value === undefined || value === '') return '-'
  const n = typeof value === 'number' ? value : Number(value)
  if (!Number.isFinite(n)) return '-'
  const text = Math.abs(n).toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  })
  const sign = n < 0 ? '-' : ''
  if (!withSymbol) return `${sign}${text}`
  return n < 0 ? `-¥${text}` : `¥${text}`
}

/** 数量格式化：千分位，最多 2 位小数（去尾零） */
export function formatQty(value?: number | string | null): string {
  if (value === null || value === undefined || value === '') return '-'
  const n = typeof value === 'number' ? value : Number(value)
  if (!Number.isFinite(n)) return '-'
  return n.toLocaleString('zh-CN', { maximumFractionDigits: 2 })
}

/**
 * 剩余天数：按本地日历日差值计算（负数为已过期，0 为今日到期）。
 * 用日历日而非毫秒差，避免时分秒导致「剩余 0 天」被判成负。
 */
export function daysUntil(dayText?: string | null): number | null {
  if (!dayText) return null
  const target = new Date(`${String(dayText).slice(0, 10)}T00:00:00`)
  if (Number.isNaN(target.getTime())) return null
  const now = new Date()
  const todayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  return Math.round((target.getTime() - todayStart.getTime()) / 86400000)
}

export type ExpireLevel = 'expired' | 'near' | 'normal'

/**
 * 效期分级（与出库批次分配预览的红/黄标语义一致）：
 * - expired：已过期
 * - near：剩余 ≤ nearDays 天（默认 30）
 * - normal：其余
 * 日期缺失/非法返回 null，便于调用方显示占位符
 */
export function expireLevel(dayText?: string | null, nearDays = 30): ExpireLevel | null {
  const days = daysUntil(dayText)
  if (days === null) return null
  if (days < 0) return 'expired'
  if (days <= nearDays) return 'near'
  return 'normal'
}

// ===== 日期约束（el-date-picker :disabled-date） =====
// 统一按本地日历日比较（YYYY-MM-DD 字符串比较），避免时区/时分秒干扰

/** Date → 本地日历日 YYYY-MM-DD */
export function toDayText(value: Date | string): string {
  if (typeof value === 'string') return value.slice(0, 10)
  const y = value.getFullYear()
  const m = String(value.getMonth() + 1).padStart(2, '0')
  const d = String(value.getDate()).padStart(2, '0')
  return `${y}-${m}-${d}`
}

/** 今天（本地日历日 YYYY-MM-DD） */
export function todayText(): string {
  return toDayText(new Date())
}

/** 禁用未来日期：业务发生日期 / 生产日期不得晚于今天 */
export function disabledFutureDate(date: Date): boolean {
  return toDayText(date) > todayText()
}

/** 禁用过去日期：证照到期日不得早于今天 */
export function disabledPastDate(date: Date): boolean {
  return toDayText(date) < todayText()
}

/** 禁用早于 min 的日期：用于查询区间「结束日期 ≥ 开始日期」 */
export function disabledBeforeDate(min?: string | Date | null) {
  if (!min) return () => false
  const minDay = toDayText(min)
  return (date: Date) => toDayText(date) < minDay
}

/** 禁用晚于 max 的日期：用于查询区间「开始日期 ≤ 结束日期」 */
export function disabledAfterDate(max?: string | Date | null) {
  if (!max) return () => false
  const maxDay = toDayText(max)
  return (date: Date) => toDayText(date) > maxDay
}

/** 禁用 min 当日及之前：用于「效期必须晚于生产日期」 */
export function disabledOnOrBeforeDate(min?: string | Date | null) {
  if (!min) return () => false
  const minDay = toDayText(min)
  return (date: Date) => toDayText(date) <= minDay
}

/** 日期时间展示：ISO 字符串 → `YYYY-MM-DD HH:mm:ss`（空值显示 '-'） */
export function formatDateTime(value?: string | null): string {
  if (!value) return '-'
  return value.replace('T', ' ').slice(0, 19)
}
