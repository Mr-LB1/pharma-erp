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
          v-model="query.supplierId"
          placeholder="输入名称远程搜索供应商"
          clearable
          filterable
          remote
          :remote-method="searchSuppliers"
          :loading="supplierLoading"
          style="width: 240px"
          @change="handleSearch"
        >
          <el-option v-for="s in supplierOptions" :key="s.id" :label="s.name" :value="s.id!" />
        </el-select>
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
          v-permission="'purchase:return:view'"
          :icon="Download"
          :loading="exporting"
          @click="handleExport"
        >
          导出
        </el-button>
        <el-button
          v-permission="'purchase:return:create'"
          type="primary"
          :icon="Plus"
          @click="openDialog"
        >
          新建采购退货
        </el-button>
      </div>
    </div>

    <DocBatchBar :selected-count="selectedCount">
      <el-button
        v-permission="'purchase:return:submit'"
        size="small"
        :disabled="selectedCount === 0"
        :loading="batchLoading"
        @click="handleBatchSubmit"
      >
        批量提交
      </el-button>
      <el-button
        v-permission="'purchase:return:void'"
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
              <el-table-column prop="quantity" label="退货数量" width="110" align="right" />
              <el-table-column label="成本单价" width="120" align="right">
                <template #default="{ row: it }">{{ formatMoney(it.unitCost) }}</template>
              </el-table-column>
              <el-table-column label="小计" width="120" align="right">
                <template #default="{ row: it }">
                  {{ formatMoney(Number(it.quantity || 0) * Number(it.unitCost || 0)) }}
                </template>
              </el-table-column>
            </el-table>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="退货单号" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">
          <el-link type="primary" :underline="false" @click="openDetail(row as PurchaseReturn)">
            {{ row.returnNo || '-' }}
          </el-link>
        </template>
      </el-table-column>
      <el-table-column label="供应商" min-width="170" show-overflow-tooltip>
        <template #default="{ row }">{{ row.supplierName || '-' }}</template>
      </el-table-column>
      <el-table-column label="来源入库单" min-width="170" show-overflow-tooltip>
        <template #default="{ row }">{{ row.receiveNo || '-' }}</template>
      </el-table-column>
      <el-table-column prop="returnDate" label="退货日期" width="120" />
      <el-table-column label="退货金额" width="130" align="right">
        <template #default="{ row }">{{ formatMoney(row.totalAmount) }}</template>
      </el-table-column>
      <el-table-column label="退货原因" min-width="170" show-overflow-tooltip>
        <template #default="{ row }">{{ row.reason || '-' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="docStatusTag(row.status)">{{ docStatusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="260" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="row.status === 'DRAFT'"
            v-permission="'purchase:return:submit'"
            link
            type="primary"
            :loading="isActing(row.id!, 'submit')"
            :disabled="isBlocked() && !isActing(row.id!, 'submit')"
            @click="handleSubmit(row as PurchaseReturn)"
          >
            提交审批
          </el-button>
          <el-button
            v-if="row.status === 'REJECTED'"
            v-permission="'purchase:return:submit'"
            link
            type="warning"
            :loading="isActing(row.id!, 'submit')"
            :disabled="isBlocked() && !isActing(row.id!, 'submit')"
            @click="handleSubmit(row as PurchaseReturn)"
          >
            重新提交
          </el-button>
          <el-button
            v-if="canVoid(row.status)"
            v-permission="'purchase:return:void'"
            link
            type="danger"
            :loading="isActing(row.id!, 'void')"
            :disabled="isBlocked() && !isActing(row.id!, 'void')"
            @click="handleVoid(row as PurchaseReturn)"
          >
            作废
          </el-button>
          <el-button
            v-if="row.status === 'PUSHED'"
            v-permission="'purchase:return:wms-callback'"
            link
            type="primary"
            :loading="isActing(row.id!, 'wms')"
            :disabled="isBlocked() && !isActing(row.id!, 'wms')"
            @click="handleWmsCallback(row as PurchaseReturn)"
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
      title="新建采购退货"
      width="1000px"
      :close-on-click-modal="false"
      :close-on-press-escape="false"
      destroy-on-close
    >
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="保存仅创建草稿，不扣减库存；提交并审批通过后下发仓库，WMS 回传实际量后扣减批次库存并生成应付红冲。"
        class="dialog-alert"
      />
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="90px">
        <el-row :gutter="16">
          <el-col :span="8">
            <el-form-item label="供应商" prop="supplierId">
              <el-select
                v-model="form.supplierId"
                placeholder="输入名称远程搜索供应商"
                filterable
                remote
                :remote-method="searchSuppliers"
                :loading="supplierLoading"
                style="width: 100%"
              >
                <el-option
                  v-for="s in supplierOptions"
                  :key="s.id"
                  :label="s.name"
                  :value="s.id!"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="来源入库单">
              <el-select
                v-model="form.receiveId"
                placeholder="可选，选后带出批次"
                clearable
                filterable
                remote
                :remote-method="searchReceives"
                :loading="receiveLoading"
                style="width: 100%"
                @change="handleReceiveChange"
              >
                <el-option
                  v-for="r in receiveOptions"
                  :key="r.id"
                  :label="`${r.receiveNo}（${r.supplierName || '-'}）`"
                  :value="r.id!"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="退货仓库" prop="warehouseId">
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
        </el-row>
        <el-row :gutter="16">
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
          <el-col :span="16">
            <el-form-item label="退货原因">
              <el-input v-model="form.reason" placeholder="可选，如质量问题/临近效期" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item label="退货明细" required>
          <div class="items-box">
            <el-table :data="form.items" size="small" border>
              <el-table-column label="商品" min-width="250">
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
              <el-table-column label="批号" width="160">
                <template #default="{ row }">
                  <el-input v-model="row.batchNo" placeholder="指定批次" />
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

    <DocDetailDrawer
      v-model="detailVisible"
      title="采购退货详情"
      :loading="detailLoading"
      :header="detailHeader"
      :items="detailItems"
      :columns="DETAIL_COLUMNS"
      :remark="detail?.reason"
    >
      <template #actions>
        <el-button
          v-if="detail?.status === 'DRAFT'"
          v-permission="'purchase:return:submit'"
          type="primary"
          :loading="isActing(detail.id ?? 0, 'submit')"
          @click="handleSubmit(detail!)"
        >
          提交审批
        </el-button>
        <el-button
          v-if="detail?.status === 'REJECTED'"
          v-permission="'purchase:return:submit'"
          type="warning"
          :loading="isActing(detail.id ?? 0, 'submit')"
          @click="handleSubmit(detail!)"
        >
          重新提交
        </el-button>
        <el-button
          v-if="detail && canVoid(detail.status)"
          v-permission="'purchase:return:void'"
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
  listPurchaseReturns,
  getPurchaseReturn,
  createPurchaseReturn,
  submitPurchaseReturn,
  voidPurchaseReturn,
  wmsCallbackPurchaseReturn,
  exportPurchaseReturns,
  batchSubmitPurchaseReturns,
  batchVoidPurchaseReturns,
  listPurchaseReceives
} from '@/api/purchase'
import { listSuppliers } from '@/api/supplier'
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
import { formatMoney, disabledFutureDate } from '@/utils/format'
import { saveBlob, dateStamp } from '@/utils/download'
import { useQuerySync } from '@/composables/useQuerySync'
import { useRowAction } from '@/composables/useRowAction'
import { useDocumentBatch } from '@/composables/useBatchOperation'
import { mergeById } from '@/utils/options'
import DocDetailDrawer from '@/components/DocDetailDrawer.vue'
import DocBatchBar from '@/components/DocBatchBar.vue'
import type { DocDetailColumn, DocDetailField } from '@/components/docDetail'
import type { PurchaseReturn, Supplier, Product, PurchaseReceive, Warehouse } from '@/types'

