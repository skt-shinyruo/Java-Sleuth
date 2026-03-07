#!/bin/bash

# Java-Sleuth Startup Script

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

LAUNCHER_JAR=""
if [ -f "$SCRIPT_DIR/java-sleuth-launcher.jar" ]; then
    LAUNCHER_JAR="$SCRIPT_DIR/java-sleuth-launcher.jar"
else
    LAUNCHER_JAR="$(ls -1t "$SCRIPT_DIR"/launcher/target/java-sleuth-launcher*-jar-with-dependencies.jar 2>/dev/null | head -n 1 || true)"
    if [ -z "${LAUNCHER_JAR}" ]; then
        LAUNCHER_JAR="$(ls -1t "$SCRIPT_DIR"/target/java-sleuth-launcher*-jar-with-dependencies.jar 2>/dev/null | head -n 1 || true)"
    fi
    if [ -z "${LAUNCHER_JAR}" ]; then
        LAUNCHER_JAR="$(ls -1t "$SCRIPT_DIR"/lib/java-sleuth-launcher*-jar-with-dependencies.jar 2>/dev/null | head -n 1 || true)"
    fi
    if [ -z "${LAUNCHER_JAR}" ]; then
        LAUNCHER_JAR="$(ls -1t "$SCRIPT_DIR"/../lib/java-sleuth-launcher*-jar-with-dependencies.jar 2>/dev/null | head -n 1 || true)"
    fi
fi

# Backward compatibility: legacy single fat-jar (artifactId=java-sleuth)
if [ -z "${LAUNCHER_JAR}" ]; then
    LAUNCHER_JAR="$(ls -1t "$SCRIPT_DIR"/core/target/java-sleuth-[0-9]*-jar-with-dependencies.jar 2>/dev/null | head -n 1 || true)"
fi
if [ -z "${LAUNCHER_JAR}" ]; then
    LAUNCHER_JAR="$(ls -1t "$SCRIPT_DIR"/target/java-sleuth-[0-9]*-jar-with-dependencies.jar 2>/dev/null | head -n 1 || true)"
fi
if [ -z "${LAUNCHER_JAR}" ]; then
    LAUNCHER_JAR="$(ls -1t "$SCRIPT_DIR"/lib/java-sleuth-[0-9]*-jar-with-dependencies.jar 2>/dev/null | head -n 1 || true)"
fi
if [ -z "${LAUNCHER_JAR}" ]; then
    LAUNCHER_JAR="$(ls -1t "$SCRIPT_DIR"/../lib/java-sleuth-[0-9]*-jar-with-dependencies.jar 2>/dev/null | head -n 1 || true)"
fi

AGENT_JAR=""
if [ -f "$SCRIPT_DIR/java-sleuth-agent.jar" ]; then
    AGENT_JAR="$SCRIPT_DIR/java-sleuth-agent.jar"
else
    AGENT_JAR="$(ls -1t "$SCRIPT_DIR"/agent/target/java-sleuth-agent-[0-9]*-jar-with-dependencies.jar 2>/dev/null | head -n 1 || true)"
    if [ -z "${AGENT_JAR}" ]; then
        AGENT_JAR="$(ls -1t "$SCRIPT_DIR"/target/java-sleuth-agent-[0-9]*-jar-with-dependencies.jar 2>/dev/null | head -n 1 || true)"
    fi
    if [ -z "${AGENT_JAR}" ]; then
        AGENT_JAR="$(ls -1t "$SCRIPT_DIR"/lib/java-sleuth-agent-[0-9]*-jar-with-dependencies.jar 2>/dev/null | head -n 1 || true)"
    fi
    if [ -z "${AGENT_JAR}" ]; then
        AGENT_JAR="$(ls -1t "$SCRIPT_DIR"/../lib/java-sleuth-agent-[0-9]*-jar-with-dependencies.jar 2>/dev/null | head -n 1 || true)"
    fi
fi

# Backward compatibility: legacy agent fat-jar built under core/target (artifactId=java-sleuth-agent)
if [ -z "${AGENT_JAR}" ]; then
    AGENT_JAR="$(ls -1t "$SCRIPT_DIR"/core/target/java-sleuth-agent-[0-9]*-jar-with-dependencies.jar 2>/dev/null | head -n 1 || true)"
fi

if [ -z "${AGENT_JAR}" ] && [ -n "${LAUNCHER_JAR}" ]; then
    base="$(basename "$LAUNCHER_JAR")"
    if [[ "$base" == java-sleuth-[0-9]*-jar-with-dependencies.jar ]]; then
        AGENT_JAR="$LAUNCHER_JAR"
    fi
fi

CONTAINER_JAR=""
if [ -f "$SCRIPT_DIR/java-sleuth-container.jar" ]; then
    CONTAINER_JAR="$SCRIPT_DIR/java-sleuth-container.jar"
