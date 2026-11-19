import type {
  Product,
  Supplier,
  Customer,
  Warehouse,
  LoginResult,
  UserInfo,
  PageResult,
  SysUser,
  SysUserCreateRequest,
  SysUserUpdateRequest,
  SysRole,
  SysPermission
} from '@/types'

// 是否启用 mock（后端未就绪时 .env.development 中 VITE_USE_MOCK=true）
export const isMock = import.meta.env.VITE_USE_MOCK === 'true'

const delay = (ms = 300) => new Promise((res) => setTimeout(res, ms))

// ---- 内存数据 ----
let products: Product[] = [
  {
    id: 1,
    code: 'YP-10001',
    genericName: '阿莫西林胶囊',
    tradeName: '阿莫仙',
    approvalNo: '国药准字H20013075',
    dosageForm: '胶囊剂',
    spec: '0.25g*24粒',
    manufacturer: '珠海联邦制药股份有限公司',
    rxCategory: 'RX',
    storageCond: '阴凉',
    firstAuditStatus: 'APPROVED',
    status: 'ACTIVE',
    createdAt: '2026-11-01 10:00:00'
  },
  {
    id: 2,
    code: 'YP-10002',
    genericName: '布洛芬缓释胶囊',
    tradeName: '芬必得',
    approvalNo: '国药准字H10900089',
    dosageForm: '缓释胶囊',
    spec: '0.3g*20粒',
    manufacturer: '中美天津史克制药有限公司',
    rxCategory: 'OTC',
    storageCond: '常温',
    firstAuditStatus: 'APPROVED',
    status: 'ACTIVE',
    createdAt: '2026-11-02 09:30:00'
  },
  {
    id: 3,
    code: 'YP-10003',
    genericName: '硝酸甘油片',
    tradeName: '耐较咛',
    approvalNo: '国药准字H11021022',
    dosageForm: '片剂',
    spec: '0.5mg*100片',
    manufacturer: '北京益民药业有限公司',
    rxCategory: 'RX',
    storageCond: '冷藏',
    firstAuditStatus: 'PENDING',
    status: 'ACTIVE',
    createdAt: '2026-11-03 14:20:00'
  },
  {
    id: 4,
    code: 'YP-10004',
    genericName: '连花清瘟胶囊',
    tradeName: '连花清瘟',
    approvalNo: '国药准字Z20040063',
    dosageForm: '胶囊剂',
    spec: '0.35g*24粒',
    manufacturer: '石家庄以岭药业股份有限公司',
    rxCategory: 'OTC',
    storageCond: '阴凉',
    firstAuditStatus: 'APPROVED',
    status: 'ACTIVE',
    createdAt: '2026-11-04 08:15:00'
  }
]

let suppliers: Supplier[] = [
  {
    id: 1,
    name: '国药控股广州有限公司',
    creditCode: '91440101727942444F',
    licenseNo: '粤AA0200001',
    licenseExpire: '2027-06-30',
    gspCert: 'A-GD19-0001',
    firstAuditStatus: 'APPROVED',
    settleType: 'MONTHLY',
    status: 'ACTIVE'
  },
  {
    id: 2,
    name: '华润医药商业集团有限公司',
    creditCode: '91110000710934880T',
    licenseNo: '京AA0100002',
    licenseExpire: '2026-12-31',
    gspCert: 'A-BJ20-0002',
    firstAuditStatus: 'APPROVED',
    settleType: 'CASH',
    status: 'ACTIVE'
  },
  {
    id: 3,
    name: '九州通医药集团股份有限公司',
    creditCode: '91420120714510805A',
    licenseNo: '鄂AA0270003',
    licenseExpire: '2027-03-31',
    gspCert: 'A-HUB21-0003',
    firstAuditStatus: 'PENDING',
    settleType: 'MONTHLY',
    status: 'ACTIVE'
  }
]

