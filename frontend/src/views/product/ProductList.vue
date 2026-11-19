<template>
  <el-card shadow="never" class="page-card">
    <div class="toolbar">
      <div class="toolbar-left">
        <el-input
          v-model="query.keyword"
          placeholder="搜索编码 / 通用名 / 商品名 / 批准文号 / 厂家"
          clearable
          style="width: 320px"
          :prefix-icon="Search"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />
        <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
        <el-switch v-model="showInactive" active-text="显示停用" @change="handleSearch" />
      </div>
      <el-button v-permission="'product:create'" type="primary" :icon="Plus" @click="openDialog()">新增商品</el-button>
    </div>

    <BatchToolbar
      :selected-count="selectedCount"
      resource-type="products"
      resource-label="商品"
      :loading="batchLoading"
      @batch-enable="handleBatchAction('ENABLE')"
      @batch-disable="handleBatchAction('DISABLE')"
      @batch-delete="handleBatchAction('DELETE')"
      @import="importVisible = true"
    />

    <el-table
      ref="tableRef"
      :data="list"
      v-loading="loading"
      border
      stripe
      @selection-change="handleSelectionChange"
    >
      <el-table-column type="selection" width="46" fixed />
      <el-table-column prop="code" label="商品编码" width="120" fixed />
      <el-table-column prop="genericName" label="通用名" min-width="140" show-overflow-tooltip />
      <el-table-column prop="tradeName" label="商品名" min-width="120" show-overflow-tooltip />
      <el-table-column prop="approvalNo" label="批准文号" min-width="160" show-overflow-tooltip />
      <el-table-column prop="dosageForm" label="剂型" width="90" />
      <el-table-column prop="spec" label="规格" min-width="110" show-overflow-tooltip />
      <el-table-column prop="manufacturer" label="生产厂家" min-width="180" show-overflow-tooltip />
      <el-table-column label="处方分类" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="rxTag(row.rxCategory)">{{ rxLabel(row.rxCategory) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="存储条件" width="90">
        <template #default="{ row }">{{ storageLabel(row.storageCond) }}</template>
      </el-table-column>
      <el-table-column label="首营状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="auditTag(row.firstAuditStatus)">
            {{ auditLabel(row.firstAuditStatus) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'">
            {{ row.status === 'ACTIVE' ? '启用' : '已停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="190" fixed="right">
        <template #default="{ row }">
          <el-button v-permission="'product:update'" link type="primary" @click="openDialog(row as Product)">编辑</el-button>
          <el-button
            v-if="row.status === 'INACTIVE'"
            v-permission="'product:update'"
            link
            type="success"
            :loading="isActing(row.id!, 'enable')"
            :disabled="isBlocked() && !isActing(row.id!, 'enable')"
            @click="handleEnable(row as Product)"
          >
            启用
          </el-button>
          <el-button
            v-permission="'product:delete'"
            link
            type="danger"
            :loading="isActing(row.id!, 'delete')"
            :disabled="isBlocked() && !isActing(row.id!, 'delete')"
            @click="handleDelete(row as Product)"
          >
            删除
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
      :title="form.id ? '编辑商品' : '新增商品'"
      width="720px"
      :close-on-click-modal="false"
      :close-on-press-escape="false"
      destroy-on-close
    >
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="100px">
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="商品编码" prop="code">
              <el-input v-model="form.code" placeholder="如 YP-10001" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="通用名" prop="genericName">
              <el-input v-model="form.genericName" placeholder="药品通用名称" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="商品名" prop="tradeName">
              <el-input v-model="form.tradeName" placeholder="商品名 / 品牌名" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="批准文号" prop="approvalNo">
              <el-input v-model="form.approvalNo" placeholder="国药准字…" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="剂型" prop="dosageForm">
              <el-input v-model="form.dosageForm" placeholder="如 胶囊剂 / 片剂" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="规格" prop="spec">
              <el-input v-model="form.spec" placeholder="如 0.25g*24粒" />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="生产厂家" prop="manufacturer">
              <el-input v-model="form.manufacturer" placeholder="生产企业全称" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="处方分类" prop="rxCategory">
              <el-select v-model="form.rxCategory" placeholder="请选择" style="width: 100%">
                <el-option label="处方药 (RX)" value="RX" />
                <el-option label="非处方药 (OTC)" value="OTC" />
                <el-option label="特殊管理药品" value="SPECIAL" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="存储条件" prop="storageCond">
              <el-select v-model="form.storageCond" placeholder="请选择" style="width: 100%">
                <el-option label="常温" value="ROOM_TEMP" />
                <el-option label="阴凉" value="COOL" />
                <el-option label="冷藏" value="COLD" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="首营状态" prop="firstAuditStatus">
              <el-select v-model="form.firstAuditStatus" placeholder="请选择" style="width: 100%">
                <el-option label="待审核" value="PENDING" />
                <el-option label="已通过" value="APPROVED" />
                <el-option label="已驳回" value="REJECTED" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="状态" prop="status">
              <el-select v-model="form.status" placeholder="请选择" style="width: 100%">
                <el-option label="启用" value="ACTIVE" />
                <el-option label="停用" value="INACTIVE" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>

    <ImportDrawer
      v-model:visible="importVisible"
      resource-type="products"
      resource-label="商品"
      @success="load"
    />
  </el-card>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules, type TableInstance } from 'element-plus'
import { Search, Plus } from '@element-plus/icons-vue'
import { listProducts, createProduct, updateProduct, deleteProduct } from '@/api/product'
import BatchToolbar from '@/components/BatchToolbar.vue'
import ImportDrawer from '@/components/ImportDrawer.vue'
import { useBatchOperation } from '@/composables/useBatchOperation'
import { useRowAction } from '@/composables/useRowAction'
import type { Product } from '@/types'

const { isActing, isBlocked, run } = useRowAction()

const list = ref<Product[]>([])
const total = ref(0)
const loading = ref(false)
const tableRef = ref<TableInstance>()
const importVisible = ref(false)
// 显示停用（含已软删除）记录，用于恢复
const showInactive = ref(false)

const query = reactive({
  keyword: '',
  page: 1,
  size: 20
})

const dialogVisible = ref(false)
const saving = ref(false)
const formRef = ref<FormInstance>()

const emptyForm = (): Product => ({
  code: '',
  genericName: '',
  tradeName: '',
  approvalNo: '',
  dosageForm: '',
  spec: '',
  manufacturer: '',
  rxCategory: 'RX',
  storageCond: 'ROOM_TEMP',
  firstAuditStatus: 'PENDING',
  status: 'ACTIVE'
})

const form = reactive<Product>(emptyForm())

const formRules: FormRules = {
  code: [{ required: true, message: '请输入商品编码', trigger: 'blur' }],
  genericName: [{ required: true, message: '请输入通用名', trigger: 'blur' }],
  manufacturer: [{ required: true, message: '请输入生产厂家', trigger: 'blur' }]
}

// 枚举展示
function rxLabel(v: string) {
  return { RX: '处方药', OTC: '非处方药', SPECIAL: '特殊管理' }[v] || v || '-'
}
function rxTag(v: string) {
  return { RX: 'danger', OTC: 'success', SPECIAL: 'warning' }[v] as 'danger' | 'success' | 'warning' | 'info' || 'info'
}
function storageLabel(v: string) {
  return { ROOM_TEMP: '常温', COOL: '阴凉', COLD: '冷藏' }[v] || v || '-'
}
function auditLabel(v: string) {
  return { PENDING: '待审核', APPROVED: '已通过', REJECTED: '已驳回' }[v] || v || '-'
}
function auditTag(v: string) {
  return { PENDING: 'warning', APPROVED: 'success', REJECTED: 'danger' }[v] as 'warning' | 'success' | 'danger' | 'info' || 'info'
}

async function load() {
  loading.value = true
  try {
    const status = showInactive.value ? 'ALL' : 'ACTIVE'
    const data = await listProducts(query.keyword, query.page, query.size, status)
    list.value = data.records
    total.value = data.total
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.page = 1
  load()
}

function openDialog(row?: Product) {
  Object.assign(form, row ? { ...row } : emptyForm())
  dialogVisible.value = true
}

async function handleSave() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    saving.value = true
    try {
      if (form.id) {
        await updateProduct(form.id, form)
        ElMessage.success('修改成功')
      } else {
        await createProduct(form)
        ElMessage.success('新增成功')
      }
      dialogVisible.value = false
      load()
    } finally {
      saving.value = false
    }
  })
}

// 启用（恢复）：软删除/停用后 status=INACTIVE，通过 PUT 置回 ACTIVE
async function handleEnable(row: Product) {
  await run(
    row.id!,
    async () => {
      try {
        await updateProduct(row.id!, { ...row, status: 'ACTIVE' })
      } catch {
        return
      }
      ElMessage.success('已启用')
      await load()
    },
    'enable'
  )
}

async function handleDelete(row: Product) {  try {
    await ElMessageBox.confirm(
      `确认删除商品「${row.genericName}」吗？删除后不可在列表查看。`,
      '提示',
      { type: 'warning' }
    )
  } catch {
    return
  }
  await run(
    row.id!,
    async () => {
      try {
        await deleteProduct(row.id!)
      } catch {
        // 删除失败（如已被引用返回 400）的提示由 request 拦截器统一展示后端 message
        return
      }
      ElMessage.success('删除成功')
      await load()
    },
    'delete'
  )
}

// ===== 批量操作 =====
const { selectedCount, batchLoading, handleSelectionChange, handleBatchAction } = useBatchOperation({
  resource: 'products',
  label: '商品',
  reload: load,
  clearSelection: () => tableRef.value?.clearSelection()
})

onMounted(load)
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

.pagination {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
</style>
