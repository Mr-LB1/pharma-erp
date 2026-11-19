// 统一响应结构（与后端契约一致）
export interface ApiResponse<T = unknown> {
  code: number
  message: string
  data: T
}

// 分页响应 data
export interface PageResult<T> {
  records: T[]
  total: number
  page: number
  size: number
}

// 分页查询参数
export interface PageQuery {
  page: number
  size: number
  keyword?: string
}

// 认证
export interface UserInfo {
  id: number
  username: string
  realName: string
  roles: string[]
  /** 权限码列表（后端 me/login 下发；缺失表示旧后端，前端按未知放行） */
  permissions?: string[]
}

export interface LoginResult {
  token: string
  user: UserInfo
}

// 商品 product
export interface Product {
  id?: number
  code: string
  genericName: string
  tradeName: string
  approvalNo: string
  dosageForm: string
  spec: string
  manufacturer: string
  rxCategory: string
  storageCond: string
  firstAuditStatus: string
  status: string
  createdAt?: string
}

// 供应商 supplier
export interface Supplier {
  id?: number
  name: string
  creditCode: string
  licenseNo: string
  licenseExpire: string
  gspCert: string
  firstAuditStatus: string
  settleType: string
  status: string
}

// 客户 customer
export interface Customer {
  id?: number
  name: string
  licenseNo: string
  licenseExpire: string
  creditLevel: string
  creditLimit: number
  firstAuditStatus: string
  status: string
}

// 仓库 warehouse
export interface Warehouse {
  id?: number
  name: string
  tempType: string
  status: string
}

// 门店 store（B2B 下游门店，POS 场景）
export interface Store {
  id?: number
  code: string
  name: string
  type: string // DIRECT 直营 / FRANCHISE 加盟
  warehouseId?: number
  warehouseName?: string
  address?: string
  contactName?: string
  contactPhone?: string
  licenseNo?: string
  licenseExpire?: string
  config?: Record<string, unknown> | null // 门店配置 JSONB，前端暂不编辑
  status: string // ACTIVE / INACTIVE
}

// 门店证照临期预警行（GET /api/stores/expiring-licenses）
export interface StoreLicenseExpiry {
  id: number
  code: string
  name: string
  licenseNo?: string
  licenseExpire: string
  daysLeft?: number // 负数表示已过期
}

// ===== 采购 =====
export interface PurchaseOrderItem {
  id?: number
  productId: number
  /** 后端 VO 冗余商品名（展示优先使用，mock 下为空） */
  productName?: string
  quantity: number
  unitPrice: number
  amount?: number
}

export interface PurchaseOrder {
  id?: number
  orderNo?: string
  supplierId: number
  /** 后端 VO 冗余供应商名 */
  supplierName?: string
  orderDate: string
  status?: string
  totalAmount?: number
  createdBy?: string
  createdAt?: string
  items?: PurchaseOrderItem[]
}

export interface PurchaseOrderItemCreateRequest {
  productId: number
  quantity: number
  unitPrice: number
}

export interface PurchaseOrderCreateRequest {
  supplierId: number
  orderDate: string
  items: PurchaseOrderItemCreateRequest[]
}

export interface PurchaseReceiveItem {
  id?: number
  productId: number
  /** 后端 VO 冗余商品名 */
  productName?: string
  quantity: number
  /** WMS 回传实际量（未回传为 null） */
  actualQty?: number | null
  /** 差异 = 实际量 - 单据量（后端回传后回填） */
  qtyDiff?: number | null
  batchNo: string
  produceDate?: string | null
  expireDate: string
  unitCost: number
  inspectionNo?: string | null
}

export interface PurchaseReceive {
  id?: number
  receiveNo?: string
  /** WMS 单号（审批通过下发后回填） */
  wmsNo?: string | null
  /** 下发 WMS 时间 */
  wmsPushedAt?: string | null
  /** WMS 回传时间 */
  wmsCallbackAt?: string | null
  orderId: number
  /** 后端 VO 冗余采购订单号 */
  orderNo?: string
  /** 后端 VO 冗余供应商名 */
  supplierName?: string
  receiveDate: string
  status?: string
  createdBy?: string
  createdAt?: string
  items?: PurchaseReceiveItem[]
}

