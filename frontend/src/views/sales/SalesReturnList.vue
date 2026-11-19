<template>
  <el-card shadow="never" class="page-card">
    <div class="toolbar">
      <div class="toolbar-left">
        <el-input
          v-model="query.keyword"
          placeholder="搜索退货单号"
          clearable
          style="width: 220px"
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
          <el-option v-for="s in STATUS_OPTIONS" :key="s" :label="statusLabel(s)" :value="s" />
        </el-select>
        <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
      </div>
      <div class="toolbar-right">
        <el-button
          v-permission="'sales:return:view'"
          :icon="Download"
          :loading="exporting"
          @click="handleExport"
        >
          导出
        </el-button>
        <el-button
          v-permission="'sales:return:create'"
          type="primary"
          :icon="Plus"
          @click="openDialog"
        >
          新建销售退货
        </el-button>
      </div>
    </div>

    <DocBatchBar :selected-count="selectedCount">
      <el-button
        v-permission="'sales:return:void'"
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
              <el-table-column prop="quantity" label="退货数量" width="100" align="right" />
              <el-table-column label="销售单价" width="110" align="right">
                <template #default="{ row: it }">{{ formatMoney(it.unitPrice) }}</template>
              </el-table-column>
              <el-table-column label="成本单价" width="110" align="right">
                <template #default="{ row: it }">{{ formatMoney(it.unitCost) }}</template>
              </el-table-column>
              <el-table-column label="验收结果" width="110">
                <template #default="{ row: it }">
                  <el-tag
                    v-if="it.acceptResult"
                    size="small"
                    :type="it.acceptResult === 'QUALIFIED' ? 'success' : 'danger'"
                  >
                    {{ it.acceptResult === 'QUALIFIED' ? '合格' : '不合格' }}
                  </el-tag>
                  <span v-else class="text-muted">待验收</span>
                </template>
              </el-table-column>
              <el-table-column label="不合格原因" min-width="150" show-overflow-tooltip>
                <template #default="{ row: it }">{{ it.rejectReason || '-' }}</template>
              </el-table-column>
            </el-table>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="退货单号" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">
          <el-link type="primary" :underline="false" @click="openDetail(row as SalesReturn)">
            {{ row.returnNo || '-' }}
          </el-link>
        </template>
      </el-table-column>
      <el-table-column label="原出库单" min-width="170" show-overflow-tooltip>
        <template #default="{ row }">{{ row.deliveryNo || '-' }}</template>
      </el-table-column>
      <el-table-column label="客户" min-width="170" show-overflow-tooltip>
        <template #default="{ row }">{{ row.customerName || '-' }}</template>
      </el-table-column>
      <el-table-column label="来源门店" min-width="130" show-overflow-tooltip>
        <template #default="{ row }">{{ storeName(row.storeId) }}</template>
      </el-table-column>
      <el-table-column prop="returnDate" label="退货日期" width="120" />
      <el-table-column label="退货金额" width="130" align="right">
        <template #default="{ row }">{{ formatMoney(row.totalAmount) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="statusTag(row.status)">{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="row.status === 'DRAFT'"
            v-permission="'sales:return:create'"
            link
            type="primary"
            :loading="isActing(row.id!, 'submit')"
            :disabled="isBlocked() && !isActing(row.id!, 'submit')"
            @click="handleSubmit(row as SalesReturn)"
          >
            提交审批
          </el-button>
          <el-button
            v-else-if="row.status === 'APPROVED'"
            v-permission="'sales:return:receive'"
            link
            type="success"
            @click="openInspect(row as SalesReturn)"
          >
            验收分流
          </el-button>
          <el-button
            v-if="canVoid(row as SalesReturn)"
            v-permission="'sales:return:void'"
            link
            type="danger"
            :loading="isActing(row.id!, 'void')"
            :disabled="isBlocked() && !isActing(row.id!, 'void')"
            @click="handleVoid(row as SalesReturn)"
          >
            作废
          </el-button>
          <span v-if="row.status === 'SUBMITTED' || row.status === 'VOID'" class="text-muted">-</span>
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

    <!-- 新建退货：必须基于原销售出库单，按批次退回指定仓库 -->
    <el-dialog
      v-model="dialogVisible"
      title="新建销售退货"
      width="1000px"
      :close-on-click-modal="false"
      :close-on-press-escape="false"
      destroy-on-close
    >
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="退货需关联原销售出库单；验收时合格品按原成本回冲库存，不合格品转入不合格品台账。"
        class="dialog-alert"
      />
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="90px">
        <el-row :gutter="16">
          <el-col :span="8">
            <el-form-item label="原出库单" prop="deliveryId">
              <el-select
                v-model="form.deliveryId"
                placeholder="输入出库单号远程搜索"
                filterable
                remote
                :remote-method="searchDeliveries"
                :loading="deliveryLoading"
                style="width: 100%"
                @change="handleDeliveryChange"
              >
                <el-option
                  v-for="d in deliveryOptions"
                  :key="d.id"
                  :label="`${d.deliveryNo}（${d.customerName || '-'}）`"
                  :value="d.id!"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="退回仓库" prop="warehouseId">
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
            <el-form-item label="退货日期" prop="returnDate">
              <el-date-picker
                v-model="form.returnDate"
                type="date"
                value-format="YYYY-MM-DD"
                :disabled-date="disabledFutureDate"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="8">
            <el-form-item label="来源门店">
              <el-select
                v-model="form.storeId"
                placeholder="可选，门店退货"
                clearable
                filterable
                remote
                :remote-method="searchStores"
                :loading="storeLoading"
                style="width: 100%"
              >
                <el-option v-for="s in storeOptions" :key="s.id" :label="s.name" :value="s.id!" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="16">
            <el-form-item label="退货原因">
              <el-input v-model="form.reason" placeholder="可选，如破损/滞销/质量问题" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item label="退货明细" required>
          <div class="items-box">
            <el-table :data="form.items" size="small" border>
              <el-table-column label="商品" min-width="240">
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
                      :label="productLabel(p)"
                      :value="p.id!"
                    />
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column label="批号" width="150">
                <template #default="{ row }">
                  <el-input v-model="row.batchNo" placeholder="批号" />
                </template>
              </el-table-column>
              <el-table-column label="退货数量" width="140">
                <template #default="{ row }">
                  <el-input-number
                    v-model="row.quantity"
                    :min="0"
                    :precision="2"
                    style="width: 100%"
                  />
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

    <!-- 验收分流 -->
    <el-dialog
      v-model="inspectVisible"
      title="退货验收分流"
      width="900px"
      :close-on-click-modal="false"
      :close-on-press-escape="false"
      destroy-on-close
    >
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="逐行判定：合格按原成本回冲库存；不合格转入不合格品台账，需填写原因。"
        class="dialog-alert"
      />
      <el-table :data="inspectItems" size="small" border>
        <el-table-column label="商品" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.productName || '-' }}</template>
        </el-table-column>
        <el-table-column prop="batchNo" label="批号" width="130" />
        <el-table-column prop="quantity" label="退货数量" width="100" align="right" />
        <el-table-column label="验收结论" width="180">
          <template #default="{ row }">
            <el-radio-group v-model="row.qualified" size="small">
              <el-radio-button :value="true">合格</el-radio-button>
              <el-radio-button :value="false">不合格</el-radio-button>
            </el-radio-group>
          </template>
        </el-table-column>
        <el-table-column label="不合格原因" min-width="180">
          <template #default="{ row }">
            <el-input
              v-model="row.rejectReason"
              size="small"
              placeholder="不合格时必填"
              :disabled="row.qualified"
            />
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="inspectVisible = false">取消</el-button>
        <el-button type="primary" :loading="inspecting" @click="handleInspect">确认验收</el-button>
      </template>
    </el-dialog>

    <DocDetailDrawer
      v-model="detailVisible"
      title="销售退货详情"
      :loading="detailLoading"
      :header="detailHeader"
      :items="detailItems"
      :columns="DETAIL_COLUMNS"
      :remark="detail?.reason"
    >
      <template #actions>
        <el-button
          v-if="detail?.status === 'DRAFT'"
          v-permission="'sales:return:create'"
          type="primary"
          :loading="isActing(detail.id ?? 0, 'submit')"
          @click="handleSubmit(detail!)"
        >
          提交审批
        </el-button>
        <el-button
          v-if="detail?.status === 'APPROVED'"
          v-permission="'sales:return:receive'"
          type="success"
          @click="openInspect(detail!)"
        >
          验收分流
        </el-button>
        <el-button
          v-if="detail && canVoid(detail)"
          v-permission="'sales:return:void'"
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
import { reactive, ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Search, Plus, Download } from '@element-plus/icons-vue'
import {
  listSalesReturns,
  getSalesReturn,
  createSalesReturn,
  submitSalesReturn,
  voidSalesReturn,
  exportSalesReturns,
  batchVoidSalesReturns,
  receiveSalesReturn,
  listSalesDeliveries
} from '@/api/sales'
import { listStores } from '@/api/store'
import { listWarehouses } from '@/api/warehouse'
import { listProducts } from '@/api/product'
import { statusLabel, statusTag } from '@/utils/status'
import { formatMoney, disabledFutureDate } from '@/utils/format'
import { saveBlob, dateStamp } from '@/utils/download'
import { mergeById } from '@/utils/options'
import { useRowAction } from '@/composables/useRowAction'
import { useQuerySync } from '@/composables/useQuerySync'
import { useDocumentBatch } from '@/composables/useBatchOperation'
import DocDetailDrawer from '@/components/DocDetailDrawer.vue'
import DocBatchBar from '@/components/DocBatchBar.vue'
import type { DocDetailColumn, DocDetailField } from '@/components/docDetail'
import type { SalesReturn, Store, Product, SalesDelivery, Warehouse } from '@/types'