let customers: Customer[] = [
  {
    id: 1,
    name: '广州市第一人民医院',
    licenseNo: '医疗机构执业许可证 440101123',
    licenseExpire: '2028-01-31',
    creditLevel: 'A',
    creditLimit: 500000,
    firstAuditStatus: 'APPROVED',
    status: 'ACTIVE'
  },
  {
    id: 2,
    name: '老百姓大药房连锁股份有限公司',
    licenseNo: '湘AA7310001',
    licenseExpire: '2027-09-30',
    creditLevel: 'B',
    creditLimit: 200000,
    firstAuditStatus: 'APPROVED',
    status: 'ACTIVE'
  },
  {
    id: 3,
    name: '广州医药大药房有限公司',
    licenseNo: '粤AA0200005',
    licenseExpire: '2026-11-30',
    creditLevel: 'A',
    creditLimit: 300000,
    firstAuditStatus: 'PENDING',
    status: 'ACTIVE'
  }
]

let warehouses: Warehouse[] = [
  { id: 1, name: '常温库 1 号', tempType: 'ROOM_TEMP', status: 'ACTIVE' },
  { id: 2, name: '阴凉库 2 号', tempType: 'COOL', status: 'ACTIVE' },
  { id: 3, name: '冷藏库 3 号', tempType: 'COLD', status: 'ACTIVE' },
  { id: 4, name: '待检库 4 号', tempType: 'ROOM_TEMP', status: 'INACTIVE' }
]

let idSeq = 10000

// ---- 认证 ----
// mock 全量权限码（与后端 V3/V8/V9/V11 权限码矩阵一致）
const ALL_PERMISSIONS = [
  'product:view', 'product:create', 'product:update', 'product:delete',
  'supplier:view', 'supplier:create', 'supplier:update', 'supplier:delete',
  'customer:view', 'customer:create', 'customer:update', 'customer:delete',
  'warehouse:view', 'warehouse:create', 'warehouse:update', 'warehouse:delete',
  'base:store:view', 'base:store:create', 'base:store:update', 'base:store:delete',
  'purchase:order:view', 'purchase:order:create', 'purchase:order:submit',
  'purchase:order:update', 'purchase:order:void',
  'purchase:receive:view', 'purchase:receive:create', 'purchase:receive:void',
  'sales:order:view', 'sales:order:create', 'sales:order:submit',
  'sales:order:update', 'sales:order:void',
  'sales:delivery:view', 'sales:delivery:create', 'sales:delivery:void',
  'inventory:stock:view', 'inventory:stocktake:view', 'inventory:stocktake:create',
  'inventory:stocktake:submit', 'inventory:cost:view',
  'quality:first-audit:view', 'quality:first-audit:approve',
  'quality:license:view', 'quality:unqualified:view',
  'quality:unqualified:create', 'quality:unqualified:dispose',
  'trace:view', 'report:view', 'approval:view', 'approval:approve',
  'system:audit-log:view',
  'system:user:view', 'system:user:create', 'system:user:update', 'system:user:delete',
  'system:role:view'
]

export async function mockLogin(username: string, _password: string): Promise<LoginResult> {
  await delay()
  if (!username) {
    throw new Error('用户名不能为空')
  }
  const user: UserInfo = {
    id: 1,
    username,
    realName: username === 'admin' ? '系统管理员' : username,
    roles: ['admin'],
    permissions: ALL_PERMISSIONS
  }
  return { token: 'mock-token-' + Date.now(), user }
}

export async function mockMe(): Promise<UserInfo> {
  await delay(150)
  return {
    id: 1,
    username: 'admin',
    realName: '系统管理员',
    roles: ['admin'],
    permissions: ALL_PERMISSIONS
  }
}

export async function mockLogout(): Promise<void> {
  await delay(50)
}

// ---- 商品 ----
export async function mockProductList(
  keyword?: string,
  page = 1,
  size = 20
): Promise<PageResult<Product>> {
  await delay()
  let list = [...products]
  if (keyword) {
    const kw = keyword.toLowerCase()
    list = list.filter(
      (p) =>
        p.code.toLowerCase().includes(kw) ||
        p.genericName.toLowerCase().includes(kw) ||
        p.tradeName.toLowerCase().includes(kw) ||
        p.approvalNo.toLowerCase().includes(kw) ||
        p.manufacturer.toLowerCase().includes(kw)
    )
  }
  const total = list.length
  const start = (page - 1) * size
  const records = list.slice(start, start + size)
  return { records, total, page, size }
}

export async function mockProductCreate(payload: Product): Promise<Product> {
  await delay()
  const p: Product = { ...payload, id: ++idSeq, createdAt: new Date().toISOString().replace('T', ' ').slice(0, 19) }
  products.unshift(p)
  return p
}