export interface PurchaseReceiveItemCreateRequest {
  productId: number
  quantity: number
  batchNo: string
  produceDate?: string | null
  expireDate: string
  unitCost: number
  inspectionNo?: string | null
}

export interface PurchaseReceiveCreateRequest {
  warehouseId: number
  orderId: number
  receiveDate: string
  items: PurchaseReceiveItemCreateRequest[]
}

// ===== 销售 =====
export interface SalesOrderItem {
  id?: number
  productId: number
  /** 后端 VO 冗余商品名 */
  productName?: string
  quantity: number
  unitPrice: number
  amount?: number
}

export interface SalesOrder {
  id?: number
  orderNo?: string
  customerId: number
  /** 后端 VO 冗余客户名 */
  customerName?: string
  orderDate: string
  status?: string
  totalAmount?: number
  createdBy?: string
  createdAt?: string
  items?: SalesOrderItem[]
}

export interface SalesOrderItemCreateRequest {
  productId: number
  quantity: number
  unitPrice: number
}

export interface SalesOrderCreateRequest {
  customerId: number
  orderDate: string
  items: SalesOrderItemCreateRequest[]
}

export interface SalesDeliveryItem {
  id?: number
  productId: number
  /** 后端 VO 冗余商品名 */
  productName?: string
  batchNo: string
  quantity: number
  /** WMS 回传实际量（未回传为 null） */
  actualQty?: number | null
  /** 差异 = 实际量 - 单据量（后端回传后回填） */
  qtyDiff?: number | null
  unitCost: number
}

export interface SalesDelivery {
  id?: number
  deliveryNo?: string
  /** WMS 单号（审批通过下发后回填） */
  wmsNo?: string | null
  /** 下发 WMS 时间 */
  wmsPushedAt?: string | null
  /** WMS 回传时间 */
  wmsCallbackAt?: string | null
  orderId: number
  /** 后端 VO 冗余销售订单号 */
  orderNo?: string
  /** 后端 VO 冗余客户名 */
  customerName?: string
  deliveryDate: string
  status?: string
  createdBy?: string
  createdAt?: string
  items?: SalesDeliveryItem[]
}

export interface SalesDeliveryItemCreateRequest {
  productId: number
  quantity: number
}

export interface SalesDeliveryCreateRequest {
  warehouseId: number
  orderId: number
  deliveryDate: string
  items: SalesDeliveryItemCreateRequest[]
}

// ===== 库存 =====
export interface Stock {
  id?: number
  productId: number
  /** 后端 VO 冗余商品名 */
  productName?: string
  batchNo: string
  produceDate?: string | null
  expireDate: string
  locationId: number
  quantity: number
  unitCost: number
}

// FEFO 批次预览（GET /api/inventory/fefo-preview，只读、不含成本）
export interface FefoBatch {
  batchNo: string
  availableQty: number
  plannedQty: number
  expireDate?: string | null
  daysToExpire?: number | null
  /** SELLABLE / NEAR_EXPIRY / EXPIRED */
  expireStatus?: string
}

export interface FefoPreview {
  productId?: number
  productName?: string
  requestedQty?: number
  nearExpiryDays?: number
  totalSellable?: number
  blocked: boolean
  blockReason?: string | null
  batches: FefoBatch[]
}

// ===== 首营审核（quality） =====
export type FirstAuditType = 'product' | 'supplier' | 'customer'

// 待审核项：后端按 type 返回统一结构 { type, id, name, code, firstAuditStatus, licenseExpire, createdAt }
// product: name=通用名, code=商品编码；supplier: name=名称, code=信用代码；customer: name=名称, code=许可证号
export interface FirstAuditPendingItem {
  type: string // product / supplier / customer
  id: number
  name: string
  code?: string
  firstAuditStatus?: string
  licenseExpire?: string
  createdAt?: string
}

// 证照到期预警项（供应商 + 客户）
export interface ExpiringLicense {
  id: number
  type: string // supplier / customer
  name: string
  licenseNo?: string
  licenseExpire: string
  creditCode?: string
  gspCert?: string
}