const { isActing, isBlocked, run } = useRowAction()

interface ItemRow {
  productId?: number
  batchNo: string
  quantity: number
}

interface InspectRow {
  itemId?: number
  productName?: string
  batchNo: string
  quantity: number
  qualified: boolean
  rejectReason: string
}

const STATUS_OPTIONS = ['DRAFT', 'SUBMITTED', 'APPROVED', 'RECEIVED', 'VOID']

const list = ref<SalesReturn[]>([])
const total = ref(0)
const loading = ref(false)
const exporting = ref(false)
const tableRef = ref<{ clearSelection: () => void } | null>(null)
const query = reactive({ keyword: '', status: '', page: 1, size: 20 })
// 筛选条件同步到地址栏（刷新/分享链接不丢筛选）
useQuerySync(query, ['keyword', 'status', 'page', 'size'], { numeric: ['page', 'size'] })

// 批量作废（SUBMITTED 不可直接作废，由后端逐条返回失败原因）
const { selectedCount, batchLoading, handleSelectionChange, handleBatchRun } = useDocumentBatch({
  label: '销售退货单',
  actionText: '作废',
  reason: 'optional',
  execute: (ids, reason) => batchVoidSalesReturns(ids, reason),
  reload: load,
  clearSelection: () => tableRef.value?.clearSelection(),
  labelOf: (row) => (row.returnNo ? String(row.returnNo) : undefined)
})