else
    CONTAINER_JAR="$(ls -1t "$SCRIPT_DIR"/container/target/java-sleuth-container*-jar-with-dependencies.jar 2>/dev/null | head -n 1 || true)"
    if [ -z "${CONTAINER_JAR}" ]; then
        CONTAINER_JAR="$(ls -1t "$SCRIPT_DIR"/target/java-sleuth-container*-jar-with-dependencies.jar 2>/dev/null | head -n 1 || true)"
    fi
    if [ -z "${CONTAINER_JAR}" ]; then
        CONTAINER_JAR="$(ls -1t "$SCRIPT_DIR"/lib/java-sleuth-container*-jar-with-dependencies.jar 2>/dev/null | head -n 1 || true)"
    fi
    if [ -z "${CONTAINER_JAR}" ]; then
        CONTAINER_JAR="$(ls -1t "$SCRIPT_DIR"/../lib/java-sleuth-container*-jar-with-dependencies.jar 2>/dev/null | head -n 1 || true)"
    fi
fi

if [ -z "${LAUNCHER_JAR}" ] || [ ! -f "$LAUNCHER_JAR" ]; then
    echo "Java-Sleuth launcher JAR not found under:"
    echo "  - $SCRIPT_DIR/launcher/target/"
    echo "  - $SCRIPT_DIR/target/"
    echo "  - $SCRIPT_DIR/lib/"
    echo "  - $SCRIPT_DIR/../lib/"
    echo "Please build the project first with: mvn clean package"
    exit 1
fi

if [ -z "${AGENT_JAR}" ] || [ ! -f "$AGENT_JAR" ]; then
    echo "Java-Sleuth agent JAR not found under:"
    echo "  - $SCRIPT_DIR/agent/target/"
    echo "  - $SCRIPT_DIR/core/target/ (legacy)"
    echo "  - $SCRIPT_DIR/target/"
    echo "  - $SCRIPT_DIR/lib/"
    echo "  - $SCRIPT_DIR/../lib/"
    echo "Tip: set -Dsleuth.agent.jar=<path> (or env SLEUTH_AGENT_JAR)"
    echo "Please build the project first with: mvn clean package"
    exit 1
fi

base="$(basename "$AGENT_JAR")"
if [ -z "${CONTAINER_JAR}" ] || [ ! -f "$CONTAINER_JAR" ]; then
    # Legacy: artifactId=java-sleuth single fat-jar can still work without a separate container jar.
    if [[ "$base" != java-sleuth-[0-9]*-jar-with-dependencies.jar ]]; then
        echo "Java-Sleuth agent CONTAINER JAR not found under:"
        echo "  - $SCRIPT_DIR/java-sleuth-container.jar"
        echo "  - $SCRIPT_DIR/container/target/"
        echo "  - $SCRIPT_DIR/target/"
        echo "  - $SCRIPT_DIR/lib/"
        echo "  - $SCRIPT_DIR/../lib/"
        echo "Tip: set -Dsleuth.agent.container.jar=<path> (or env SLEUTH_AGENT_CONTAINER_JAR)"
        echo "Please build the project first with: mvn clean package"
        exit 1
    fi
fi

# Check if tools.jar is available (required for JDK < 9)
JAVA_SPEC="$(java -XshowSettings:properties -version 2>&1 | awk -F' = ' '/java.specification.version =/ {print $2; exit}')"
JAVA_MAJOR=""
if [ -n "${JAVA_SPEC}" ]; then
    if [[ "${JAVA_SPEC}" == 1.* ]]; then
        JAVA_MAJOR="${JAVA_SPEC#1.}"
        JAVA_MAJOR="${JAVA_MAJOR%%.*}"
    else
        JAVA_MAJOR="${JAVA_SPEC%%.*}"
    fi
fi

CLASSPATH="$LAUNCHER_JAR"
if [ -n "${JAVA_MAJOR}" ] && [ "${JAVA_MAJOR}" -lt 9 ]; then
    TOOLS_JAR="${JAVA_HOME:-}/lib/tools.jar"
    if [ -z "${JAVA_HOME:-}" ] || [ ! -f "$TOOLS_JAR" ]; then
        echo "tools.jar not found. Please set JAVA_HOME correctly for JDK < 9"
        exit 1
    fi
    CLASSPATH="$LAUNCHER_JAR:$TOOLS_JAR"
fi

echo "Starting Java-Sleuth..."
if [ -n "${JAVA_MAJOR}" ]; then
    echo "Java Major Version: $JAVA_MAJOR"
fi
echo "Launcher JAR: $LAUNCHER_JAR"
echo "Agent JAR: $AGENT_JAR"
if [ -n "${CONTAINER_JAR}" ]; then
    echo "Agent CONTAINER JAR: $CONTAINER_JAR"
fi

LAUNCHER_JAVA_OPTS=()
if [ -n "${SLEUTH_CONFIG_FILE:-}" ]; then
    LAUNCHER_JAVA_OPTS+=("-Dsleuth.config.file=${SLEUTH_CONFIG_FILE}")
fi
if [ -n "${CONTAINER_JAR}" ]; then
    LAUNCHER_JAVA_OPTS+=("-Dsleuth.agent.container.jar=${CONTAINER_JAR}")
fi

java "${LAUNCHER_JAVA_OPTS[@]}" -Dsleuth.agent.jar="$AGENT_JAR" -cp "$CLASSPATH" com.javasleuth.launcher.SleuthLauncher "$@"
