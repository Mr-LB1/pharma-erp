<template>
  <el-card shadow="never" class="page-card">
    <div class="toolbar">
      <div class="toolbar-left">
        <span class="toolbar-label">预警天数</span>
        <el-input-number v-model="days" :min="1" :max="365" :step="10" style="width: 140px" />
        <el-button type="primary" :icon="Search" @click="load">查询</el-button>
      </div>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
    </div>

    <el-table :data="list" v-loading="loading" border stripe>
      <el-table-column label="类型" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="row.type === 'supplier' ? 'primary' : 'success'">
            {{ row.type === 'supplier' ? '供应商' : '客户' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="name" label="名称" min-width="200" show-overflow-tooltip />
      <el-table-column prop="licenseNo" label="许可证号" width="170" show-overflow-tooltip />
      <el-table-column prop="licenseExpire" label="证照效期" width="120" />
      <el-table-column label="剩余天数" width="100" align="center">
        <template #default="{ row }">{{ daysLeft(row.licenseExpire) ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="120" align="center">
        <template #default="{ row }">
          <el-tag size="small" :type="expireTag(row.licenseExpire)">
            {{ expireText(row.licenseExpire) }}
          </el-tag>
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { Search, Refresh } from '@element-plus/icons-vue'
import { listExpiringLicenses } from '@/api/quality'
import type { ExpiringLicense } from '@/types'

const list = ref<ExpiringLicense[]>([])
const loading = ref(false)
const days = ref(90)

function daysLeft(expireDate?: string): number | null {
  if (!expireDate) return null
  const diff = new Date(expireDate).getTime() - Date.now()
  return Math.ceil(diff / (1000 * 60 * 60 * 24))
}

function expireText(expireDate?: string) {
  const d = daysLeft(expireDate)
  if (d == null) return '-'
  if (d < 0) return '已过期'
  return '即将到期'
}

function expireTag(expireDate?: string) {
  const d = daysLeft(expireDate)
  if (d == null) return 'info'
  if (d < 0) return 'danger'
  return 'warning'
}

async function load() {
  loading.value = true
  try {
    list.value = await listExpiringLicenses(days.value)
  } finally {
    loading.value = false
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

.toolbar-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.toolbar-label {
  font-size: 14px;
  color: #606266;
}
</style>
