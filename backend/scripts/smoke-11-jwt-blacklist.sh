#!/usr/bin/env bash
# 批次十一 curl 冒烟：单 token 登出黑名单
set -u
# 服务地址：默认 localhost:8080，可用 BASE（兼容旧名 B）覆盖
BASE=${BASE:-${B:-http://localhost:8080}}
B="$BASE"
R=${REDIS_CLI:-"/c/Program Files/Redis/redis-cli.exe"}

login() {
  curl -s -X POST "$B/api/auth/login" -H 'Content-Type: application/json' \
    -d '{"username":"admin","password":"admin123"}' | grep -o '"token":"[^"]*"' | cut -d'"' -f4
}

jti_of() {
  local payload
  payload=$(echo "$1" | cut -d. -f2 | tr '_-' '/+')
  case $(( ${#payload} % 4 )) in
    2) payload="$payload==" ;;
    3) payload="$payload=" ;;
  esac
  echo "$payload" | base64 -d 2>/dev/null | grep -o '"jti":"[^"]*"' | cut -d'"' -f4
}

echo "== 1) 同一用户登录两次，拿两个独立 token =="
TA=$(login); TB=$(login)
JA=$(jti_of "$TA"); JB=$(jti_of "$TB")
echo "tokenA jti=$JA"
echo "tokenB jti=$JB"
echo "两个 jti 不同: $([ -n "$JA" ] && [ "$JA" != "$JB" ] && echo yes || echo NO)"
echo "登出前 A /me = $(curl -s -o /dev/null -w '%{http_code}' $B/api/auth/me -H "Authorization: Bearer $TA")"
echo "登出前 B /me = $(curl -s -o /dev/null -w '%{http_code}' $B/api/auth/me -H "Authorization: Bearer $TB")"

echo
echo "== 2) 用 tokenA 登出 =="
curl -s -X POST "$B/api/auth/logout" -H "Authorization: Bearer $TA"; echo

echo
echo "== 3) Redis 黑名单 key =="
echo "A 命中 EXISTS=$("$R" exists "jwt:blacklist:$JA")  TTL=$("$R" ttl "jwt:blacklist:$JA") 秒"
echo "B 命中 EXISTS=$("$R" exists "jwt:blacklist:$JB")"
echo "key 样例： $("$R" --scan --pattern 'jwt:blacklist:*' | head -3 | tr '\n' ' ')"

echo
echo "== 4) 登出后访问 /me =="
echo "A（已登出） = $(curl -s -o /dev/null -w '%{http_code}' $B/api/auth/me -H "Authorization: Bearer $TA")"
echo "B（另一 token） = $(curl -s -o /dev/null -w '%{http_code}' $B/api/auth/me -H "Authorization: Bearer $TB")"

echo
echo "== 5) 退出时清理本次 A 的黑名单 key =="
"$R" del "jwt:blacklist:$JA" > /dev/null
echo "done"
