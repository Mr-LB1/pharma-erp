#!/usr/bin/env bash
# 批次三后端冒烟：用户管理 / 权限下发 / 老 token 失效 / refresh·logout / 数据权限 / 成本脱敏 / 字段级校验
set -u
# 服务地址：默认 localhost:8080，可用 BASE（兼容旧名 B）覆盖
BASE=${BASE:-${B:-http://localhost:8080}}
B="$BASE"
JSON="Content-Type: application/json"
PY=python
TS=$(date +%s)

jget() {
  $PY -c '
import sys,json
try:
    d=json.load(sys.stdin)
except Exception:
    print(""); raise SystemExit
cur=d
for k in sys.argv[1].split("."):
    if not k: continue
    if isinstance(cur,list):
        cur=cur[int(k)] if len(cur)>int(k) else None
    elif isinstance(cur,dict):
        cur=cur.get(k)
    else:
        cur=None
    if cur is None: break
print("" if cur is None else cur)
' "$1"
}
login_tok() {
  curl -s -X POST $BASE/api/auth/login -H "$JSON" \
    -d "{\"username\":\"$1\",\"password\":\"$2\"}" | jget data.token
}
code() { curl -s -o /dev/null -w "%{http_code}" "$@"; }
say() { echo; echo "===== $1 ====="; }

ADMIN=$(login_tok admin admin123)
AUTH_ADMIN="Authorization: Bearer $ADMIN"

say "1. me 返回权限码（P1-21）"
ME=$(curl -s $BASE/api/auth/me -H "$AUTH_ADMIN")
echo "$ME" | $PY -c "import sys,json;d=json.load(sys.stdin)['data'];print('roles=',d['roles'],'perm_count=',len(d['permissions']))"

say "2. 新建用户 sales1 / sales2 / wh（P0-6）"
U1=$(curl -s -X POST $BASE/api/system/users -H "$AUTH_ADMIN" -H "$JSON" \
  -d "{\"username\":\"smoke-s1-$TS\",\"password\":\"pwd12345\",\"realName\":\"冒烟销售1\",\"roleCodes\":[\"SALES\"]}")
U1ID=$(echo "$U1" | jget data.id)
U2=$(curl -s -X POST $BASE/api/system/users -H "$AUTH_ADMIN" -H "$JSON" \
  -d "{\"username\":\"smoke-s2-$TS\",\"password\":\"pwd12345\",\"realName\":\"冒烟销售2\",\"roles\":[\"SALES\"]}")
U2ID=$(echo "$U2" | jget data.id)
U3=$(curl -s -X POST $BASE/api/system/users -H "$AUTH_ADMIN" -H "$JSON" \
  -d "{\"username\":\"smoke-wh-$TS\",\"password\":\"pwd12345\",\"realName\":\"冒烟仓管\",\"roleCodes\":[\"WAREHOUSE\"]}")
U3ID=$(echo "$U3" | jget data.id)
echo "ids: $U1ID / $U2ID / $U3ID"
echo "用户列表 keyword 命中:"
curl -s "$BASE/api/system/users?keyword=smoke-s1-$TS" -H "$AUTH_ADMIN" | $PY -c "import sys,json;d=json.load(sys.stdin)['data'];print('total=',d['total'],'roles=',d['records'][0]['roles'])"

say "3. 字段级校验：缺用户名 -> 400 + data.errors（P1-31）"
curl -s -X POST $BASE/api/system/users -H "$AUTH_ADMIN" -H "$JSON" \
  -d "{\"username\":\"\",\"password\":\"pwd12345\"}" | head -c 400; echo

S1=$(login_tok "smoke-s1-$TS" pwd12345)
S2=$(login_tok "smoke-s2-$TS" pwd12345)
WH=$(login_tok "smoke-wh-$TS" pwd12345)
AUTH_S1="Authorization: Bearer $S1"
AUTH_S2="Authorization: Bearer $S2"

say "4. WAREHOUSE 权限补配：盘点 + 成本查看（P1-22）"
curl -s $BASE/api/auth/me -H "Authorization: Bearer $WH" | $PY -c "
import sys,json
p=json.load(sys.stdin)['data']['permissions']
print('stocktake:create=', 'inventory:stocktake:create' in p, '| cost:view=', 'inventory:cost:view' in p)"

say "5. 成本脱敏：SALES 无 inventory:cost:view -> unitCost=null；ADMIN -> 有值（P1-23）"
echo -n "sales stock unitCost = "
curl -s "$BASE/api/inventory/stocks?size=1" -H "$AUTH_S1" | $PY -c "import sys,json;r=json.load(sys.stdin)['data']['records'];print(r[0]['unitCost'] if r else 'no-stock')"
echo -n "admin stock unitCost = "
curl -s "$BASE/api/inventory/stocks?size=1" -H "$AUTH_ADMIN" | $PY -c "import sys,json;r=json.load(sys.stdin)['data']['records'];print(r[0]['unitCost'] if r else 'no-stock')"

say "6. 数据权限：sales1 建单，sales2 列表不可见，ADMIN 可见"
SO=$(curl -s -X POST $BASE/api/sales/orders -H "$AUTH_S1" -H "$JSON" \
  -d '{"customerId":91,"items":[{"productId":91,"quantity":1,"unitPrice":9}]}')
SOID=$(echo "$SO" | jget data.id)
echo "created sales order id=$SOID (createdBy=$(echo "$SO" | jget data.createdBy))"
echo -n "sales2 列表包含该单? "
curl -s "$BASE/api/sales/orders?size=100" -H "$AUTH_S2" | $PY -c "import sys,json;ids=[r['id'] for r in json.load(sys.stdin)['data']['records']];print('YES(异常)' if $SOID in ids else 'NO(正确)')"
echo -n "admin 列表包含该单? "
curl -s "$BASE/api/sales/orders?size=100" -H "$AUTH_ADMIN" | $PY -c "import sys,json;ids=[r['id'] for r in json.load(sys.stdin)['data']['records']];print('YES(正确)' if $SOID in ids else 'NO(异常)')"

say "7. 改密：旧 token 立即 401，新密码登录成功（P1-24）"
curl -s -X POST $BASE/api/system/users/$U1ID/password -H "$AUTH_ADMIN" -H "$JSON" -d '{"password":"newpwd123"}' >/dev/null
echo -n "旧 token /me: "; code $BASE/api/auth/me -H "$AUTH_S1"; echo
echo -n "新密码登录 /me: "
S1B=$(login_tok "smoke-s1-$TS" newpwd123)
code $BASE/api/auth/me -H "Authorization: Bearer $S1B"; echo

say "8. 启停：禁用后旧 token 401，启用后可再登录"
curl -s -X POST $BASE/api/system/users/$U1ID/toggle -H "$AUTH_ADMIN" | jget data.status
echo -n "禁用后 /me: "; code $BASE/api/auth/me -H "Authorization: Bearer $S1B"; echo
echo -n "禁用后登录: "; code -X POST $BASE/api/auth/login -H "$JSON" -d "{\"username\":\"smoke-s1-$TS\",\"password\":\"newpwd123\"}"; echo
curl -s -X POST $BASE/api/system/users/$U1ID/toggle -H "$AUTH_ADMIN" | jget data.status
S1C=$(login_tok "smoke-s1-$TS" newpwd123)
echo -n "启用后登录 /me: "; code $BASE/api/auth/me -H "Authorization: Bearer $S1C"; echo

say "9. refresh 可用，logout 后 token 失效"
RTOKEN=$(curl -s -X POST $BASE/api/auth/refresh -H "Authorization: Bearer $S1C" | jget data.token)
echo -n "refresh 后 /me: "; code $BASE/api/auth/me -H "Authorization: Bearer $RTOKEN"; echo
curl -s -X POST $BASE/api/auth/logout -H "Authorization: Bearer $RTOKEN" >/dev/null
echo -n "logout 后 /me: "; code $BASE/api/auth/me -H "Authorization: Bearer $RTOKEN"; echo

say "10. 角色分配：sales2 -> QUALITY，重新登录后权限码生效"
curl -s -X POST $BASE/api/system/users/$U2ID/roles -H "$AUTH_ADMIN" -H "$JSON" \
  -d '{"roles":["QUALITY"]}' | jget data.roles
S2B=$(login_tok "smoke-s2-$TS" pwd12345)
curl -s $BASE/api/auth/me -H "Authorization: Bearer $S2B" | $PY -c "
import sys,json
d=json.load(sys.stdin)['data']
print('roles=',d['roles'],'first-audit:approve=', 'quality:first-audit:approve' in d['permissions'])"

say "11. 清理冒烟用户"
for id in $U2ID $U3ID $U1ID; do
  echo -n "delete $id -> "; code -X DELETE $BASE/api/system/users/$id -H "$AUTH_ADMIN"; echo
done
