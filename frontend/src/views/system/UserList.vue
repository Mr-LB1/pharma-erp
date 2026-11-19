<template>
  <el-card shadow="never" class="page-card">
    <div class="toolbar">
      <div class="toolbar-left">
        <el-input
          v-model="query.keyword"
          placeholder="搜索用户名 / 姓名"
          clearable
          style="width: 300px"
          :prefix-icon="Search"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />
        <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
      </div>
      <el-button v-permission="'system:user:create'" type="primary" :icon="Plus" @click="openDialog()">
        新增用户
      </el-button>
    </div>

    <el-table :data="list" v-loading="loading" border stripe>
      <el-table-column prop="username" label="用户名" min-width="150" show-overflow-tooltip />
      <el-table-column prop="realName" label="姓名" min-width="120" show-overflow-tooltip />
      <el-table-column label="角色" min-width="220">
        <template #default="{ row }">
          <el-tag
            v-for="r in row.roles || []"
            :key="r"
            size="small"
            class="role-tag"
            type="info"
          >
            {{ roleLabel(r) }}
          </el-tag>
          <span v-if="!row.roles || row.roles.length === 0">-</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'">
            {{ row.status === 'ACTIVE' ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="260" fixed="right">
        <template #default="{ row }">
          <el-button v-permission="'system:user:update'" link type="primary" @click="openDialog(row as SysUser)">
            编辑
          </el-button>
          <el-button
            v-permission="'system:user:update'"
            link
            type="primary"
            @click="openPasswordDialog(row as SysUser)"
          >
            重置密码
          </el-button>
          <el-button
            v-permission="'system:user:update'"
            link
            :type="row.status === 'ACTIVE' ? 'warning' : 'success'"
            :loading="isActing(row.id!, 'toggle')"
            :disabled="isBlocked() && !isActing(row.id!, 'toggle')"
            @click="handleToggle(row as SysUser)"
          >
            {{ row.status === 'ACTIVE' ? '停用' : '启用' }}
          </el-button>
          <el-button
            v-permission="'system:user:delete'"
            link
            type="danger"
            :loading="isActing(row.id!, 'delete')"
            :disabled="isBlocked() && !isActing(row.id!, 'delete')"
            @click="handleDelete(row as SysUser)"
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

    <!-- 新增 / 编辑 -->
    <el-dialog
      v-model="dialogVisible"
      :title="form.id ? '编辑用户' : '新增用户'"
      width="560px"
      :close-on-click-modal="false"
      :close-on-press-escape="false"
      destroy-on-close
    >
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="90px">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" :disabled="!!form.id" placeholder="登录账号" />
        </el-form-item>
        <el-form-item v-if="!form.id" label="初始密码" prop="password">
          <el-input v-model="form.password" type="password" show-password placeholder="至少 6 位" />
        </el-form-item>
        <el-form-item label="姓名" prop="realName">
          <el-input v-model="form.realName" placeholder="真实姓名" />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="form.roles" multiple placeholder="请选择角色" style="width: 100%">
            <el-option
              v-for="r in roleOptions"
              :key="r.code"
              :label="`${roleDisplayName(r.code, r.name)}（${r.code}）`"
              :value="r.code"
            />
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

    <!-- 重置密码 -->
    <el-dialog
      v-model="passwordVisible"
      title="重置密码"
      width="420px"
      :close-on-press-escape="false"
      destroy-on-close
    >
      <el-form label-width="90px">
        <el-form-item label="用户名">
          <span>{{ passwordTarget?.username }}</span>
        </el-form-item>
        <el-form-item label="新密码">
          <el-input v-model="newPassword" type="password" show-password placeholder="至少 6 位" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="passwordVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleResetPassword">确定</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Search, Plus } from '@element-plus/icons-vue'
import {
  listUsers,
  createUser,
  updateUser,
  deleteUser,
  toggleUser,
  resetUserPassword,
  assignUserRoles,
  listRoles
} from '@/api/system'
import type { SysUser, SysRole } from '@/types'
import { useRowAction } from '@/composables/useRowAction'
import { useQuerySync } from '@/composables/useQuerySync'

const { isActing, isBlocked, run } = useRowAction()

const list = ref<SysUser[]>([])
const total = ref(0)
const loading = ref(false)

const query = reactive({ keyword: '', page: 1, size: 20 })
// 筛选条件同步到地址栏（刷新/分享链接不丢筛选）
useQuerySync(query, ['keyword', 'page', 'size'], { numeric: ['page', 'size'] })

const dialogVisible = ref(false)
const saving = ref(false)
const formRef = ref<FormInstance>()

const passwordVisible = ref(false)
const passwordTarget = ref<SysUser | null>(null)
const newPassword = ref('')

// 角色选项：优先取 GET /api/system/roles（需 system:role:view），失败静默回退内置清单
const roleOptions = ref<SysRole[]>([
  { code: 'ADMIN', name: '系统管理员' },
  { code: 'PURCHASER', name: '采购员' },
  { code: 'SALES', name: '销售员' },
  { code: 'WAREHOUSE', name: '仓管' },
  { code: 'APPROVER', name: '审批人' },
  { code: 'SENIOR_APPROVER', name: '高级审批人' },
  { code: 'RETURN_SPECIALIST', name: '退货专员' },
  { code: 'QUALITY', name: '质量管理' }
])

interface UserForm {
  id?: number
  username: string
  password: string
  realName: string
  status: string
  roles: string[]
}

const emptyForm = (): UserForm => ({
  username: '',
  password: '',
  realName: '',
  status: 'ACTIVE',
  roles: []
})
const form = reactive<UserForm>(emptyForm())

const formRules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [
    {
      validator: (_r, v: string, cb) => {
        if (form.id) return cb()
        if (!v) return cb(new Error('请输入初始密码'))
        if (v.length < 6) return cb(new Error('密码至少 6 位'))
        cb()
      },
      trigger: 'blur'
    }
  ]
}

