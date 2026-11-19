<template>
  <el-card shadow="never" class="page-card">
    <div class="toolbar">
      <div class="toolbar-left">
        <el-input
          v-model="query.keyword"
          placeholder="搜索调拨单号"
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
          <el-option v-for="s in DOC_STATUS_OPTIONS" :key="s" :label="statusLabel(s)" :value="s" />
        </el-select>
        <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
      </div>
      <div class="toolbar-right">
        <el-button
          v-permission="'inventory:transfer:view'"
          :icon="Download"
          :loading="exporting"
          @click="handleExport"
        >
          导出
        </el-button>
        <el-button
          v-permission="'inventory:transfer:create'"
          type="primary"
          :icon="Plus"
          @click="openDialog"
        >
          新建调拨
        </el-button>
      </div>
    </div>

    <DocBatchBar :selected-count="selectedCount">
      <el-button
        v-permission="'inventory:transfer:submit'"
        size="small"
        :disabled="selectedCount === 0"
        :loading="batchLoading"
        @click="handleBatchSubmit"
      >
        批量提交
      </el-button>
      <el-button
        v-permission="'inventory:transfer:void'"
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
              <el-table-column prop="quantity" label="调拨数量" width="110" align="right" />
            </el-table>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="调拨单号" min-width="190" show-overflow-tooltip>
        <template #default="{ row }">
          <el-link type="primary" :underline="false" @click="openDetail(row as StockTransfer)">
            {{ row.transferNo || '-' }}
          </el-link>
        </template>
      </el-table-column>
      <el-table-column label="调出仓" min-width="140" show-overflow-tooltip>
        <template #default="{ row }">{{ row.fromWarehouseName || '-' }}</template>
      </el-table-column>
      <el-table-column label="调入仓" min-width="140" show-overflow-tooltip>
        <template #default="{ row }">{{ row.toWarehouseName || '-' }}</template>
      </el-table-column>
      <el-table-column prop="transferDate" label="调拨日期" width="120" />
      <el-table-column label="备注" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">{{ row.remark || '-' }}</template>
      </el-table-column>
      <el-table-column label="制单人" width="110">
        <template #default="{ row }">{{ row.createdBy || '-' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="docStatusTag(row.status)">{{ docStatusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="row.status === 'DRAFT'"
            v-permission="'inventory:transfer:submit'"
            link
            type="primary"
            :loading="isActing(row.id!, 'submit')"
            :disabled="isBlocked() && !isActing(row.id!, 'submit')"
            @click="handleSubmit(row as StockTransfer)"
          >
            提交审批
          </el-button>
          <el-button
            v-if="row.status === 'REJECTED'"
            v-permission="'inventory:transfer:submit'"
            link
            type="warning"
            :loading="isActing(row.id!, 'submit')"
            :disabled="isBlocked() && !isActing(row.id!, 'submit')"
            @click="handleSubmit(row as StockTransfer)"
          >
            重新提交
          </el-button>
          <el-button
            v-if="canVoid(row.status)"
            v-permission="'inventory:transfer:void'"
            link
            type="danger"
            :loading="isActing(row.id!, 'void')"
            :disabled="isBlocked() && !isActing(row.id!, 'void')"
            @click="handleVoid(row as StockTransfer)"
          >
            作废
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
      title="新建仓库调拨"
      width="1000px"
      :close-on-click-modal="false"
      :close-on-press-escape="false"
      destroy-on-close
    >
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="保存仅创建草稿，不移动库存；提交并审批通过后下发仓库，WMS 回传实际量后同步两仓库存。"
        class="dialog-alert"
      />
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="90px">
        <el-row :gutter="16">
          <el-col :span="8">
            <el-form-item label="调出仓" prop="fromWarehouseId">
              <el-select
                v-model="form.fromWarehouseId"
                placeholder="输入名称远程搜索仓库"
                filterable
                remote
                :remote-method="searchWarehouses"
                :loading="warehouseLoading"
                style="width: 100%"
                @change="handleFromWarehouseChange"
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
            <el-form-item label="调入仓" prop="toWarehouseId">
              <el-select
                v-model="form.toWarehouseId"
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
                  :disabled="w.id === form.fromWarehouseId"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="调拨日期" prop="transferDate">
              <el-date-picker
                v-model="form.transferDate"
                type="date"
                value-format="YYYY-MM-DD"
                :disabled-date="disabledFutureDate"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="备注">
          <el-input v-model="form.remark" placeholder="可选" />
        </el-form-item>

        <el-form-item label="调拨明细" required>
          <div class="items-box">
            <el-table :data="form.items" size="small" border>
              <el-table-column label="商品" min-width="230">
                <template #default="{ row }">
                  <el-select
                    v-model="row.productId"
                    placeholder="输入编码/名称远程搜索商品"
                    filterable
                    remote
                    :remote-method="searchProducts"
                    :loading="productLoading"
                    style="width: 100%"
                    @change="() => handleProductChange(row as ItemRow)"
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
              <el-table-column label="批次" width="200">
                <template #default="{ row }">
                  <el-select
                    v-model="row.batchNo"
                    placeholder="选择/输入批号"
                    filterable
                    allow-create
                    default-first-option
                    style="width: 100%"
                    :loading="row.lotLoading"
                    @change="() => handleBatchChange(row as ItemRow)"
                  >
                    <el-option
                      v-for="lot in row.lots"
                      :key="lot.id"
                      :label="`${lot.batchNo}（可调 ${lot.quantity}）`"
                      :value="lot.batchNo"
                    />
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column label="可用库存" width="100" align="right">
                <template #default="{ row }">
                  <span :class="{ danger: row.availableQty != null && row.quantity > row.availableQty }">
                    {{ row.availableQty ?? '-' }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column label="调拨数量" width="150">
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
      title="仓库调拨详情"
      :loading="detailLoading"
      :header="detailHeader"
      :items="detailItems"
      :columns="DETAIL_COLUMNS"
      :remark="detail?.remark"
    >
      <template #actions>
        <el-button
          v-if="detail?.status === 'DRAFT'"
          v-permission="'inventory:transfer:submit'"
          type="primary"
          :loading="isActing(detail.id ?? 0, 'submit')"
          @click="handleSubmit(detail!)"
        >
          提交审批
        </el-button>
        <el-button
          v-if="detail?.status === 'REJECTED'"
          v-permission="'inventory:transfer:submit'"
          type="warning"
          :loading="isActing(detail.id ?? 0, 'submit')"
          @click="handleSubmit(detail!)"
        >
          重新提交
        </el-button>
        <el-button
          v-if="detail && canVoid(detail.status)"
          v-permission="'inventory:transfer:void'"
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
  listStockTransfers,
  getStockTransfer,
  createStockTransfer,
  submitStockTransfer,
  voidStockTransfer,
  exportStockTransfers,
  batchSubmitStockTransfers,
  batchVoidStockTransfers,
  listStocks
} from '@/api/inventory'
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
import { mergeById } from '@/utils/options'
import { disabledFutureDate } from '@/utils/format'
import { saveBlob, dateStamp } from '@/utils/download'
import { useQuerySync } from '@/composables/useQuerySync'
import { useRowAction } from '@/composables/useRowAction'
import { useDocumentBatch } from '@/composables/useBatchOperation'
import DocDetailDrawer from '@/components/DocDetailDrawer.vue'
import DocBatchBar from '@/components/DocBatchBar.vue'
import type { DocDetailColumn, DocDetailField } from '@/components/docDetail'
import type { StockTransfer, Warehouse, Product, Stock } from '@/types'

