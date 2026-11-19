<template>
  <el-card shadow="never" class="page-card">
    <div class="toolbar">
      <div class="toolbar-left">
        <el-input
          v-model="query.keyword"
          placeholder="搜索单号/客户名"
          clearable
          style="width: 220px"
          :prefix-icon="Search"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />
        <el-select
          v-model="query.status"
          placeholder="状态"
          clearable
          style="width: 140px"
          @change="handleSearch"
        >
          <el-option label="草稿" value="DRAFT" />
          <el-option label="待审批" value="SUBMITTED" />
          <el-option label="已审核" value="APPROVED" />
          <el-option label="已驳回" value="REJECTED" />
          <el-option label="部分出库" value="PARTIAL_DELIVERED" />
          <el-option label="已完成" value="COMPLETED" />
          <el-option label="已作废" value="VOID" />
        </el-select>
        <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
      </div>
      <div class="toolbar-right">
        <el-button
          v-permission="'sales:order:view'"
          :icon="Download"
          :loading="exporting"
          @click="handleExport"
        >
          导出
        </el-button>
        <el-button
          v-permission="'sales:order:create'"
          type="primary"
          :icon="Plus"
          @click="openDialog"
        >
          新增销售订单
        </el-button>
      </div>
    </div>

    <DocBatchBar :selected-count="selectedCount">
      <el-button
        v-permission="'sales:order:submit'"
        size="small"
        :disabled="selectedCount === 0"
        :loading="batchLoading"
        @click="handleBatchSubmit"
      >
        批量提交
      </el-button>
      <el-button
        v-permission="'sales:order:void'"
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
              <el-table-column label="商品" min-width="200" show-overflow-tooltip>
                <template #default="{ row: it }">{{ it.productName || '-' }}</template>
              </el-table-column>
              <el-table-column prop="quantity" label="数量" width="100" align="right" />
              <el-table-column label="单价" width="120" align="right">
                <template #default="{ row: it }">{{ formatMoney(it.unitPrice) }}</template>
              </el-table-column>
              <el-table-column label="金额" width="120" align="right">
                <template #default="{ row: it }">{{ formatMoney(it.amount) }}</template>
              </el-table-column>
            </el-table>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="订单号" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">
          <el-link type="primary" :underline="false" @click="openDetail(row as SalesOrder)">
            {{ row.orderNo || '-' }}
          </el-link>
        </template>
      </el-table-column>
      <el-table-column label="客户" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">{{ row.customerName || '-' }}</template>
      </el-table-column>
      <el-table-column prop="orderDate" label="订单日期" width="120" />
      <el-table-column prop="totalAmount" label="金额" width="140" align="right">
        <template #default="{ row }">{{ formatMoney(row.totalAmount) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag size="small" :type="statusTag(row.status)">{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="210" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="row.status === 'DRAFT'"
            v-permission="'sales:order:submit'"
            link
            type="primary"
            :loading="isActing(row.id!, 'submit')"
            :disabled="isBlocked() && !isActing(row.id!, 'submit')"
            @click="handleSubmit(row as SalesOrder)"
          >
            提交
          </el-button>
          <el-button
            v-if="row.status === 'DRAFT' || row.status === 'REJECTED'"
            v-permission="'sales:order:update'"
            link
            type="primary"
            @click="openEdit(row as SalesOrder)"
          >
            编辑
          </el-button>
          <el-button
            v-if="row.status === 'DRAFT' || row.status === 'SUBMITTED'"
            v-permission="'sales:order:void'"
            link
            type="danger"
            :loading="isActing(row.id!, 'void')"
            :disabled="isBlocked() && !isActing(row.id!, 'void')"
            @click="handleVoid(row as SalesOrder)"
          >
            作废
          </el-button>
          <el-button
            v-if="row.status === 'REJECTED'"
            v-permission="'sales:order:submit'"
            link
            type="warning"
            :loading="isActing(row.id!, 'resubmit')"
            :disabled="isBlocked() && !isActing(row.id!, 'resubmit')"
            @click="handleResubmit(row as SalesOrder)"
          >
            重新提交
          </el-button>
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
      :title="dialogTitle"
      width="820px"
      :close-on-click-modal="false"
      :close-on-press-escape="false"
      destroy-on-close
    >
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="90px">
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="客户" prop="customerId">
              <el-select
                v-model="form.customerId"
                placeholder="输入名称远程搜索客户"
                filterable
                remote
                :remote-method="searchCustomers"
                :loading="customerLoading"
                style="width: 100%"
              >
                <el-option
                  v-for="c in customerOptions"
                  :key="c.id"
                  :label="c.name"
                  :value="c.id!"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="订单日期" prop="orderDate">
              <el-date-picker
                v-model="form.orderDate"
                type="date"
                value-format="YYYY-MM-DD"
                :disabled-date="disabledFutureDate"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item label="订单明细" required>
          <div class="items-box">
            <el-table :data="form.items" size="small" border>
              <el-table-column label="商品" min-width="220">
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
              <el-table-column label="数量" width="150">
                <template #default="{ row }">
                  <el-input-number v-model="row.quantity" :min="0" :precision="2" style="width: 100%" />
                </template>
              </el-table-column>
              <el-table-column label="单价" width="150">
                <template #default="{ row }">
                  <el-input-number v-model="row.unitPrice" :min="0" :precision="2" style="width: 100%" />
                </template>
              </el-table-column>
              <el-table-column label="金额" width="130" align="right">
                <template #default="{ row }">{{ rowAmount(row as ItemRow) }}</template>
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
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>

    <DocDetailDrawer
      v-model="detailVisible"
      title="销售订单详情"
      :loading="detailLoading"
      :header="detailHeader"
      :items="detailItems"
      :columns="DETAIL_COLUMNS"
    >
      <template #actions>
        <el-button
          v-if="detail?.status === 'DRAFT'"
          v-permission="'sales:order:submit'"
          type="primary"
          :loading="isActing(detail.id ?? 0, 'submit')"
          @click="handleSubmit(detail!)"
        >
          提交
        </el-button>
        <el-button
          v-if="detail?.status === 'DRAFT' || detail?.status === 'REJECTED'"
          v-permission="'sales:order:update'"
          @click="openEdit(detail!)"
        >
          编辑
        </el-button>
        <el-button
          v-if="detail?.status === 'REJECTED'"
          v-permission="'sales:order:submit'"
          type="warning"
          :loading="isActing(detail.id ?? 0, 'resubmit')"
          @click="handleResubmit(detail!)"
        >
          重新提交
        </el-button>
        <el-button
          v-if="detail?.status === 'DRAFT' || detail?.status === 'SUBMITTED'"
          v-permission="'sales:order:void'"
          type="danger"
          :loading="isActing(detail.id ?? 0, 'void')"
          @click="handleVoid(detail!)"
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
  listSalesOrders,
  getSalesOrder,
  createSalesOrder,
  updateSalesOrder,
  submitSalesOrder,
  resubmitSalesOrder,
  voidSalesOrder,
  exportSalesOrders,
  batchSubmitSalesOrders,
  batchVoidSalesOrders
} from '@/api/sales'
import { listCustomers } from '@/api/customer'
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
import type { SalesOrder, Customer, Product } from '@/types'

