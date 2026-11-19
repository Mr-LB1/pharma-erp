<template>
  <el-drawer
    v-model="drawerVisible"
    :title="`导入${resourceLabel}`"
    size="780px"
    :close-on-click-modal="false"
    :close-on-press-escape="false"
    destroy-on-close
    @closed="handleClosed"
  >
    <el-steps :active="step" simple finish-status="success" class="import-steps">
      <el-step title="下载模板" />
      <el-step title="上传并预览" />
      <el-step title="重复策略" />
      <el-step title="执行与结果" />
    </el-steps>

    <!-- ① 下载模板 -->
    <section v-show="step === 0" class="import-step">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="请先下载标准模板，按模板表头填写后上传"
      >
        <template #default>
          <p class="import-alert-text">
            1. 模板为 .xlsx 格式，请勿修改或删除表头行；<br />
            2. 标注为必填的列不可为空，日期列请使用 YYYY-MM-DD 格式；<br />
            3. 单次导入不超过 5000 行，文件不超过 5MB。
          </p>
        </template>
      </el-alert>
      <div class="import-step__body">
        <el-button type="primary" :icon="Download" :loading="downloading" @click="handleDownloadTemplate">
          下载导入模板
        </el-button>
        <span class="import-hint">下载后可离线填写，再回到本页面上传</span>
      </div>
    </section>

    <!-- ② 上传 + 预览 -->
    <section v-show="step === 1" class="import-step">
      <el-upload
        drag
        :auto-upload="false"
        :show-file-list="false"
        accept=".xlsx,.xls"
        :on-change="handleFileChange"
      >
        <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
        <div class="el-upload__text">将 Excel 拖到此处，或<em>点击选择文件</em></div>
        <template #tip>
          <div class="el-upload__tip">仅支持 .xlsx / .xls，单个文件不超过 5MB</div>
        </template>
      </el-upload>

      <div v-if="fileName" class="import-file">
        <el-icon><Document /></el-icon>
        <span class="import-file__name">{{ fileName }}</span>
        <el-button link type="primary" @click="resetFile">重新选择</el-button>
      </div>

      <div v-loading="previewing" class="import-preview">
        <template v-if="preview">
          <div class="import-summary">
            <el-tag type="info">共 {{ preview.total }} 行</el-tag>
            <el-tag type="success">校验通过 {{ preview.validCount }} 行</el-tag>
            <el-tag type="danger">校验失败 {{ preview.invalidCount }} 行</el-tag>
          </div>

          <el-alert
            v-if="preview.invalidCount > 0"
            type="warning"
            :closable="false"
            show-icon
            :title="`有 ${preview.invalidCount} 行数据校验未通过（表中红色行），请修正后重新上传`"
            class="import-alert"
          />

          <el-table
            v-if="previewRows.length"
            :data="previewRows"
            border
            size="small"
            max-height="320"
            :row-class-name="previewRowClass"
            class="import-table"
          >
            <el-table-column prop="__row" label="Excel 行" width="86" />
            <el-table-column
              v-for="col in previewColumns"
              :key="col"
              :prop="col"
              :label="fieldLabel(col)"
              min-width="120"
              show-overflow-tooltip
            />
          </el-table>

          <el-table
            v-if="previewErrors.length"
            :data="previewErrors"
            border
            size="small"
            max-height="200"
            class="import-table"
          >
            <el-table-column prop="row" label="行号" width="80" />
            <el-table-column label="字段" width="150" show-overflow-tooltip>
              <template #default="{ row }">{{ fieldLabel(row.field) }}</template>
            </el-table-column>
            <el-table-column prop="message" label="错误原因" min-width="220" show-overflow-tooltip />
          </el-table>
        </template>
        <el-empty v-else-if="!previewing" description="尚未上传文件或尚未完成预览" :image-size="80" />
      </div>
    </section>

    <!-- ③ 重复数据处理策略 -->
    <section v-show="step === 2" class="import-step">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="请选择与已有数据重复时的处理方式"
        class="import-alert"
      />
      <el-radio-group v-model="strategy" class="import-strategy">
        <el-radio value="SKIP" border class="import-strategy__item">
          <div class="import-strategy__label">跳过重复（SKIP）</div>
          <div class="import-strategy__desc">保留系统中已有数据，重复行计入「跳过」，其余正常导入</div>
        </el-radio>
        <el-radio value="UPDATE" border class="import-strategy__item">
          <div class="import-strategy__label">覆盖更新（UPDATE）</div>
          <div class="import-strategy__desc">用 Excel 中的新值覆盖已有记录，重复行计入「更新」</div>
        </el-radio>
        <el-radio value="ERROR" border class="import-strategy__item">
          <div class="import-strategy__label">遇重复即报错（ERROR）</div>
          <div class="import-strategy__desc">只有全部数据都无重复时才导入，否则整体失败</div>
        </el-radio>
      </el-radio-group>

      <el-alert
        v-if="preview && preview.invalidCount > 0"
        type="warning"
        :closable="false"
        show-icon
        :title="`仍有 ${preview.invalidCount} 行校验未通过，这些行会导入失败并出现在结果明细中`"
        class="import-alert"
      />
    </section>

    <!-- ④ 结果汇总 -->
    <section v-show="step === 3" class="import-step">
      <template v-if="result">
        <el-result
          :icon="result.failedCount > 0 ? 'warning' : 'success'"
          :title="result.failedCount > 0 ? '导入完成，部分数据失败' : '导入成功'"
          :sub-title="`共处理 ${result.total} 行`"
        />
        <div class="import-summary">
          <el-tag type="success">成功 {{ result.successCount }} 行</el-tag>
          <el-tag type="info">新增 {{ result.insertedCount }} 行</el-tag>
          <el-tag type="warning">更新 {{ result.updatedCount }} 行</el-tag>
          <el-tag type="info">跳过 {{ result.skippedCount }} 行</el-tag>
          <el-tag type="danger">失败 {{ result.failedCount }} 行</el-tag>
        </div>

        <el-table
          v-if="resultErrors.length"
          :data="resultErrors"
          border
          size="small"
          max-height="260"
          class="import-table"
        >
          <el-table-column prop="row" label="行号" width="80" />
          <el-table-column label="字段" width="150" show-overflow-tooltip>
            <template #default="{ row }">{{ fieldLabel(row.field) }}</template>
          </el-table-column>
          <el-table-column prop="message" label="失败原因" min-width="240" show-overflow-tooltip />
        </el-table>
      </template>
    </section>

    <template #footer>
      <div class="import-footer">
        <el-button v-if="step > 0 && step < 3" @click="step--">上一步</el-button>
        <el-button v-if="step === 0" type="primary" @click="step = 1">下一步</el-button>
        <el-button v-else-if="step === 1" type="primary" :disabled="!canPreviewNext" @click="step = 2">
          下一步
        </el-button>
        <el-button v-else-if="step === 2" type="primary" :loading="executing" @click="handleExecute">
          确认导入
        </el-button>
        <template v-else>
          <el-button @click="reset">再导一批</el-button>
          <el-button type="primary" @click="closeDrawer">完成</el-button>
        </template>
      </div>
    </template>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage, type UploadFile } from 'element-plus'
