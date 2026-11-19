<template>
  <div class="batch-preview">
    <div v-if="!canQuery" class="batch-preview__hint">请先选择仓库/商品</div>
    <template v-else>
      <div class="batch-preview__head">
        <span class="batch-preview__title">
          批号分配预览（可售合计 {{ formatQty(preview?.totalSellable) }}）
        </span>
        <el-tag v-if="preview?.blocked" type="danger" size="small" effect="dark">库存不足</el-tag>
        <el-tag v-else-if="preview" type="success" size="small" effect="plain">可满足</el-tag>
      </div>
      <div v-if="preview?.blocked && preview.blockReason" class="batch-preview__block">
        {{ preview.blockReason }}
      </div>
      <el-table
        :data="preview?.batches || []"
        v-loading="loading"
        size="small"
        border
        :row-class-name="rowClassName"
        empty-text="暂无可用批次"
      >
        <el-table-column prop="batchNo" label="批号" min-width="130" show-overflow-tooltip />
        <el-table-column label="可售量" width="110" align="right">
          <template #default="{ row }">{{ formatQty(row.availableQty) }}</template>
        </el-table-column>
        <el-table-column label="本次分配" width="110" align="right">
          <template #default="{ row }">
            <span class="planned-qty">{{ formatQty(row.plannedQty) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="效期" width="120">
          <template #default="{ row }">{{ row.expireDate || '-' }}</template>
        </el-table-column>
        <el-table-column label="剩余天数" width="100" align="right">
          <template #default="{ row }">
            {{ row.daysToExpire === null || row.daysToExpire === undefined ? '-' : row.daysToExpire }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="expireTag(row.expireStatus)">
              {{ expireLabel(row.expireStatus) }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { fefoPreview } from '@/api/inventory'
import { formatQty } from '@/utils/format'
import type { FefoPreview } from '@/types'
import type { TagType } from '@/utils/status'

const props = defineProps<{
  productId?: number
  warehouseId?: number
  quantity: number
}>()

const emit = defineEmits<{
  (e: 'update:preview', value: FefoPreview | null): void
  (e: 'update:loading', value: boolean): void
}>()

const preview = ref<FefoPreview | null>(null)
const loading = ref(false)

const canQuery = computed(
  () => !!props.productId && !!props.warehouseId && Number(props.quantity) > 0
)

let timer: ReturnType<typeof setTimeout> | null = null
// 请求序号：丢弃过期响应，避免快速切换时旧数据覆盖新数据
let seq = 0

function clearPreview() {
  preview.value = null
  emit('update:preview', null)
}

function setLoading(v: boolean) {
  loading.value = v
  emit('update:loading', v)
}

function expireTag(status?: string): TagType {
  if (status === 'SELLABLE') return 'success'
  if (status === 'NEAR_EXPIRY') return 'warning'
  if (status === 'EXPIRED') return 'danger'
  return 'info'
}

const EXPIRE_LABELS: Record<string, string> = {
  SELLABLE: '可售',
  NEAR_EXPIRY: '近效期',
  EXPIRED: '已过期'
}

function expireLabel(status?: string) {
  return EXPIRE_LABELS[status || ''] || status || '-'
}

function rowClassName({ row }: { row: { expireStatus?: string } }) {
  return row.expireStatus === 'EXPIRED' ? 'batch-row--expired' : ''
}

async function reload(): Promise<FefoPreview | null> {
  const current = ++seq
  if (!canQuery.value) {
    clearPreview()
    setLoading(false)
    return null
  }
  setLoading(true)
  try {
    const data = await fefoPreview({
      productId: props.productId,
      warehouseId: props.warehouseId,
      quantity: Number(props.quantity)
    })
    if (current !== seq) return preview.value
    preview.value = data
    emit('update:preview', data)
    return data
  } catch {
    // 预览为辅助信息，失败不打断录入；保留清空状态避免误导
    if (current === seq) clearPreview()
    return null
  } finally {
    if (current === seq) setLoading(false)
  }
}

function schedule() {
  if (timer) clearTimeout(timer)
  timer = setTimeout(() => {
    timer = null
    reload()
  }, 300)
}

watch(
  () => [props.productId, props.warehouseId, props.quantity],
  () => {
    if (!canQuery.value) {
      if (timer) clearTimeout(timer)
      timer = null
      seq++
      clearPreview()
      setLoading(false)
      return
    }
    schedule()
  },
  { immediate: true }
)

onBeforeUnmount(() => {
  if (timer) clearTimeout(timer)
})

defineExpose({ reload })
</script>

<style scoped>
.batch-preview {
  padding: 6px 12px 10px;
}

.batch-preview__hint {
  padding: 8px 0;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.batch-preview__head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.batch-preview__title {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-regular);
}

.batch-preview__block {
  margin-bottom: 8px;
  font-size: 12px;
  color: var(--el-color-danger);
}

.planned-qty {
  font-weight: 600;
}

:deep(.batch-row--expired) {
  color: var(--el-text-color-placeholder);
  background: var(--el-fill-color-lighter);
}
</style>
