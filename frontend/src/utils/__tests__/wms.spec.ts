import { describe, expect, it } from 'vitest'
import { wmsActualText, wmsDiffText } from '../wms'

describe('wmsActualText', () => {
  it('未回传显示 -', () => {
    expect(wmsActualText({ quantity: 10, actualQty: null })).toBe('-')
    expect(wmsActualText({ quantity: 10 })).toBe('-')
  })

  it('已回传按数量千分位显示', () => {
    expect(wmsActualText({ quantity: 10, actualQty: 8 })).toBe('8')
    expect(wmsActualText({ quantity: 10, actualQty: 1234.5 })).toBe('1,234.5')
  })
})

describe('wmsDiffText', () => {
  it('未回传显示 -', () => {
    expect(wmsDiffText({ quantity: 10, actualQty: null })).toBe('-')
  })

  it('差异带正负号，相等显示 0', () => {
    expect(wmsDiffText({ quantity: 10, actualQty: 8 })).toBe('-2')
    expect(wmsDiffText({ quantity: 10, actualQty: 12 })).toBe('+2')
    expect(wmsDiffText({ quantity: 10, actualQty: 10 })).toBe('0')
  })

  it('兼容字符串数量（后端 BigDecimal 序列化）', () => {
    expect(wmsDiffText({ quantity: '10.50', actualQty: '12.00' })).toBe('+1.5')
  })

  it('后端已给 qtyDiff 时优先使用', () => {
    expect(wmsDiffText({ quantity: 10, actualQty: 8, qtyDiff: -2 })).toBe('-2')
    // 后端口径与前端相减一致，但精度以 qtyDiff 为准
    expect(wmsDiffText({ quantity: 3, actualQty: 3.333, qtyDiff: 0.33 })).toBe('+0.33')
  })
})