// ===== 追溯（trace） =====
export interface TraceInbound {
  receiveItemId: number
  receiveId: number
  receiveNo: string
  receiveDate: string
  quantity: number
  produceDate?: string | null
  expireDate?: string | null
  unitCost?: number
  inspectionNo?: string | null
  orderId?: number
  orderNo?: string
  supplierId?: number
  supplierName?: string
  /** 入库单状态（VOID 表示已作废，链路保留标记） */
  receiveStatus?: string
}

export interface TraceOutbound {
  deliveryItemId: number
  deliveryId: number
  deliveryNo: string
  deliveryDate: string
  quantity: number
  unitCost?: number
  orderId?: number
  orderNo?: string
  customerId?: number
  customerName?: string
  /** 出库单状态（VOID 表示已作废，链路保留标记） */
  deliveryStatus?: string
}

export interface TraceResult {
  productId?: number
  batchNo?: string
  direction?: string
  inbounds: TraceInbound[]
  outbounds: TraceOutbound[]
}

// ===== 审批（approval） =====
export interface ApprovalPendingItemDetail {
  productId: number
  productName?: string
  quantity: number
  /** 调拨等内部单据无单价口径，为 null */
  unitPrice: number | null
  amount?: number | null
}

export interface ApprovalPendingItem {
  // PURCHASE_ORDER / SALES_ORDER / SALES_RETURN / PURCHASE_RECEIVE / SALES_DELIVERY / PURCHASE_RETURN / STOCK_TRANSFER
  bizType: string
  bizId: number
  orderNo?: string
  orderDate?: string
  totalAmount?: number
  partyId?: number
  partyName?: string
  createdBy?: string
  items?: ApprovalPendingItemDetail[]
}

// ===== 用户管理（system） =====
export interface SysUser {
  id?: number
  username: string
  realName?: string
  status?: string // ACTIVE / INACTIVE
  roles?: string[]
  createdAt?: string
}

export interface SysUserCreateRequest {
  username: string
  password: string
  realName?: string
  status?: string
  roles?: string[]
}

export interface SysUserUpdateRequest {
  realName?: string
  status?: string
  roles?: string[]
}

export interface SysRole {
  id?: number
  code: string
  name: string
}

/** 权限码（GET /api/system/permissions） */
export interface SysPermission {
  id?: number
  code: string
  name: string
}

/** 角色权限查询响应（兼容后端返回数组或包装对象） */
export interface SysRolePermissions {
  roleId?: number
  roleCode?: string
  permissionCodes?: string[]
}

// ===== 审计日志（system） =====
export interface AuditLog {
  id: number
  username: string
  action: string
  objectType: string
  objectId?: number
  detail?: string
  createdAt: string
}

// ===== 盘点（stocktake） =====
export interface StocktakeItem {
  id?: number
  productId: number
  batchNo: string
  produceDate?: string | null
  expireDate?: string | null
  bookQty?: number
  actualQty: number
  diffQty?: number
}

export interface Stocktake {
  id?: number
  stocktakeNo?: string
  warehouseId: number
  status?: string // DRAFT / COMPLETED
  createdBy?: string
  createdAt?: string
  items?: StocktakeItem[]
}

export interface StocktakeItemCreateRequest {
  productId: number
  batchNo: string
  // 盘盈新批次时补录生产日期与效期
  produceDate?: string | null
  expireDate?: string | null
  actualQty: number
}

export interface StocktakeCreateRequest {
  warehouseId: number
  items: StocktakeItemCreateRequest[]
}

// ===== 报表（report） =====
// 进销存汇总行
export interface StockSummaryItem {
  productId: number
  productCode?: string
  genericName?: string
  tradeName?: string
  spec?: string
  manufacturer?: string
  openingQty?: number
  inboundQty?: number
  outboundQty?: number
  closingQty?: number
  // 金额（后端按 cost_ledger 聚合，旧后端不下发时显示 '-'）
  openingAmount?: number
  inboundAmount?: number
  outboundAmount?: number
  closingAmount?: number
}

