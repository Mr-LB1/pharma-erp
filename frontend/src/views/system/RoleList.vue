<template>
  <el-card shadow="never" class="page-card">
    <div class="toolbar">
      <div class="toolbar-left">
        <el-input
          v-model="keyword"
          placeholder="搜索角色编码 / 名称"
          clearable
          style="width: 260px"
          :prefix-icon="Search"
        />
      </div>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
    </div>

    <el-table :data="filteredRoles" v-loading="loading" border stripe>
      <el-table-column prop="code" label="角色编码" width="180" show-overflow-tooltip />
      <el-table-column prop="name" label="角色名称" min-width="180" show-overflow-tooltip />
      <el-table-column label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <el-button
            v-permission="'system:role:config'"
            link
            type="primary"
            :icon="Setting"
            @click="openConfig(row as SysRole)"
          >
            配置权限
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-empty v-if="!loading && filteredRoles.length === 0" description="暂无角色数据" />

    <!-- 权限配置 -->
    <el-drawer
      v-model="drawerVisible"
      size="760px"
      :close-on-click-modal="false"
      :close-on-press-escape="false"
      destroy-on-close
    >
      <template #header>
        <div class="drawer-head">
          <span class="drawer-title">配置权限</span>
          <div class="drawer-sub" v-if="currentRole">
            <span class="label">当前角色：</span>
            <el-tag type="primary">{{ currentRole.name }}</el-tag>
            <el-tag type="info" class="code-tag">{{ currentRole.code }}</el-tag>
          </div>
        </div>
      </template>

      <div class="panel-toolbar">
        <span class="selected-count">
          已选 <b>{{ selected.length }}</b> / {{ permissions.length }} 项
        </span>
        <div class="panel-actions">
          <el-button size="small" @click="selectAll">全选</el-button>
          <el-button size="small" @click="clearAll">清空</el-button>
        </div>
      </div>

      <el-alert
        v-if="isAdmin"
        type="warning"
        :closable="false"
        show-icon
        class="admin-alert"
        title="ADMIN 为系统内置管理员角色"
        description="「角色权限配置」权限已锁定不可取消，防止误操作导致系统无法再授权。"
      />

      <div v-loading="permLoading" class="groups">
        <div v-for="group in groups" :key="group.prefix" class="group">
          <div class="group-head">
            <el-checkbox
              :model-value="isGroupChecked(group)"
              :indeterminate="isGroupIndeterminate(group)"
              @update:model-value="(val: unknown) => toggleGroup(group, val === true)"
            >
              {{ group.title }}
            </el-checkbox>
            <span class="group-count">{{ countInGroup(group) }}/{{ group.items.length }}</span>
          </div>
          <el-checkbox-group
            :model-value="valueOfGroup(group)"
            class="group-body"
            @update:model-value="(val: unknown) => setGroupValue(group, val as string[])"
          >
            <el-checkbox
              v-for="p in group.items"
              :key="p.code"
              :value="p.code"
              :disabled="isLocked(p.code)"
              :title="p.code"
            >
              {{ p.name }}
            </el-checkbox>
          </el-checkbox-group>
        </div>
        <el-empty v-if="!permLoading && permissions.length === 0" description="暂无可配置权限" />
      </div>

      <template #footer>
        <el-button @click="drawerVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </el-drawer>
  </el-card>
</template>

<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh, Setting } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import {
  listRoles,
  listPermissions,
  getRolePermissions,
  updateRolePermissions
} from '@/api/system'
import type { SysRole, SysPermission } from '@/types'

/** 权限码前缀 → 中文分组名（顺序即展示顺序，未列出的前缀归到「其他」） */
const GROUP_META: Array<[string, string]> = [
  ['product', '商品管理'],
  ['supplier', '供应商管理'],
  ['customer', '客户管理'],
  ['warehouse', '仓库管理'],
  ['base', '门店与基础资料'],
  ['purchase', '采购管理'],
  ['sales', '销售管理'],
  ['inventory', '库存管理'],
  ['finance', '财务中心'],
  ['report', '报表中心'],
  ['trace', '追溯查询'],
  ['quality', '质管中心'],
  ['approval', '审批中心'],
  ['system', '系统管理']
]

/** ADMIN 角色的关键权限：禁止在前端取消（后端另有 400 兜底） */
const ADMIN_LOCKED_CODE = 'system:role:config'

const authStore = useAuthStore()

const roles = ref<SysRole[]>([])
const keyword = ref('')
const loading = ref(false)

const drawerVisible = ref(false)
const currentRole = ref<SysRole | null>(null)
const permissions = ref<SysPermission[]>([])
const permLoading = ref(false)
const saving = ref(false)
const selected = ref<string[]>([])
/** 打开抽屉时服务端已有的权限集合，用于保存前校验 ADMIN 关键权限未丢失 */
const initialSelected = ref<string[]>([])

const filteredRoles = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return roles.value
  return roles.value.filter(
    (r) => r.code.toLowerCase().includes(kw) || (r.name || '').toLowerCase().includes(kw)
  )
})

const isAdmin = computed(() => currentRole.value?.code === 'ADMIN')

interface PermGroup {
  prefix: string
  title: string
  items: SysPermission[]
}

