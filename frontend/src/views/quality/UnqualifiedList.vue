<template>
  <el-card shadow="never" class="page-card">
    <div class="toolbar">
      <div class="toolbar-left">
        <span class="toolbar-label">不合格品登记</span>
      </div>
      <el-button
        v-permission="'quality:unqualified:create'"
        type="primary"
        :icon="Plus"
        @click="openDialog"
      >
        登记不合格品
      </el-button>
    </div>

    <el-table :data="list" v-loading="loading" border stripe>
      <el-table-column prop="productCode" label="商品编码" min-width="140" show-overflow-tooltip fixed />
      <el-table-column prop="genericName" label="通用名" min-width="180" show-overflow-tooltip />
      <el-table-column prop="tradeName" label="商品名" min-width="160" show-overflow-tooltip />
      <el-table-column prop="spec" label="规格" min-width="140" show-overflow-tooltip />
      <el-table-column prop="warehouseName" label="隔离仓库" min-width="140" show-overflow-tooltip>
        <template #default="{ row }">{{ row.warehouseName || '-' }}</template>
      </el-table-column>
      <el-table-column prop="batchNo" label="批号" width="140" />
      <el-table-column prop="quantity" label="数量" width="100" align="right" />
      <el-table-column prop="reason" label="不合格原因" min-width="160" show-overflow-tooltip />
      <el-table-column label="状态" width="110" align="center">
        <template #default="{ row }">
          <el-tag size="small" :type="statusTag(row.status)">{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="销毁方式" width="100">
        <template #default="{ row }">{{ row.disposeMethod || '-' }}</template>
      </el-table-column>
      <el-table-column label="销毁日期" width="120">
        <template #default="{ row }">{{ dateOnly(row.disposedAt) }}</template>
      </el-table-column>
      <el-table-column label="监督人" width="110">
        <template #default="{ row }">{{ row.disposedBy || '-' }}</template>
      </el-table-column>
      <el-table-column prop="createdBy" label="登记人" width="100" />
      <el-table-column prop="createdAt" label="登记时间" width="170" />
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="row.status === 'PENDING'"
            v-permission="'quality:unqualified:dispose'"
            link
            type="primary"
            @click="handleDispose(row as Unqualified)"
          >
            处理
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
        @size-change="handleSizeChange"
      />
    </div>

    <el-dialog
      v-model="dialogVisible"
      title="登记不合格品"
      width="520px"
      :close-on-click-modal="false"
      :close-on-press-escape="false"
      destroy-on-close
    >
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="90px">
        <el-form-item label="商品" prop="productId">
          <el-select
            v-model="form.productId"
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
        </el-form-item>
        <el-form-item label="仓库" prop="warehouseId">
          <el-select
            v-model="form.warehouseId"
            placeholder="输入名称远程搜索仓库"
            filterable
            remote
            :remote-method="searchWarehouses"
            :loading="warehouseLoading"
            style="width: 100%"
          >
            <el-option v-for="w in warehouseOptions" :key="w.id" :label="w.name" :value="w.id!" />
          </el-select>
        </el-form-item>
        <el-form-item label="批号" prop="batchNo">
          <el-input v-model="form.batchNo" placeholder="不合格批次号" />
        </el-form-item>
        <el-form-item label="数量" prop="quantity">
          <el-input-number v-model="form.quantity" :min="0" :precision="2" style="width: 100%" />
        </el-form-item>
        <el-form-item label="原因" prop="reason">
          <el-select
            v-model="form.reason"
            placeholder="请选择或输入不合格原因"
            filterable
            allow-create
            default-first-option
            style="width: 100%"
          >
            <el-option label="包装破损" value="包装破损" />
            <el-option label="超过有效期" value="超过有效期" />
            <el-option label="检验不合格" value="检验不合格" />
            <el-option label="性状异常" value="性状异常" />
            <el-option label="其他" value="其他" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>
    <el-dialog
      v-model="disposeVisible"
      title="处理不合格品"
      width="480px"
      :close-on-click-modal="false"
      :close-on-press-escape="false"
      destroy-on-close
    >
      <el-alert
        v-if="currentRow"
        :title="`${currentRow.genericName || ''} ${currentRow.tradeName || ''} | 批号 ${currentRow.batchNo} | 数量 ${currentRow.quantity}`"
        type="warning"
        :closable="false"
        style="margin-bottom: 16px"
      />
      <el-form ref="disposeFormRef" :model="disposeForm" :rules="disposeRules" label-width="90px">
        <el-form-item label="销毁方式" prop="disposeMethod">
          <el-select v-model="disposeForm.disposeMethod" placeholder="请选择销毁方式" style="width: 100%">
            <el-option label="销毁" value="销毁" />
            <el-option label="退回" value="退回" />
            <el-option label="其他" value="其他" />
          </el-select>
        </el-form-item>
        <el-form-item label="销毁日期" prop="disposedAt">
          <el-date-picker
            v-model="disposeForm.disposedAt"
            type="date"
            value-format="YYYY-MM-DD"
            :disabled-date="disabledFutureDate"
            placeholder="请选择销毁日期"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="监督人" prop="disposedBy">
          <el-input v-model="disposeForm.disposedBy" placeholder="请输入监督人" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="disposeVisible = false">取消</el-button>
        <el-button type="primary" :loading="disposing" @click="handleDisposeSave">确认处理</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { listUnqualified, createUnqualified, disposeUnqualified } from '@/api/quality'
import { listProducts } from '@/api/product'
import { listWarehouses } from '@/api/warehouse'
import { useAuthStore } from '@/stores/auth'
import { mergeById } from '@/utils/options'
import { disabledFutureDate } from '@/utils/format'
import type {
  Unqualified,
  UnqualifiedCreateRequest,
  UnqualifiedDisposeRequest,
  Product,
  Warehouse
} from '@/types'

const authStore = useAuthStore()
const list = ref<Unqualified[]>([])
const total = ref(0)
const loading = ref(false)
const products = ref<Product[]>([])
const warehouses = ref<Warehouse[]>([])
const productOptions = ref<Product[]>([])
const warehouseOptions = ref<Warehouse[]>([])
const productLoading = ref(false)
const warehouseLoading = ref(false)

const query = reactive({ page: 1, size: 20 })

const dialogVisible = ref(false)
const saving = ref(false)
const formRef = ref<FormInstance>()

// 弹窗表单：仓库未选时为 undefined，提交前校验必填
type UnqualifiedForm = Omit<UnqualifiedCreateRequest, 'warehouseId'> & { warehouseId?: number }

const emptyForm = (): UnqualifiedForm => ({
  productId: 0,
  warehouseId: undefined,
  batchNo: '',
  quantity: 0,
  reason: ''
})
const form = reactive<UnqualifiedForm>(emptyForm())

const formRules: FormRules = {
  productId: [{ required: true, message: '请选择商品', trigger: 'change' }],
  warehouseId: [
    {
      required: true,
      validator: (_rule, value, callback) =>
        value ? callback() : callback(new Error('请选择隔离仓库')),
      trigger: 'change'
    }
  ],
  batchNo: [{ required: true, message: '请输入批号', trigger: 'blur' }],
  quantity: [{ required: true, message: '请输入数量', trigger: 'change' }],
  reason: [{ required: true, message: '请选择不合格原因', trigger: 'change' }]
}

// ===== 处理（销毁） =====
const disposeVisible = ref(false)
const disposing = ref(false)
const disposeFormRef = ref<FormInstance>()
const currentRow = ref<Unqualified | null>(null)

// 本地时区的今天（避免 toISOString 的 UTC 偏移）
const today = () => {
  const d = new Date()
  const month = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${d.getFullYear()}-${month}-${day}`
}
const emptyDisposeForm = (): UnqualifiedDisposeRequest => ({
  disposeMethod: '',
  disposedAt: today(),
  disposedBy: authStore.realName || ''
})
const disposeForm = reactive<UnqualifiedDisposeRequest>(emptyDisposeForm())

const disposeRules: FormRules = {
  disposeMethod: [{ required: true, message: '请选择销毁方式', trigger: 'change' }],
  disposedAt: [{ required: true, message: '请选择销毁日期', trigger: 'change' }],
  disposedBy: [{ required: true, message: '请输入监督人', trigger: 'blur' }]
}

function statusLabel(v?: string) {
  return v === 'DISPOSED' ? '已处理' : '待处理'
}

function statusTag(v?: string) {
  return v === 'DISPOSED' ? 'success' : 'warning'
}

// 后端 disposedAt 为 ISO 日期时间（2026-11-19T00:00:00），展示截取日期部分
function dateOnly(v?: string | null) {
  return v ? v.slice(0, 10) : '-'
}

function productOptionLabel(p: Product) {
  return `${p.code} | ${p.genericName} ${p.tradeName} ${p.spec}`
}

// 下拉远程搜索（P1-30）
async function searchProducts(keyword: string) {
  productLoading.value = true
  try {
    const data = await listProducts(keyword, 1, 50)
    const selected = products.value.find((p) => p.id === form.productId)
    productOptions.value = selected ? mergeById([selected], data.records) : data.records
    products.value = mergeById(products.value, data.records)
  } finally {
    productLoading.value = false
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

async function loadOptions() {
  const [p, w] = await Promise.all([listProducts('', 1, 100), listWarehouses('', 1, 100)])
  products.value = p.records
  productOptions.value = p.records
  warehouses.value = w.records
  warehouseOptions.value = w.records
}

async function load() {
  loading.value = true
  try {
    const data = await listUnqualified(query.page, query.size)
    list.value = data.records
    total.value = data.total
  } finally {
    loading.value = false
  }
}

function handleSizeChange() {
  query.page = 1
  load()
}

function openDialog() {
  Object.assign(form, emptyForm())
  dialogVisible.value = true
}

async function handleSave() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    saving.value = true
    try {
      await createUnqualified({ ...form, warehouseId: form.warehouseId! })
      ElMessage.success('登记成功')
      dialogVisible.value = false
      load()
    } finally {
      saving.value = false
    }
  })
}

function handleDispose(row: Unqualified) {
  currentRow.value = row
  Object.assign(disposeForm, emptyDisposeForm())
  disposeVisible.value = true
}

async function handleDisposeSave() {
  if (!disposeFormRef.value || !currentRow.value) return
  await disposeFormRef.value.validate(async (valid) => {
    if (!valid) return
    disposing.value = true
    try {
      await disposeUnqualified(currentRow.value!.id!, { ...disposeForm })
      ElMessage.success('已处理')
      disposeVisible.value = false
      load()
    } finally {
      disposing.value = false
    }
  })
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
  align-items: center;
  gap: 12px;
}

.toolbar-label {
  font-size: 14px;
  color: #606266;
}

.pagination {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
</style>
