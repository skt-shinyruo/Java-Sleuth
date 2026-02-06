#!/bin/bash

# 编译 examples 下的示例应用源码到独立输出目录（避免进入发布 jar/fat-jar）。
#
# 输出目录：target/examples-classes
# 源码目录：examples/src/main/java

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$PROJECT_DIR"

EXAMPLES_SRC_DIR="$PROJECT_DIR/examples/src/main/java"
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

JAVAC_BIN="${JAVAC:-javac}"
if ! command -v "${JAVAC_BIN}" >/dev/null 2>&1; then
    echo "未找到 javac，请确认当前环境为 JDK（JAVAC=${JAVAC_BIN}）" >&2
    exit 1
fi

release_flags=()
if "${JAVAC_BIN}" --help 2>&1 | grep -q -- "--release"; then
    release_flags+=(--release 8)
else
    release_flags+=(-source 8 -target 8)
fi

find "${EXAMPLES_SRC_DIR}" -type f -name "*.java" -print0 | \
    xargs -0 "${JAVAC_BIN}" -encoding UTF-8 "${release_flags[@]}" -d "${OUT_DIR}"

echo "✓ examples 编译完成：${OUT_DIR}"
