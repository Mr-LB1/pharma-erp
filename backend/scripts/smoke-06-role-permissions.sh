#!/usr/bin/env bash
# 批次六后端冒烟：角色权限配置（读取 / 增删 / 非法码 / ADMIN 自锁 / 审计）
# 前置：后端已在 http://localhost:8080 运行，管理员 admin/admin123 可用
# 用法：BASE=http://localhost:8080 bash smoke-b6.sh
set -u
B=${BASE:-http://localhost:8080}
JSON="Content-Type: application/json"
PY=python
jg() { $PY -c "import sys,json;d=json.load(sys.stdin);print(d$1)"; }

TOK=$(curl -s -X POST $B/api/auth/login -H "$JSON" -d '{"username":"admin","password":"admin123"}' | jg "['data']['token']")
A="Authorization: Bearer $TOK"
QID=$(curl -s $B/api/system/roles -H "$A" | $PY -c "import sys,json;print([r['id'] for r in json.load(sys.stdin)['data'] if r['code']=='QUALITY'][0])")
AID=$(curl -s $B/api/system/roles -H "$A" | $PY -c "import sys,json;print([r['id'] for r in json.load(sys.stdin)['data'] if r['code']=='ADMIN'][0])")

echo "===== 1. 权限字典 ====="
curl -s $B/api/system/permissions -H "$A" | $PY -c "import sys,json;d=json.load(sys.stdin)['data'];print('权限码', len(d), '| 含 system:role:config ->', any(p['code']=='system:role:config' for p in d))"

echo "===== 2. QUALITY($QID) 现有权限 ====="
ORIG=$(curl -s $B/api/system/roles/$QID/permissions -H "$A" | $PY -c "import sys,json;print(json.dumps(json.load(sys.stdin)['data']))")
echo "$ORIG"

echo "===== 3. 增加 warehouse:view 后立即读取 ====="
NEW=$(echo "$ORIG" | $PY -c "import sys,json;c=json.load(sys.stdin);c.append('warehouse:view');print(json.dumps({'permissionCodes':c}))")
curl -s -X PUT $B/api/system/roles/$QID/permissions -H "$A" -H "$JSON" -d "$NEW" | $PY -c "import sys,json;d=json.load(sys.stdin)['data'];print('PUT 返回 warehouse:view ->', 'warehouse:view' in d, '| 共', len(d))"
curl -s $B/api/system/roles/$QID/permissions -H "$A" | $PY -c "import sys,json;d=json.load(sys.stdin)['data'];print('GET 反映 warehouse:view ->', 'warehouse:view' in d)"

echo "===== 4. 该角色用户重新登录 /me ====="
UN="smoke-q-$(date +%s)"
USERID=$(curl -s -X POST $B/api/system/users -H "$A" -H "$JSON" \
  -d "{\"username\":\"$UN\",\"password\":\"pwd12345\",\"realName\":\"冒烟质量员\",\"roleCodes\":[\"QUALITY\"]}" | jg "['data']['id']")
QTOK=$(curl -s -X POST $B/api/auth/login -H "$JSON" -d "{\"username\":\"$UN\",\"password\":\"pwd12345\"}" | jg "['data']['token']")
curl -s $B/api/auth/me -H "Authorization: Bearer $QTOK" | $PY -c "import sys,json;d=json.load(sys.stdin)['data'];print('/me warehouse:view ->', 'warehouse:view' in d['permissions'], '| 权限数', len(d['permissions']))"

echo "===== 5. 移除 warehouse:view 并还原 ====="
curl -s -X PUT $B/api/system/roles/$QID/permissions -H "$A" -H "$JSON" -d "{\"permissionCodes\":$ORIG}" | $PY -c "import sys,json;print('还原后权限数', len(json.load(sys.stdin)['data']))"
QTOK2=$(curl -s -X POST $B/api/auth/login -H "$JSON" -d "{\"username\":\"$UN\",\"password\":\"pwd12345\"}" | jg "['data']['token']")
curl -s $B/api/auth/me -H "Authorization: Bearer $QTOK2" | $PY -c "import sys,json;d=json.load(sys.stdin)['data'];print('/me warehouse:view ->', 'warehouse:view' in d['permissions'])"

echo "===== 6. 清理临时用户 ====="
curl -s -X DELETE $B/api/system/users/$USERID -H "$A" | $PY -c "import sys,json;print(json.load(sys.stdin)['message'])"

echo "===== 7. 异常分支 ====="
echo -n "非法权限码 -> "; curl -s -o /tmp/b6r1 -w "%{http_code} " -X PUT $B/api/system/roles/$QID/permissions -H "$A" -H "$JSON" -d '{"permissionCodes":["product:view","no:such:perm"]}'; cat /tmp/b6r1; echo
echo -n "ADMIN($AID) 移除 system:role:config -> "; curl -s -o /tmp/b6r2 -w "%{http_code} " -X PUT $B/api/system/roles/$AID/permissions -H "$A" -H "$JSON" -d '{"permissionCodes":["system:role:view"]}'; cat /tmp/b6r2; echo
echo -n "角色不存在 -> "; curl -s -o /tmp/b6r3 -w "%{http_code} " $B/api/system/roles/999999/permissions -H "$A"; cat /tmp/b6r3; echo
echo -n "无 token -> "; curl -s -o /dev/null -w "%{http_code}\n" $B/api/system/permissions

echo "===== 8. 审计日志 ====="
curl -s "$B/api/system/audit-logs?page=1&size=5" -H "$A" | $PY -c "
import sys,json
for r in json.load(sys.stdin)['data']['records']:
    if r['action'] in ('ROLE_PERMISSION_UPDATE',): print(r['action'], '|', r['objectType'], r['objectId'], '|', r['detail'][:110])"
