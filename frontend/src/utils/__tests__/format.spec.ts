import { describe, expect, it } from 'vitest'
import {
  formatMoney,
  formatQty,
  daysUntil,
  expireLevel,
  todayText,
  toDayText
} from '../format'

describe('formatMoney', () => {
  it('零值保留两位小数与符号', () => {
    expect(formatMoney(0)).toBe('¥0.00')
    expect(formatMoney('0')).toBe('¥0.00')
  })

  it('负数符号在货币符号之前', () => {
    expect(formatMoney(-1234.5)).toBe('-¥1,234.50')
    expect(formatMoney(-0.5, false)).toBe('-0.50')
  })

  it('千分位与四舍五入', () => {
    expect(formatMoney(1234567.891)).toBe('¥1,234,567.89')
    expect(formatMoney(1234.567, false)).toBe('1,234.57')
  })

  it('空值与非法数字显示占位符', () => {
    expect(formatMoney(null)).toBe('-')
    expect(formatMoney(undefined)).toBe('-')
    expect(formatMoney('')).toBe('-')
    expect(formatMoney('abc')).toBe('-')
    expect(formatMoney(Number.POSITIVE_INFINITY)).toBe('-')
  })
})

describe('formatQty', () => {
  it('千分位且去尾零', () => {
    expect(formatQty(1234.5)).toBe('1,234.5')
    expect(formatQty(1200)).toBe('1,200')
    expect(formatQty('0.50')).toBe('0.5')
  })

  it('空值显示占位符', () => {
    expect(formatQty(null)).toBe('-')
    expect(formatQty('')).toBe('-')
  })
})

describe('日期与效期辅助', () => {
  it('toDayText 统一为 YYYY-MM-DD', () => {
    expect(toDayText(new Date(2026, 0, 5))).toBe('2026-01-05')
    expect(toDayText('2026-03-09T12:00:00')).toBe('2026-03-09')
  })

  it('daysUntil 按本地日历日计算', () => {
    expect(daysUntil(todayText())).toBe(0)
    const tomorrow = new Date()
    tomorrow.setDate(tomorrow.getDate() + 1)
    expect(daysUntil(toDayText(tomorrow))).toBe(1)
    const yesterday = new Date()
    yesterday.setDate(yesterday.getDate() - 1)
    expect(daysUntil(toDayText(yesterday))).toBe(-1)
  })

  it('daysUntil 对缺失/非法日期返回 null', () => {
    expect(daysUntil(undefined)).toBeNull()
    expect(daysUntil('')).toBeNull()
    expect(daysUntil('not-a-date')).toBeNull()
  })

  it('expireLevel 分级与出库预览红黄标一致', () => {
    const offset = (days: number) => {
      const d = new Date()
      d.setDate(d.getDate() + days)
      return toDayText(d)
    }
    expect(expireLevel(offset(-1))).toBe('expired')
    expect(expireLevel(offset(0))).toBe('near')
    expect(expireLevel(offset(30))).toBe('near')
    expect(expireLevel(offset(31))).toBe('normal')
    expect(expireLevel(offset(365))).toBe('normal')
    expect(expireLevel(undefined)).toBeNull()
  })
})
