#!/bin/sh
# Gradle wrapper 启动脚本（精简版，去掉 Windows 分支）
DIR=$(cd "$(dirname "$0")" && pwd)
CLASSPATH="$DIR/gradle/wrapper/gradle-wrapper.jar"
exec java -Xmx64m -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