function handleBatchVoid() {
  handleBatchRun()
}

// ===== 详情抽屉 =====
const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<SalesReturn | null>(null)

const DETAIL_COLUMNS: DocDetailColumn[] = [
  { prop: 'productName', label: '商品', minWidth: 180 },
  { prop: 'batchNo', label: '批号', width: 130 },
  { prop: 'quantity', label: '退货数量', width: 100, align: 'right', type: 'qty' },
  { prop: 'unitPrice', label: '销售单价', width: 110, align: 'right', type: 'money' },
  { prop: 'unitCost', label: '成本单价', width: 110, align: 'right', type: 'money' },
  {
    prop: 'acceptResult',
    label: '验收结果',
    width: 110,
    formatter: (row) =>
      row.acceptResult === 'QUALIFIED'
        ? '合格'
        : row.acceptResult === 'UNQUALIFIED'
          ? '不合格'
          : '待验收'
  },
  { prop: 'rejectReason', label: '不合格原因', minWidth: 150 }
]

const detailItems = computed(
  () => (detail.value?.items || []) as unknown as Record<string, unknown>[]
)

const detailHeader = computed<DocDetailField[]>(() => {
  const d = detail.value
  return [
    { label: '退货单号', value: d?.returnNo || '-' },
    { label: '退货日期', value: d?.returnDate || '-' },
    { label: '客户', value: d?.customerName || '-' },
    { label: '原出库单', value: d?.deliveryNo || '-' },
    { label: '来源门店', value: d?.storeName || (d?.storeId ? storeName(d.storeId) : '-') },
    { label: '退货金额', value: formatMoney(d?.totalAmount) },
    { label: '状态', value: d?.status ? statusLabel(d.status) : '-', danger: d?.status === 'VOID' },
    { label: '制单人', value: d?.createdBy || '-' }
  ]
})

