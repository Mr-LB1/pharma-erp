import type { Component } from 'vue'
import {
  Goods,
  OfficeBuilding,
  UserFilled,
  House,
  Shop,
  ShoppingCart,
  Sell,
  Box,
  Connection,
  DocumentChecked,
  Stamp,
  Tools,
  SetUp,
  Warning,
  Files,
  Document,
  Tickets,
  Van,
  TrendCharts,
  DataLine,
  Timer,
  CircleClose,
  FolderOpened,
  User,
  Odometer,
  Money,
  Wallet,
  CreditCard,
  RefreshLeft,
  Sort
} from '@element-plus/icons-vue'

export interface MenuLeaf {
  path: string
  title: string
  icon: Component
  /** 所需权限码；缺省表示不受权限控制（如首页/403） */
  permission?: string | string[]
}

export interface MenuGroup {
  key: string
  title: string
  icon: Component
  children: MenuLeaf[]
}

export type MenuNode = MenuLeaf | MenuGroup

export function isMenuGroup(node: MenuNode): node is MenuGroup {
  return (node as MenuGroup).children !== undefined
}

/**
 * 侧边菜单配置（权限码与后端 V3/V8/V9/V11 一致）
 * 菜单显隐与路由守卫共用同一份配置，避免两处漂移。
 */
export const MENUS: MenuNode[] = [
  { path: '/dashboard', title: '工作台', icon: Odometer },
  {
    key: 'base',
    title: '基础资料',
    icon: FolderOpened,
    children: [
      { path: '/products', title: '商品管理', icon: Goods, permission: 'product:view' },
      { path: '/suppliers', title: '供应商管理', icon: OfficeBuilding, permission: 'supplier:view' },
      { path: '/customers', title: '客户管理', icon: UserFilled, permission: 'customer:view' },
      { path: '/warehouses', title: '仓库管理', icon: House, permission: 'warehouse:view' },
      { path: '/stores', title: '门店管理', icon: Shop, permission: 'base:store:view' }
    ]
  },
  {
    key: 'purchase',
    title: '采购管理',
    icon: ShoppingCart,
    children: [
      { path: '/purchase/orders', title: '采购订单', icon: Tickets, permission: 'purchase:order:view' },
      { path: '/purchase/receives', title: '采购入库', icon: Van, permission: 'purchase:receive:view' },
      { path: '/purchase/returns', title: '采购退货', icon: RefreshLeft, permission: 'purchase:return:view' }
    ]
  },
  {
    key: 'sales',
    title: '销售管理',
    icon: Sell,
    children: [
      { path: '/sales/orders', title: '销售订单', icon: Tickets, permission: 'sales:order:view' },
      { path: '/sales/deliveries', title: '销售出库', icon: Van, permission: 'sales:delivery:view' },
      { path: '/sales/returns', title: '销售退货', icon: RefreshLeft, permission: 'sales:return:view' }
    ]
  },
  {
    key: 'inventory',
    title: '库存管理',
    icon: Box,
    children: [
      { path: '/inventory/stocks', title: '库存查询', icon: Goods, permission: 'inventory:stock:view' },
      { path: '/inventory/stocktakes', title: '盘点', icon: Files, permission: 'inventory:stocktake:view' },
      { path: '/inventory/transfers', title: '仓库调拨', icon: Sort, permission: 'inventory:transfer:view' }
    ]
  },
  {
    key: 'finance',
    title: '财务中心',
    icon: Money,
    children: [
      { path: '/finance/ar-invoices', title: '应收单', icon: Tickets, permission: 'finance:ar:view' },
      { path: '/finance/ap-invoices', title: '应付单', icon: Tickets, permission: 'finance:ap:view' },
      { path: '/finance/receipts', title: '收款单', icon: Wallet, permission: 'finance:receipt:view' },
      { path: '/finance/payments', title: '付款单', icon: CreditCard, permission: 'finance:payment:view' },
      { path: '/finance/invoice-registers', title: '发票登记', icon: Stamp, permission: 'finance:invoice:view' },
      { path: '/finance/statements/ar', title: '应收对账', icon: DataLine, permission: 'finance:statement:view' }
    ]
  },
  {
    key: 'report',
    title: '报表中心',
    icon: TrendCharts,
    children: [
      { path: '/reports/stock-summary', title: '进销存汇总', icon: DataLine, permission: 'report:view' },
      { path: '/reports/near-expiry', title: '近效期清单', icon: Timer, permission: 'report:view' }
    ]
  },
  { path: '/trace', title: '追溯查询', icon: Connection, permission: 'trace:view' },
  {
    key: 'quality',
    title: '质管中心',
    icon: DocumentChecked,
    children: [
      { path: '/quality/first-audits', title: '首营审核', icon: Stamp, permission: 'quality:first-audit:view' },
      { path: '/quality/licenses', title: '证照预警', icon: Warning, permission: 'quality:license:view' },
      { path: '/quality/unqualified', title: '不合格品', icon: CircleClose, permission: 'quality:unqualified:view' }
    ]
  },
  { path: '/approvals', title: '审批中心', icon: DocumentChecked, permission: 'approval:view' },
  {
    key: 'system',
    title: '系统管理',
    icon: Tools,
    children: [
      { path: '/system/users', title: '用户管理', icon: User, permission: 'system:user:view' },
      { path: '/system/roles', title: '角色管理', icon: SetUp, permission: 'system:role:view' },
      { path: '/system/audit-logs', title: '审计日志', icon: Document, permission: 'system:audit-log:view' }
    ]
  }
]

/** 按权限过滤菜单：组内叶子全部无权限则整组隐藏 */
export function filterMenus(can: (code?: string | string[]) => boolean): MenuNode[] {
  const result: MenuNode[] = []
  for (const node of MENUS) {
    if (isMenuGroup(node)) {
      const children = node.children.filter((child) => can(child.permission))
      if (children.length > 0) result.push({ ...node, children })
    } else if (can(node.permission)) {
      result.push(node)
    }
  }
  return result
}

/** 第一个可访问的菜单路径（登录后默认落地页） */
export function firstAccessiblePath(can: (code?: string | string[]) => boolean): string {
  for (const node of filterMenus(can)) {
    if (isMenuGroup(node)) {
      if (node.children[0]) return node.children[0].path
    } else {
      return node.path
    }
  }
  return '/403'
}