interface ItemRow {
  productId?: number
  batchNo: string
  quantity: number
}

const list = ref<PurchaseReturn[]>([])
const total = ref(0)
const loading = ref(false)
const exporting = ref(false)
const tableRef = ref<{ clearSelection: () => void } | null>(null)
const { isActing, isBlocked, run } = useRowAction()

const query = reactive({
  keyword: '',
  supplierId: undefined as number | undefined,
  status: '',
  page: 1,
  size: 20
})
// 筛选条件同步到地址栏（刷新/分享链接不丢筛选）
useQuerySync(query, ['keyword', 'supplierId', 'status', 'page', 'size'], {
  numeric: ['supplierId', 'page', 'size']
})

// 批量提交 / 批量作废（单据逐条独立事务）
const { selectedCount, batchLoading, handleSelectionChange, handleBatchRun } = useDocumentBatch({
  label: '采购退货单',
  actionText: '提交',
  execute: (ids) => batchSubmitPurchaseReturns(ids),
  reload: load,
  clearSelection: () => tableRef.value?.clearSelection(),
  labelOf: (row) => (row.returnNo ? String(row.returnNo) : undefined)
})

function handleBatchSubmit() {
  handleBatchRun({ actionText: '提交', execute: (ids) => batchSubmitPurchaseReturns(ids) })
}

