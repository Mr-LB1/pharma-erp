#!/usr/bin/env bash
# 批次四后端冒烟：财务 AR/AP、收付款核销、对账、退货、调拨、报表工作台
# 前置：后端已在 http://localhost:8080 运行，管理员 admin/admin123 可用
set -u
BASE=http://localhost:8080
JSON="Content-Type: application/json"
PY=python

jget() {
  $PY -c '
import sys,json
d=json.load(sys.stdin)
cur=d
for k in sys.argv[1].split("."):
    if not k: continue
    cur=cur.get(k) if isinstance(cur,dict) else None
    if cur is None: break
print("" if cur is None else cur)
' "$1"
}

TOK=$(curl -s -X POST $BASE/api/auth/login -H "$JSON" \
  -d '{"username":"admin","password":"admin123"}' | jget data.token)
AUTH="Authorization: Bearer $TOK"
CID=$(curl -s "$BASE/api/customers?size=1" -H "$AUTH" | $PY -c "import sys,json;r=json.load(sys.stdin)['data']['records'];print(r[0]['id'] if r else '')")
SID=$(curl -s "$BASE/api/suppliers?size=1" -H "$AUTH" | $PY -c "import sys,json;r=json.load(sys.stdin)['data']['records'];print(r[0]['id'] if r else '')")

echo "===== 1. 财务列表接口（AR/AP/收款/付款/发票） ====="
for p in /api/finance/ar-invoices /api/finance/ap-invoices /api/finance/receipts \
         /api/finance/payments /api/finance/invoice-registers; do
  echo -n "$p => "; curl -s "$BASE$p?page=1&size=1" -H "$AUTH" | head -c 120; echo
done

echo "===== 2. 手工应收 → 收款核销 → 对账平衡 ====="
AR=$(curl -s -X POST $BASE/api/finance/ar-invoices -H "$AUTH" -H "$JSON" \
  -d "{\"customerId\":$CID,\"amount\":100,\"remark\":\"冒烟应收\"}")
ARID=$(echo "$AR" | jget data.id)
echo "AR: $(echo "$AR" | head -c 200)"
curl -s -X POST $BASE/api/finance/receipts -H "$AUTH" -H "$JSON" \
  -d "{\"customerId\":$CID,\"amount\":40,\"remark\":\"冒烟收款\",\"items\":[{\"arInvoiceId\":$ARID,\"amount\":40}]}" | head -c 300; echo
echo -n "对账: "; curl -s "$BASE/api/finance/statements/ar?customerId=$CID" -H "$AUTH" \
  | $PY -c "import sys,json;d=json.load(sys.stdin)['data'];print('期初',d['openingBalance'],'发货',d['deliveryAmount'],'退货',d['returnAmount'],'收款',d['receiptAmount'],'期末',d['closingBalance'])"

echo "===== 3. 手工应付 + 发票登记 ====="
curl -s -X POST $BASE/api/finance/ap-invoices -H "$AUTH" -H "$JSON" \
  -d "{\"supplierId\":$SID,\"amount\":88,\"remark\":\"冒烟应付\"}" | head -c 220; echo
curl -s -X POST $BASE/api/finance/invoice-registers -H "$AUTH" -H "$JSON" \
  -d "{\"invoiceNo\":\"FP-SMOKE-$(date +%s)\",\"bizType\":\"MANUAL\",\"amount\":100}" | head -c 220; echo

echo "===== 4. 退货/调拨列表 ====="
for p in /api/sales/returns /api/purchase/returns /api/inventory/transfers; do
  echo -n "$p => "; curl -s "$BASE$p?page=1&size=1" -H "$AUTH" | head -c 120; echo
done

echo "===== 5. 报表工作台 ====="
echo -n "dashboard: "; curl -s "$BASE/api/reports/dashboard" -H "$AUTH" | head -c 420; echo
echo -n "gross-profit: "; curl -s "$BASE/api/reports/gross-profit" -H "$AUTH" | head -c 240; echo
echo -n "near-expiry 分页: "; curl -s "$BASE/api/reports/near-expiry?days=365&page=1&size=2" -H "$AUTH" | head -c 220; echo
echo -n "near-expiry 导出: "
curl -s -o /tmp/near-expiry.xlsx -w "http=%{http_code} bytes=%{size_download}\n" \
  "$BASE/api/reports/near-expiry/export?days=365" -H "$AUTH"
