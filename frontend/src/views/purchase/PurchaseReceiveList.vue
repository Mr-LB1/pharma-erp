<template>
  <el-card shadow="never" class="page-card">
    <div class="toolbar">
      <div class="toolbar-left">
        <el-input
          v-model="query.keyword"
          placeholder="搜索入库单号"
          clearable
          style="width: 260px"
          :prefix-icon="Search"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />
        <el-select
          v-model="query.status"
          placeholder="单据状态"
          clearable
          style="width: 130px"
          @change="handleSearch"
        >
          <el-option v-for="s in DOC_STATUS_OPTIONS" :key="s" :label="statusLabel(s)" :value="s" />
        </el-select>
        <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
      </div>
      <div class="toolbar-right">
        <el-button
          v-permission="'purchase:receive:view'"
          :icon="Download"
          :loading="exporting"
          @click="handleExport"
        >
          导出
        </el-button>
        <el-button
          v-permission="'purchase:receive:create'"
          type="primary"
          :icon="Plus"
          @click="openDialog"
        >
          新增采购入库
        </el-button>
      </div>
    </div>

    <DocBatchBar :selected-count="selectedCount">
      <el-button
        v-permission="'purchase:receive:submit'"
        size="small"
        :disabled="selectedCount === 0"
        :loading="batchLoading"
        @click="handleBatchSubmit"
      >
        批量提交
      </el-button>
      <el-button
        v-permission="'purchase:receive:void'"
        size="small"
        type="danger"
        plain
        :disabled="selectedCount === 0"
        :loading="batchLoading"
        @click="handleBatchVoid"
      >
        批量作废
      </el-button>
    </DocBatchBar>

    <el-table
      ref="tableRef"
      :data="list"
      v-loading="loading"
      border
      stripe
      row-key="id"
      @selection-change="handleSelectionChange"
    >
      <el-table-column type="selection" width="45" />
      <el-table-column type="expand">
        <template #default="{ row }">
          <div class="expand-box">
            <el-table :data="row.items || []" size="small" border>
              <el-table-column label="商品" min-width="180" show-overflow-tooltip>
                <template #default="{ row: it }">{{ it.productName || '-' }}</template>
              </el-table-column>
              <el-table-column prop="quantity" label="数量" width="90" align="right" />
              <el-table-column prop="batchNo" label="批号" width="130" />
              <el-table-column prop="produceDate" label="生产日期" width="110" />
              <el-table-column prop="expireDate" label="效期" width="110" />
              <el-table-column label="入库单价" width="120" align="right">
                <template #default="{ row: it }">{{ formatMoney(it.unitCost) }}</template>
              </el-table-column>
              <el-table-column prop="inspectionNo" label="检验单号" width="140" />
            </el-table>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="入库单号" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">
          <el-link type="primary" :underline="false" @click="openDetail(row as PurchaseReceive)">
            {{ row.receiveNo || '-' }}
          </el-link>
        </template>
      </el-table-column>
      <el-table-column label="采购订单" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">{{ row.orderNo || '-' }}</template>
      </el-table-column>
      <el-table-column label="供应商" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">{{ row.supplierName || '-' }}</template>
      </el-table-column>
      <el-table-column prop="receiveDate" label="入库日期" width="120" />
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag size="small" :type="docStatusTag(row.status)">{{ docStatusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="260" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="row.status === 'DRAFT'"
            v-permission="'purchase:receive:submit'"
            link
            type="primary"
            :loading="isActing(row.id!, 'submit')"
            :disabled="isBlocked() && !isActing(row.id!, 'submit')"
            @click="handleSubmit(row as PurchaseReceive)"
          >
            提交审批
          </el-button>
          <el-button
            v-if="row.status === 'REJECTED'"
            v-permission="'purchase:receive:submit'"
            link
            type="warning"
            :loading="isActing(row.id!, 'submit')"
            :disabled="isBlocked() && !isActing(row.id!, 'submit')"
            @click="handleSubmit(row as PurchaseReceive)"
          >
            重新提交
          </el-button>
          <el-button
            v-if="canVoid(row.status)"
            v-permission="'purchase:receive:void'"
            link
            type="danger"
            :loading="isActing(row.id!, 'void')"
            :disabled="isBlocked() && !isActing(row.id!, 'void')"
            @click="handleVoid(row as PurchaseReceive)"
          >
            作废
          </el-button>
          <el-button
            v-if="row.status === 'PUSHED'"
            v-permission="'purchase:receive:wms-callback'"
            link
            type="primary"
            :loading="isActing(row.id!, 'wms')"
            :disabled="isBlocked() && !isActing(row.id!, 'wms')"
            @click="handleWmsCallback(row as PurchaseReceive)"
          >
            回传实量
          </el-button>
          <span v-if="isAwaitingWms(row.status)" class="text-muted">已下发WMS，待回传</span>
          <span v-else-if="!canVoid(row.status) && row.status !== 'DRAFT'" class="text-muted">-</span>
        </template>
      </el-table-column>
    </el-table>

    <div class="pagination">
      <el-pagination
        v-model:current-page="query.page"
        v-model:page-size="query.size"
        :total="total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        @current-change="load"
        @size-change="handleSearch"
      />
    </div>

    <el-dialog
      v-model="dialogVisible"
      title="新增采购入库"
      width="960px"
      :close-on-click-modal="false"
      :close-on-press-escape="false"
      destroy-on-close
    >
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="保存仅创建草稿，不增加库存；提交并审批通过后下发仓库，WMS 回传实际量后生效。"
        class="dialog-alert"
      />
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="90px">
        <el-row :gutter="16">
          <el-col :span="8">
            <el-form-item label="采购订单" prop="orderId">
              <el-select
                v-model="form.orderId"
                placeholder="输入订单号远程搜索"
                filterable
                remote
                :remote-method="searchOrders"
                :loading="orderLoading"
                style="width: 100%"
                @change="handleOrderChange"
              >
                <el-option
                  v-for="o in receivableOrders"
                  :key="o.id"
                  :label="orderOptionLabel(o)"
                  :value="o.id!"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="入库仓库" prop="warehouseId">
              <el-select
                v-model="form.warehouseId"
                placeholder="输入名称远程搜索仓库"
                filterable
                remote
                :remote-method="searchWarehouses"
                :loading="warehouseLoading"
                style="width: 100%"
              >
                <el-option
                  v-for="w in warehouseOptions"
                  :key="w.id"
                  :label="w.name"
                  :value="w.id!"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="入库日期" prop="receiveDate">
              <el-date-picker
                v-model="form.receiveDate"
                type="date"
                value-format="YYYY-MM-DD"
                :disabled-date="disabledFutureDate"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item label="入库明细" required>
          <div class="items-box">
            <el-table :data="form.items" size="small" border>
              <el-table-column label="商品" min-width="200">
                <template #default="{ row }">
                  <el-select
                    v-model="row.productId"
                    placeholder="输入编码/名称远程搜索商品"
                    filterable
                    remote
                    :remote-method="searchProducts"
                    :loading="productLoading"
                    style="width: 100%"
                  >
                    <el-option
                      v-for="p in productOptions"
                      :key="p.id"
                      :label="productOptionLabel(p)"
                      :value="p.id!"
                    />
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column label="数量" width="120">
                <template #default="{ row }">
                  <el-input-number v-model="row.quantity" :min="0" :precision="2" style="width: 100%" />
                </template>
              </el-table-column>
              <el-table-column label="批号" width="130">
                <template #default="{ row }">
                  <el-input v-model="row.batchNo" placeholder="批号" />
                </template>
              </el-table-column>
              <el-table-column label="生产日期" width="150">
                <template #default="{ row }">
                  <el-date-picker
                    v-model="row.produceDate"
                    type="date"
                    value-format="YYYY-MM-DD"
                    :disabled-date="disabledFutureDate"
                    style="width: 100%"
                  />
                </template>
              </el-table-column>
              <el-table-column label="效期" width="150">
                <template #default="{ row }">
                  <el-date-picker
                    v-model="row.expireDate"
                    type="date"
                    value-format="YYYY-MM-DD"
                    :disabled-date="disabledOnOrBeforeDate(row.produceDate)"
                    placeholder="必填"
                    style="width: 100%"
                  />
                </template>
              </el-table-column>
              <el-table-column label="采购单价" width="130">
                <template #default="{ row }">
                  <el-input-number v-model="row.unitCost" :min="0" :precision="2" style="width: 100%" />
                </template>
              </el-table-column>
              <el-table-column label="检验单号" width="140">
                <template #default="{ row }">
                  <el-input v-model="row.inspectionNo" placeholder="检验单号（必填）" />
                </template>
              </el-table-column>
              <el-table-column label="操作" width="70" align="center" fixed="right">
                <template #default="{ $index }">
                  <el-button link type="danger" @click="removeItem($index)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
            <el-button class="add-item-btn" type="primary" plain :icon="Plus" @click="addItem">
              添加明细
            </el-button>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存草稿</el-button>
      </template>
    </el-dialog>

    <DocDetailDrawer
      v-model="detailVisible"
      title="采购入库详情"
      :loading="detailLoading"
      :header="detailHeader"
      :items="detailItems"
      :columns="DETAIL_COLUMNS"
    >
      <template #actions>
        <el-button
          v-if="detail?.status === 'DRAFT'"
          v-permission="'purchase:receive:submit'"
          type="primary"
          :loading="isActing(detail.id ?? 0, 'submit')"
          @click="handleSubmit(detail!)"
        >
          提交审批
        </el-button>
        <el-button
          v-if="detail?.status === 'REJECTED'"
          v-permission="'purchase:receive:submit'"
          type="warning"
          :loading="isActing(detail.id ?? 0, 'submit')"
          @click="handleSubmit(detail!)"
        >
          重新提交
        </el-button>
        <el-button
          v-if="detail && canVoid(detail.status)"
          v-permission="'purchase:receive:void'"
          type="danger"
          :loading="isActing(detail.id ?? 0, 'void')"
          @click="handleVoid(detail)"
        >
          作废
        </el-button>
      </template>
    </DocDetailDrawer>
  </el-card>
</template>

<script setup lang="ts">
import { reactive, ref, computed, onMounted, onActivated } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Search, Plus, Download } from '@element-plus/icons-vue'
import {
  listPurchaseReceives,
  getPurchaseReceive,
  createPurchaseReceive,
  submitPurchaseReceive,
  voidPurchaseReceive,
  wmsCallbackPurchaseReceive,
  exportPurchaseReceives,
  batchSubmitPurchaseReceives,
  batchVoidPurchaseReceives,
  listPurchaseOrders
} from '@/api/purchase'
import { listWarehouses } from '@/api/warehouse'
import { listProducts } from '@/api/product'
import {
  statusLabel,
  docStatusLabel,
  docStatusTag,
  canVoid,
  isAwaitingWms,
  DOC_STATUS_OPTIONS
} from '@/utils/status'
import { wmsActualText, wmsDiffText } from '@/utils/wms'
import { formatMoney, disabledFutureDate, disabledOnOrBeforeDate } from '@/utils/format'
import { saveBlob, dateStamp } from '@/utils/download'
import { mergeById } from '@/utils/options'
import { useRowAction } from '@/composables/useRowAction'
import { useQuerySync } from '@/composables/useQuerySync'
import { useDocumentBatch } from '@/composables/useBatchOperation'
import DocDetailDrawer from '@/components/DocDetailDrawer.vue'
import DocBatchBar from '@/components/DocBatchBar.vue'
import type { DocDetailColumn, DocDetailField } from '@/components/docDetail'
import type { PurchaseReceive, PurchaseOrder, Warehouse, Product } from '@/types'

const { isActing, isBlocked, run } = useRowAction()

interface ItemRow {
  productId?: number
  quantity: number
  batchNo: string
  produceDate?: string | null
  expireDate: string
  unitCost: number
  inspectionNo?: string | null
}

const list = ref<PurchaseReceive[]>([])
const total = ref(0)
const loading = ref(false)
const exporting = ref(false)
const tableRef = ref<{ clearSelection: () => void } | null>(null)

const query = reactive({ keyword: '', status: '', page: 1, size: 20 })
// 筛选条件同步到地址栏（刷新/分享链接不丢筛选）
useQuerySync(query, ['keyword', 'status', 'page', 'size'], { numeric: ['page', 'size'] })

// 批量提交 / 批量作废（单据逐条独立事务）
const { selectedCount, batchLoading, handleSelectionChange, handleBatchRun } = useDocumentBatch({
  label: '采购入库单',
  actionText: '提交',
  execute: (ids) => batchSubmitPurchaseReceives(ids),
  reload: load,
  clearSelection: () => tableRef.value?.clearSelection(),
  labelOf: (row) => (row.receiveNo ? String(row.receiveNo) : undefined)
})

function handleBatchSubmit() {
  handleBatchRun({ actionText: '提交', execute: (ids) => batchSubmitPurchaseReceives(ids) })
}

function handleBatchVoid() {
  handleBatchRun({
    actionText: '作废',
    reason: 'required',
    execute: (ids, reason) => batchVoidPurchaseReceives(ids, reason)
  })
}

// ===== 详情抽屉 =====
const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<PurchaseReceive | null>(null)

const DETAIL_COLUMNS: DocDetailColumn[] = [
  { prop: 'productName', label: '商品', minWidth: 180 },
  { prop: 'batchNo', label: '批号', width: 130 },
  { prop: 'quantity', label: '单据量', width: 90, align: 'right', type: 'qty' },
  { prop: 'actualQty', label: '实际量', width: 90, align: 'right', formatter: (row) => wmsActualText(row) },
  { prop: 'diff', label: '差异', width: 90, align: 'right', formatter: (row) => wmsDiffText(row) },
  { prop: 'produceDate', label: '生产日期', width: 110 },
  { prop: 'expireDate', label: '效期', width: 110 },
  { prop: 'unitCost', label: '入库单价', width: 110, align: 'right', type: 'money' },
  { prop: 'inspectionNo', label: '检验单号', width: 140 }
]

const detailItems = computed(
  () => (detail.value?.items || []) as unknown as Record<string, unknown>[]
)

const detailHeader = computed<DocDetailField[]>(() => {
  const d = detail.value
  return [
    { label: '入库单号', value: d?.receiveNo || '-' },
    { label: '入库日期', value: d?.receiveDate || '-' },
    { label: '供应商', value: d?.supplierName || '-' },
    { label: '采购订单', value: d?.orderNo || '-' },
    {
      label: '状态',
      value: d?.status ? docStatusLabel(d.status) : '-',
      danger: d?.status === 'VOID' || d?.status === 'REJECTED'
    },
    ...(d?.wmsNo || d?.wmsPushedAt || d?.wmsCallbackAt
      ? [
          { label: 'WMS 单号', value: d?.wmsNo || '-' },
          { label: '下发时间', value: d?.wmsPushedAt || '-' },
          { label: '回传时间', value: d?.wmsCallbackAt || '-' }
        ]
      : []),
    { label: '制单人', value: d?.createdBy || '-' },
    { label: '创建时间', value: d?.createdAt || '-' }
  ]
})

async function openDetail(row: PurchaseReceive) {
  detail.value = row
  detailVisible.value = true
  if (!row.id) return
  detailLoading.value = true
  try {
    detail.value = await getPurchaseReceive(row.id)
  } catch {
    ElMessage.warning('详情加载失败，已展示列表中的单据信息')
  } finally {
    detailLoading.value = false
  }
}

const orders = ref<PurchaseOrder[]>([])
const warehouses = ref<Warehouse[]>([])
// 下拉展示列表（远程搜索结果）与缓存分离：缓存用于表格名称回显
const orderOptions = ref<PurchaseOrder[]>([])
const warehouseOptions = ref<Warehouse[]>([])
const productOptions = ref<Product[]>([])
const orderLoading = ref(false)
const warehouseLoading = ref(false)
const productLoading = ref(false)
const products = ref<Product[]>([])

const receivableOrders = computed(() =>
  orderOptions.value.filter(
    (o) => o.status === 'APPROVED' || o.status === 'PARTIAL_RECEIVED'
  )
)

const dialogVisible = ref(false)
const saving = ref(false)
const formRef = ref<FormInstance>()

// 本地时区的今天（避免 toISOString 的 UTC 偏移导致跨天误判）
const today = () => {
  const d = new Date()
  const month = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${d.getFullYear()}-${month}-${day}`
}

const emptyItem = (): ItemRow => ({
  productId: undefined,
  quantity: 1,
  batchNo: '',
  produceDate: null,
  expireDate: '',
  unitCost: 0,
  inspectionNo: ''
})

const form = reactive({
  orderId: undefined as number | undefined,
  warehouseId: undefined as number | undefined,
  receiveDate: today(),
  items: [emptyItem()] as ItemRow[]
})

const formRules: FormRules = {
  orderId: [{ required: true, message: '请选择采购订单', trigger: 'change' }],
  warehouseId: [{ required: true, message: '请选择入库仓库', trigger: 'change' }],
  receiveDate: [{ required: true, message: '请选择入库日期', trigger: 'change' }]
}

// 列表展示直接用后端 VO 名称（null 显示 '-'）
function productOptionLabel(p: Product) {
  return `${p.code} | ${p.genericName} ${p.tradeName} ${p.spec}`
}
function orderOptionLabel(o: PurchaseOrder) {
  return `${o.orderNo}（${statusLabel(o.status)}）`
}

// 下拉远程搜索（P1-30）：可入库订单仅限 APPROVED / PARTIAL_RECEIVED
async function searchOrders(keyword: string) {
  orderLoading.value = true
  try {
    const [approved, partial] = await Promise.all([
      listPurchaseOrders(keyword, 'APPROVED', 1, 30),
      listPurchaseOrders(keyword, 'PARTIAL_RECEIVED', 1, 30)
    ])
    const data = [...approved.records, ...partial.records]
    const selected = orders.value.find((o) => o.id === form.orderId)
    orderOptions.value = selected ? mergeById([selected], data) : data
    orders.value = mergeById(orders.value, data)
  } finally {
    orderLoading.value = false
  }
}

async function searchWarehouses(keyword: string) {
  warehouseLoading.value = true
  try {
    const data = await listWarehouses(keyword, 1, 50)
    const selected = warehouses.value.find((w) => w.id === form.warehouseId)
    warehouseOptions.value = selected ? mergeById([selected], data.records) : data.records
    warehouses.value = mergeById(warehouses.value, data.records)
  } finally {
    warehouseLoading.value = false
  }
}

async function searchProducts(keyword: string) {
  productLoading.value = true
  try {
    const data = await listProducts(keyword, 1, 50)
    const selected = form.items
      .map((it) => products.value.find((p) => p.id === it.productId))
      .filter((p): p is Product => !!p)
    productOptions.value = mergeById(selected, data.records)
    products.value = mergeById(products.value, data.records)
  } finally {
    productLoading.value = false
  }
}

async function loadOptions() {
  const [o1, o2, w, p] = await Promise.all([
    listPurchaseOrders('', 'APPROVED', 1, 100),
    listPurchaseOrders('', 'PARTIAL_RECEIVED', 1, 100),
    listWarehouses('', 1, 100),
    listProducts('', 1, 100)
  ])
  orders.value = [...o1.records, ...o2.records]
  orderOptions.value = [...o1.records, ...o2.records]
  warehouses.value = w.records
  warehouseOptions.value = w.records
  products.value = p.records
  productOptions.value = p.records
}

async function load() {
  loading.value = true
  try {
    const data = await listPurchaseReceives(
      query.keyword || undefined,
      query.status || undefined,
      query.page,
      query.size
    )
    list.value = data.records
    total.value = data.total
    tableRef.value?.clearSelection()
  } finally {
    loading.value = false
  }
}

// 导出当前查询条件下的全部入库单
async function handleExport() {
  if (total.value === 0) {
    ElMessage.warning('当前条件下暂无数据可导出')
    return
  }
  exporting.value = true
  try {
    const blob = await exportPurchaseReceives(query.keyword || undefined, query.status || undefined)
    saveBlob(blob, `采购入库单_${dateStamp()}.xlsx`)
    ElMessage.success('导出成功')
  } catch (e) {
    if (!(e as { isAxiosError?: boolean })?.isAxiosError) {
      ElMessage.error((e as Error)?.message || '导出失败')
    }
  } finally {
    exporting.value = false
  }
}

function handleSearch() {
  query.page = 1
  load()
}

function openDialog() {
  form.orderId = undefined
  form.warehouseId = undefined
  form.receiveDate = today()
  form.items = [emptyItem()]
  lastOrderId.value = undefined
  dialogVisible.value = true
}

function addItem() {
  form.items.push(emptyItem())
}

function removeItem(index: number) {
  form.items.splice(index, 1)
}

// 当前明细所来源的订单；切换订单时用于取消回滚
const lastOrderId = ref<number | undefined>(undefined)

async function handleOrderChange(orderId: number | undefined) {
  if (orderId === lastOrderId.value) return
  const order = orders.value.find((o) => o.id === orderId)
  if (!order || !order.items) return
  // 明细已有内容才确认，首次选单（仅空行）不打扰
  const hasItems = form.items.some((it) => !!it.productId)
  if (hasItems) {
    try {
      await ElMessageBox.confirm('切换订单将清空已填明细，确定切换吗？', '切换订单', {
        type: 'warning',
        confirmButtonText: '确定切换',
        cancelButtonText: '取消'
      })
    } catch {
      // 取消：回滚选择，保留原明细（批号/效期/检验单号等手工录入不丢）
      form.orderId = lastOrderId.value
      return
    }
  }
  form.items = order.items.map((it) => ({
    productId: it.productId,
    quantity: it.quantity,
    batchNo: '',
    produceDate: null,
    expireDate: '',
    unitCost: it.unitPrice,
    inspectionNo: ''
  }))
  lastOrderId.value = orderId
}

// 存储条件 → 仓库温控类型 映射（真实数据为 ROOM_TEMP/COOL/COLD，兼容旧中文值）
const STORAGE_TEMP_MAP: Record<string, string> = {
  ROOM_TEMP: 'ROOM_TEMP',
  COOL: 'COOL',
  COLD: 'COLD',
  常温: 'ROOM_TEMP',
  阴凉: 'COOL',
  冷藏: 'COLD'
}
const TEMP_LABEL: Record<string, string> = {
  ROOM_TEMP: '常温库',
  COOL: '阴凉库',
  COLD: '冷藏库'
}
// 与后端一致的兼容矩阵：常温只可入常温；阴凉可入阴凉/冷藏；冷藏只可入冷藏
const TEMP_COMPATIBLE: Record<string, string[]> = {
  ROOM_TEMP: ['ROOM_TEMP'],
  COOL: ['COOL', 'COLD'],
  COLD: ['COLD']
}

// 温控硬校验：不兼容则阻止提交（与后端 400 一致，避免提交后才报错）
function collectTempErrors(): string[] {
  const warehouse = warehouses.value.find((w) => w.id === form.warehouseId)
  if (!warehouse) return []
  const whTemp = warehouse.tempType
  if (!whTemp) return []
  const errors: string[] = []
  for (const it of form.items) {
    if (!it.productId) continue
    const product = products.value.find((p) => p.id === it.productId)
    if (!product) continue
    const need = STORAGE_TEMP_MAP[product.storageCond]
    if (!need) continue
    const allowed = TEMP_COMPATIBLE[need] || []
    if (!allowed.includes(whTemp)) {
      errors.push(
        `${product.genericName || product.code}（${TEMP_LABEL[need] || need}）温控不兼容：当前${TEMP_LABEL[whTemp] || whTemp}，只能入${allowed
          .map((t) => TEMP_LABEL[t] || t)
          .join('/')}`
      )
    }
  }
  return errors
}

async function handleSave() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    if (form.items.length === 0) {
      ElMessage.warning('请至少添加一条明细')
      return
    }
    for (const it of form.items) {
      if (!it.productId) {
        ElMessage.warning('请选择明细商品')
        return
      }
      if (!it.quantity || it.quantity <= 0) {
        ElMessage.warning('明细数量必须大于 0')
        return
      }
      if (!it.batchNo) {
        ElMessage.warning('请填写批号')
        return
      }
      if (!it.expireDate) {
        ElMessage.warning('请选择效期')
        return
      }
      if (it.produceDate && it.expireDate <= it.produceDate) {
        ElMessage.warning('效期必须晚于生产日期')
        return
      }
      if (it.expireDate <= today()) {
        ElMessage.warning('效期必须晚于今天，不能入库已过期或当日到期商品')
        return
      }
      if (!it.inspectionNo || !it.inspectionNo.trim()) {
        ElMessage.warning('请填写检验单号')
        return
      }
    }
    // 同商品同批号在本次单据内效期必须一致（与后端「同批号效期冲突」校验对齐）
    const batchExpireMap = new Map<string, string>()
    for (const it of form.items) {
      const key = `${it.productId}|${it.batchNo}`
      const prev = batchExpireMap.get(key)
      if (prev && prev !== it.expireDate) {
        ElMessage.warning(`同批号效期冲突：批号 ${it.batchNo} 在本次明细中存在不同的效期`)
        return
      }
      batchExpireMap.set(key, it.expireDate)
    }
    // 温控相容性硬校验：与后端矩阵一致，不兼容直接拦截
    const tempErrors = collectTempErrors()
    if (tempErrors.length) {
      ElMessage.warning(tempErrors.join('；'))
      return
    }
    saving.value = true
    try {
      await createPurchaseReceive({
        orderId: form.orderId!,
        warehouseId: form.warehouseId!,
        receiveDate: form.receiveDate,
        items: form.items.map((it) => ({
          productId: it.productId!,
          quantity: it.quantity,
          batchNo: it.batchNo,
          produceDate: it.produceDate || null,
          expireDate: it.expireDate,
          unitCost: it.unitCost,
          inspectionNo: it.inspectionNo || null
        }))
      })
      ElMessage.success('入库单已保存为草稿，提交审批通过后下发仓库，WMS 回传实际量后生效')
      dialogVisible.value = false
      load()
    } finally {
      saving.value = false
    }
  })
}

// 提交审批（DRAFT 首提 / REJECTED 重提，后端同一接口）
async function handleSubmit(row: PurchaseReceive) {
  await run(
    row.id!,
    async () => {
      await submitPurchaseReceive(row.id!)
      ElMessage.success(row.status === 'REJECTED' ? '已重新提交审批' : '已提交审批')
      detailVisible.value = false
      await load()
    },
    'submit'
  )
}

async function handleVoid(row: PurchaseReceive) {
  let reason = ''
  try {
    const { value } = await ElMessageBox.prompt(
      `确认作废入库单「${row.receiveNo}」吗？已审批执行的单据将回冲库存并重算订单状态。`,
      '作废入库单',
      {
        type: 'warning',
        confirmButtonText: '确认作废',
        cancelButtonText: '取消',
        inputType: 'textarea',
        inputPlaceholder: '请填写作废原因',
        inputValidator: (v: string) => (v && v.trim() ? true : '作废原因不能为空')
      }
    )
    reason = (value || '').trim()
  } catch {
    return
  }
  await run(
    row.id!,
    async () => {
      await voidPurchaseReceive(row.id!, reason)
      ElMessage.success('已作废')
      detailVisible.value = false
      await load()
      loadOptions()
    },
    'void'
  )
}

// WMS 回传实量：确认后按当前单据量回传（不传明细，后端按单据量执行库存/财务），成功后刷新列表与下拉
async function handleWmsCallback(row: PurchaseReceive) {
  try {
    await ElMessageBox.confirm(
      `确认将入库单「${row.receiveNo || `#${row.id}`}」按当前单据量回传实量吗？回传后将按实量执行库存入库与应付生成，且不可重复回传。`,
      'WMS 回传实量',
      { type: 'warning', confirmButtonText: '确认回传', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  await run(
    row.id!,
    async () => {
      await wmsCallbackPurchaseReceive(row.id!)
      ElMessage.success('已按单据量回传实量')
      detailVisible.value = false
      await load()
      loadOptions()
    },
    'wms'
  )
}

onMounted(() => {
  load()
  loadOptions()
})

// keep-alive 多标签页：切回标签时刷新列表与下拉缓存，避免看到切走前的旧数据。
// 首次 onActivated 紧随 onMounted（挂载即激活），用标记跳过，避免首屏重复请求。
let activatedOnce = false
onActivated(() => {
  if (!activatedOnce) {
    activatedOnce = true
    return
  }
  load()
  loadOptions()
})
</script>

<style scoped>
.page-card {
  border-radius: 8px;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.toolbar-left {
  display: flex;
  gap: 12px;
}

.toolbar-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.pagination {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}

.expand-box {
  padding: 8px 16px;
}

.items-box {
  width: 100%;
}

.add-item-btn {
  margin-top: 8px;
}

.dialog-alert {
  margin-bottom: 12px;
}

.text-muted {
  color: #c0c4cc;
}
</style>