const { isActing, isBlocked, run } = useRowAction()

interface ItemRow {
  productId?: number
  quantity: number
  unitPrice: number
}

const list = ref<SalesOrder[]>([])
const total = ref(0)
const loading = ref(false)
const exporting = ref(false)
const tableRef = ref<{ clearSelection: () => void } | null>(null)

const query = reactive({ keyword: '', status: '', page: 1, size: 20 })
// 筛选条件同步到地址栏（刷新/分享链接不丢筛选）
useQuerySync(query, ['keyword', 'status', 'page', 'size'], { numeric: ['page', 'size'] })

// 批量提交 / 批量作废
const { selectedCount, batchLoading, handleSelectionChange, handleBatchRun } = useDocumentBatch({
  label: '销售订单',
  actionText: '提交',
  execute: (ids) => batchSubmitSalesOrders(ids),
  reload: load,
  clearSelection: () => tableRef.value?.clearSelection(),
  labelOf: (row) => (row.orderNo ? String(row.orderNo) : undefined)
})

function handleBatchSubmit() {
  handleBatchRun({ actionText: '提交', execute: (ids) => batchSubmitSalesOrders(ids) })
}

function handleBatchVoid() {
  handleBatchRun({
    actionText: '作废',
    reason: 'required',
    execute: (ids, reason) => batchVoidSalesOrders(ids, reason)
  })
}

