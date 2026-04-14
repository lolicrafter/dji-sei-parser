#!/usr/bin/env bash
set -euo pipefail

# 用法:
#   ./scripts/run_ws_poc.sh \
#     "/绝对路径/样本.h264" \
#     "rtmp://127.0.0.1/live/livestream" \
#     "127.0.0.1" \
#     "18080"

INPUT_H264="${1:-/Users/zhuwei/code/test/AISEI解析demo/测试文件及运行解析输出/dji_stream_drone_20251215_132402.h264}"
RTMP_URL="${2:-rtmp://127.0.0.1/live/livestream}"
WS_HOST="${3:-127.0.0.1}"
WS_PORT="${4:-18081}"

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

mkdir -p output
LOG_FILE="output/ws-parse.log"
PUSH_LOG="output/ws-push.log"
PULL_LOG="output/ws-pull.log"

echo "[1/3] 使用 JDK17 + Maven 编译项目..."
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
mvn -q -DskipTests package dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/dependency

echo "[2/3] 启动样本推流到 SRS(循环): $RTMP_URL"
push_loop() {
  while true; do
    ffmpeg -re -stream_loop -1 -fflags +genpts -r 25 -f h264 -i "$INPUT_H264" -c:v copy -f flv "$RTMP_URL" >>"$PUSH_LOG" 2>&1 || true
    echo "[warn] 推流进程异常退出，1秒后自动重启..." >>"$PUSH_LOG"
    sleep 1
  done
}
push_loop &
PUSH_PID=$!

cleanup() {
  kill -TERM "$PUSH_PID" >/dev/null 2>&1 || true
}
trap cleanup EXIT

sleep 2
echo "[3/3] 启动实时解析 + WebSocket 网关: ws://$WS_HOST:$WS_PORT"
echo "    日志: $LOG_FILE"

while true; do
  ffmpeg -fflags nobuffer -flags low_delay -i "$RTMP_URL" -an -c:v copy -bsf:v h264_mp4toannexb -f h264 - 2>"$PULL_LOG" \
  | java -cp "target/classes:target/dependency/*" demo.DjiAiSeiH264ParserDemo --stdin-ws "$WS_HOST" "$WS_PORT" "$LOG_FILE"
  echo "[warn] 拉流或解析中断，1秒后自动重连..."
  sleep 1
done