const groups = computed<PermGroup[]>(() => {
  const map = new Map<string, SysPermission[]>()
  for (const p of permissions.value) {
    const prefix = p.code.split(':')[0] || 'other'
    const items = map.get(prefix)
    if (items) items.push(p)
    else map.set(prefix, [p])
  }
  const result: PermGroup[] = []
  for (const [prefix, title] of GROUP_META) {
    const items = map.get(prefix)
    if (items && items.length > 0) {
      result.push({ prefix, title, items })
      map.delete(prefix)
    }
  }
  for (const [prefix, items] of map) {
    result.push({ prefix, title: '其他', items })
  }
  return result
})

function isLocked(code: string) {
  return isAdmin.value && code === ADMIN_LOCKED_CODE
}

function countInGroup(group: PermGroup) {
  return group.items.filter((p) => selected.value.includes(p.code)).length
}

function isGroupChecked(group: PermGroup) {
  return group.items.length > 0 && countInGroup(group) === group.items.length
}

function isGroupIndeterminate(group: PermGroup) {
  const count = countInGroup(group)
  return count > 0 && count < group.items.length
}

function valueOfGroup(group: PermGroup) {
  return selected.value.filter((code) => group.items.some((p) => p.code === code))
}

/** 组内勾选变化：锁定项保持原状态不被覆盖 */
function setGroupValue(group: PermGroup, codes: string[]) {
  const lockedSelected = group.items
    .filter((p) => isLocked(p.code) && selected.value.includes(p.code))
    .map((p) => p.code)
  const rest = selected.value.filter((code) => !group.items.some((p) => p.code === code))
  selected.value = Array.from(new Set([...rest, ...codes, ...lockedSelected]))
}

function toggleGroup(group: PermGroup, checked: boolean) {
  setGroupValue(
    group,
    checked ? group.items.map((p) => p.code) : []
  )
}

function selectAll() {
  selected.value = permissions.value.map((p) => p.code)
}

function clearAll() {
  selected.value = permissions.value.filter((p) => isLocked(p.code)).map((p) => p.code)
}

async function load() {
  loading.value = true
  try {
    roles.value = await listRoles(false)
  } finally {
    loading.value = false
  }
}

async function openConfig(row: SysRole) {
  if (!row.id) {
    ElMessage.warning('角色缺少 ID，无法配置权限')
    return
  }
  currentRole.value = row
  drawerVisible.value = true
  permLoading.value = true
  selected.value = []
  initialSelected.value = []
  try {
    const [all, owned] = await Promise.all([listPermissions(), getRolePermissions(row.id)])
    permissions.value = Array.isArray(all) ? all : []
    // 只保留后端目录中真实存在的权限码，避免脏数据被原样回传
    const valid = new Set(permissions.value.map((p) => p.code))
    selected.value = owned.filter((code) => valid.has(code))
    initialSelected.value = [...selected.value]
  } catch {
    // 错误提示由请求拦截器统一展示
    drawerVisible.value = false
  } finally {
    permLoading.value = false
  }
}

async function handleSave() {
  if (!currentRole.value?.id) return
  const payload = [...selected.value]

  // 前端二次兜底：ADMIN 原本拥有的关键权限不允许被清空
  if (
    isAdmin.value &&
    initialSelected.value.includes(ADMIN_LOCKED_CODE) &&
    !payload.includes(ADMIN_LOCKED_CODE)
  ) {
    ElMessage.warning('ADMIN 角色的「角色权限配置」权限不可移除')
    return
  }

  saving.value = true
  try {
    // PUT 为全量覆盖：空数组等于清空该角色全部权限，非 ADMIN 角色需二次确认
    if (payload.length === 0) {
      try {
        await ElMessageBox.confirm(
          `保存后角色「${currentRole.value.code}」将失去全部权限，其下用户将无法访问任何业务菜单。确认继续？`,
          '确认清空权限',
          { type: 'warning', confirmButtonText: '继续保存', cancelButtonText: '取消' }
        )
      } catch {
        return
      }
    }
    // 以后端返回的最终权限码为准（后端会去重/去空白，可能与本地位序不同）
    const updated = await updateRolePermissions(currentRole.value.id, payload)
    selected.value = updated
    initialSelected.value = [...updated]
    // 角色权限可能影响当前登录账号自身，实时拉一次 /me 刷新权限码；失败不阻断
    try {
      await authStore.fetchMe()
    } catch {
      // 忽略：下次刷新页面会自动纠正
    }
    ElMessage.success('权限保存成功')
    drawerVisible.value = false
    load()
  } catch {
    // 后端校验失败（如 ADMIN 关键权限）由拦截器展示 message，抽屉保持打开便于调整
  } finally {
    saving.value = false
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
  gap: 12px;
}

.drawer-head {
  display: flex;
  align-items: center;
  gap: 16px;
}

.drawer-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.drawer-sub {
  display: flex;
  align-items: center;
  font-size: 13px;
  color: var(--el-text-color-regular);
}

.drawer-sub .label {
  margin-right: 6px;
}

.code-tag {
  margin-left: 6px;
}

.panel-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.selected-count {
  font-size: 13px;
  color: var(--el-text-color-regular);
}

.panel-actions {
  display: flex;
  gap: 8px;
}

.admin-alert {
  margin-top: 12px;
}

.groups {
  margin-top: 16px;
  min-height: 120px;
}

.group {
  padding: 12px 16px;
  margin-bottom: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-fill-color-blank);
}

.group-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.group-count {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.group-body {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 8px;
}
</style>
