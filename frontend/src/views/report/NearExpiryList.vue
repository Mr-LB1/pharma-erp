<template>
  <el-card shadow="never" class="page-card">
    <div class="toolbar">
      <div class="toolbar-left">
        <span class="toolbar-label">预警天数</span>
        <el-input-number v-model="query.days" :min="1" :max="365" :step="5" style="width: 140px" />
        <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
      </div>
      <div class="toolbar-right">
        <el-button :icon="Refresh" @click="load">刷新</el-button>
        <el-button
          v-permission="'report:view'"
          type="success"
          :icon="Download"
          :loading="exporting"
          @click="handleExport"
        >
          导出 Excel
        </el-button>
      </div>
    </div>

    <el-table :data="list" v-loading="loading" border stripe>
      <el-table-column prop="productCode" label="商品编码" min-width="140" show-overflow-tooltip fixed />
      <el-table-column prop="genericName" label="通用名" min-width="180" show-overflow-tooltip />
      <el-table-column prop="tradeName" label="商品名" min-width="160" show-overflow-tooltip />
      <el-table-column prop="spec" label="规格" min-width="140" show-overflow-tooltip />
      <el-table-column prop="manufacturer" label="生产厂家" min-width="200" show-overflow-tooltip />
      <el-table-column prop="batchNo" label="批号" width="140" />
      <el-table-column prop="produceDate" label="生产日期" width="120" />
      <el-table-column prop="expireDate" label="效期" width="120" />
      <el-table-column prop="quantity" label="数量" width="100" align="right" />
      <el-table-column label="剩余天数" width="110" align="center">
        <template #default="{ row }">
          <span :class="row.daysLeft != null && row.daysLeft < 0 ? 'overdue' : ''">
            {{ row.daysLeft ?? '-' }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="110" align="center">
        <template #default="{ row }">
          <el-tag size="small" :type="expireTag(row.daysLeft)">{{ expireText(row.daysLeft) }}</el-tag>
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
  </el-card>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Search, Refresh, Download } from '@element-plus/icons-vue'
import { nearExpiryPage, exportNearExpiry } from '@/api/report'
import { useQuerySync } from '@/composables/useQuerySync'
import type { NearExpiryItem } from '@/types'

const list = ref<NearExpiryItem[]>([])
const total = ref(0)
const loading = ref(false)
const exporting = ref(false)

const query = reactive({ days: 30, page: 1, size: 20 })
// 筛选条件同步到地址栏（刷新/分享链接不丢筛选）
useQuerySync(query, ['days', 'page', 'size'], { numeric: ['days', 'page', 'size'] })

function expireText(daysLeft?: number) {
  if (daysLeft == null) return '-'
  if (daysLeft < 0) return '已过期'
  if (daysLeft === 0) return '今日到期'
  return '即将到期'
}

function expireTag(daysLeft?: number) {
  if (daysLeft == null) return 'info'
  if (daysLeft < 0) return 'danger'
  if (daysLeft === 0) return 'warning'
  return 'warning'
}

async function load() {
  loading.value = true
  try {
    const data = await nearExpiryPage(query.days, query.page, query.size)
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

function handleSizeChange() {
  query.page = 1
  load()
}

function formatDate(d: Date) {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}${m}${day}`
}

async function handleExport() {
  exporting.value = true
  try {
    const blob = await exportNearExpiry(query.days)
    const url = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `近效期清单_${formatDate(new Date())}.xlsx`
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    window.URL.revokeObjectURL(url)
    ElMessage.success('导出成功')
  } catch (e) {
    // 网络/HTTP 错误已由请求拦截器统一提示，这里只补充导出体解析失败的原因
    if (!(e as { isAxiosError?: boolean })?.isAxiosError) {
      ElMessage.error((e as Error)?.message || '导出失败')
    }
  } finally {
    exporting.value = false
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

.toolbar-right {
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

.overdue {
  color: #f56c6c;
  font-weight: 600;
}
</style>