import { Download, Document, UploadFilled } from '@element-plus/icons-vue'
import { downloadTemplate, importExecute, importPreview } from '@/api/batch'
import type {
  BatchResource,
  DuplicateStrategy,
  ImportErrorItem,
  ImportExecuteResult,
  ImportPreviewResult
} from '@/types'

const props = defineProps<{
  visible: boolean
  resourceType: BatchResource
  resourceLabel?: string
}>()

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  // 导入成功（新增/更新 > 0）时通知父页面刷新列表
  (e: 'success'): void
}>()

const drawerVisible = computed({
  get: () => props.visible,
  set: (value: boolean) => emit('update:visible', value)
})

const resourceLabel = computed(() => props.resourceLabel || '数据')

// 导入行字段 → 中文表头（与后端模板表头保持一致）
const FIELD_LABELS: Record<string, string> = {
  code: '编码',
  name: '名称',
  genericName: '通用名',
  tradeName: '商品名',
  spec: '规格',
  manufacturer: '生产厂家',
  rxCategory: '处方分类',
  storageCond: '存储条件',
  firstAuditStatus: '首营状态',
  status: '状态',
  creditCode: '统一社会信用代码',
  licenseNo: '许可证号',
  licenseExpire: '许可证到期日',
  gspCert: 'GSP 证书号',
  settleType: '结算方式',
  creditLevel: '资信等级',
  creditLimit: '授信额度',
  tempType: '温控类型',
  type: '门店类型',
  warehouseId: '关联仓库ID',
  address: '地址',
  contactName: '联系人',
  contactPhone: '联系电话'
}

function fieldLabel(field?: string | null) {
  if (!field) return '-'
  return FIELD_LABELS[field] || field
}
const MAX_SIZE = 5 * 1024 * 1024

const step = ref(0)
const downloading = ref(false)
const file = ref<File | null>(null)
const fileName = ref('')
const previewing = ref(false)
const preview = ref<ImportPreviewResult | null>(null)
const strategy = ref<DuplicateStrategy>('SKIP')
const executing = ref(false)
const result = ref<ImportExecuteResult | null>(null)

