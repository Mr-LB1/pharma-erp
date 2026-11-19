<template>
  <el-card shadow="never" class="page-card">
    <div class="toolbar">
      <div class="toolbar-left">
        <span class="toolbar-label">操作审计日志</span>
      </div>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
    </div>

    <el-table :data="list" v-loading="loading" border stripe>
      <el-table-column prop="id" label="ID" width="80" align="right" />
      <el-table-column prop="username" label="操作人" width="120" />
      <el-table-column prop="action" label="动作" min-width="180" show-overflow-tooltip />
      <el-table-column prop="objectType" label="对象类型" width="140" />
      <el-table-column prop="objectId" label="对象ID" width="100" align="right" />
      <el-table-column prop="detail" label="详情" min-width="240" show-overflow-tooltip />
      <el-table-column prop="createdAt" label="时间" width="180" />
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
  </el-card>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { listAuditLogs } from '@/api/system'
import type { AuditLog } from '@/types'

const list = ref<AuditLog[]>([])
const total = ref(0)
const loading = ref(false)

const query = reactive({ page: 1, size: 20 })

async function load() {
  loading.value = true
  try {
    const data = await listAuditLogs(query.page, query.size)
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