// ===== 详情抽屉 =====
const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<SalesOrder | null>(null)

const DETAIL_COLUMNS: DocDetailColumn[] = [
  { prop: 'productName', label: '商品', minWidth: 200 },
  { prop: 'quantity', label: '数量', width: 100, align: 'right', type: 'qty' },
  { prop: 'unitPrice', label: '单价', width: 120, align: 'right', type: 'money' },
  { prop: 'amount', label: '金额', width: 120, align: 'right', type: 'money' }
]

const detailItems = computed(
  () => (detail.value?.items || []) as unknown as Record<string, unknown>[]
)

const detailHeader = computed<DocDetailField[]>(() => {
  const d = detail.value
  return [
    { label: '单号', value: d?.orderNo || '-' },
    { label: '订单日期', value: d?.orderDate || '-' },
    { label: '客户', value: d?.customerName || '-' },
    { label: '金额', value: formatMoney(d?.totalAmount) },
    { label: '状态', value: d?.status ? statusLabel(d.status) : '-', danger: d?.status === 'VOID' },
    { label: '制单人', value: d?.createdBy || '-' },
    { label: '创建时间', value: d?.createdAt || '-' }
  ]
})

async function openDetail(row: SalesOrder) {
  detail.value = row
  detailVisible.value = true
  if (!row.id) return
  detailLoading.value = true
  try {
    detail.value = await getSalesOrder(row.id)
  } catch {
    ElMessage.warning('详情加载失败，已展示列表中的单据信息')
  } finally {
    detailLoading.value = false
  }
}

const customers = ref<Customer[]>([])
const products = ref<Product[]>([])
// 下拉展示列表（远程搜索结果）与缓存分离：缓存用于表格名称回显
const customerOptions = ref<Customer[]>([])
const productOptions = ref<Product[]>([])
const customerLoading = ref(false)
const productLoading = ref(false)

const dialogVisible = ref(false)
const saving = ref(false)
const formRef = ref<FormInstance>()
const editingId = ref<number | null>(null)

const dialogTitle = computed(() => (editingId.value ? '编辑销售订单' : '新增销售订单'))

const today = () => new Date().toISOString().slice(0, 10)

const emptyItem = (): ItemRow => ({ productId: undefined, quantity: 1, unitPrice: 0 })

const form = reactive({
  customerId: undefined as number | undefined,
  orderDate: today(),
  items: [emptyItem()] as ItemRow[]
})

const formRules: FormRules = {
  customerId: [{ required: true, message: '请选择客户', trigger: 'change' }],
  orderDate: [{ required: true, message: '请选择订单日期', trigger: 'change' }]
}

// 列表展示直接用后端 VO 名称（null 显示 '-'）；下拉仍走远程搜索
function productOptionLabel(p: Product) {
  if (!p.code) return p.genericName || String(p.id ?? '')
  return `${p.code} | ${p.genericName} ${p.tradeName} ${p.spec}`
}

// 编辑老单据时，把 VO 名称注入下拉选项，避免已选项因不在搜索结果里显示成 ID
function ensureCustomerOption(id?: number, name?: string) {
  if (!id || !name || customers.value.some((c) => c.id === id)) return
  const option = { id, name } as Customer
  customers.value = [...customers.value, option]
  customerOptions.value = [...customerOptions.value, option]
}

function ensureProductOption(id?: number, name?: string) {
  if (!id || !name || products.value.some((p) => p.id === id)) return
  const option = { id, code: '', genericName: name, tradeName: '', spec: '' } as Product
  products.value = [...products.value, option]
  productOptions.value = [...productOptions.value, option]
}
function rowAmount(row: ItemRow) {
  const v = (row.quantity || 0) * (row.unitPrice || 0)
  return formatMoney(Number.isFinite(v) ? v : 0)
}

