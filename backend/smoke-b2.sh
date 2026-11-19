#!/usr/bin/env bash
# 批次二后端冒烟：软删引用拦截/软删生效、入库合规、盘点时序重算、不合格品限仓与销毁留痕
set -u
BASE=http://localhost:8080
TOKEN=$(curl -s -X POST $BASE/api/auth/login -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' | sed -E 's/.*"token":"([^"]+)".*/\1/')
AUTH="Authorization: Bearer $TOKEN"
JSON="Content-Type: application/json"
TS=$(date +%s)

say() { echo; echo "===== $1 ====="; }

say "1. 删除被库存引用的商品（期望 400 + 引用文案）"
curl -s -X DELETE $BASE/api/products/91 -H "$AUTH"; echo

say "2. 无引用商品软删后列表不可见"
NEWP=$(curl -s -X POST $BASE/api/products -H "$AUTH" -H "$JSON" \
  -d "{\"code\":\"SMOKE-SD-$TS\",\"genericName\":\"冒烟软删商品\",\"status\":\"ACTIVE\",\"firstAuditStatus\":\"APPROVED\"}")
PID=$(echo "$NEWP" | sed -E 's/.*"id":([0-9]+).*/\1/')
echo "created id=$PID"
curl -s -X DELETE $BASE/api/products/$PID -H "$AUTH"; echo
echo "list total:"
curl -s "$BASE/api/products?keyword=SMOKE-SD-$TS" -H "$AUTH" | sed -E 's/.*"total":([0-9]+).*/\1/'

say "3. 新建常温仓（用于后续入库/盘点冒烟）"
W=$(curl -s -X POST $BASE/api/warehouses -H "$AUTH" -H "$JSON" \
  -d "{\"name\":\"冒烟常温仓-$TS\",\"tempType\":\"ROOM_TEMP\",\"status\":\"ACTIVE\"}")
WID=$(echo "$W" | sed -E 's/.*"id":([0-9]+).*/\1/')
echo "warehouse id=$WID"

say "4. 温控不兼容（常温药品 91 入 COOL 仓 1，期望 400）"
curl -s -X POST $BASE/api/purchase/receives -H "$AUTH" -H "$JSON" \
  -d "{\"warehouseId\":1,\"orderId\":101,\"items\":[{\"productId\":91,\"quantity\":1,\"batchNo\":\"SMOKE-TC-$TS\",\"produceDate\":\"2026-01-01\",\"expireDate\":\"2028-12-31\",\"unitCost\":5,\"inspectionNo\":\"INS-TC\"}]}"; echo

say "5. 效期早于今天（期望 400）"
curl -s -X POST $BASE/api/purchase/receives -H "$AUTH" -H "$JSON" \
  -d "{\"warehouseId\":$WID,\"orderId\":101,\"items\":[{\"productId\":91,\"quantity\":1,\"batchNo\":\"SMOKE-EXP-$TS\",\"produceDate\":\"2026-01-01\",\"expireDate\":\"2026-01-02\",\"unitCost\":5,\"inspectionNo\":\"INS-EXP\"}]}"; echo

say "6. 缺检验报告单号（期望 400）"
curl -s -X POST $BASE/api/purchase/receives -H "$AUTH" -H "$JSON" \
  -d "{\"warehouseId\":$WID,\"orderId\":101,\"items\":[{\"productId\":91,\"quantity\":1,\"batchNo\":\"SMOKE-INS-$TS\",\"produceDate\":\"2026-01-01\",\"expireDate\":\"2028-12-31\",\"unitCost\":5}]}"; echo

BATCH="SMOKE-TM-$TS"
say "7. 正常入库 2 件（批次 $BATCH）"
curl -s -o /dev/null -w "http=%{http_code}\n" -X POST $BASE/api/purchase/receives -H "$AUTH" -H "$JSON" \
  -d "{\"warehouseId\":$WID,\"orderId\":101,\"items\":[{\"productId\":91,\"quantity\":2,\"batchNo\":\"$BATCH\",\"produceDate\":\"2026-01-01\",\"expireDate\":\"2028-12-31\",\"unitCost\":5,\"inspectionNo\":\"INS-SMOKE\"}]}"

say "8. 建盘点单（账面 2，实盘 5，快照盈 3）"
ST=$(curl -s -X POST $BASE/api/inventory/stocktakes -H "$AUTH" -H "$JSON" \
  -d "{\"warehouseId\":$WID,\"items\":[{\"productId\":91,\"batchNo\":\"$BATCH\",\"actualQty\":5}]}")
STID=$(echo "$ST" | sed -E 's/.*"id":([0-9]+).*/\1/' | head -1)
echo "$ST" | head -c 400; echo
echo "stocktake id=$STID"

say "9. 盘点期间再入库 2 件（账面变 4）"
curl -s -o /dev/null -w "http=%{http_code}\n" -X POST $BASE/api/purchase/receives -H "$AUTH" -H "$JSON" \
  -d "{\"warehouseId\":$WID,\"orderId\":101,\"items\":[{\"productId\":91,\"quantity\":2,\"batchNo\":\"$BATCH\",\"produceDate\":\"2026-01-01\",\"expireDate\":\"2028-12-31\",\"unitCost\":5,\"inspectionNo\":\"INS-SMOKE2\"}]}"

say "10. 提交盘点（应按提交时账面 4 重算 +1，最终 5；旧实现会变 7）"
curl -s -o /dev/null -w "http=%{http_code}\n" -X POST $BASE/api/inventory/stocktakes/$STID/submit -H "$AUTH"
curl -s "$BASE/api/inventory/stocks?productId=91&batchNo=$BATCH" -H "$AUTH" | head -c 500; echo

say "11. 不合格品登记（限仓扣减 1）"
UQ=$(curl -s -X POST $BASE/api/quality/unqualified -H "$AUTH" -H "$JSON" \
  -d "{\"productId\":91,\"warehouseId\":$WID,\"batchNo\":\"$BATCH\",\"quantity\":1,\"reason\":\"包装破损\"}")
echo "$UQ" | head -c 400; echo
UQID=$(echo "$UQ" | sed -E 's/.*"id":([0-9]+).*/\1/' | head -1)

say "12. 不合格品处理（销毁留痕）"
curl -s -X POST $BASE/api/quality/unqualified/$UQID/dispose -H "$AUTH" -H "$JSON" \
  -d "{\"disposeMethod\":\"销毁\",\"disposedAt\":\"$(date +%Y-%m-%d)\",\"disposedBy\":\"监督员A\"}" | head -c 500; echo

say "13. 他仓登记同批次（应 400 库存不足，不跨仓扣减）"
curl -s -X POST $BASE/api/quality/unqualified -H "$AUTH" -H "$JSON" \
  -d "{\"productId\":91,\"warehouseId\":1,\"batchNo\":\"$BATCH\",\"quantity\":1,\"reason\":\"跨仓尝试\"}"; echo
