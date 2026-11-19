<template>
  <el-card shadow="never" class="page-card">
    <div class="toolbar">
      <div class="toolbar-left">
        <el-input
          v-model="query.keyword"
          placeholder="搜索客户名称 / 许可证号"
          clearable
          style="width: 320px"
          :prefix-icon="Search"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />
        <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
        <el-switch v-model="showInactive" active-text="显示停用" @change="handleSearch" />
      </div>
      <el-button v-permission="'customer:create'" type="primary" :icon="Plus" @click="openDialog()">新增客户</el-button>
    </div>

    <BatchToolbar
      :selected-count="selectedCount"
      resource-type="customers"
      resource-label="客户"
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
      <el-table-column prop="name" label="客户名称" min-width="220" show-overflow-tooltip fixed />
      <el-table-column prop="licenseNo" label="许可证号" min-width="180" show-overflow-tooltip />
      <el-table-column label="许可证有效期" width="120">
        <template #default="{ row }">
          <span :style="{ color: isExpired(row.licenseExpire) ? '#f56c6c' : '' }">
            {{ row.licenseExpire || '-' }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="信用等级" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="creditTag(row.creditLevel)">{{ row.creditLevel || '-' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="信用额度" width="120" align="right">
        <template #default="{ row }">{{ formatMoney(row.creditLimit) }}</template>
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
          <el-button v-permission="'customer:update'" link type="primary" @click="openDialog(row as Customer)">编辑</el-button>
          <el-button
            v-if="row.status === 'INACTIVE'"
            v-permission="'customer:update'"
            link
            type="success"
            :loading="isActing(row.id!, 'enable')"
            :disabled="isBlocked() && !isActing(row.id!, 'enable')"
            @click="handleEnable(row as Customer)"
          >
            启用
          </el-button>
          <el-button
            v-permission="'customer:delete'"
            link
            type="danger"
            :loading="isActing(row.id!, 'delete')"
            :disabled="isBlocked() && !isActing(row.id!, 'delete')"
            @click="handleDelete(row as Customer)"
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
      :title="form.id ? '编辑客户' : '新增客户'"
      width="640px"
      :close-on-click-modal="false"
      :close-on-press-escape="false"
      destroy-on-close
    >
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="120px">
        <el-form-item label="客户名称" prop="name">
          <el-input v-model="form.name" placeholder="客户全称" />
        </el-form-item>
        <el-form-item label="许可证号">
          <el-input v-model="form.licenseNo" placeholder="药品经营许可证号" />
        </el-form-item>
        <el-form-item label="许可证有效期">
          <el-date-picker
            v-model="form.licenseExpire"
            type="date"
            value-format="YYYY-MM-DD"
            :disabled-date="disabledPastDate"
            placeholder="选择日期"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="信用等级">
          <el-select v-model="form.creditLevel" style="width: 100%">
            <el-option label="A（优质）" value="A" />
            <el-option label="B（一般）" value="B" />
            <el-option label="C（谨慎）" value="C" />
          </el-select>
        </el-form-item>
        <el-form-item label="信用额度(元)">
          <el-input-number v-model="form.creditLimit" :min="0" :precision="2" style="width: 100%" />
        </el-form-item>
        <el-form-item label="首营状态">
          <el-select v-model="form.firstAuditStatus" style="width: 100%">
            <el-option label="待审核" value="PENDING" />
            <el-option label="已通过" value="APPROVED" />
            <el-option label="已驳回" value="REJECTED" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status" style="width: 100%">
            <el-option label="启用" value="ACTIVE" />
            <el-option label="停用" value="INACTIVE" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>

    <ImportDrawer
      v-model:visible="importVisible"
      resource-type="customers"
      resource-label="客户"
      @success="load"
    />
  </el-card>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules, type TableInstance } from 'element-plus'
import { Search, Plus } from '@element-plus/icons-vue'
import { listCustomers, createCustomer, updateCustomer, deleteCustomer } from '@/api/customer'
import BatchToolbar from '@/components/BatchToolbar.vue'
import ImportDrawer from '@/components/ImportDrawer.vue'
import { useBatchOperation } from '@/composables/useBatchOperation'
import { useRowAction } from '@/composables/useRowAction'
import { formatMoney, disabledPastDate } from '@/utils/format'
import type { Customer } from '@/types'

const { isActing, isBlocked, run } = useRowAction()

const list = ref<Customer[]>([])
const total = ref(0)
const loading = ref(false)
const tableRef = ref<TableInstance>()
const importVisible = ref(false)
// 显示停用（含已软删除）记录，用于恢复
const showInactive = ref(false)

const query = reactive({ keyword: '', page: 1, size: 20 })

const dialogVisible = ref(false)
const saving = ref(false)
const formRef = ref<FormInstance>()

const emptyForm = (): Customer => ({
  name: '',
  licenseNo: '',
  licenseExpire: '',
  creditLevel: 'B',
  creditLimit: 0,
  firstAuditStatus: 'PENDING',
  status: 'ACTIVE'
})
const form = reactive<Customer>(emptyForm())

const formRules: FormRules = {
  name: [{ required: true, message: '请输入客户名称', trigger: 'blur' }]
}

function auditLabel(v: string) {
  return { PENDING: '待审核', APPROVED: '已通过', REJECTED: '已驳回' }[v] || v || '-'
}
function auditTag(v: string) {
  return { PENDING: 'warning', APPROVED: 'success', REJECTED: 'danger' }[v] as 'warning' | 'success' | 'danger' | 'info' || 'info'
}
function creditTag(v: string) {
  return { A: 'success', B: 'primary', C: 'warning' }[v] as 'success' | 'primary' | 'warning' | 'info' || 'info'
}
function isExpired(dateStr: string) {
  if (!dateStr) return false
  return dateStr < new Date().toISOString().slice(0, 10)
}

async function load() {
  loading.value = true
  try {
    const status = showInactive.value ? 'ALL' : 'ACTIVE'
    const data = await listCustomers(query.keyword, query.page, query.size, status)
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

function openDialog(row?: Customer) {
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
        await updateCustomer(form.id, form)
        ElMessage.success('修改成功')
      } else {
        await createCustomer(form)
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
async function handleEnable(row: Customer) {
  await run(
    row.id!,
    async () => {
      try {
        await updateCustomer(row.id!, { ...row, status: 'ACTIVE' })
      } catch {
        return
      }
      ElMessage.success('已启用')
      await load()
    },
    'enable'
  )
}

async function handleDelete(row: Customer) {
  try {
    await ElMessageBox.confirm(
      `确认删除客户「${row.name}」吗？删除后不可在列表查看。`,
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
        await deleteCustomer(row.id!)
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
  resource: 'customers',
  label: '客户',
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
