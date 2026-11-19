#!/usr/bin/env bash
# 批次十三 curl 冒烟
# 1) L2（>=10 万）单据：APPROVER 待审列表看不到、审批 403；SENIOR_APPROVER 可见并可审
# 2) 退货专员可作废他人销售/采购退货；无权限用户 403
set -u
# 服务地址：默认 localhost:8080，可用 BASE（兼容旧名 B）覆盖
BASE=${BASE:-${B:-http://localhost:8080}}
B="$BASE"
PSQL=${PSQL:-"/c/Program Files/PostgreSQL/17/bin/psql.exe"}
export PGPASSWORD=${PGPASSWORD:?请先 export PGPASSWORD=本地数据库口令}
export PGCLIENTENCODING=UTF8
TS=$(date +%s)
APPROVER="b13-appr-$TS"
SENIOR="b13-senior-$TS"
RET="b13-ret-$TS"
PLAIN="b13-plain-$TS"

sql() { "$PSQL" -h localhost -U postgres -d pharma_erp -t -A -c "$1" 2>/dev/null; }
login() {
  curl -s -X POST "$B/api/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"$2\"}" | grep -o '"token":"[^"]*"' | cut -d'"' -f4
}
mkuser() {
  curl -s -X POST "$B/api/system/users" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"pass123\",\"realName\":\"$1\",\"roleCodes\":[\"$2\"]}" > /dev/null
}
code() { curl -s -o /tmp/b13-resp.json -w '%{http_code}' "$@"; }
field() { grep -o "\"$1\":[^,}]*" /tmp/b13-resp.json | head -1; }

echo "== 0) 准备用户 =="
ADMIN=$(login admin admin123)
mkuser "$APPROVER" APPROVER
mkuser "$SENIOR" SENIOR_APPROVER
mkuser "$RET" RETURN_SPECIALIST
mkuser "$PLAIN" SALES
TA=$(login "$APPROVER" pass123); TSEN=$(login "$SENIOR" pass123)
TRET=$(login "$RET" pass123); TPLAIN=$(login "$PLAIN" pass123)
echo "登录 ok: APPROVER=$([ -n "$TA" ] && echo y) SENIOR=$([ -n "$TSEN" ] && echo y) RETURN=$([ -n "$TRET" ] && echo y) PLAIN=$([ -n "$TPLAIN" ] && echo y)"

echo
echo "== 1) 造 L2 采购订单（20 万）并由 ADMIN 提交 =="
SUP=$(sql "select id from supplier where first_audit_status='APPROVED' and status='ACTIVE' limit 1")
PROD=$(sql "select id from product where first_audit_status='APPROVED' and status='ACTIVE' limit 1")
PO=$(curl -s -X POST "$B/api/purchase/orders" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d "{\"supplierId\":$SUP,\"orderDate\":\"$(date +%F)\",\"items\":[{\"productId\":$PROD,\"quantity\":20000,\"unitPrice\":10}]}")
POID=$(echo "$PO" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
AMT=$(echo "$PO" | grep -o '"totalAmount":[0-9.]*' | head -1 | cut -d: -f2)
curl -s -X POST "$B/api/purchase/orders/$POID/submit" -H "Authorization: Bearer $ADMIN" > /dev/null
echo "订单 id=$POID 金额=$AMT（应 200000 → L2）"

echo
echo "== 2) APPROVER：待审列表不可见 + 审批 403 =="
if curl -s "$B/api/approvals/pending?page=1&size=200" -H "Authorization: Bearer $TA" | grep -q "\"bizId\":$POID,"; then
  echo "待审列表含 L2 单: YES（预期 NO，异常）"
else
  echo "待审列表含 L2 单: NO（符合预期）"
fi
C=$(code -X POST "$B/api/approvals/PURCHASE_ORDER/$POID/approve" -H "Authorization: Bearer $TA" -H 'Content-Type: application/json' -d '{"comment":"agree"}')
echo "APPROVER 审批 L2 → HTTP $C（应 403）"

echo
echo "== 3) SENIOR_APPROVER：可见并可审 =="
if curl -s "$B/api/approvals/pending?page=1&size=200" -H "Authorization: Bearer $TSEN" | grep -q "\"bizId\":$POID,"; then
  echo "待审列表含 L2 单: YES（符合预期）"
else
  echo "待审列表含 L2 单: NO（预期 YES，异常）"
fi
C=$(code -X POST "$B/api/approvals/PURCHASE_ORDER/$POID/approve" -H "Authorization: Bearer $TSEN" -H 'Content-Type: application/json' -d '{"comment":"senior approve"}')
echo "SENIOR_APPROVER 审批 L2 → HTTP $C（应 200）"
echo "订单最终状态: $(curl -s "$B/api/purchase/orders/$POID" -H "Authorization: Bearer $ADMIN" | grep -o '"status":"[^"]*"' | head -1)"

echo
echo "== 4) 退货专员作废他人销售/采购退货 =="
DEL=$(sql "select id from sales_delivery limit 1")
CUS=$(sql "select id from customer where status='ACTIVE' limit 1")
SID=$(sql "insert into sales_return(return_no,delivery_id,customer_id,return_date,status,total_amount,reason,created_by) values('SMK-SR-$TS',$DEL,$CUS,current_date,'DRAFT',10.00,'smoke','b13-maker') returning id" | head -1)
PID=$(sql "insert into purchase_return(return_no,supplier_id,return_date,status,total_amount,reason,created_by) values('SMK-PR-$TS',$SUP,current_date,'COMPLETED',10.00,'smoke','b13-maker') returning id" | head -1)
C=$(code -X POST "$B/api/sales/returns/$SID/void" -H "Authorization: Bearer $TRET" -H 'Content-Type: application/json' -d '{"reason":"smoke void"}')
echo "作废销售退货(id=$SID, created_by=b13-maker) → HTTP $C（应 200） $(field status)"
C=$(code -X POST "$B/api/purchase/returns/$PID/void" -H "Authorization: Bearer $TRET" -H 'Content-Type: application/json' -d '{"reason":"smoke void"}')
echo "作废采购退货(id=$PID, created_by=b13-maker) → HTTP $C（应 200） $(field status)"

echo
echo "== 5) 无作废权限的销售员 → 403 =="
SID2=$(sql "insert into sales_return(return_no,delivery_id,customer_id,return_date,status,total_amount,created_by) values('SMK-SR2-$TS',$DEL,$CUS,current_date,'DRAFT',10.00,'b13-maker') returning id" | head -1)
C=$(code -X POST "$B/api/sales/returns/$SID2/void" -H "Authorization: Bearer $TPLAIN")
echo "SALES 用户作废销售退货 → HTTP $C（应 403）"

echo
echo "== 清理 =="
sql "delete from sales_return where id in ($SID,$SID2)" > /dev/null
sql "delete from purchase_return where id = $PID" > /dev/null
sql "delete from approval_record where biz_type='PURCHASE_ORDER' and biz_id=$POID" > /dev/null
echo "done（保留采购订单 $POID 作为业务数据，用户 b13-*-$TS 保留）"