// 近效期清单行
export interface NearExpiryItem {
  productId: number
  productCode?: string
  genericName?: string
  tradeName?: string
  spec?: string
  manufacturer?: string
  batchNo: string
  produceDate?: string | null
  expireDate: string
  quantity?: number
  warehouseId?: number
  warehouseName?: string
  daysLeft?: number
}

// 库存结存行
export interface InventoryBalanceItem {
  productId: number
  productCode?: string
  genericName?: string
  tradeName?: string
  spec?: string
  manufacturer?: string
  batchNo: string
  locationId?: number
  warehouseName?: string
  quantity?: number
  unitCost?: number
  expireDate?: string | null
}

// 毛利报表行（GET /api/reports/gross-profit）
export interface GrossProfitItem {
  productId: number
  productCode?: string
  productName?: string
  quantity?: number
  revenue?: number
  cost?: number
  grossProfit?: number
  grossMargin?: number
}

// ===== 不合格品（quality） =====
export interface Unqualified {
  id?: number
  productId: number
  productCode?: string
  genericName?: string
  tradeName?: string
  spec?: string
  warehouseId?: number
  warehouseName?: string
  batchNo: string
  quantity: number
  reason: string
  status?: string // PENDING / DISPOSED
  // 销毁留痕
  disposeMethod?: string | null
  disposedAt?: string | null
  disposedBy?: string | null
  createdBy?: string
  createdAt?: string
}

export interface UnqualifiedCreateRequest {
  productId: number
  warehouseId: number
  batchNo: string
  quantity: number
  reason: string
}

// 不合格品处理（销毁）入参
export interface UnqualifiedDisposeRequest {
  disposeMethod: string
  disposedAt: string
  disposedBy: string
}

// ===== 财务 AR/AP（批次四） =====
export interface ArInvoice {
  id?: number
  invoiceNo?: string
  customerId: number
  customerName?: string
  bizType?: string
  bizId?: number
  bizNo?: string
  invoiceAmount: number
  settledAmount?: number
  /** 未结金额（后端 VO 直接下发） */
  outstanding?: number
  status?: string // UNSETTLED / PARTIAL / SETTLED / VOID
  remark?: string
  createdBy?: string
  createdAt?: string
}

export interface ApInvoice {
  id?: number
  invoiceNo?: string
  supplierId: number
  supplierName?: string
  bizType?: string
  bizId?: number
  bizNo?: string
  invoiceAmount: number
  settledAmount?: number
  /** 未结金额（后端 VO 直接下发） */
  outstanding?: number
  status?: string
  remark?: string
  createdBy?: string
  createdAt?: string
}

// 手工补录（后端固定 bizType=MANUAL、bizId=0）
export interface ArInvoiceCreateRequest {
  customerId: number
  amount: number
  remark?: string | null
}

export interface ApInvoiceCreateRequest {
  supplierId: number
  amount: number
  remark?: string | null
}

export interface ReceiptItemVO {
  id?: number
  arInvoiceId: number
  invoiceNo?: string
  amount: number
}

export interface Receipt {
  id?: number
  receiptNo?: string
  customerId: number
  customerName?: string
  amount: number
  status?: string
  remark?: string
  createdBy?: string
  createdAt?: string
  items?: ReceiptItemVO[]
}

export interface ReceiptItemRequest {
  arInvoiceId: number
  amount: number
}

export interface ReceiptCreateRequest {
  customerId: number
  amount: number
  remark?: string | null
  items: ReceiptItemRequest[]
}

export interface PaymentItemVO {
  id?: number
  apInvoiceId: number
  invoiceNo?: string
  amount: number
}

export interface Payment {
  id?: number
  paymentNo?: string
  supplierId: number
  supplierName?: string
  amount: number
  status?: string
  remark?: string
  createdBy?: string
  createdAt?: string
  items?: PaymentItemVO[]
}

export interface PaymentItemRequest {
  apInvoiceId: number
  amount: number
}

export interface PaymentCreateRequest {
  supplierId: number
  amount: number
  remark?: string | null
  items: PaymentItemRequest[]
}

