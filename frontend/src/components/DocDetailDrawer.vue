<template>
  <el-drawer
    :model-value="modelValue"
    :title="title"
    size="760px"
    destroy-on-close
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
  >
    <div v-loading="!!loading" class="doc-detail">
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item v-for="(f, idx) in header" :key="idx" :label="f.label">
          <span :class="{ 'doc-detail__danger': f.danger }">{{ f.value ?? '-' }}</span>
        </el-descriptions-item>
      </el-descriptions>

      <div class="doc-detail__title">单据明细</div>
      <el-table :data="items" size="small" border empty-text="暂无明细">
        <el-table-column
          v-for="col in columns"
          :key="col.prop"
          :prop="col.prop"
          :label="col.label"
          :width="col.width"
          :min-width="col.minWidth"
          :align="col.align || 'left'"
          show-overflow-tooltip
        >
          <template v-if="col.formatter || col.type" #default="{ row }">
            {{ cellText(col, row as Record<string, unknown>) }}
          </template>
        </el-table-column>
      </el-table>

      <div v-if="remark" class="doc-detail__remark">备注/原因：{{ remark }}</div>
    </div>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">关闭</el-button>
      <slot name="actions" />
    </template>
  </el-drawer>
</template>

<script setup lang="ts">
import { formatMoney, formatQty } from '@/utils/format'
import type { DocDetailColumn, DocDetailField } from './docDetail'

defineProps<{
  modelValue: boolean
  title: string
  loading?: boolean
  header: DocDetailField[]
  items: Record<string, unknown>[]
  columns: DocDetailColumn[]
  /** 单据备注 / 作废原因 */
  remark?: string | null
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
}>()

function cellText(col: DocDetailColumn, row: Record<string, unknown>) {
  if (col.formatter) return col.formatter(row)
  const value = row[col.prop]
  if (col.type === 'money') return formatMoney(value as number)
  if (col.type === 'qty') return formatQty(value as number)
  return value == null || value === '' ? '-' : String(value)
}
</script>

<style scoped>
.doc-detail {
  min-height: 200px;
}

.doc-detail__title {
  margin: 16px 0 8px;
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.doc-detail__remark {
  margin-top: 12px;
  font-size: 13px;
  color: var(--el-text-color-regular);
  word-break: break-all;
}

.doc-detail__danger {
  color: var(--el-color-danger);
}
</style>
