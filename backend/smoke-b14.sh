#!/usr/bin/env bash
# 批次十四 curl 冒烟：单据导出 / 入库出库详情 / 批量作废（部分成功）/ 批量提交返回结构
# 用法：B=http://127.0.0.1:18080 ./smoke-b14.sh
set -u
B=${B:-http://127.0.0.1:18080}
PSQL="/c/Program Files/PostgreSQL/17/bin/psql.exe"
export PGPASSWORD=${PGPASSWORD:-'Lb123456%'}
export PGCLIENTENCODING=UTF8
TS=$(date +%s)
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
num() { grep -o "\"$1\":[0-9]*" "$2" 2>/dev/null | head -1 | cut -d: -f2; }
str() { sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p" "$2" 2>/dev/null | head -1; }
id_of() { printf '%s' "$1" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2; }
ok() { PASS=$((PASS + 1)); echo "  PASS  $1"; }
ng() { FAIL=$((FAIL + 1)); echo "  FAIL  $1"; }
check() { if [ "$2" = "$3" ]; then ok "$1 ($2)"; else ng "$1 期望 $3，实际 $2"; fi; }
checkf() { if [ "$2" -gt "$3" ] 2>/dev/null; then ok "$1 ($2 > $3)"; else ng "$1 期望 > $3，实际 $2"; fi; }

echo "== 0) 登录与造审批人 =="
ADMIN=$(login admin admin123)
[ -n "$ADMIN" ] && ok "admin 登录" || { ng "admin 登录失败"; exit 1; }
APPR="b14-appr-$TS"
mkuser "$APPR" APPROVER
TA=$(login "$APPR" pass123)
[ -n "$TA" ] && ok "APPROVER 登录" || ng "APPROVER 登录失败"

SUP=$(sql "select id from supplier where first_audit_status='APPROVED' and status='ACTIVE' limit 1")
PROD=$(sql "select id from product where first_audit_status='APPROVED' and status='ACTIVE' and storage_cond='ROOM_TEMP' limit 1")
WH=$(sql "select id from warehouse where status='ACTIVE' and temp_type='ROOM_TEMP' limit 1")
CUS=$(sql "select id from customer where first_audit_status='APPROVED' and status='ACTIVE' limit 1")
echo "  夹具：supplier=$SUP product=$PROD warehouse=$WH customer=$CUS"

echo
echo "== 1) 采购订单：批量提交返回 BatchResult + 导出 =="
P1=$(curl -s -X POST "$B/api/purchase/orders" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d "{\"supplierId\":$SUP,\"orderDate\":\"$(date +%F)\",\"items\":[{\"productId\":$PROD,\"quantity\":30,\"unitPrice\":10}]}")
PO1=$(id_of "$P1")
P2=$(curl -s -X POST "$B/api/purchase/orders" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d "{\"supplierId\":$SUP,\"orderDate\":\"$(date +%F)\",\"items\":[{\"productId\":$PROD,\"quantity\":1,\"unitPrice\":10}]}")
PO2=$(id_of "$P2")
curl -s -X POST "$B/api/purchase/orders/batch-submit" -H "Authorization: Bearer $ADMIN" \
  -H 'Content-Type: application/json' -d "{\"ids\":[$PO1,$PO2]}" > "$TMP/batch-submit.json"
check "batch-submit total" "$(num total "$TMP/batch-submit.json")" 2
check "batch-submit successCount" "$(num successCount "$TMP/batch-submit.json")" 2
check "batch-submit failedCount" "$(num failedCount "$TMP/batch-submit.json")" 0
grep -q '"results"' "$TMP/batch-submit.json" && ok "batch-submit 含逐条 results" || ng "batch-submit 缺 results"

curl -s -D "$TMP/h-po.txt" -o "$TMP/po.xlsx" -w '%{http_code}' \
  "$B/api/purchase/orders/export?keyword=&status=SUBMITTED" -H "Authorization: Bearer $ADMIN" > "$TMP/c-po.txt"
check "采购订单导出 HTTP" "$(cat "$TMP/c-po.txt")" 200
grep -qi "spreadsheetml.sheet" "$TMP/h-po.txt" && ok "采购订单导出 Content-Type=xlsx" || ng "采购订单导出 Content-Type 非 xlsx"
checkf "采购订单导出文件大小" "$(stat -c%s "$TMP/po.xlsx")" 1000

echo
echo "== 2) 审批采购订单并开 3 张入库单 =="
curl -s -X POST "$B/api/approvals/PURCHASE_ORDER/$PO1/approve" -H "Authorization: Bearer $TA" \
  -H 'Content-Type: application/json' -d '{"comment":"agree"}' > /dev/null
RC1=""; RC2=""; RC3=""
for i in 1 2 3; do
  BATCH="SM14-$TS-$i"
  R=$(curl -s -X POST "$B/api/purchase/receives" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
    -d "{\"warehouseId\":$WH,\"orderId\":$PO1,\"receiveDate\":\"$(date +%F)\",\"items\":[{\"productId\":$PROD,\"quantity\":10,\"batchNo\":\"$BATCH\",\"produceDate\":\"$(date -d '-5 day' +%F)\",\"expireDate\":\"$(date -d '+180 day' +%F)\",\"unitCost\":10,\"inspectionNo\":\"INS-$BATCH\"}]}")
  eval "RC$i=\$(id_of \"\$R\")"
  eval "RNO$i=\$(echo '$R' | sed -n 's/.*\"receiveNo\":\"\\([^\"]*\\)\".*/\\1/p' | head -1)"
  eval "RCID=\$RC$i"
  curl -s -X POST "$B/api/purchase/receives/$RCID/submit" -H "Authorization: Bearer $ADMIN" > /dev/null
  curl -s -X POST "$B/api/approvals/PURCHASE_RECEIVE/$RCID/approve" -H "Authorization: Bearer $TA" \
    -H 'Content-Type: application/json' -d '{"comment":"agree"}' > /dev/null
  curl -s -X POST "$B/api/purchase/receives/$RCID/wms-callback" -H "Authorization: Bearer $ADMIN" \
    -H 'Content-Type: application/json' -d '{}' > /dev/null
done
echo "  入库单：$RC1 / $RC2 / $RC3"

echo
echo "== 3) 入库详情 =="
C=$(curl -s -o "$TMP/rc-detail.json" -w '%{http_code}' "$B/api/purchase/receives/$RC1" -H "Authorization: Bearer $ADMIN")
check "入库详情 HTTP" "$C" 200
grep -q '"items":\[{' "$TMP/rc-detail.json" && ok "入库详情含明细" || ng "入库详情缺明细"
grep -q '"supplierName":"' "$TMP/rc-detail.json" && ok "入库详情含供应商名" || ng "入库详情缺供应商名"
C=$(curl -s -o "$TMP/rc-404.json" -w '%{http_code}' "$B/api/purchase/receives/99999999" -H "Authorization: Bearer $ADMIN")
check "入库详情不存在 HTTP" "$C" 404
check "入库详情不存在 code" "$(num code "$TMP/rc-404.json")" 404

echo
echo "== 4) 入库导出（按单号精确命中） =="
curl -s -D "$TMP/h-rc.txt" -o "$TMP/rc.xlsx" "$B/api/purchase/receives/export?keyword=$RNO1" \
  -H "Authorization: Bearer $ADMIN" > /dev/null
grep -qi "spreadsheetml.sheet" "$TMP/h-rc.txt" && ok "采购入库导出 Content-Type=xlsx" || ng "采购入库导出 Content-Type 非 xlsx"
ROWS=$(grep -i "^X-Export-Rows:" "$TMP/h-rc.txt" | tr -d '\r' | awk '{print $2}')
check "采购入库导出行数" "$ROWS" 1
checkf "采购入库导出文件大小" "$(stat -c%s "$TMP/rc.xlsx")" 1000

echo
echo "== 5) 批量作废：部分成功（一条已作废 + 一条正常） =="
curl -s -X POST "$B/api/purchase/receives/$RC1/void" -H "Authorization: Bearer $ADMIN" \
  -H 'Content-Type: application/json' -d '{"reason":"单条先作废"}' > /dev/null
curl -s -X POST "$B/api/purchase/receives/batch-void" -H "Authorization: Bearer $ADMIN" \
  -H 'Content-Type: application/json' -d "{\"ids\":[$RC1,$RC2],\"reason\":\"批量作废\"}" > "$TMP/bv.json"
check "batch-void total" "$(num total "$TMP/bv.json")" 2
check "batch-void successCount" "$(num successCount "$TMP/bv.json")" 1
check "batch-void failedCount" "$(num failedCount "$TMP/bv.json")" 1
grep -q '"success":false' "$TMP/bv.json" && ok "batch-void 含失败明细" || ng "batch-void 缺失败明细"
grep -q '已作废' "$TMP/bv.json" && ok "batch-void 失败原因含已作废" || ng "batch-void 失败原因缺已作废"
ST=$(sql "select status from purchase_receive where id=$RC2")
check "RC2 已置 VOID" "$ST" VOID

C=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$B/api/purchase/receives/batch-void" \
  -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d '{"ids":[]}')
check "batch-void 空 ids" "$C" 400

echo
echo "== 6) 销售出库：下单→审批→出库→详情→导出→批量作废 =="
SO=$(curl -s -X POST "$B/api/sales/orders" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d "{\"customerId\":$CUS,\"orderDate\":\"$(date +%F)\",\"items\":[{\"productId\":$PROD,\"quantity\":8,\"unitPrice\":20}]}")
SOID=$(id_of "$SO")
curl -s -X POST "$B/api/sales/orders/$SOID/submit" -H "Authorization: Bearer $ADMIN" > /dev/null
curl -s -X POST "$B/api/approvals/SALES_ORDER/$SOID/approve" -H "Authorization: Bearer $TA" \
  -H 'Content-Type: application/json' -d '{"comment":"agree"}' > /dev/null
DO=$(curl -s -X POST "$B/api/sales/deliveries" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d "{\"warehouseId\":$WH,\"orderId\":$SOID,\"deliveryDate\":\"$(date +%F)\",\"items\":[{\"productId\":$PROD,\"quantity\":8}]}")
DOID=$(id_of "$DO")
DNO=$(echo "$DO" | sed -n 's/.*"deliveryNo":"\([^"]*\)".*/\1/p' | head -1)
curl -s -X POST "$B/api/sales/deliveries/$DOID/submit" -H "Authorization: Bearer $ADMIN" > /dev/null
curl -s -X POST "$B/api/approvals/SALES_DELIVERY/$DOID/approve" -H "Authorization: Bearer $TA" \
  -H 'Content-Type: application/json' -d '{"comment":"agree"}' > /dev/null
curl -s -X POST "$B/api/sales/deliveries/$DOID/wms-callback" -H "Authorization: Bearer $ADMIN" \
  -H 'Content-Type: application/json' -d '{}' > /dev/null
echo "  出库单：$DNO (id=$DOID)"

C=$(curl -s -o "$TMP/do-detail.json" -w '%{http_code}' "$B/api/sales/deliveries/$DOID" -H "Authorization: Bearer $ADMIN")
check "出库详情 HTTP" "$C" 200
grep -q '"items":\[{' "$TMP/do-detail.json" && ok "出库详情含明细" || ng "出库详情缺明细"
grep -q '"customerName":"' "$TMP/do-detail.json" && ok "出库详情含客户名" || ng "出库详情缺客户名"
C=$(curl -s -o /dev/null -w '%{http_code}' "$B/api/sales/deliveries/99999999" -H "Authorization: Bearer $ADMIN")
check "出库详情不存在 HTTP" "$C" 404

curl -s -D "$TMP/h-do.txt" -o "$TMP/do.xlsx" "$B/api/sales/deliveries/export?keyword=$DNO" -H "Authorization: Bearer $ADMIN" > /dev/null
grep -qi "spreadsheetml.sheet" "$TMP/h-do.txt" && ok "销售出库导出 Content-Type=xlsx" || ng "销售出库导出 Content-Type 非 xlsx"
DOBATCH=$(grep -o '"batchNo":"[^"]*"' "$TMP/do-detail.json" | head -1 | cut -d'"' -f4)

echo
echo "== 7) 退货单导出（销售退货 / 采购退货） =="
SR=$(curl -s -X POST "$B/api/sales/returns" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d "{\"deliveryId\":$DOID,\"returnDate\":\"$(date +%F)\",\"reason\":\"冒烟\",\"items\":[{\"productId\":$PROD,\"batchNo\":\"$DOBATCH\",\"quantity\":1,\"warehouseId\":$WH}]}")
SRNO=$(echo "$SR" | sed -n 's/.*"returnNo":"\([^"]*\)".*/\1/p' | head -1)
curl -s -D "$TMP/h-sr.txt" -o "$TMP/sr.xlsx" "$B/api/sales/returns/export?keyword=$SRNO" -H "Authorization: Bearer $ADMIN" > /dev/null
grep -qi "spreadsheetml.sheet" "$TMP/h-sr.txt" && ok "销售退货导出 Content-Type=xlsx" || ng "销售退货导出 Content-Type 非 xlsx"
check "销售退货导出行数" "$(grep -i '^X-Export-Rows:' "$TMP/h-sr.txt" | tr -d '\r' | awk '{print $2}')" 1

PR=$(curl -s -X POST "$B/api/purchase/returns" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d "{\"receiveId\":$RC3,\"supplierId\":$SUP,\"returnDate\":\"$(date +%F)\",\"reason\":\"冒烟\",\"items\":[{\"productId\":$PROD,\"batchNo\":\"SM14-$TS-3\",\"quantity\":2,\"warehouseId\":$WH}]}")
PRNO=$(echo "$PR" | sed -n 's/.*"returnNo":"\([^"]*\)".*/\1/p' | head -1)
curl -s -D "$TMP/h-pr.txt" -o "$TMP/pr.xlsx" "$B/api/purchase/returns/export?keyword=$PRNO" -H "Authorization: Bearer $ADMIN" > /dev/null
grep -qi "spreadsheetml.sheet" "$TMP/h-pr.txt" && ok "采购退货导出 Content-Type=xlsx" || ng "采购退货导出 Content-Type 非 xlsx"
check "采购退货导出行数" "$(grep -i '^X-Export-Rows:' "$TMP/h-pr.txt" | tr -d '\r' | awk '{print $2}')" 1

curl -s -D "$TMP/h-so.txt" -o "$TMP/so.xlsx" "$B/api/sales/orders/export" -H "Authorization: Bearer $ADMIN" > /dev/null
grep -qi "spreadsheetml.sheet" "$TMP/h-so.txt" && ok "销售订单导出 Content-Type=xlsx" || ng "销售订单导出 Content-Type 非 xlsx"

echo
echo "== 7.5) 订单批量作废（采购/销售） =="
OV1=$(curl -s -X POST "$B/api/purchase/orders" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d "{\"supplierId\":$SUP,\"orderDate\":\"$(date +%F)\",\"items\":[{\"productId\":$PROD,\"quantity\":1,\"unitPrice\":1}]}")
OV1ID=$(id_of "$OV1")
OV2=$(curl -s -X POST "$B/api/purchase/orders" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d "{\"supplierId\":$SUP,\"orderDate\":\"$(date +%F)\",\"items\":[{\"productId\":$PROD,\"quantity\":1,\"unitPrice\":1}]}")
OV2ID=$(id_of "$OV2")
curl -s -X POST "$B/api/purchase/orders/batch-void" -H "Authorization: Bearer $ADMIN" \
  -H 'Content-Type: application/json' -d "{\"ids\":[$OV1ID,$OV2ID],\"reason\":\"批量作废订单\"}" > "$TMP/ov.json"
check "采购订单 batch-void successCount" "$(num successCount "$TMP/ov.json")" 2
curl -s -X POST "$B/api/purchase/orders/batch-void" -H "Authorization: Bearer $ADMIN" \
  -H 'Content-Type: application/json' -d "{\"ids\":[$OV1ID],\"reason\":\"重复作废\"}" > "$TMP/ov2.json"
check "重复作废 failedCount" "$(num failedCount "$TMP/ov2.json")" 1
check "采购订单状态 VOID" "$(sql "select status from purchase_order where id=$OV1ID")" VOID

SON=$(curl -s -X POST "$B/api/sales/orders" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d "{\"customerId\":$CUS,\"orderDate\":\"$(date +%F)\",\"items\":[{\"productId\":$PROD,\"quantity\":1,\"unitPrice\":1}]}")
SONID=$(id_of "$SON")
curl -s -X POST "$B/api/sales/orders/batch-void" -H "Authorization: Bearer $ADMIN" \
  -H 'Content-Type: application/json' -d "{\"ids\":[$SONID],\"reason\":\"批量作废\"}" > "$TMP/sov.json"
check "销售订单 batch-void successCount" "$(num successCount "$TMP/sov.json")" 1

echo
echo "== 7.6) 退货详情 =="
SRID=$(sql "select id from sales_return where return_no='$SRNO'")
C=$(curl -s -o "$TMP/sr-detail.json" -w '%{http_code}' "$B/api/sales/returns/$SRID" -H "Authorization: Bearer $ADMIN")
check "销售退货详情 HTTP" "$C" 200
grep -q '"items":\[{' "$TMP/sr-detail.json" && ok "销售退货详情含明细" || ng "销售退货详情缺明细"
PRID=$(sql "select id from purchase_return where return_no='$PRNO'")
C=$(curl -s -o "$TMP/pr-detail.json" -w '%{http_code}' "$B/api/purchase/returns/$PRID" -H "Authorization: Bearer $ADMIN")
check "采购退货详情 HTTP" "$C" 200
grep -q '"items":\[{' "$TMP/pr-detail.json" && ok "采购退货详情含明细" || ng "采购退货详情缺明细"
C=$(curl -s -o /dev/null -w '%{http_code}' "$B/api/sales/returns/99999999" -H "Authorization: Bearer $ADMIN")
check "销售退货详情不存在" "$C" 404

echo
echo "== 7.7) 仓库调拨：详情 / 导出 / 批量作废 =="
WH2=$(sql "insert into warehouse(name,temp_type) values('B14-TRANSFER-TARGET-$TS','ROOM_TEMP') returning id" | head -1)
# 调拨需要独立可用批次：新开一张采购订单并入库 3 件
TRPO=$(curl -s -X POST "$B/api/purchase/orders" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d "{\"supplierId\":$SUP,\"orderDate\":\"$(date +%F)\",\"items\":[{\"productId\":$PROD,\"quantity\":3,\"unitPrice\":4}]}")
TRPOID=$(id_of "$TRPO")
curl -s -X POST "$B/api/purchase/orders/$TRPOID/submit" -H "Authorization: Bearer $ADMIN" > /dev/null
curl -s -X POST "$B/api/approvals/PURCHASE_ORDER/$TRPOID/approve" -H "Authorization: Bearer $TA" \
  -H 'Content-Type: application/json' -d '{"comment":"agree"}' > /dev/null
TRBATCH="SM14-TR-$TS"
TRRC=$(curl -s -X POST "$B/api/purchase/receives" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d "{\"warehouseId\":$WH,\"orderId\":$TRPOID,\"receiveDate\":\"$(date +%F)\",\"items\":[{\"productId\":$PROD,\"quantity\":3,\"batchNo\":\"$TRBATCH\",\"produceDate\":\"$(date -d '-5 day' +%F)\",\"expireDate\":\"$(date -d '+180 day' +%F)\",\"unitCost\":4,\"inspectionNo\":\"INS-$TRBATCH\"}]}" )
TRRCID=$(id_of "$TRRC")
curl -s -X POST "$B/api/purchase/receives/$TRRCID/submit" -H "Authorization: Bearer $ADMIN" > /dev/null
curl -s -X POST "$B/api/approvals/PURCHASE_RECEIVE/$TRRCID/approve" -H "Authorization: Bearer $TA" \
  -H 'Content-Type: application/json' -d '{"comment":"agree"}' > /dev/null
curl -s -X POST "$B/api/purchase/receives/$TRRCID/wms-callback" -H "Authorization: Bearer $ADMIN" \
  -H 'Content-Type: application/json' -d '{}' > /dev/null
TRO=$(curl -s -X POST "$B/api/inventory/transfers" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d "{\"fromWarehouseId\":$WH,\"toWarehouseId\":$WH2,\"transferDate\":\"$(date +%F)\",\"remark\":\"冒烟\",\"items\":[{\"productId\":$PROD,\"batchNo\":\"$TRBATCH\",\"quantity\":1}]}")
TRID=$(id_of "$TRO")
TRNO=$(echo "$TRO" | sed -n 's/.*"transferNo":"\([^"]*\)".*/\1/p' | head -1)
curl -s -X POST "$B/api/inventory/transfers/$TRID/submit" -H "Authorization: Bearer $ADMIN" > /dev/null
curl -s -X POST "$B/api/approvals/STOCK_TRANSFER/$TRID/approve" -H "Authorization: Bearer $TA" \
  -H 'Content-Type: application/json' -d '{"comment":"agree"}' > /dev/null
curl -s -X POST "$B/api/inventory/transfers/$TRID/wms-callback" -H "Authorization: Bearer $ADMIN" \
  -H 'Content-Type: application/json' -d '{}' > /dev/null
C=$(curl -s -o "$TMP/tr-detail.json" -w '%{http_code}' "$B/api/inventory/transfers/$TRID" -H "Authorization: Bearer $ADMIN")
check "调拨详情 HTTP" "$C" 200
grep -q '"items":\[{' "$TMP/tr-detail.json" && ok "调拨详情含明细" || ng "调拨详情缺明细"
curl -s -D "$TMP/h-tr.txt" -o "$TMP/tr.xlsx" "$B/api/inventory/transfers/export?keyword=$TRNO" -H "Authorization: Bearer $ADMIN" > /dev/null
grep -qi "spreadsheetml.sheet" "$TMP/h-tr.txt" && ok "调拨导出 Content-Type=xlsx" || ng "调拨导出 Content-Type 非 xlsx"
check "调拨导出行数" "$(grep -i '^X-Export-Rows:' "$TMP/h-tr.txt" | tr -d '\r' | awk '{print $2}')" 1
curl -s -X POST "$B/api/inventory/transfers/batch-void" -H "Authorization: Bearer $ADMIN" \
  -H 'Content-Type: application/json' -d "{\"ids\":[$TRID],\"reason\":\"冒烟作废\"}" > "$TMP/trv.json"
check "调拨 batch-void successCount" "$(num successCount "$TMP/trv.json")" 1
check "调拨状态 VOID" "$(sql "select status from stock_transfer where id=$TRID")" VOID

echo
echo "== 8) 导出审计留痕 =="
N=$(sql "select count(*) from audit_log where action='EXPORT' and object_type in ('PURCHASE_ORDER','PURCHASE_RECEIVE','SALES_ORDER','SALES_DELIVERY','SALES_RETURN','PURCHASE_RETURN')")
checkf "EXPORT 审计条数" "$N" 5

echo
echo "================ 结果：PASS=$PASS FAIL=$FAIL ================"
[ "$FAIL" = "0" ] && echo "全部通过" || echo "存在失败项"
