<template>
  <el-card shadow="never" class="page-card">
    <div class="toolbar">
      <div class="toolbar-left">
        <el-input
          v-model="query.keyword"
          placeholder="搜索出库单号"
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
          v-permission="'sales:delivery:view'"
          :icon="Download"
          :loading="exporting"
          @click="handleExport"
        >
          导出
        </el-button>
        <el-button
          v-permission="'sales:delivery:create'"
          type="primary"
          :icon="Plus"
          @click="openDialog"
        >
          新增销售出库
        </el-button>
      </div>
    </div>

    <DocBatchBar :selected-count="selectedCount">
      <el-button
        v-permission="'sales:delivery:submit'"
        size="small"
        :disabled="selectedCount === 0"
        :loading="batchLoading"
        @click="handleBatchSubmit"
      >
        批量提交
      </el-button>
      <el-button
        v-permission="'sales:delivery:void'"
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
              <el-table-column prop="batchNo" label="批号" width="130" />
              <el-table-column prop="quantity" label="数量" width="100" align="right" />
              <el-table-column label="出库成本" width="120" align="right">
                <template #default="{ row: it }">{{ formatMoney(it.unitCost) }}</template>
              </el-table-column>
            </el-table>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="出库单号" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">
          <el-link type="primary" :underline="false" @click="openDetail(row as SalesDelivery)">
            {{ row.deliveryNo || '-' }}
          </el-link>
        </template>
      </el-table-column>
      <el-table-column label="销售订单" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">{{ row.orderNo || '-' }}</template>
      </el-table-column>
      <el-table-column label="客户" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">{{ row.customerName || '-' }}</template>
      </el-table-column>
      <el-table-column prop="deliveryDate" label="出库日期" width="120" />
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag size="small" :type="docStatusTag(row.status)">{{ docStatusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="260" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="row.status === 'DRAFT'"
            v-permission="'sales:delivery:submit'"
            link
            type="primary"
            :loading="isActing(row.id!, 'submit')"
            :disabled="isBlocked() && !isActing(row.id!, 'submit')"
            @click="handleSubmit(row as SalesDelivery)"
          >
            提交审批
          </el-button>
          <el-button
            v-if="row.status === 'REJECTED'"
            v-permission="'sales:delivery:submit'"
            link
            type="warning"
            :loading="isActing(row.id!, 'submit')"
            :disabled="isBlocked() && !isActing(row.id!, 'submit')"
            @click="handleSubmit(row as SalesDelivery)"
          >
            重新提交
          </el-button>
          <el-button
            v-if="canVoid(row.status)"
            v-permission="'sales:delivery:void'"
            link
            type="danger"
            :loading="isActing(row.id!, 'void')"
            :disabled="isBlocked() && !isActing(row.id!, 'void')"
            @click="handleVoid(row as SalesDelivery)"
          >
            作废
          </el-button>
          <el-button
            v-if="row.status === 'PUSHED'"
            v-permission="'sales:delivery:wms-callback'"
            link
            type="primary"
            :loading="isActing(row.id!, 'wms')"
            :disabled="isBlocked() && !isActing(row.id!, 'wms')"
            @click="handleWmsCallback(row as SalesDelivery)"
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
      title="新增销售出库"
      width="960px"
      :close-on-click-modal="false"
      :close-on-press-escape="false"
      destroy-on-close
    >
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="保存仅创建草稿，不扣减库存；提交并审批通过后下发仓库，WMS 回传实际量后按 FEFO 出库生效。"
        class="dialog-alert"
      />
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="90px">
        <el-row :gutter="16">
          <el-col :span="8">
            <el-form-item label="销售订单" prop="orderId">
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
                  v-for="o in deliverableOrders"
                  :key="o.id"
                  :label="orderOptionLabel(o)"
                  :value="o.id!"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="出库仓库" prop="warehouseId">
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
            <el-form-item label="出库日期" prop="deliveryDate">
              <el-date-picker
                v-model="form.deliveryDate"
                type="date"
                value-format="YYYY-MM-DD"
                :disabled-date="disabledFutureDate"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item label="出库明细" required>
          <div class="items-box">
            <el-alert
              v-if="blockedItems.length > 0"
              class="blocked-alert"
              type="error"
              :closable="false"
              show-icon
              title="以下商品库存不足，无法出库"
            >
              <div v-for="(it, idx) in blockedItems" :key="idx" class="blocked-line">
                {{ itemLabel(it) }}：{{ it.preview?.blockReason || '可售库存不足' }}
              </div>
            </el-alert>
            <el-table :data="form.items" size="small" border>
              <el-table-column type="expand">
                <template #default="{ row }">
                  <BatchAllocationPreview
                    v-model:preview="row.preview"
                    v-model:loading="row.previewLoading"
                    :product-id="row.productId"
                    :warehouse-id="form.warehouseId"
                    :quantity="row.quantity"
                  />
                </template>
              </el-table-column>
              <el-table-column label="商品" min-width="260">
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
              <el-table-column label="数量" width="140">
                <template #default="{ row }">
                  <el-input-number v-model="row.quantity" :min="0" :precision="2" style="width: 100%" />
                </template>
              </el-table-column>
              <el-table-column label="可用/效期" min-width="190">
                <template #default="{ row }">
                  <template v-if="row.preview">
                    <span class="sellable">可售 {{ formatQty(row.preview.totalSellable) }}</span>
                    <span v-if="row.preview.blocked" class="shortage">不足</span>
                  </template>
                  <span v-else class="text-muted">
                    {{ row.previewLoading ? '计算中…' : '-' }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="70" align="center">
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
        <el-button
          type="primary"
          :loading="saving"
          :disabled="blockedItems.length > 0 || previewLoadingCount > 0"
          @click="handleSave"
        >
          保存草稿
        </el-button>
      </template>
    </el-dialog>

    <DocDetailDrawer
      v-model="detailVisible"
      title="销售出库详情"
      :loading="detailLoading"
      :header="detailHeader"
      :items="detailItems"
      :columns="DETAIL_COLUMNS"
    >
      <template #actions>
        <el-button
          v-if="detail?.status === 'DRAFT'"
          v-permission="'sales:delivery:submit'"
          type="primary"
          :loading="isActing(detail.id ?? 0, 'submit')"
          @click="handleSubmit(detail!)"
        >
          提交审批
        </el-button>
        <el-button
          v-if="detail?.status === 'REJECTED'"
          v-permission="'sales:delivery:submit'"
          type="warning"
          :loading="isActing(detail.id ?? 0, 'submit')"
          @click="handleSubmit(detail!)"
        >
          重新提交
        </el-button>
        <el-button
          v-if="detail && canVoid(detail.status)"
          v-permission="'sales:delivery:void'"
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
import { reactive, ref, computed, watch, onMounted, onActivated } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Search, Plus, Download } from '@element-plus/icons-vue'
import {
  listSalesDeliveries,
  getSalesDelivery,
  createSalesDelivery,
  submitSalesDelivery,
  voidSalesDelivery,
  wmsCallbackSalesDelivery,
  exportSalesDeliveries,
  batchSubmitSalesDeliveries,
  batchVoidSalesDeliveries,
  listSalesOrders
} from '@/api/sales'
import { listWarehouses } from '@/api/warehouse'
import { listProducts } from '@/api/product'
import { fefoPreview } from '@/api/inventory'
import BatchAllocationPreview from '@/components/BatchAllocationPreview.vue'
import {
  statusLabel,
  docStatusLabel,
  docStatusTag,
  canVoid,
  isAwaitingWms,
  DOC_STATUS_OPTIONS
} from '@/utils/status'
import { wmsActualText, wmsDiffText } from '@/utils/wms'
import { formatMoney, formatQty, disabledFutureDate, todayText } from '@/utils/format'
import { saveBlob, dateStamp } from '@/utils/download'
import { mergeById } from '@/utils/options'
import { useRowAction } from '@/composables/useRowAction'
import { useQuerySync } from '@/composables/useQuerySync'
import { useDocumentBatch } from '@/composables/useBatchOperation'
import DocDetailDrawer from '@/components/DocDetailDrawer.vue'
import DocBatchBar from '@/components/DocBatchBar.vue'
import type { DocDetailColumn, DocDetailField } from '@/components/docDetail'
import type { FefoPreview, SalesDelivery, SalesOrder, Warehouse, Product } from '@/types'

const { isActing, isBlocked, run } = useRowAction()

interface ItemRow {
  productId?: number
  quantity: number
  /** FEFO 事前预览结果（仅前端，不提交） */
  preview?: FefoPreview | null
  previewLoading?: boolean
}

const list = ref<SalesDelivery[]>([])
const total = ref(0)
const loading = ref(false)
const exporting = ref(false)
const tableRef = ref<{ clearSelection: () => void } | null>(null)

const query = reactive({ keyword: '', status: '', page: 1, size: 20 })
// 筛选条件同步到地址栏（刷新/分享链接不丢筛选）
useQuerySync(query, ['keyword', 'status', 'page', 'size'], { numeric: ['page', 'size'] })

// 批量提交 / 批量作废（单据逐条独立事务）
const { selectedCount, batchLoading, handleSelectionChange, handleBatchRun } = useDocumentBatch({
  label: '销售出库单',
  actionText: '提交',
  execute: (ids) => batchSubmitSalesDeliveries(ids),
  reload: load,
  clearSelection: () => tableRef.value?.clearSelection(),
  labelOf: (row) => (row.deliveryNo ? String(row.deliveryNo) : undefined)
})

function handleBatchSubmit() {
  handleBatchRun({ actionText: '提交', execute: (ids) => batchSubmitSalesDeliveries(ids) })
}

function handleBatchVoid() {
  handleBatchRun({
    actionText: '作废',
    reason: 'required',
    execute: (ids, reason) => batchVoidSalesDeliveries(ids, reason)
  })
}

// ===== 详情抽屉 =====
const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<SalesDelivery | null>(null)

const DETAIL_COLUMNS: DocDetailColumn[] = [
  { prop: 'productName', label: '商品', minWidth: 180 },
  { prop: 'batchNo', label: '批号', width: 130 },
  { prop: 'quantity', label: '单据量', width: 100, align: 'right', type: 'qty' },
  { prop: 'actualQty', label: '实际量', width: 100, align: 'right', formatter: (row) => wmsActualText(row) },
  { prop: 'diff', label: '差异', width: 90, align: 'right', formatter: (row) => wmsDiffText(row) },
  { prop: 'unitCost', label: '出库成本', width: 120, align: 'right', type: 'money' }
]

const detailItems = computed(
  () => (detail.value?.items || []) as unknown as Record<string, unknown>[]
)

const detailHeader = computed<DocDetailField[]>(() => {
  const d = detail.value
  return [
    { label: '出库单号', value: d?.deliveryNo || '-' },
    { label: '出库日期', value: d?.deliveryDate || '-' },
    { label: '客户', value: d?.customerName || '-' },
    { label: '销售订单', value: d?.orderNo || '-' },
    {
      label: '状态',
      value: d?.status ? docStatusLabel(d.status) : '-',
      danger: d?.status === 'VOID' || d?.status === 'REJECTED'
    },
    ...(d && (d.status === 'DRAFT' || d.status === 'SUBMITTED')
      ? [{ label: '批次说明', value: '审批通过后按 FEFO 自动选批，当前明细仅记录品种与数量' }]
      : []),
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

async function openDetail(row: SalesDelivery) {
  detail.value = row
  detailVisible.value = true
  if (!row.id) return
  detailLoading.value = true
  try {
    detail.value = await getSalesDelivery(row.id)
  } catch {
    ElMessage.warning('详情加载失败，已展示列表中的单据信息')
  } finally {
    detailLoading.value = false
  }
}

const orders = ref<SalesOrder[]>([])
const warehouses = ref<Warehouse[]>([])
const products = ref<Product[]>([])
// 下拉展示列表（远程搜索结果）与缓存分离：缓存用于表格名称回显
const orderOptions = ref<SalesOrder[]>([])
const warehouseOptions = ref<Warehouse[]>([])
const productOptions = ref<Product[]>([])
const orderLoading = ref(false)
const warehouseLoading = ref(false)
const productLoading = ref(false)

const deliverableOrders = computed(() =>
  orderOptions.value.filter(
    (o) => o.status === 'APPROVED' || o.status === 'PARTIAL_DELIVERED'
  )
)

const dialogVisible = ref(false)
const saving = ref(false)
const formRef = ref<FormInstance>()

// 默认业务日期取本地日历日，避免 toISOString() 的 UTC 偏移在东八区凌晨串天
const today = () => todayText()

const emptyItem = (): ItemRow => ({
  productId: undefined,
  quantity: 1,
  preview: null,
  previewLoading: false
})

const form = reactive({
  orderId: undefined as number | undefined,
  warehouseId: undefined as number | undefined,
  deliveryDate: today(),
  items: [emptyItem()] as ItemRow[]
})

const formRules: FormRules = {
  orderId: [{ required: true, message: '请选择销售订单', trigger: 'change' }],
  warehouseId: [{ required: true, message: '请选择出库仓库', trigger: 'change' }],
  deliveryDate: [{ required: true, message: '请选择出库日期', trigger: 'change' }]
}

// 库存不足的明细行（阻断保存）
const blockedItems = computed(() => form.items.filter((it) => it.preview?.blocked))
// 预览计算中的明细行（阻断保存，避免用陈旧数据判定）
const previewLoadingCount = computed(() => form.items.filter((it) => it.previewLoading).length)

function itemLabel(it: ItemRow) {
  const p = products.value.find((x) => x.id === it.productId)
  if (p) return `${p.genericName || p.code}${p.spec ? ` ${p.spec}` : ''}`
  return it.preview?.productName || `商品 #${it.productId ?? '-'}`
}

/** 逐行刷新 FEFO 预览（用于保存前防陈旧数据；折叠行未挂载组件时也能刷新） */
async function refreshPreviews() {
  const warehouseId = form.warehouseId
  await Promise.all(
    form.items.map(async (it) => {
      if (!it.productId || !warehouseId || !it.quantity || it.quantity <= 0) {
        it.preview = null
        return
      }
      it.previewLoading = true
      try {
        it.preview = await fefoPreview({
          productId: it.productId,
          warehouseId,
          quantity: it.quantity
        })
      } catch {
        it.preview = null
      } finally {
        it.previewLoading = false
      }
    })
  )
}

// 切换仓库后所有行重算
watch(
  () => form.warehouseId,
  () => {
    form.items.forEach((it) => {
      it.preview = null
    })
    refreshPreviews()
  }
)

// 列表展示直接用后端 VO 名称（null 显示 '-'）
function productOptionLabel(p: Product) {
  return `${p.code} | ${p.genericName} ${p.tradeName} ${p.spec}`
}
function orderOptionLabel(o: SalesOrder) {
  return `${o.orderNo}（${statusLabel(o.status)}）`
}

// 下拉远程搜索（P1-30）：可出库订单仅限 APPROVED / PARTIAL_DELIVERED
async function searchOrders(keyword: string) {
  orderLoading.value = true
  try {
    const [approved, partial] = await Promise.all([
      listSalesOrders(keyword, 'APPROVED', 1, 30),
      listSalesOrders(keyword, 'PARTIAL_DELIVERED', 1, 30)
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
    listSalesOrders('', 'APPROVED', 1, 100),
    listSalesOrders('', 'PARTIAL_DELIVERED', 1, 100),
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
    const data = await listSalesDeliveries(
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

// 导出当前查询条件下的全部出库单
async function handleExport() {
  if (total.value === 0) {
    ElMessage.warning('当前条件下暂无数据可导出')
    return
  }
  exporting.value = true
  try {
    const blob = await exportSalesDeliveries(query.keyword || undefined, query.status || undefined)
    saveBlob(blob, `销售出库单_${dateStamp()}.xlsx`)
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
  form.deliveryDate = today()
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
      // 取消：回滚选择，保留原明细
      form.orderId = lastOrderId.value
      return
    }
  }
  form.items = order.items.map((it) => ({
    productId: it.productId,
    quantity: it.quantity,
    preview: null,
    previewLoading: false
  }))
  lastOrderId.value = orderId
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
        ElMessage.warning('出库数量必须大于 0')
        return
      }
    }
    saving.value = true
    try {
      // 保存前强制刷新预览，避免用陈旧结果判定；后端 400 仍是最终裁决
      await refreshPreviews()
      if (blockedItems.value.length > 0) {
        ElMessage.error(
          `库存不足：${blockedItems.value
            .map((it) => `${itemLabel(it)}（${it.preview?.blockReason || '可售库存不足'}）`)
            .join('；')}`
        )
        return
      }
      const created = await createSalesDelivery({
        orderId: form.orderId!,
        warehouseId: form.warehouseId!,
        deliveryDate: form.deliveryDate,
        items: form.items.map((it) => ({ productId: it.productId!, quantity: it.quantity }))
      })
      ElMessage.success(
        `出库单${created.deliveryNo ? `「${created.deliveryNo}」` : ''}已保存为草稿，提交审批通过后下发仓库，WMS 回传实际量后生效`
      )
      dialogVisible.value = false
      load()
    } finally {
      saving.value = false
    }
  })
}

async function handleVoid(row: SalesDelivery) {
  let reason = ''
  try {
    const { value } = await ElMessageBox.prompt(
      `确认作废出库单「${row.deliveryNo}」吗？已审批执行的单据将回冲库存并重算订单状态。`,
      '作废出库单',
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
      await voidSalesDelivery(row.id!, reason)
      ElMessage.success('已作废')
      detailVisible.value = false
      await load()
      loadOptions()
    },
    'void'
  )
}

// 提交审批（DRAFT 首提 / REJECTED 重提，后端同一接口）
async function handleSubmit(row: SalesDelivery) {
  await run(
    row.id!,
    async () => {
      await submitSalesDelivery(row.id!)
      ElMessage.success(row.status === 'REJECTED' ? '已重新提交审批' : '已提交审批')
      detailVisible.value = false
      await load()
    },
    'submit'
  )
}

// WMS 回传实量：确认后按当前单据量回传（不传明细，后端按单据量执行库存/财务），成功后刷新列表与下拉
async function handleWmsCallback(row: SalesDelivery) {
  try {
    await ElMessageBox.confirm(
      `确认将出库单「${row.deliveryNo || `#${row.id}`}」按当前单据量回传实量吗？回传后将按实量执行库存扣减与应收确认，且不可重复回传。`,
      'WMS 回传实量',
      { type: 'warning', confirmButtonText: '确认回传', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  await run(
    row.id!,
    async () => {
      await wmsCallbackSalesDelivery(row.id!)
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

.blocked-alert {
  margin-bottom: 8px;
}

.blocked-line {
  font-size: 12px;
  line-height: 20px;
}

.sellable {
  color: var(--el-text-color-regular);
}

.shortage {
  margin-left: 8px;
  font-size: 12px;
  color: var(--el-color-danger);
}
</style>