export interface InvoiceRegister {
  id?: number
  invoiceNo: string
  bizType?: string | null
  bizId?: number | null
  amount: number
  status?: string
  remark?: string | null
  createdBy?: string
  createdAt?: string
}

export interface InvoiceRegisterCreateRequest {
  invoiceNo: string
  bizType?: string | null
  bizId?: number | null
  amount: number
  remark?: string | null
}

// 对账明细行（DELIVERY / RETURN / RECEIPT）
export interface StatementItem {
  date?: string
  type?: string
  bizNo?: string
  amount?: number
  remark?: string
}

// 应收对账单（GET /api/finance/statements/ar）
export interface ArStatement {
  customerId: number
  customerName?: string
  startDate?: string
  endDate?: string
  openingBalance: number
  deliveryAmount: number
  returnAmount: number
  receiptAmount: number
  closingBalance: number
  items?: StatementItem[]
}

// ===== 销售退货（批次四） =====
export interface SalesReturnItem {
  id?: number
  productId: number
  productName?: string
  batchNo: string
  quantity: number
  unitPrice?: number
  unitCost?: number
  warehouseId?: number
  acceptResult?: string | null // QUALIFIED / UNQUALIFIED
  rejectReason?: string | null
}

export interface SalesReturn {
  id?: number
  returnNo?: string
  deliveryId: number
  deliveryNo?: string
  customerId: number
  customerName?: string
  storeId?: number
  storeName?: string
  returnDate: string
  status?: string // DRAFT/SUBMITTED/APPROVED/REJECTED/RECEIVED/VOID
  totalAmount?: number
  reason?: string
  createdBy?: string
  createdAt?: string
  items?: SalesReturnItem[]
}

export interface SalesReturnItemRequest {
  productId: number
  batchNo: string
  quantity: number
  warehouseId: number
}

export interface SalesReturnCreateRequest {
  deliveryId: number
  storeId?: number | null
  returnDate: string
  reason?: string | null
  items: SalesReturnItemRequest[]
}

// 验收分流：逐行判定整行合格/不合格（不合格需原因，转不合格品台账）
export interface SalesReturnReceiveItem {
  itemId: number
  qualified: boolean
  rejectReason?: string | null
}

export interface SalesReturnReceiveRequest {
  items: SalesReturnReceiveItem[]
}

// ===== 采购退货（批次四） =====
export interface PurchaseReturnItem {
  id?: number
  productId: number
  productName?: string
  batchNo: string
  quantity: number
  /** WMS 回传实际量（未回传为 null） */
  actualQty?: number | null
  /** 差异 = 实际量 - 单据量（后端回传后回填） */
  qtyDiff?: number | null
  unitCost: number
  warehouseId?: number
}

export interface PurchaseReturn {
  id?: number
  returnNo?: string
  /** WMS 单号（审批通过下发后回填） */
  wmsNo?: string | null
  /** 下发 WMS 时间 */
  wmsPushedAt?: string | null
  /** WMS 回传时间 */
  wmsCallbackAt?: string | null
  receiveId?: number
  receiveNo?: string
  supplierId: number
  supplierName?: string
  returnDate: string
  status?: string
  totalAmount?: number
  reason?: string
  createdBy?: string
  createdAt?: string
  items?: PurchaseReturnItem[]
}

export interface PurchaseReturnItemRequest {
  productId: number
  batchNo: string
  quantity: number
  warehouseId: number
}

export interface PurchaseReturnCreateRequest {
  receiveId?: number | null
  supplierId: number
  returnDate: string
  reason?: string | null
  items: PurchaseReturnItemRequest[]
}

// ===== 仓库调拨（批次四） =====
export interface StockTransferItem {
  id?: number
  productId: number
  productName?: string
  batchNo: string
  quantity: number
  /** WMS 回传实际量（未回传为 null） */
  actualQty?: number | null
  /** 差异 = 实际量 - 单据量（后端回传后回填） */
  qtyDiff?: number | null
}

