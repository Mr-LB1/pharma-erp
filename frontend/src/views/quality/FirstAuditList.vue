<template>
  <el-card shadow="never" class="page-card">
    <div class="toolbar">
      <el-radio-group v-model="query.type" @change="handleTypeChange">
        <el-radio-button value="product">商品</el-radio-button>
        <el-radio-button value="supplier">供应商</el-radio-button>
        <el-radio-button value="customer">客户</el-radio-button>
      </el-radio-group>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
    </div>

    <el-table :data="list" v-loading="loading" border stripe>
      <el-table-column prop="name" label="名称" min-width="200" show-overflow-tooltip fixed />
      <el-table-column prop="code" label="编码" min-width="180" show-overflow-tooltip />
      <el-table-column v-if="query.type !== 'product'" prop="licenseExpire" label="证照效期" width="120" />
      <el-table-column label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <el-button
            link
            type="success"
            :loading="isActing(row as FirstAuditPendingItem, 'approve')"
            :disabled="isDisabled(row as FirstAuditPendingItem, 'approve')"
            @click="handleApprove(row as FirstAuditPendingItem)"
          >
            通过
          </el-button>
          <el-button
            link
            type="danger"
            :loading="isActing(row as FirstAuditPendingItem, 'reject')"
            :disabled="isDisabled(row as FirstAuditPendingItem, 'reject')"
            @click="handleReject(row as FirstAuditPendingItem)"
          >
            驳回
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
        @size-change="handleTypeChange"
      />
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { listFirstAuditsPending, firstAuditApprove, firstAuditReject } from '@/api/quality'
import type { FirstAuditType, FirstAuditPendingItem } from '@/types'

const list = ref<FirstAuditPendingItem[]>([])
const total = ref(0)
const loading = ref(false)

// 行级操作状态：防止同一行/其他行连点重复提交
type RowAction = 'approve' | 'reject'
const acting = ref<{ id: number; action: RowAction } | null>(null)

function isActing(row: FirstAuditPendingItem, action: RowAction) {
  return acting.value?.id === row.id && acting.value?.action === action
}

function isDisabled(row: FirstAuditPendingItem, action: RowAction) {
  return acting.value != null && !isActing(row, action)
}

const query = reactive({
  type: 'product' as FirstAuditType,
  page: 1,
  size: 20
})

async function load() {
  loading.value = true
  try {
    const data = await listFirstAuditsPending(query.type, query.page, query.size)
    list.value = data.records
    total.value = data.total
  } finally {
    loading.value = false
  }
}

function handleTypeChange() {
  query.page = 1
  load()
}

async function handleApprove(row: FirstAuditPendingItem) {
  try {
    await ElMessageBox.confirm(`确认通过「${row.name}」的首营审核吗？`, '提示', { type: 'warning' })
  } catch {
    return
  }
  acting.value = { id: row.id, action: 'approve' }
  try {
    await firstAuditApprove(query.type, row.id)
    ElMessage.success('已通过')
    await load()
  } finally {
    acting.value = null
  }
}

async function handleReject(row: FirstAuditPendingItem) {
  let comment = ''
  try {
    const { value } = await ElMessageBox.prompt(
      `确认驳回「${row.name}」的首营审核吗？驳回意见必填。`,
      '驳回首营审核',
      {
        type: 'warning',
        confirmButtonText: '确认驳回',
        cancelButtonText: '取消',
        inputType: 'textarea',
        inputPlaceholder: '请填写驳回意见',
        inputValidator: (v: string) => (v && v.trim() ? true : '驳回必须填写意见')
      }
    )
    comment = (value || '').trim()
  } catch {
    return
  }
  acting.value = { id: row.id, action: 'reject' }
  try {
    await firstAuditReject(query.type, row.id, comment)
    ElMessage.success('已驳回')
    await load()
  } finally {
    acting.value = null
  }
}

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

.pagination {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
</style>
