#!/usr/bin/env bash
# 批次十四验收冒烟（WMS 版）：4 类单据 DRAFT → SUBMITTED → APPROVED → PUSHED → COMPLETED
# 校验：create/submit/approve 均不动库存与财务；WMS 回传才按实量执行；重复审批/重复回传均 400
# 用法：B=http://127.0.0.1:18080 ./smoke-b14-approval.sh
set -u
B=${B:-http://127.0.0.1:18080}
PSQL="/c/Program Files/PostgreSQL/17/bin/psql.exe"
export PGPASSWORD=${PGPASSWORD:-'Lb123456%'}
export PGCLIENTENCODING=UTF8
TS="$(date +%s)-$RANDOM"
TMP=$(mktemp -d)
PASS=0
FAIL=0

sql() { "$PSQL" -h localhost -U postgres -d pharma_erp -t -A -c "$1" 2>/dev/null; }
token_of() { sed -n 's/.*"token":"\([^"]*\)".*/\1/p' | head -1; }
login() {
  curl -s -X POST "$B/api/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"$2\"}" | token_of
}
mkuser() {
  curl -s -X POST "$B/api/system/users" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"pass123\",\"realName\":\"$1\",\"roleCodes\":[\"$2\"]}" > /dev/null
}
id_of() { printf '%s' "$1" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2; }
status_of() { sed -n 's/.*"status":"\([^"]*\)".*/\1/p' "$1" | head -1; }
num() { grep -o "\"$1\":[0-9]*" "$2" 2>/dev/null | head -1 | cut -d: -f2; }
ok() { PASS=$((PASS + 1)); echo "  PASS  $1"; }
ng() { FAIL=$((FAIL + 1)); echo "  FAIL  $1"; }
check() { if [ "$2" = "$3" ]; then ok "$1 ($2)"; else ng "$1 期望 $3，实际 $2"; fi; }
st() { sql "select status from $1 where id=$2"; }
cnt() { sql "select count(*) from $1 where $2"; }

# 库存数量（按 商品 + 批号 + 仓库）
qty() { sql "select coalesce(sum(quantity),0) from stock where product_id=$PROD and batch_no='$1' and location_id=$2" | sed 's/\.0*$//'; }
pqty() { sql "select coalesce(sum(quantity),0) from stock where product_id=$PROD and location_id=$1" | sed 's/\.0*$//'; }

sub() { curl -s -X POST "$B/$1" -H "Authorization: Bearer $ADMIN" > "$TMP/sub-$2.json"; }
appr() { curl -s -o "$TMP/appr-$2.json" -w '%{http_code}' -X POST "$B/api/approvals/$1/approve" \
  -H "Authorization: Bearer $TA" -H 'Content-Type: application/json' -d '{"comment":"agree"}'; }
cb() { curl -s -o "$TMP/cb-$3.json" -w '%{http_code}' -X POST "$B/$1/wms-callback" \
  -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d "$2"; }
itemid() { sql "select id from ${1}_item where ${2}=$3 order by id limit 1"; }

echo "== 0) 登录 / 审批人 / 夹具 =="
ADMIN=$(login admin admin123)
[ -n "$ADMIN" ] && ok "admin 登录" || { ng "admin 登录失败"; exit 1; }
APPR="b14a-$TS"
mkuser "$APPR" APPROVER
TA=$(login "$APPR" pass123)
[ -n "$TA" ] && ok "APPROVER 登录" || ng "APPROVER 登录失败"

SUP=$(sql "select id from supplier where first_audit_status='APPROVED' and status='ACTIVE' limit 1")
PROD=$(sql "select id from product where first_audit_status='APPROVED' and status='ACTIVE' and storage_cond='ROOM_TEMP' limit 1")
WH=$(sql "select id from warehouse where status='ACTIVE' and temp_type='ROOM_TEMP' limit 1")
CUS=$(sql "select id from customer where first_audit_status='APPROVED' and status='ACTIVE' limit 1")
WH2=$(sql "insert into warehouse(name,temp_type) values('B14A-TARGET-$TS','ROOM_TEMP') returning id" | head -1)
echo "  夹具：supplier=$SUP product=$PROD warehouse=$WH customer=$CUS target=$WH2"

echo
echo "== 1) 采购入库：create/submit/approve 不动库存 → WMS 回传才入库 =="
BATCH="B14A-RC-$TS"
PO=$(curl -s -X POST "$B/api/purchase/orders" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d "{\"supplierId\":$SUP,\"orderDate\":\"$(date +%F)\",\"items\":[{\"productId\":$PROD,\"quantity\":20,\"unitPrice\":5}]}")
POID=$(id_of "$PO")
sub "api/purchase/orders/$POID/submit" po
appr "PURCHASE_ORDER/$POID" po-x > /dev/null
RC=$(curl -s -X POST "$B/api/purchase/receives" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d "{\"warehouseId\":$WH,\"orderId\":$POID,\"receiveDate\":\"$(date +%F)\",\"items\":[{\"productId\":$PROD,\"quantity\":10,\"batchNo\":\"$BATCH\",\"produceDate\":\"$(date -d '-5 day' +%F)\",\"expireDate\":\"$(date -d '+180 day' +%F)\",\"unitCost\":5,\"inspectionNo\":\"INS-$BATCH\"}]}")
RCID=$(id_of "$RC")
echo "$RC" > "$TMP/rc-create.json"
check "入库 create 状态" "$(status_of "$TMP/rc-create.json")" DRAFT
check "入库 create 后库存" "$(qty "$BATCH" "$WH")" 0
check "入库 create 后应付" "$(cnt ap_invoice "biz_type='PURCHASE_RECEIVE' and biz_id=$RCID")" 0

sub "api/purchase/receives/$RCID/submit" rc
check "入库 submit 后状态" "$(st purchase_receive "$RCID")" SUBMITTED
check "入库 submit 后待审记录" "$(cnt approval_record "biz_type='PURCHASE_RECEIVE' and biz_id=$RCID and status='PENDING'")" 1

check "入库 approve HTTP" "$(appr "PURCHASE_RECEIVE/$RCID" rc)" 200
check "入库 approve 后状态" "$(st purchase_receive "$RCID")" PUSHED
check "入库 approve 后库存（不动）" "$(qty "$BATCH" "$WH")" 0
check "入库 approve 后应付（不动）" "$(cnt ap_invoice "biz_type='PURCHASE_RECEIVE' and biz_id=$RCID")" 0
check "入库 wms_pushed_at 已写" "$(sql "select case when wms_pushed_at is null then 'N' else 'Y' end from purchase_receive where id=$RCID")" Y
check "重复 approve HTTP" "$(appr "PURCHASE_RECEIVE/$RCID" rc2)" 400
grep -q "仅已提交状态可审批" "$TMP/appr-rc2.json" && ok "重复 approve 提示仅已提交可审批" || ng "重复 approve 提示缺失"

check "入库回传 HTTP" "$(cb "api/purchase/receives/$RCID" '{"wmsNo":"WMS-RC-'"$TS"'"}' rc)" 200
check "入库回传后状态" "$(st purchase_receive "$RCID")" COMPLETED
check "入库回传后库存" "$(qty "$BATCH" "$WH")" 10
check "入库回传后应付条数" "$(cnt ap_invoice "biz_type='PURCHASE_RECEIVE' and biz_id=$RCID")" 1
check "入库回传 wms_no" "$(sql "select wms_no from purchase_receive where id=$RCID")" "WMS-RC-$TS"
check "重复回传 HTTP" "$(cb "api/purchase/receives/$RCID" '{}' rc2)" 400
check "重复回传后库存不变" "$(qty "$BATCH" "$WH")" 10

echo
echo "== 2) WMS 实收少于单据量：按实收 + 差异留痕 =="
BATCH2="B14A-DIFF-$TS"
PO2=$(curl -s -X POST "$B/api/purchase/orders" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d "{\"supplierId\":$SUP,\"orderDate\":\"$(date +%F)\",\"items\":[{\"productId\":$PROD,\"quantity\":5,\"unitPrice\":5}]}")
PO2ID=$(id_of "$PO2")
sub "api/purchase/orders/$PO2ID/submit" po2
appr "PURCHASE_ORDER/$PO2ID" po2-x > /dev/null
RC2=$(curl -s -X POST "$B/api/purchase/receives" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d "{\"warehouseId\":$WH,\"orderId\":$PO2ID,\"receiveDate\":\"$(date +%F)\",\"items\":[{\"productId\":$PROD,\"quantity\":5,\"batchNo\":\"$BATCH2\",\"produceDate\":\"$(date -d '-5 day' +%F)\",\"expireDate\":\"$(date -d '+180 day' +%F)\",\"unitCost\":5,\"inspectionNo\":\"INS-$BATCH2\"}]}")
RC2ID=$(id_of "$RC2")
sub "api/purchase/receives/$RC2ID/submit" rc2s
appr "PURCHASE_RECEIVE/$RC2ID" rc2a > /dev/null
ITEM2=$(itemid purchase_receive receive_id "$RC2ID")
check "差异单回传 HTTP" "$(cb "api/purchase/receives/$RC2ID" "{\"wmsNo\":\"WMS-DIFF-$TS\",\"items\":[{\"itemId\":$ITEM2,\"actualQty\":3}]}" rc2)" 200
check "差异单状态" "$(st purchase_receive "$RC2ID")" COMPLETED
check "差异单实收库存" "$(qty "$BATCH2" "$WH")" 3
check "差异单 actual_qty" "$(sql "select actual_qty::text from purchase_receive_item where id=$ITEM2" | sed 's/\.0*$//')" 3
check "差异单 qty_diff" "$(sql "select qty_diff::text from purchase_receive_item where id=$ITEM2" | sed 's/\.0*$//')" -2
check "差异单应付按实收(15)" "$(sql "select invoice_amount::text from ap_invoice where biz_type='PURCHASE_RECEIVE' and biz_id=$RC2ID" | sed 's/\.00$//')" 15

echo
echo "== 3) 销售出库：approve 不扣库存 → 回传实发后出库+应收 =="
WH_TOTAL_BEFORE=$(pqty "$WH")
SO=$(curl -s -X POST "$B/api/sales/orders" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d "{\"customerId\":$CUS,\"orderDate\":\"$(date +%F)\",\"items\":[{\"productId\":$PROD,\"quantity\":6,\"unitPrice\":20}]}")
SOID=$(id_of "$SO")
sub "api/sales/orders/$SOID/submit" so
appr "SALES_ORDER/$SOID" so-x > /dev/null
DO=$(curl -s -X POST "$B/api/sales/deliveries" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d "{\"warehouseId\":$WH,\"orderId\":$SOID,\"deliveryDate\":\"$(date +%F)\",\"items\":[{\"productId\":$PROD,\"quantity\":6}]}")
DOID=$(id_of "$DO")
echo "$DO" > "$TMP/do-create.json"
check "出库 create 状态" "$(status_of "$TMP/do-create.json")" DRAFT
check "出库 create 后总库存" "$(pqty "$WH")" "$WH_TOTAL_BEFORE"
check "出库 create 后应收" "$(cnt ar_invoice "biz_type='SALES_DELIVERY' and biz_id=$DOID")" 0

sub "api/sales/deliveries/$DOID/submit" do
check "出库 submit 后待审记录" "$(cnt approval_record "biz_type='SALES_DELIVERY' and biz_id=$DOID and status='PENDING'")" 1
check "出库 approve HTTP" "$(appr "SALES_DELIVERY/$DOID" do)" 200
check "出库 approve 后状态" "$(st sales_delivery "$DOID")" PUSHED
check "出库 approve 后总库存（不动）" "$(pqty "$WH")" "$WH_TOTAL_BEFORE"
check "出库 approve 后应收（不动）" "$(cnt ar_invoice "biz_type='SALES_DELIVERY' and biz_id=$DOID")" 0

check "出库回传 HTTP" "$(cb "api/sales/deliveries/$DOID" '{"wmsNo":"WMS-DO-'"$TS"'"}' do)" 200
check "出库回传后状态" "$(st sales_delivery "$DOID")" COMPLETED
check "出库回传后总库存" "$(pqty "$WH")" "$((WH_TOTAL_BEFORE - 6))"
check "出库回传后应收条数" "$(cnt ar_invoice "biz_type='SALES_DELIVERY' and biz_id=$DOID")" 1
check "重复回传 HTTP" "$(cb "api/sales/deliveries/$DOID" '{}' do2)" 400
check "重复回传后总库存不变" "$(pqty "$WH")" "$((WH_TOTAL_BEFORE - 6))"

echo
echo "== 4) 采购退货：approve 不扣库存 → 回传实退后扣减+红字应付 =="
PR_BEFORE=$(qty "$BATCH" "$WH")
PR=$(curl -s -X POST "$B/api/purchase/returns" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d "{\"receiveId\":$RCID,\"supplierId\":$SUP,\"returnDate\":\"$(date +%F)\",\"reason\":\"验收冒烟\",\"items\":[{\"productId\":$PROD,\"batchNo\":\"$BATCH\",\"quantity\":2,\"warehouseId\":$WH}]}")
PRID=$(id_of "$PR")
echo "$PR" > "$TMP/pr-create.json"
check "退货 create 状态" "$(status_of "$TMP/pr-create.json")" DRAFT
check "退货 create 后库存" "$(qty "$BATCH" "$WH")" "$PR_BEFORE"
check "退货 create 后红字应付" "$(cnt ap_invoice "biz_type='PURCHASE_RETURN' and biz_id=$PRID")" 0

sub "api/purchase/returns/$PRID/submit" pr
check "退货 submit 后待审记录" "$(cnt approval_record "biz_type='PURCHASE_RETURN' and biz_id=$PRID and status='PENDING'")" 1
check "退货 approve HTTP" "$(appr "PURCHASE_RETURN/$PRID" pr)" 200
check "退货 approve 后状态" "$(st purchase_return "$PRID")" PUSHED
check "退货 approve 后库存（不动）" "$(qty "$BATCH" "$WH")" "$PR_BEFORE"

check "退货回传 HTTP" "$(cb "api/purchase/returns/$PRID" '{"wmsNo":"WMS-PR-'"$TS"'"}' pr)" 200
check "退货回传后状态" "$(st purchase_return "$PRID")" COMPLETED
check "退货回传后库存" "$(qty "$BATCH" "$WH")" "$((PR_BEFORE - 2))"
RED=$(sql "select invoice_amount::text from ap_invoice where biz_type='PURCHASE_RETURN' and biz_id=$PRID" | sed 's/\.00$//')
check "退货红字应付金额" "$RED" "-10"
check "重复回传 HTTP" "$(cb "api/purchase/returns/$PRID" '{}' pr2)" 400
check "重复回传后库存不变" "$(qty "$BATCH" "$WH")" "$((PR_BEFORE - 2))"

echo
echo "== 5) 仓库调拨：approve 不移动 → 回传实调后 from 扣 to 增 =="
TR_BEFORE=$(qty "$BATCH" "$WH")
TR=$(curl -s -X POST "$B/api/inventory/transfers" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d "{\"fromWarehouseId\":$WH,\"toWarehouseId\":$WH2,\"transferDate\":\"$(date +%F)\",\"remark\":\"验收冒烟\",\"items\":[{\"productId\":$PROD,\"batchNo\":\"$BATCH\",\"quantity\":1}]}")
TRID=$(id_of "$TR")
echo "$TR" > "$TMP/tr-create.json"
check "调拨 create 状态" "$(status_of "$TMP/tr-create.json")" DRAFT
check "调拨 create 后 from 库存" "$(qty "$BATCH" "$WH")" "$TR_BEFORE"
check "调拨 create 后 to 库存" "$(qty "$BATCH" "$WH2")" 0

sub "api/inventory/transfers/$TRID/submit" tr
check "调拨 submit 后待审记录" "$(cnt approval_record "biz_type='STOCK_TRANSFER' and biz_id=$TRID and status='PENDING'")" 1
check "调拨 approve HTTP" "$(appr "STOCK_TRANSFER/$TRID" tr)" 200
check "调拨 approve 后状态" "$(st stock_transfer "$TRID")" PUSHED
check "调拨 approve 后 from 库存（不动）" "$(qty "$BATCH" "$WH")" "$TR_BEFORE"

check "调拨回传 HTTP" "$(cb "api/inventory/transfers/$TRID" '{"wmsNo":"WMS-TR-'"$TS"'"}' tr)" 200
check "调拨回传后状态" "$(st stock_transfer "$TRID")" COMPLETED
check "调拨回传后 from 库存" "$(qty "$BATCH" "$WH")" "$((TR_BEFORE - 1))"
check "调拨回传后 to 库存" "$(qty "$BATCH" "$WH2")" 1
check "重复回传 HTTP" "$(cb "api/inventory/transfers/$TRID" '{}' tr2)" 400
check "重复回传后 from 库存不变" "$(qty "$BATCH" "$WH")" "$((TR_BEFORE - 1))"

echo
echo "== 6) 待审批列表含新类型 + 驳回可重提 =="
NEWBATCH="B14A-PEND-$TS"
PO3=$(curl -s -X POST "$B/api/purchase/orders" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d "{\"supplierId\":$SUP,\"orderDate\":\"$(date +%F)\",\"items\":[{\"productId\":$PROD,\"quantity\":2,\"unitPrice\":5}]}")
PO3ID=$(id_of "$PO3")
sub "api/purchase/orders/$PO3ID/submit" po3
appr "PURCHASE_ORDER/$PO3ID" po3-x > /dev/null
RC3=$(curl -s -X POST "$B/api/purchase/receives" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d "{\"warehouseId\":$WH,\"orderId\":$PO3ID,\"receiveDate\":\"$(date +%F)\",\"items\":[{\"productId\":$PROD,\"quantity\":2,\"batchNo\":\"$NEWBATCH\",\"produceDate\":\"$(date -d '-5 day' +%F)\",\"expireDate\":\"$(date -d '+180 day' +%F)\",\"unitCost\":5,\"inspectionNo\":\"INS-$NEWBATCH\"}]}")
RC3ID=$(id_of "$RC3")
sub "api/purchase/receives/$RC3ID/submit" rc3
curl -s "$B/api/approvals/pending?page=1&size=200" -H "Authorization: Bearer $TA" > "$TMP/pending.json"
for t in PURCHASE_ORDER SALES_ORDER PURCHASE_RECEIVE; do
  grep -q "\"bizType\":\"$t\"" "$TMP/pending.json" && ok "待审列表含 $t" || ng "待审列表缺 $t"
done

curl -s -o "$TMP/reject.json" -w '%{http_code}' -X POST "$B/api/approvals/PURCHASE_RECEIVE/$RC3ID/reject" \
  -H "Authorization: Bearer $TA" -H 'Content-Type: application/json' -d '{"comment":"随货同行单不符"}' > "$TMP/reject-code.txt"
check "驳回 HTTP" "$(cat "$TMP/reject-code.txt")" 200
check "驳回后状态" "$(st purchase_receive "$RC3ID")" REJECTED
check "驳回后库存不变" "$(qty "$NEWBATCH" "$WH")" 0
sub "api/purchase/receives/$RC3ID/submit" rc4
check "驳回后可重新提交" "$(st purchase_receive "$RC3ID")" SUBMITTED

echo
echo "== 7) PUSHED 状态不可作废 =="
appr "PURCHASE_RECEIVE/$RC3ID" rc5 > /dev/null
PCODE=$(curl -s -o "$TMP/void-pushed.json" -w '%{http_code}' -X POST "$B/api/purchase/receives/$RC3ID/void" \
  -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d '{"reason":"测试"}')
check "PUSHED 作废 HTTP" "$PCODE" 400
grep -q "已下发 WMS" "$TMP/void-pushed.json" && ok "PUSHED 作废提示已下发 WMS" || ng "PUSHED 作废提示缺失"

echo
echo "================ 结果：PASS=$PASS FAIL=$FAIL ================"
[ "$FAIL" = "0" ] && echo "全部通过" || echo "存在失败项"
