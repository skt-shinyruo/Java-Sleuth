#!/bin/bash

# 编译 examples Maven 模块的示例应用源码到独立输出目录（避免进入发布 jar/fat-jar）。
#
# Maven 模块：examples/
# Maven 默认输出：examples/target/classes
# 兼容输出目录：target/examples-classes

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$PROJECT_DIR"

EXAMPLES_MODULE_DIR="$PROJECT_DIR/examples"
EXAMPLES_SRC_DIR="$EXAMPLES_MODULE_DIR/src/main/java"
MODULE_CLASSES_DIR="$EXAMPLES_MODULE_DIR/target/classes"
OUT_DIR="$PROJECT_DIR/target/examples-classes"

if [[ ! -d "${EXAMPLES_SRC_DIR}" ]]; then
    echo "examples 源码目录不存在：${EXAMPLES_SRC_DIR}" >&2
    exit 1
fi

mkdir -p "${OUT_DIR}"

if ! find "${EXAMPLES_SRC_DIR}" -type f -name "*.java" -print -quit | grep -q .; then
    echo "未找到 examples Java 源码：${EXAMPLES_SRC_DIR}" >&2
    exit 1
fi

MVN_BIN="${MVN:-mvn}"
if ! command -v "${MVN_BIN}" >/dev/null 2>&1; then
    echo "未找到 mvn，请确认已安装 Maven（MVN=${MVN_BIN}）" >&2
    exit 1
fi

"${MVN_BIN}" -q -pl examples -am -DskipTests package

if [[ ! -d "${MODULE_CLASSES_DIR}" ]]; then
    echo "examples 编译产物不存在：${MODULE_CLASSES_DIR}" >&2
    exit 1
fi

# 清理旧 class，避免示例源码删除/改名时残留旧产物影响演示。
find "${OUT_DIR}" -type f -name "*.class" -delete 2>/dev/null || true

cp -a "${MODULE_CLASSES_DIR}/." "${OUT_DIR}/"

echo "✓ examples 编译完成：${OUT_DIR}（来源：${MODULE_CLASSES_DIR}）"
