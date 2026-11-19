import { describe, expect, it } from 'vitest'
import {
  canVoid,
  docStatusLabel,
  docStatusTag,
  DOC_STATUS_OPTIONS,
  isAwaitingWms,
  statusLabel,
  statusTag
} from '../status'

describe('statusLabel / statusTag', () => {
  it('审批流状态有中文标签与 tag 类型', () => {
    expect(statusLabel('DRAFT')).toBe('草稿')
    expect(statusLabel('SUBMITTED')).toBe('待审批')
    expect(statusLabel('APPROVED')).toBe('已审核')
    expect(statusLabel('PUSHED')).toBe('已下发WMS')
    expect(statusLabel('REJECTED')).toBe('已驳回')
    expect(statusLabel('COMPLETED')).toBe('已完成')
    expect(statusTag('PUSHED')).toBe('primary')
    expect(statusTag('REJECTED')).toBe('danger')
    expect(statusTag('COMPLETED')).toBe('success')
  })

  it('未知状态回退原值 / info', () => {
    expect(statusLabel('UNKNOWN')).toBe('UNKNOWN')
    expect(statusLabel(undefined)).toBe('-')
    expect(statusTag('UNKNOWN')).toBe('info')
  })
})

describe('docStatusLabel / docStatusTag（兼容历史 ACTIVE）', () => {
  it('ACTIVE 展示为已完成', () => {
    expect(docStatusLabel('ACTIVE')).toBe('已完成')
    expect(docStatusTag('ACTIVE')).toBe('success')
  })

  it('其余状态与原映射一致', () => {
    expect(docStatusLabel('PUSHED')).toBe('已下发WMS')
    expect(docStatusTag('REJECTED')).toBe('danger')
    expect(docStatusLabel(undefined)).toBe('-')
  })
})

describe('canVoid', () => {
  it('草稿与已驳回可作废', () => {
    expect(canVoid('DRAFT')).toBe(true)
    expect(canVoid('REJECTED')).toBe(true)
  })

  it('待审批 / 已下发 WMS / 已执行 / 已作废不可作废', () => {
    expect(canVoid('SUBMITTED')).toBe(false)
    expect(canVoid('PUSHED')).toBe(false)
    expect(canVoid('COMPLETED')).toBe(false)
    expect(canVoid('VOID')).toBe(false)
    expect(canVoid(undefined)).toBe(false)
  })
})

describe('isAwaitingWms', () => {
  it('仅 PUSHED 视为等待 WMS 回传', () => {
    expect(isAwaitingWms('PUSHED')).toBe(true)
    expect(isAwaitingWms('COMPLETED')).toBe(false)
    expect(isAwaitingWms('SUBMITTED')).toBe(false)
    expect(isAwaitingWms(undefined)).toBe(false)
  })
})

describe('DOC_STATUS_OPTIONS', () => {
  it('覆盖审批流全部状态且顺序稳定', () => {
    expect([...DOC_STATUS_OPTIONS]).toEqual([
      'DRAFT',
      'SUBMITTED',
      'APPROVED',
      'PUSHED',
      'REJECTED',
      'COMPLETED',
      'VOID'
    ])
  })
})