async function openDetail(row: SalesReturn) {
  detail.value = row
  detailVisible.value = true
  if (!row.id) return
  detailLoading.value = true
  try {
    detail.value = await getSalesReturn(row.id)
  } catch {
    ElMessage.warning('详情加载失败，已展示列表中的单据信息')
  } finally {
    detailLoading.value = false
  }
}

const deliveryOptions = ref<SalesDelivery[]>([])
const warehouseOptions = ref<Warehouse[]>([])
const storeOptions = ref<Store[]>([])
const productOptions = ref<Product[]>([])
const products = ref<Product[]>([])
const deliveryLoading = ref(false)
const warehouseLoading = ref(false)
const storeLoading = ref(false)
const productLoading = ref(false)
const storeMap = ref<Record<number, string>>({})

const dialogVisible = ref(false)
const inspectVisible = ref(false)
const saving = ref(false)
const inspecting = ref(false)
const formRef = ref<FormInstance>()
const currentReturn = ref<SalesReturn | null>(null)
const inspectItems = ref<InspectRow[]>([])

const today = () => {
  const d = new Date()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${d.getFullYear()}-${m}-${day}`
}

const emptyItem = (): ItemRow => ({ productId: undefined, batchNo: '', quantity: 1 })

const form = reactive({
  deliveryId: undefined as number | undefined,
  warehouseId: undefined as number | undefined,
  storeId: undefined as number | undefined,
  returnDate: today(),
  reason: '',
  items: [emptyItem()] as ItemRow[]
})

const formRules: FormRules = {
  deliveryId: [{ required: true, message: '请选择原销售出库单', trigger: 'change' }],
  warehouseId: [{ required: true, message: '请选择退回仓库', trigger: 'change' }],
  returnDate: [{ required: true, message: '请选择退货日期', trigger: 'change' }]
}

function productLabel(p: Product) {
  return `${p.code} | ${p.genericName} ${p.tradeName} ${p.spec}`
}

function storeName(storeId?: number) {
  if (!storeId) return '-'
  return storeMap.value[storeId] || `#${storeId}`
}