interface ItemRow {
  productId?: number
  batchNo: string
  quantity: number
  lots: Stock[]
  availableQty?: number
  lotLoading: boolean
}

const list = ref<StockTransfer[]>([])
const total = ref(0)
const loading = ref(false)
const exporting = ref(false)
const tableRef = ref<{ clearSelection: () => void } | null>(null)
const { isActing, isBlocked, run } = useRowAction()
const query = reactive({ keyword: '', status: '', page: 1, size: 20 })
// 筛选条件同步到地址栏（刷新/分享链接不丢筛选）
useQuerySync(query, ['keyword', 'status', 'page', 'size'], { numeric: ['page', 'size'] })

// 批量提交 / 批量作废（单据逐条独立事务）
const { selectedCount, batchLoading, handleSelectionChange, handleBatchRun } = useDocumentBatch({
  label: '调拨单',
  actionText: '提交',
  execute: (ids) => batchSubmitStockTransfers(ids),
  reload: load,
  clearSelection: () => tableRef.value?.clearSelection(),
  labelOf: (row) => (row.transferNo ? String(row.transferNo) : undefined)
})

function handleBatchSubmit() {
  handleBatchRun({ actionText: '提交', execute: (ids) => batchSubmitStockTransfers(ids) })
}

function handleBatchVoid() {
  handleBatchRun({
    actionText: '作废',
    reason: 'required',
    execute: (ids, reason) => batchVoidStockTransfers(ids, reason)
  })
}

