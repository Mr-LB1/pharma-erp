/** 单据详情抽屉：头部字段 */
export interface DocDetailField {
  label: string
  value?: string | number | null
  /** 是否以警示色显示（如作废状态） */
  danger?: boolean
}

/** 单据详情抽屉：明细列配置 */
export interface DocDetailColumn {
  prop: string
  label: string
  width?: number | string
  minWidth?: number | string
  align?: 'left' | 'right' | 'center'
  /** money=金额千分位带 ¥；qty=数量千分位；custom 需配合 formatter */
  type?: 'money' | 'qty' | 'custom'
  formatter?: (row: Record<string, unknown>) => string
}