export async function mockProductUpdate(id: number, payload: Product): Promise<Product> {
  await delay()
  const idx = products.findIndex((p) => p.id === id)
  if (idx === -1) throw new Error('商品不存在')
  products[idx] = { ...products[idx], ...payload, id }
  return products[idx]
}

// ---- 供应商 / 客户 / 仓库（分页列表） ----
export async function mockSupplierList(
  keyword?: string,
  page = 1,
  size = 20
): Promise<PageResult<Supplier>> {
  await delay()
  let list = [...suppliers]
  if (keyword) {
    const kw = keyword.toLowerCase()
    list = list.filter(
      (s) =>
        s.name.toLowerCase().includes(kw) ||
        s.creditCode.toLowerCase().includes(kw) ||
        s.licenseNo.toLowerCase().includes(kw) ||
        s.gspCert.toLowerCase().includes(kw)
    )
  }
  const total = list.length
  const start = (page - 1) * size
  const records = list.slice(start, start + size)
  return { records, total, page, size }
}

export async function mockCustomerList(
  keyword?: string,
  page = 1,
  size = 20
): Promise<PageResult<Customer>> {
  await delay()
  let list = [...customers]
  if (keyword) {
    const kw = keyword.toLowerCase()
    list = list.filter(
      (c) => c.name.toLowerCase().includes(kw) || c.licenseNo.toLowerCase().includes(kw)
    )
  }
  const total = list.length
  const start = (page - 1) * size
  const records = list.slice(start, start + size)
  return { records, total, page, size }
}

export async function mockWarehouseList(
  keyword?: string,
  page = 1,
  size = 20
): Promise<PageResult<Warehouse>> {
  await delay()
  let list = [...warehouses]
  if (keyword) {
    const kw = keyword.toLowerCase()
    list = list.filter((w) => w.name.toLowerCase().includes(kw))
  }
  const total = list.length
  const start = (page - 1) * size
  const records = list.slice(start, start + size)
  return { records, total, page, size }
}

// ---- 用户管理（system，批次三） ----
let sysUsers: SysUser[] = [
  { id: 1, username: 'admin', realName: '系统管理员', status: 'ACTIVE', roles: ['ADMIN'], createdAt: '2026-11-01 10:00:00' },
  { id: 2, username: 'purchaser', realName: '张采购', status: 'ACTIVE', roles: ['PURCHASER'], createdAt: '2026-11-02 09:00:00' },
  { id: 3, username: 'sales', realName: '李销售', status: 'ACTIVE', roles: ['SALES'], createdAt: '2026-11-03 09:00:00' },
  { id: 4, username: 'warehouse', realName: '王仓管', status: 'INACTIVE', roles: ['WAREHOUSE'], createdAt: '2026-11-04 09:00:00' }
]

const mockRoles: SysRole[] = [
  { id: 1, code: 'ADMIN', name: '系统管理员' },
  { id: 2, code: 'PURCHASER', name: '采购员' },
  { id: 3, code: 'SALES', name: '销售员' },
  { id: 4, code: 'WAREHOUSE', name: '仓管' },
  { id: 5, code: 'APPROVER', name: '审批人' },
  { id: 6, code: 'SENIOR_APPROVER', name: '高级审批人' },
  { id: 7, code: 'RETURN_SPECIALIST', name: '退货专员' },
  { id: 8, code: 'QUALITY', name: '质量管理' }
]

export async function mockUserList(
  keyword?: string,
  page = 1,
  size = 20
): Promise<PageResult<SysUser>> {
  await delay()
  let list = [...sysUsers]
  if (keyword) {
    const kw = keyword.toLowerCase()
    list = list.filter(
      (u) =>
        u.username.toLowerCase().includes(kw) || (u.realName || '').toLowerCase().includes(kw)
    )
  }
  const total = list.length
  const start = (page - 1) * size
  return { records: list.slice(start, start + size), total, page, size }
}