// 下拉远程搜索（P1-30）
async function searchCustomers(keyword: string) {
  customerLoading.value = true
  try {
    const data = await listCustomers(keyword, 1, 50)
    const selected = customers.value.find((c) => c.id === form.customerId)
    customerOptions.value = selected ? mergeById([selected], data.records) : data.records
    customers.value = mergeById(customers.value, data.records)
  } finally {
    customerLoading.value = false
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
  const [c, p] = await Promise.all([listCustomers('', 1, 100), listProducts('', 1, 100)])
  customers.value = c.records
  products.value = p.records
  customerOptions.value = c.records
  productOptions.value = p.records
}

async function load() {
  loading.value = true
  try {
    const data = await listSalesOrders(query.keyword, query.status || undefined, query.page, query.size)
    list.value = data.records
    total.value = data.total
    tableRef.value?.clearSelection()
  } finally {
    loading.value = false
  }
}

// 导出当前查询条件下的全部销售订单
async function handleExport() {
  if (total.value === 0) {
    ElMessage.warning('当前条件下暂无数据可导出')
    return
  }
  exporting.value = true
  try {
    const blob = await exportSalesOrders(query.keyword || undefined, query.status || undefined)
    saveBlob(blob, `销售订单_${dateStamp()}.xlsx`)
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
  editingId.value = null
  form.customerId = undefined
  form.orderDate = today()
  form.items = [emptyItem()]
  dialogVisible.value = true
}

function openEdit(row: SalesOrder) {
  detailVisible.value = false
  editingId.value = row.id ?? null
  form.customerId = row.customerId
  form.orderDate = row.orderDate
  ensureCustomerOption(row.customerId, row.customerName)
  form.items = (row.items || []).map((it) => {
    ensureProductOption(it.productId, it.productName)
    return {
      productId: it.productId,
      quantity: it.quantity,
      unitPrice: it.unitPrice
    }
  })
  if (form.items.length === 0) form.items = [emptyItem()]
  dialogVisible.value = true
}

function addItem() {
  form.items.push(emptyItem())
}

function removeItem(index: number) {
  form.items.splice(index, 1)
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
    }
    saving.value = true
    try {
      const payload = {
        customerId: form.customerId!,
        orderDate: form.orderDate,
        items: form.items.map((it) => ({
          productId: it.productId!,
          quantity: it.quantity,
          unitPrice: it.unitPrice
        }))
      }
      if (editingId.value) {
        await updateSalesOrder(editingId.value, payload)
        ElMessage.success('修改成功')
      } else {
        await createSalesOrder(payload)
        ElMessage.success('新增成功')
      }
      dialogVisible.value = false
      load()
    } finally {
      saving.value = false
    }
  })
}

async function handleSubmit(row: SalesOrder) {
  try {
    await ElMessageBox.confirm(`确认提交销售订单「${row.orderNo}」吗？`, '提示', { type: 'warning' })
  } catch {
    return
  }
  await run(
    row.id!,
    async () => {
      await submitSalesOrder(row.id!)
      ElMessage.success('提交成功')
      detailVisible.value = false
      await load()
    },
    'submit'
  )
}

async function handleResubmit(row: SalesOrder) {
  try {
    await ElMessageBox.confirm(
      `确认重新提交销售订单「${row.orderNo}」吗？将再次进入待审批。`,
      '提示',
      { type: 'warning' }
    )
  } catch {
    return
  }
  await run(
    row.id!,
    async () => {
      await resubmitSalesOrder(row.id!)
      ElMessage.success('已重新提交')
      detailVisible.value = false
      await load()
    },
    'resubmit'
  )
}

async function handleVoid(row: SalesOrder) {
  let reason = ''
  try {
    const { value } = await ElMessageBox.prompt(
      `确认作废销售订单「${row.orderNo}」吗？作废后不可恢复。`,
      '作废订单',
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
      await voidSalesOrder(row.id!, reason)
      ElMessage.success('已作废')
      detailVisible.value = false
      await load()
    },
    'void'
  )
}

onMounted(() => {
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
</style>