function handleBatchVoid() {
  handleBatchRun({
    actionText: '作废',
    reason: 'required',
    execute: (ids, reason) => batchVoidPurchaseReturns(ids, reason)
  })
}

// ===== 详情抽屉 =====
const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<PurchaseReturn | null>(null)

const DETAIL_COLUMNS: DocDetailColumn[] = [
  { prop: 'productName', label: '商品', minWidth: 180 },
  { prop: 'batchNo', label: '批号', width: 130 },
  { prop: 'quantity', label: '单据量', width: 110, align: 'right', type: 'qty' },
  { prop: 'actualQty', label: '实际量', width: 100, align: 'right', formatter: (row) => wmsActualText(row) },
  { prop: 'diff', label: '差异', width: 90, align: 'right', formatter: (row) => wmsDiffText(row) },
  { prop: 'unitCost', label: '成本单价', width: 120, align: 'right', type: 'money' },
  {
    prop: 'subtotal',
    label: '小计',
    width: 120,
    align: 'right',
    formatter: (row) => formatMoney(Number(row.quantity || 0) * Number(row.unitCost || 0))
  }
]

const detailItems = computed(
  () => (detail.value?.items || []) as unknown as Record<string, unknown>[]
)

const detailHeader = computed<DocDetailField[]>(() => {
  const d = detail.value
  return [
    { label: '退货单号', value: d?.returnNo || '-' },
    { label: '退货日期', value: d?.returnDate || '-' },
    { label: '供应商', value: d?.supplierName || '-' },
    { label: '来源入库单', value: d?.receiveNo || '-' },
    { label: '退货金额', value: formatMoney(d?.totalAmount) },
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
    { label: '制单人', value: d?.createdBy || '-' }
  ]
})

async function openDetail(row: PurchaseReturn) {
  detail.value = row
  detailVisible.value = true
  if (!row.id) return
  detailLoading.value = true
  try {
    detail.value = await getPurchaseReturn(row.id)
  } catch {
    ElMessage.warning('详情加载失败，已展示列表中的单据信息')
  } finally {
    detailLoading.value = false
  }
}

// 作废：库存回补 + 原红字应付联动作废
async function handleVoid(row: PurchaseReturn) {
  let reason = ''
  try {
    const { value } = await ElMessageBox.prompt(
      `确认作废采购退货单「${row.returnNo || `#${row.id}`}」吗？将回补库存并作废原红字应付。`,
      '作废采购退货',
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
      await voidPurchaseReturn(row.id!, reason)
      ElMessage.success('已作废')
      detailVisible.value = false
      await load()
    },
    'void'
  )
}

const supplierOptions = ref<Supplier[]>([])
const receiveOptions = ref<PurchaseReceive[]>([])
const warehouseOptions = ref<Warehouse[]>([])
const productOptions = ref<Product[]>([])
const products = ref<Product[]>([])
const supplierLoading = ref(false)
const receiveLoading = ref(false)
const warehouseLoading = ref(false)
const productLoading = ref(false)

const dialogVisible = ref(false)
const saving = ref(false)
const formRef = ref<FormInstance>()