// 内置中文名兜底：本地库存在历史英文角色名（如 Approver），展示统一用中文
const ROLE_NAME_ZH: Record<string, string> = {
  ADMIN: '系统管理员',
  PURCHASER: '采购员',
  SALES: '销售员',
  WAREHOUSE: '仓管',
  APPROVER: '审批人',
  SENIOR_APPROVER: '高级审批人',
  RETURN_SPECIALIST: '退货专员',
  QUALITY: '质量管理'
}

function roleDisplayName(code: string, name?: string) {
  return ROLE_NAME_ZH[code] || name || code
}

function roleLabel(code: string) {
  const role = roleOptions.value.find((r) => r.code === code)
  return roleDisplayName(code, role?.name)
}

async function load() {
  loading.value = true
  try {
    const data = await listUsers(query.keyword, query.page, query.size)
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

async function loadRoleOptions() {
  try {
    const roles = await listRoles()
    if (Array.isArray(roles) && roles.length > 0) roleOptions.value = roles
  } catch {
    // 无 system:role:view 或接口异常时沿用内置清单（请求已 silent，不弹提示）
  }
}

function openDialog(row?: SysUser) {
  Object.assign(form, emptyForm())
  if (row) {
    form.id = row.id
    form.username = row.username
    form.realName = row.realName || ''
    form.status = row.status || 'ACTIVE'
    form.roles = [...(row.roles || [])]
  }
  dialogVisible.value = true
}

async function handleSave() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    saving.value = true
    try {
      if (form.id) {
        await updateUser(form.id, {
          realName: form.realName,
          status: form.status,
          roles: form.roles
        })
        // 角色单独下发，兼容后端仅支持 /roles 的分配语义；失败不阻断主流程
        try {
          await assignUserRoles(form.id, form.roles)
        } catch {
          // 角色已在 PUT 体里提交，忽略独立分配接口的失败
        }
        ElMessage.success('修改成功')
      } else {
        const created = await createUser({
          username: form.username,
          password: form.password,
          realName: form.realName,
          status: form.status,
          roles: form.roles
        })
        if (created?.id && form.roles.length > 0) {
          try {
            await assignUserRoles(created.id, form.roles)
          } catch {
            // ignore
          }
        }
        ElMessage.success('新增成功')
      }
      dialogVisible.value = false
      load()
    } finally {
      saving.value = false
    }
  })
}

function openPasswordDialog(row: SysUser) {
  passwordTarget.value = row
  newPassword.value = ''
  passwordVisible.value = true
}

async function handleResetPassword() {
  if (!passwordTarget.value?.id) return
  if (!newPassword.value || newPassword.value.length < 6) {
    ElMessage.warning('新密码至少 6 位')
    return
  }
  saving.value = true
  try {
    await resetUserPassword(passwordTarget.value.id, newPassword.value)
    ElMessage.success('密码已重置，该用户旧登录将失效')
    passwordVisible.value = false
  } finally {
    saving.value = false
  }
}

async function handleToggle(row: SysUser) {
  const action = row.status === 'ACTIVE' ? '停用' : '启用'
  try {
    await ElMessageBox.confirm(
      `确认${action}用户「${row.username}」吗？${action === '停用' ? '停用后该用户登录将立即失效。' : ''}`,
      '提示',
      { type: 'warning' }
    )
  } catch {
    return
  }
  await run(
    row.id!,
    async () => {
      await toggleUser(row.id!)
      ElMessage.success(`已${action}`)
      await load()
    },
    'toggle'
  )
}

async function handleDelete(row: SysUser) {
  try {
    await ElMessageBox.confirm(`确认删除用户「${row.username}」吗？删除后不可恢复。`, '提示', {
      type: 'warning'
    })
  } catch {
    return
  }
  await run(
    row.id!,
    async () => {
      try {
        await deleteUser(row.id!)
      } catch {
        // 删除失败（如系统内置账号）由拦截器展示后端 message
        return
      }
      ElMessage.success('删除成功')
      await load()
    },
    'delete'
  )
}

onMounted(() => {
  load()
  loadRoleOptions()
})
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

.role-tag {
  margin-right: 6px;
}
</style>