// 预览行：{ __row: Excel 行号, __valid: 是否通过校验, ...业务字段 }
type PreviewTableRow = Record<string, unknown> & { __row: number; __valid: boolean }

const previewRows = computed<PreviewTableRow[]>(() =>
  (preview.value?.preview || []).map((item) => ({
    __row: item.row,
    __valid: item.valid,
    ...(item.data || {})
  }))
)
const previewErrors = computed<ImportErrorItem[]>(() => preview.value?.errors || [])
const resultErrors = computed<ImportErrorItem[]>(() => result.value?.errors || [])

// 预览表列：取首行业务字段名（去掉内部标记列）
const previewColumns = computed<string[]>(() => {
  const first = previewRows.value[0]
  if (!first) return []
  return Object.keys(first).filter((key) => key !== '__row' && key !== '__valid')
})

const canPreviewNext = computed(
  () => Boolean(file.value && preview.value && !previewing.value)
)

function previewRowClass({ row }: { row: PreviewTableRow }) {
  return row.__valid === false ? 'import-row-error' : ''
}

async function handleDownloadTemplate() {
  downloading.value = true
  try {
    await downloadTemplate(props.resourceType)
    ElMessage.success('模板已开始下载')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '模板下载失败')
  } finally {
    downloading.value = false
  }
}

function handleFileChange(uploadFile: UploadFile) {
  const raw = uploadFile.raw
  if (!raw) return
  if (raw.size > MAX_SIZE) {
    ElMessage.warning('文件不能超过 5MB')
    return
  }
  if (!/\.(xlsx|xls)$/i.test(raw.name)) {
    ElMessage.warning('仅支持 .xlsx / .xls 文件')
    return
  }
  file.value = raw
  fileName.value = raw.name
  doPreview()
}

async function doPreview() {
  if (!file.value) return
  previewing.value = true
  preview.value = null
  try {
    preview.value = await importPreview(props.resourceType, file.value)
    if (preview.value.invalidCount > 0) {
      ElMessage.warning(`预览完成，其中 ${preview.value.invalidCount} 行校验未通过`)
    }
  } catch {
    // 错误提示由 request 拦截器统一处理
  } finally {
    previewing.value = false
  }
}

function resetFile() {
  file.value = null
  fileName.value = ''
  preview.value = null
}

async function handleExecute() {
  if (!file.value) {
    ElMessage.warning('请先上传文件')
    return
  }
  executing.value = true
  try {
    const res = await importExecute(props.resourceType, file.value, strategy.value)
    result.value = res
    step.value = 3
    if ((res.successCount ?? 0) > 0) emit('success')
    if ((res.failedCount ?? 0) > 0) {
      ElMessage.warning(`导入完成：成功 ${res.successCount} 行，失败 ${res.failedCount} 行`)
    } else {
      ElMessage.success(`导入完成：成功 ${res.successCount} 行`)
    }
  } catch {
    // 错误提示由 request 拦截器统一处理
  } finally {
    executing.value = false
  }
}

function reset() {
  step.value = 0
  resetFile()
  strategy.value = 'SKIP'
  result.value = null
}

function closeDrawer() {
  drawerVisible.value = false
}

function handleClosed() {
  reset()
}
</script>

<style scoped>
.import-steps {
  margin-bottom: 20px;
}

.import-step {
  min-height: 320px;
}

.import-step__body {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 16px;
}

.import-alert {
  margin-top: 12px;
}

.import-alert-text {
  margin: 6px 0 0;
  line-height: 1.7;
  font-size: 12px;
}

.import-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.import-file {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
  padding: 8px 12px;
  background: var(--el-fill-color-lighter);
  border-radius: 6px;
  font-size: 13px;
}

.import-file__name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.import-preview {
  margin-top: 16px;
  min-height: 80px;
}

.import-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}

.import-table {
  margin-top: 12px;
  width: 100%;
}

.import-strategy {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-top: 16px;
  width: 100%;
}

.import-strategy__item {
  height: auto;
  margin-right: 0;
  padding: 10px 14px;
  width: 100%;
}

.import-strategy__label {
  font-size: 13px;
  font-weight: 600;
  line-height: 20px;
}

.import-strategy__desc {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 18px;
}

.import-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

/* 错误行标红（el-table 行样式需穿透 scoped） */
.import-table :deep(.import-row-error) {
  --el-table-tr-bg-color: var(--el-color-danger-light-9);
  --el-table-row-hover-bg-color: var(--el-color-danger-light-8);
  color: var(--el-color-danger);
}
</style>