export interface StockTransfer {
  id?: number
  transferNo?: string
  /** WMS 单号（审批通过下发后回填） */
  wmsNo?: string | null
  /** 下发 WMS 时间 */
  wmsPushedAt?: string | null
  /** WMS 回传时间 */
  wmsCallbackAt?: string | null
  fromWarehouseId: number
  fromWarehouseName?: string
  toWarehouseId: number
  toWarehouseName?: string
  transferDate: string
  status?: string // COMPLETED/VOID
  remark?: string
  createdBy?: string
  createdAt?: string
  items?: StockTransferItem[]
}

export interface StockTransferItemRequest {
  productId: number
  batchNo: string
  quantity: number
}

export interface StockTransferCreateRequest {
  fromWarehouseId: number
  toWarehouseId: number
  transferDate: string
  remark?: string | null
  items: StockTransferItemRequest[]
}

// ===== 工作台 Dashboard（批次四） =====
export interface DashboardTrendPoint {
  date: string
  inboundAmount: number
  outboundAmount: number
  /** 后端 DashboardVO 兼容字段（采购/销售金额） */
  purchaseAmount?: number
  salesAmount?: number
}

export interface DashboardTopProduct {
  productId?: number
  productName?: string
  quantity?: number
  amount?: number
}

export interface DashboardTodo {
  bizType?: string
  bizId?: number
  title?: string
  createdAt?: string
}

export interface DashboardNearExpiry {
  productId?: number
  productName?: string
  batchNo?: string
  expireDate?: string
  quantity?: number
  warehouseName?: string
  daysLeft?: number
}

export interface DashboardData {
  pendingApprovalCount: number
  nearExpiryCount: number
  licenseExpiringCount: number
  todayInboundAmount: number
  todayOutboundAmount: number
  /** 后端 DashboardVO 兼容字段（今日采购/销售金额） */
  todayPurchaseAmount?: number
  todaySalesAmount?: number
  trends?: DashboardTrendPoint[]
  topProducts?: DashboardTopProduct[]
  todos?: DashboardTodo[]
  nearExpiry?: DashboardNearExpiry[]
}

// ===== 批量操作与导入（batch，契约 §13） =====
// 批量操作资源路径（与后端 /api/{resource}/batch 对齐）
export type BatchResource = 'products' | 'suppliers' | 'customers' | 'warehouses' | 'stores'

// 批量动作：启用 / 停用 / 删除
export type BatchAction = 'ENABLE' | 'DISABLE' | 'DELETE'

// 导入重复数据处理策略
export type DuplicateStrategy = 'SKIP' | 'UPDATE' | 'ERROR'

// POST /api/{resource}/batch body
export interface BatchRequest<T = Record<string, unknown>> {
  action: BatchAction
  ids?: number[]
  items?: T[]
}

// 批量操作单行结果（index 为请求数组下标）
export interface BatchResultItem {
  index?: number
  success: boolean
  id?: number
  message?: string
}

// 批量操作汇总结果（兼容 successCount/failedCount 与 success/failed 两种命名）
export interface BatchResult {
  action?: string
  total: number
  successCount?: number
  failedCount?: number
  success?: number
  failed?: number
  results?: BatchResultItem[]
  /** 部分单据接口用 items 返回逐条结果 */
  items?: BatchResultItem[]
}

// 导入错误明细（row 为 Excel 行号：表头第 1 行，数据从第 2 行起）
export interface ImportErrorItem {
  row: number
  field?: string | null
  message: string
}

// 导入预览单行：data 为该行的原始字段值（camelCase）
export interface ImportPreviewRow {
  row: number
  valid: boolean
  data?: Record<string, unknown>
  errors?: ImportErrorItem[]
}

// POST /api/{resource}/import/preview 结果（preview 仅含前 20 行）
export interface ImportPreviewResult {
  total: number
  validCount: number
  invalidCount: number
  preview?: ImportPreviewRow[]
  errors: ImportErrorItem[]
}

// POST /api/{resource}/import 结果
export interface ImportExecuteResult {
  total: number
  successCount: number
  failedCount: number
  insertedCount: number
  updatedCount: number
  skippedCount: number
  errors: ImportErrorItem[]
}