export async function mockUserCreate(payload: SysUserCreateRequest): Promise<SysUser> {
  await delay()
  if (sysUsers.some((u) => u.username === payload.username)) {
    throw new Error('用户名已存在')
  }
  const user: SysUser = {
    id: Math.max(0, ...sysUsers.map((u) => u.id || 0)) + 1,
    username: payload.username,
    realName: payload.realName,
    status: payload.status || 'ACTIVE',
    roles: payload.roles || [],
    createdAt: new Date().toISOString().replace('T', ' ').slice(0, 19)
  }
  sysUsers.unshift(user)
  return user
}

export async function mockUserUpdate(id: number, payload: SysUserUpdateRequest): Promise<SysUser> {
  await delay()
  const idx = sysUsers.findIndex((u) => u.id === id)
  if (idx === -1) throw new Error('用户不存在')
  sysUsers[idx] = { ...sysUsers[idx], ...payload, id }
  return sysUsers[idx]
}

export async function mockUserDelete(id: number): Promise<void> {
  await delay()
  sysUsers = sysUsers.filter((u) => u.id !== id)
}

export async function mockUserResetPassword(id: number, _password: string): Promise<void> {
  await delay()
  if (!sysUsers.some((u) => u.id === id)) throw new Error('用户不存在')
}

export async function mockUserToggle(id: number): Promise<SysUser> {
  await delay()
  const idx = sysUsers.findIndex((u) => u.id === id)
  if (idx === -1) throw new Error('用户不存在')
  const next = sysUsers[idx].status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
  sysUsers[idx] = { ...sysUsers[idx], status: next }
  return sysUsers[idx]
}

export async function mockUserAssignRoles(id: number, roles: string[]): Promise<SysUser> {
  await delay()
  const idx = sysUsers.findIndex((u) => u.id === id)
  if (idx === -1) throw new Error('用户不存在')
  sysUsers[idx] = { ...sysUsers[idx], roles }
  return sysUsers[idx]
}

export async function mockRoleList(): Promise<SysRole[]> {
  await delay(100)
  return [...mockRoles]
}

// ---- 角色权限配置（system，批次六） ----
const mockPermissionCatalog: SysPermission[] = (
  [
    ['product:view', '商品查看'],
    ['product:create', '商品新增'],
    ['product:update', '商品编辑'],
    ['product:delete', '商品删除'],
    ['supplier:view', '供应商查看'],
    ['supplier:create', '供应商新增'],
    ['supplier:update', '供应商编辑'],
    ['supplier:delete', '供应商删除'],
    ['customer:view', '客户查看'],
    ['customer:create', '客户新增'],
    ['customer:update', '客户编辑'],
    ['customer:delete', '客户删除'],
    ['warehouse:view', '仓库查看'],
    ['warehouse:create', '仓库新增'],
    ['warehouse:update', '仓库编辑'],
    ['warehouse:delete', '仓库删除'],
    ['base:store:view', '门店查看'],
    ['base:store:create', '门店新增'],
    ['base:store:update', '门店编辑'],
    ['base:store:delete', '门店删除'],
    ['purchase:order:view', '采购订单查看'],
    ['purchase:order:create', '采购订单创建'],
    ['purchase:order:update', '采购订单编辑'],
    ['purchase:order:submit', '采购订单提交'],
    ['purchase:order:void', '采购订单作废'],
    ['purchase:receive:view', '采购入库查看'],
    ['purchase:receive:create', '采购入库创建'],
    ['purchase:receive:void', '采购入库作废'],
    ['purchase:return:view', '采购退货查看'],
    ['purchase:return:create', '采购退货创建'],
    ['purchase:return:void', '采购退货作废'],
    ['sales:order:view', '销售订单查看'],
    ['sales:order:create', '销售订单创建'],
    ['sales:order:update', '销售订单编辑'],
    ['sales:order:submit', '销售订单提交'],
    ['sales:order:void', '销售订单作废'],
    ['sales:delivery:view', '销售出库查看'],
    ['sales:delivery:create', '销售出库创建'],
    ['sales:delivery:void', '销售出库作废'],
    ['sales:return:view', '销售退货查看'],
    ['sales:return:create', '销售退货创建'],
    ['sales:return:receive', '销售退货验收'],
    ['sales:return:void', '销售退货作废'],
    ['inventory:stock:view', '库存查看'],
    ['inventory:cost:view', '库存成本查看'],
    ['inventory:stocktake:view', '盘点查看'],
    ['inventory:stocktake:create', '盘点创建'],
    ['inventory:stocktake:submit', '盘点提交'],
    ['inventory:transfer:view', '仓库调拨查看'],
    ['inventory:transfer:create', '仓库调拨创建'],
    ['finance:ar:view', '应收查看'],
    ['finance:ar:create', '应收补录'],
    ['finance:ap:view', '应付查看'],
    ['finance:ap:create', '应付补录'],
    ['finance:receipt:view', '收款单查看'],
    ['finance:receipt:create', '收款核销'],
    ['finance:payment:view', '付款单查看'],
    ['finance:payment:create', '付款核销'],
    ['finance:statement:view', '客户对账查看'],
    ['finance:invoice:view', '发票登记查看'],
    ['finance:invoice:create', '发票登记'],
    ['report:view', '报表查看'],
    ['trace:view', '追溯查询'],
    ['quality:first-audit:view', '首营审核查看'],
    ['quality:first-audit:approve', '首营审核审批'],
    ['quality:license:view', '证照预警查看'],
    ['quality:unqualified:view', '不合格品查看'],
    ['quality:unqualified:create', '不合格品登记'],
    ['quality:unqualified:dispose', '不合格品处理'],
    ['approval:view', '审批查看'],
    ['approval:approve', '审批操作'],
    ['system:user:view', '用户查看'],
    ['system:user:create', '用户新增'],
    ['system:user:update', '用户编辑'],
    ['system:user:delete', '用户删除'],
    ['system:role:view', '角色查看'],
    ['system:role:create', '角色新增'],
    ['system:role:update', '角色编辑'],
    ['system:role:delete', '角色删除'],
    ['system:role:config', '角色权限配置'],
    ['system:audit-log:view', '审计日志查看']
  ] as Array<[string, string]>
).map(([code, name], i) => ({ id: i + 1, code, name }))