// ===== 详情抽屉 =====
const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<StockTransfer | null>(null)

const DETAIL_COLUMNS: DocDetailColumn[] = [
  { prop: 'productName', label: '商品', minWidth: 180 },
  { prop: 'batchNo', label: '批号', width: 130 },
  { prop: 'quantity', label: '单据量', width: 110, align: 'right', type: 'qty' },
  { prop: 'actualQty', label: '实际量', width: 100, align: 'right', formatter: (row) => wmsActualText(row) },
  { prop: 'diff', label: '差异', width: 90, align: 'right', formatter: (row) => wmsDiffText(row) }
]

const detailItems = computed(
  () => (detail.value?.items || []) as unknown as Record<string, unknown>[]
)

const detailHeader = computed<DocDetailField[]>(() => {
  const d = detail.value
  return [
    { label: '调拨单号', value: d?.transferNo || '-' },
    { label: '调拨日期', value: d?.transferDate || '-' },
    { label: '调出仓', value: d?.fromWarehouseName || '-' },
    { label: '调入仓', value: d?.toWarehouseName || '-' },
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

async function openDetail(row: StockTransfer) {
  detail.value = row
  detailVisible.value = true
  if (!row.id) return
  detailLoading.value = true
  try {
    detail.value = await getStockTransfer(row.id)
  } catch {
    ElMessage.warning('详情加载失败，已展示列表中的单据信息')
  } finally {
    detailLoading.value = false
  }
}

// 作废调拨（回冲两仓库存）
async function handleVoid(row: StockTransfer) {
  let reason = ''
  try {
    const { value } = await ElMessageBox.prompt(
      `确认作废调拨单「${row.transferNo || `#${row.id}`}」吗？已审批执行的单据将回冲调出仓与调入仓库存。`,
      '作废调拨单',
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
      await voidStockTransfer(row.id!, reason)
      ElMessage.success('已作废')
      detailVisible.value = false
      await load()
    },
    'void'
  )
}

const warehouseOptions = ref<Warehouse[]>([])
const warehouseLoading = ref(false)
const productOptions = ref<Product[]>([])
const products = ref<Product[]>([])
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

const emptyItem = (): ItemRow => ({
  productId: undefined,
  batchNo: '',
  quantity: 1,
  lots: [],
  availableQty: undefined,
  lotLoading: false
})

const form = reactive({
  fromWarehouseId: undefined as number | undefined,
  toWarehouseId: undefined as number | undefined,
  transferDate: today(),
  remark: '',
  items: [emptyItem()] as ItemRow[]
})

const formRules: FormRules = {
  fromWarehouseId: [{ required: true, message: '请选择调出仓', trigger: 'change' }],
  toWarehouseId: [{ required: true, message: '请选择调入仓', trigger: 'change' }],
  transferDate: [{ required: true, message: '请选择调拨日期', trigger: 'change' }]
}

function productLabel(p: Product) {
  return `${p.code} | ${p.genericName} ${p.tradeName} ${p.spec}`
}

async function load() {
  loading.value = true
  try {
    const data = await listStockTransfers(
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

// 导出当前查询条件下的全部调拨单
async function handleExport() {
  if (total.value === 0) {
    ElMessage.warning('当前条件下暂无数据可导出')
    return
  }
  exporting.value = true
  try {
    const blob = await exportStockTransfers(query.keyword || undefined, query.status || undefined)
    saveBlob(blob, `仓库调拨单_${dateStamp()}.xlsx`)
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
  form.fromWarehouseId = undefined
  form.toWarehouseId = undefined
  form.transferDate = today()
  form.remark = ''
  form.items = [emptyItem()]
  dialogVisible.value = true
}

function addItem() {
  form.items.push(emptyItem())
}

function removeItem(index: number) {
  form.items.splice(index, 1)
}

function handleFromWarehouseChange() {
  // 切换调出仓后，原批次可用量失效，重新拉取
  for (const row of form.items) {
    row.batchNo = ''
    row.lots = []
    row.availableQty = undefined
    if (row.productId) handleProductChange(row)
  }
}

// 按 商品 + 调出仓 拉取可调批次（FEFO 顺序由后端返回）
async function handleProductChange(row: ItemRow) {
  row.lots = []
  row.availableQty = undefined
  row.batchNo = ''
  if (!row.productId || !form.fromWarehouseId) return
  row.lotLoading = true
  try {
    const data = await listStocks({
      productId: row.productId,
      warehouseId: form.fromWarehouseId,
      page: 1,
      size: 100
    })
    row.lots = data.records.filter((s) => Number(s.quantity || 0) > 0)
  } catch {
    // 无库存查询权限时允许手工录入批次
    row.lots = []
  } finally {
    row.lotLoading = false
  }
}

function handleBatchChange(row: ItemRow) {
  const lot = row.lots.find((l) => l.batchNo === row.batchNo)
  row.availableQty = lot ? Number(lot.quantity || 0) : undefined
}

async function handleSave() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    if (form.fromWarehouseId === form.toWarehouseId) {
      ElMessage.warning('调出仓与调入仓不能相同')
      return
    }
    if (form.items.length === 0) {
      ElMessage.warning('请至少添加一条调拨明细')
      return
    }
    for (const it of form.items) {
      if (!it.productId) {
        ElMessage.warning('请选择明细商品')
        return
      }
      if (!it.batchNo) {
        ElMessage.warning('请选择或填写批号')
        return
      }
      if (!it.quantity || it.quantity <= 0) {
        ElMessage.warning('调拨数量必须大于 0')
        return
      }
      if (it.availableQty != null && it.quantity > it.availableQty) {
        ElMessage.warning(`批号 ${it.batchNo} 调拨数量超过可用库存 ${it.availableQty}`)
        return
      }
    }
    saving.value = true
    try {
      await createStockTransfer({
        fromWarehouseId: form.fromWarehouseId!,
        toWarehouseId: form.toWarehouseId!,
        transferDate: form.transferDate,
        remark: form.remark || null,
        items: form.items.map((it) => ({
          productId: it.productId!,
          batchNo: it.batchNo,
          quantity: it.quantity
        }))
      })
      ElMessage.success('调拨单已保存为草稿，提交审批通过后下发仓库，WMS 回传实际量后同步两仓库存')
      dialogVisible.value = false
      load()
    } finally {
      saving.value = false
    }
  })
}

// 提交审批（DRAFT 首提 / REJECTED 重提，后端同一接口）
async function handleSubmit(row: StockTransfer) {
  await run(
    row.id!,
    async () => {
      await submitStockTransfer(row.id!)
      ElMessage.success(row.status === 'REJECTED' ? '已重新提交审批' : '已提交审批')
      detailVisible.value = false
      await load()
    },
    'submit'
  )
}

onMounted(async () => {
  load()
  try {
    const [w, p] = await Promise.all([
      listWarehouses('', 1, 50, 'ACTIVE'),
      listProducts('', 1, 50, 'ACTIVE')
    ])
    warehouseOptions.value = w.records
    products.value = p.records
    productOptions.value = p.records
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
.danger {
  color: #f56c6c;
  font-weight: 600;
}
.text-muted {
  color: #c0c4cc;
}
</style>