async function load() {
  loading.value = true
  try {
    const data = await listSalesReturns(
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

// 导出当前查询条件下的全部退货单
async function handleExport() {
  if (total.value === 0) {
    ElMessage.warning('当前条件下暂无数据可导出')
    return
  }
  exporting.value = true
  try {
    const blob = await exportSalesReturns(query.keyword || undefined, query.status || undefined)
    saveBlob(blob, `销售退货单_${dateStamp()}.xlsx`)
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

async function searchDeliveries(keyword: string) {
  deliveryLoading.value = true
  try {
    const data = await listSalesDeliveries(keyword, undefined, 1, 30)
    // 仅审批通过（已执行）的出库单可作为退货来源
    deliveryOptions.value = data.records.filter(
      (d) => d.status === 'COMPLETED' || d.status === 'ACTIVE'
    )
  } finally {
    deliveryLoading.value = false
  }
}

async function searchWarehouses(keyword: string) {
  warehouseLoading.value = true
  try {
    const data = await listWarehouses(keyword, 1, 50, 'ACTIVE')
    warehouseOptions.value = data.records
  } finally {
    warehouseLoading.value = false
  }
}

async function searchStores(keyword: string) {
  storeLoading.value = true
  try {
    const data = await listStores(keyword, 1, 30, 'ACTIVE')
    storeOptions.value = data.records
    data.records.forEach((s) => {
      if (s.id) storeMap.value[s.id] = s.name
    })
  } finally {
    storeLoading.value = false
  }
}

async function searchProducts(keyword: string) {
  productLoading.value = true
  try {
    const data = await listProducts(keyword, 1, 50, 'ACTIVE')
    const selected = form.items
      .map((it) => products.value.find((p) => p.id === it.productId))
      .filter((p): p is Product => !!p)
    productOptions.value = mergeById(selected, data.records)
    products.value = mergeById(products.value, data.records)
  } finally {
    productLoading.value = false
  }
}

function openDialog() {
  form.deliveryId = undefined
  form.warehouseId = undefined
  form.storeId = undefined
  form.returnDate = today()
  form.reason = ''
  form.items = [emptyItem()]
  dialogVisible.value = true
}

function addItem() {
  form.items.push(emptyItem())
}

function removeItem(index: number) {
  form.items.splice(index, 1)
}

async function handleDeliveryChange(deliveryId?: number) {
  if (!deliveryId) return
  const delivery = deliveryOptions.value.find((d) => d.id === deliveryId)
  if (!delivery) return
  // 出库单明细带出商品/批号/数量，用户按实际退货调整
  if (delivery.items && delivery.items.length) {
    form.items = delivery.items.map((it) => ({
      productId: it.productId,
      batchNo: it.batchNo || '',
      quantity: it.quantity
    }))
    ElMessage.info(`已带出出库单 ${delivery.deliveryNo || ''} 的明细，请核对退货数量`)
  }
}

async function handleSave() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    if (form.items.length === 0) {
      ElMessage.warning('请至少添加一条退货明细')
      return
    }
    for (const it of form.items) {
      if (!it.productId) {
        ElMessage.warning('请选择明细商品')
        return
      }
      if (!it.batchNo) {
        ElMessage.warning('请填写批号，退货需按批次回冲')
        return
      }
      if (!it.quantity || it.quantity <= 0) {
        ElMessage.warning('退货数量必须大于 0')
        return
      }
    }
    saving.value = true
    try {
      await createSalesReturn({
        deliveryId: form.deliveryId!,
        storeId: form.storeId ?? null,
        returnDate: form.returnDate,
        reason: form.reason || null,
        items: form.items.map((it) => ({
          productId: it.productId!,
          batchNo: it.batchNo,
          quantity: it.quantity,
          warehouseId: form.warehouseId!
        }))
      })
      ElMessage.success('退货单已保存为草稿，请在列表提交审批')
      dialogVisible.value = false
      load()
    } finally {
      saving.value = false
    }
  })
}

// 待审批（SUBMITTED）需先驳回或完成审批，已作废不再重复作废
function canVoid(row: SalesReturn) {
  return row.status !== 'SUBMITTED' && row.status !== 'VOID'
}

async function handleVoid(row: SalesReturn) {
  let reason = ''
  try {
    const { value } = await ElMessageBox.prompt(
      `确认作废退货单「${row.returnNo || `#${row.id}`}」吗？已验收单据将回冲库存并作废红字应收。`,
      '作废销售退货',
      {
        type: 'warning',
        confirmButtonText: '确认作废',
        cancelButtonText: '取消',
        inputType: 'textarea',
        inputPlaceholder: '作废原因（选填）',
        inputValidator: () => true
      }
    )
    reason = (value || '').trim()
  } catch {
    return
  }
  await run(
    row.id!,
    async () => {
      await voidSalesReturn(row.id!, reason || undefined)
      ElMessage.success('已作废')
      detailVisible.value = false
      await load()
    },
    'void'
  )
}

async function handleSubmit(row: SalesReturn) {
  await run(
    row.id!,
    async () => {
      await submitSalesReturn(row.id!)
      ElMessage.success('已提交审批')
      detailVisible.value = false
      await load()
    },
    'submit'
  )
}

function openInspect(row: SalesReturn) {
  detailVisible.value = false
  currentReturn.value = row
  inspectItems.value = (row.items || []).map((it) => ({
    itemId: it.id,
    productName: it.productName,
    batchNo: it.batchNo,
    quantity: Number(it.quantity || 0),
    qualified: true,
    rejectReason: ''
  }))
  inspectVisible.value = true
}

async function handleInspect() {
  if (!currentReturn.value?.id) return
  for (const it of inspectItems.value) {
    if (!it.qualified && !it.rejectReason.trim()) {
      ElMessage.warning(`批号 ${it.batchNo} 判定不合格，请填写不合格原因`)
      return
    }
  }
  inspecting.value = true
  try {
    await receiveSalesReturn(currentReturn.value.id, {
      items: inspectItems.value
        .filter((it) => it.itemId != null)
        .map((it) => ({
          itemId: it.itemId!,
          qualified: it.qualified,
          rejectReason: it.qualified ? null : it.rejectReason
        }))
    })
    ElMessage.success('验收完成：合格品已回库存，不合格品已转不合格台账')
    inspectVisible.value = false
    load()
  } finally {
    inspecting.value = false
  }
}

onMounted(async () => {
  load()
  try {
    const [w, p, s] = await Promise.all([
      listWarehouses('', 1, 50, 'ACTIVE'),
      listProducts('', 1, 50, 'ACTIVE'),
      listStores('', 1, 100, 'ACTIVE')
    ])
    warehouseOptions.value = w.records
    products.value = p.records
    productOptions.value = p.records
    storeOptions.value = s.records
    s.records.forEach((st) => {
      if (st.id) storeMap.value[st.id] = st.name
    })
  } catch {
    // 下拉加载失败不阻塞列表
  }
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