const mockRolePermissionsMap: Record<string, string[]> = {
  ADMIN: mockPermissionCatalog.map((p) => p.code),
  PURCHASER: [
    'product:view',
    'supplier:view',
    'purchase:order:view',
    'purchase:order:create',
    'purchase:order:submit',
    'purchase:receive:view',
    'purchase:receive:create',
    'inventory:stock:view'
  ],
  SALES: [
    'product:view',
    'customer:view',
    'sales:order:view',
    'sales:order:create',
    'sales:order:submit',
    'sales:delivery:view',
    'sales:delivery:create',
    'inventory:stock:view'
  ],
  WAREHOUSE: [
    'product:view',
    'warehouse:view',
    'purchase:receive:view',
    'purchase:receive:create',
    'sales:delivery:view',
    'sales:delivery:create',
    'inventory:stock:view'
  ],
  APPROVER: ['approval:view', 'approval:approve'],
  // 与后端 V23 对齐：高级审批人同 APPROVER 权限，承接 L2（金额 >=100000）审批
  SENIOR_APPROVER: ['approval:view', 'approval:approve', 'purchase:order:view', 'sales:order:view'],
  RETURN_SPECIALIST: [
    'sales:return:view',
    'sales:return:create',
    'sales:return:receive',
    'sales:return:void',
    'purchase:return:view',
    'purchase:return:create',
    'purchase:return:void',
    'inventory:stock:view'
  ],
  QUALITY: ['quality:first-audit:view', 'quality:first-audit:approve', 'quality:license:view']
}

export async function mockPermissionList(): Promise<SysPermission[]> {
  await delay(100)
  return mockPermissionCatalog.map((p) => ({ ...p }))
}

export async function mockRolePermissions(roleId: number): Promise<string[]> {
  await delay(100)
  const role = mockRoles.find((r) => r.id === roleId)
  if (!role) throw new Error('角色不存在')
  return [...(mockRolePermissionsMap[role.code] || [])]
}

export async function mockUpdateRolePermissions(
  roleId: number,
  permissionCodes: string[]
): Promise<string[]> {
  await delay()
  const role = mockRoles.find((r) => r.id === roleId)
  if (!role) throw new Error('角色不存在')
  if (role.code === 'ADMIN' && !permissionCodes.includes('system:role:config')) {
    throw new Error('ADMIN 角色不可移除「角色权限配置」权限')
  }
  mockRolePermissionsMap[role.code] = [...permissionCodes]
  return [...permissionCodes]
}