const today = () => {
  const d = new Date()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${d.getFullYear()}-${m}-${day}`
}

const emptyItem = (): ItemRow => ({ productId: undefined, batchNo: '', quantity: 1 })

const form = reactive({
  supplierId: undefined as number | undefined,
  receiveId: undefined as number | undefined,
  warehouseId: undefined as number | undefined,
  returnDate: today(),
  reason: '',
  items: [emptyItem()] as ItemRow[]
})

const formRules: FormRules = {
  supplierId: [{ required: true, message: '请选择供应商', trigger: 'change' }],
  warehouseId: [{ required: true, message: '请选择退货仓库', trigger: 'change' }],
  returnDate: [{ required: true, message: '请选择退货日期', trigger: 'change' }]
}

function productLabel(p: Product) {
  return `${p.code} | ${p.genericName} ${p.tradeName} ${p.spec}`
}

async function load() {
  loading.value = true
  try {
    const data = await listPurchaseReturns(
      query.keyword || undefined,
      query.supplierId,
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
    const blob = await exportPurchaseReturns(query.keyword || undefined, query.supplierId, query.status || undefined)
    saveBlob(blob, `采购退货单_${dateStamp()}.xlsx`)
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

async function searchSuppliers(keyword: string) {
  supplierLoading.value = true
  try {
    const data = await listSuppliers(keyword, 1, 30, 'ACTIVE')
    supplierOptions.value = data.records
  } finally {
    supplierLoading.value = false
  }
}

async function searchReceives(keyword: string) {
  receiveLoading.value = true
  try {
    const data = await listPurchaseReceives(keyword, undefined, 1, 30)
    // 仅审批通过（已执行）的入库单可作为退货来源
    receiveOptions.value = data.records.filter(
      (r) => r.status === 'COMPLETED' || r.status === 'ACTIVE'
    )
  } finally {
    receiveLoading.value = false
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
  form.supplierId = undefined
  form.receiveId = undefined
  form.warehouseId = undefined
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

function handleReceiveChange(receiveId?: number) {
  const receive = receiveOptions.value.find((r) => r.id === receiveId)
  if (!receive || !receive.items || receive.items.length === 0) return
  form.items = receive.items.map((it) => ({
    productId: it.productId,
    batchNo: it.batchNo,
    quantity: it.quantity
  }))
  ElMessage.info('已按入库单带出批次与数量，请按实际退货调整')
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
        ElMessage.warning('请填写批号，采购退货需按批次扣减')
        return
      }
      if (!it.quantity || it.quantity <= 0) {
        ElMessage.warning('退货数量必须大于 0')
        return
      }
    }
    saving.value = true
    try {
      await createPurchaseReturn({
        supplierId: form.supplierId!,
        receiveId: form.receiveId ?? null,
        returnDate: form.returnDate,
        reason: form.reason || null,
        items: form.items.map((it) => ({
          productId: it.productId!,
          batchNo: it.batchNo,
          quantity: it.quantity,
          warehouseId: form.warehouseId!
        }))
      })
      ElMessage.success('退货单已保存为草稿，提交审批通过后下发仓库，WMS 回传实际量后扣减库存并生成应付红冲')
      dialogVisible.value = false
      load()
    } finally {
      saving.value = false
    }
  })
}

// 提交审批（DRAFT 首提 / REJECTED 重提，后端同一接口）
async function handleSubmit(row: PurchaseReturn) {
  await run(
    row.id!,
    async () => {
      await submitPurchaseReturn(row.id!)
      ElMessage.success(row.status === 'REJECTED' ? '已重新提交审批' : '已提交审批')
      detailVisible.value = false
      await load()
    },
    'submit'
  )
}

// WMS 回传实量：确认后按当前单据量回传（不传明细，后端按单据量执行库存/财务），成功后刷新列表
async function handleWmsCallback(row: PurchaseReturn) {
  try {
    await ElMessageBox.confirm(
      `确认将采购退货单「${row.returnNo || `#${row.id}`}」按当前单据量回传实量吗？回传后将按实量扣减库存并生成应付红冲，且不可重复回传。`,
      'WMS 回传实量',
      { type: 'warning', confirmButtonText: '确认回传', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  await run(
    row.id!,
    async () => {
      await wmsCallbackPurchaseReturn(row.id!)
      ElMessage.success('已按单据量回传实量')
      detailVisible.value = false
      await load()
    },
    'wms'
  )
}

// 下拉选项预热：供应商/仓库/商品，用于筛选与明细名称回显
async function loadOptions() {
  try {
    const [s, w, p] = await Promise.all([
      listSuppliers('', 1, 50, 'ACTIVE'),
      listWarehouses('', 1, 50, 'ACTIVE'),
      listProducts('', 1, 50, 'ACTIVE')
    ])
    supplierOptions.value = s.records
    warehouseOptions.value = w.records
    products.value = p.records
    productOptions.value = p.records
  } catch {
    // 下拉加载失败不阻塞列表
  }
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
