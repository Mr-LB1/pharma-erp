#!/usr/bin/env bash
# 用本地 JDK17 运行后端（不改系统 JAVA_HOME）
# 用法：./run.sh [额外 mvn 参数]
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
TOOLS="$(cd "$HERE/../../.tools" && pwd)"
export JAVA_HOME="$TOOLS/jdk-17"
MVN="$TOOLS/apache-maven-3.9.16/bin/mvn"
cd "$HERE"
exec "$MVN" spring-boot:run "$@"
