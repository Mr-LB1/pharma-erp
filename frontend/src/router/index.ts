import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { TOKEN_KEY } from '@/api/request'
import { useAuthStore } from '@/stores/auth'
import { firstAccessiblePath } from '@/layout/menus'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { title: '登录', public: true }
  },
  {
    path: '/',
    component: () => import('@/layout/MainLayout.vue'),
    children: [
      {
        path: 'dashboard',
        name: 'dashboard',
        component: () => import('@/views/Dashboard.vue'),
        meta: { title: '工作台' }
      },
      {
        path: 'products',
        name: 'products',
        component: () => import('@/views/product/ProductList.vue'),
        meta: { title: '商品管理', permission: 'product:view' }
      },
      {
        path: 'suppliers',
        name: 'suppliers',
        component: () => import('@/views/supplier/SupplierList.vue'),
        meta: { title: '供应商管理', permission: 'supplier:view' }
      },
      {
        path: 'customers',
        name: 'customers',
        component: () => import('@/views/customer/CustomerList.vue'),
        meta: { title: '客户管理', permission: 'customer:view' }
      },
      {
        path: 'warehouses',
        name: 'warehouses',
        component: () => import('@/views/warehouse/WarehouseList.vue'),
        meta: { title: '仓库管理', permission: 'warehouse:view' }
      },
      {
        path: 'stores',
        name: 'stores',
        component: () => import('@/views/base/StoreList.vue'),
        meta: { title: '门店管理', permission: 'base:store:view' }
      },
      {
        path: 'purchase/orders',
        name: 'purchase-orders',
        component: () => import('@/views/purchase/PurchaseOrderList.vue'),
        meta: { title: '采购订单', permission: 'purchase:order:view' }
      },
      {
        path: 'purchase/receives',
        name: 'purchase-receives',
        component: () => import('@/views/purchase/PurchaseReceiveList.vue'),
        meta: { title: '采购入库', permission: 'purchase:receive:view' }
      },
      {
        path: 'sales/orders',
        name: 'sales-orders',
        component: () => import('@/views/sales/SalesOrderList.vue'),
        meta: { title: '销售订单', permission: 'sales:order:view' }
      },
      {
        path: 'sales/deliveries',
        name: 'sales-deliveries',
        component: () => import('@/views/sales/SalesDeliveryList.vue'),
        meta: { title: '销售出库', permission: 'sales:delivery:view' }
      },
      {
        path: 'inventory/stocks',
        name: 'inventory-stocks',
        component: () => import('@/views/inventory/StockList.vue'),
        meta: { title: '库存查询', permission: 'inventory:stock:view' }
      },
      {
        path: 'inventory/stocktakes',
        name: 'inventory-stocktakes',
        component: () => import('@/views/inventory/StocktakeList.vue'),
        meta: { title: '盘点', permission: 'inventory:stocktake:view' }
      },
      {
        path: 'inventory/transfers',
        name: 'inventory-transfers',
        component: () => import('@/views/inventory/StockTransferList.vue'),
        meta: { title: '仓库调拨', permission: 'inventory:transfer:view' }
      },
      {
        path: 'sales/returns',
        name: 'sales-returns',
        component: () => import('@/views/sales/SalesReturnList.vue'),
        meta: { title: '销售退货', permission: 'sales:return:view' }
      },
      {
        path: 'purchase/returns',
        name: 'purchase-returns',
        component: () => import('@/views/purchase/PurchaseReturnList.vue'),
        meta: { title: '采购退货', permission: 'purchase:return:view' }
      },
      {
        path: 'finance/ar-invoices',
        name: 'finance-ar-invoices',
        component: () => import('@/views/finance/ArInvoiceList.vue'),
        meta: { title: '应收单', permission: 'finance:ar:view' }
      },
      {
        path: 'finance/ap-invoices',
        name: 'finance-ap-invoices',
        component: () => import('@/views/finance/ApInvoiceList.vue'),
        meta: { title: '应付单', permission: 'finance:ap:view' }
      },
      {
        path: 'finance/receipts',
        name: 'finance-receipts',
        component: () => import('@/views/finance/ReceiptList.vue'),
        meta: { title: '收款单', permission: 'finance:receipt:view' }
      },
      {
        path: 'finance/payments',
        name: 'finance-payments',
        component: () => import('@/views/finance/PaymentList.vue'),
        meta: { title: '付款单', permission: 'finance:payment:view' }
      },
      {
        path: 'finance/invoice-registers',
        name: 'finance-invoice-registers',
        component: () => import('@/views/finance/InvoiceRegisterList.vue'),
        meta: { title: '发票登记', permission: 'finance:invoice:view' }
      },
      {
        path: 'finance/statements/ar',
        name: 'finance-statement-ar',
        component: () => import('@/views/finance/StatementView.vue'),
        meta: { title: '应收对账', permission: 'finance:statement:view' }
      },
      {
        path: 'trace',
        name: 'trace',
        component: () => import('@/views/trace/TraceQuery.vue'),
        meta: { title: '追溯查询', permission: 'trace:view' }
      },
      {
        path: 'reports/stock-summary',
        name: 'report-stock-summary',
        component: () => import('@/views/report/StockSummary.vue'),
        meta: { title: '进销存汇总', permission: 'report:view' }
      },
      {
        path: 'reports/near-expiry',
        name: 'report-near-expiry',
        component: () => import('@/views/report/NearExpiryList.vue'),
        meta: { title: '近效期清单', permission: 'report:view' }
      },
      {
        path: 'quality/first-audits',
        name: 'quality-first-audits',
        component: () => import('@/views/quality/FirstAuditList.vue'),
        meta: { title: '首营审核', permission: 'quality:first-audit:view' }
      },
      {
        path: 'quality/licenses',
        name: 'quality-licenses',
        component: () => import('@/views/quality/ExpiringLicenseList.vue'),
        meta: { title: '证照预警', permission: 'quality:license:view' }
      },
      {
        path: 'quality/unqualified',
        name: 'quality-unqualified',
        component: () => import('@/views/quality/UnqualifiedList.vue'),
        meta: { title: '不合格品', permission: 'quality:unqualified:view' }
      },
      {
        path: 'approvals',
        name: 'approvals',
        component: () => import('@/views/approval/PendingList.vue'),
        meta: { title: '审批中心', permission: 'approval:view' }
      },
      {
        path: 'system/users',
        name: 'system-users',
        component: () => import('@/views/system/UserList.vue'),
        meta: { title: '用户管理', permission: 'system:user:view' }
      },
      {
        path: 'system/roles',
        name: 'system-roles',
        component: () => import('@/views/system/RoleList.vue'),
        meta: { title: '角色管理', permission: 'system:role:view' }
      },
      {
        path: 'system/audit-logs',
        name: 'system-audit-logs',
        component: () => import('@/views/system/AuditLogList.vue'),
        meta: { title: '审计日志', permission: 'system:audit-log:view' }
      },
      {
        path: '403',
        name: 'forbidden',
        component: () => import('@/views/ForbiddenView.vue'),
        meta: { title: '无权访问' }
      }
    ]
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/'
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 路由守卫：校验 token + 拉取用户信息 + 校验路由权限（P1-21）
router.beforeEach(async (to) => {
  const token = localStorage.getItem(TOKEN_KEY)

  if (to.meta.public) {
    // 已登录访问登录页则跳转首页
    if (to.path === '/login' && token) {
      return { path: '/' }
    }
    return true
  }

  if (!token) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  const authStore = useAuthStore()
  // 刷新页面后 store 为空，先拉取当前用户（含权限码）再判定
  if (!authStore.user) {
    try {
      await authStore.fetchMe()
    } catch {
      return { path: '/login', query: { redirect: to.fullPath } }
    }
  }

  // 根路径按权限落到第一个可访问菜单
  if (to.path === '/') {
    return { path: firstAccessiblePath(authStore.hasPermission) }
  }

  // 无权限：跳 403（403 页自身无需权限）
  const required = to.meta.permission as string | string[] | undefined
  if (required && !authStore.hasPermission(required)) {
    return { path: '/403' }
  }

  return true
})

router.afterEach((to) => {
  const title = to.meta.title as string | undefined
  document.title = title ? `${title} · 医药 B2B ERP` : '医药 B2B ERP'
})

export default router
