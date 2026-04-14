# DJI AI 目标识别 SEI 解析 Java Demo

本 Demo 用于从 H.264 Annex-B 码流中解析 DJI 上云 API 推送的 **AI 目标识别 SEI 元数据**，并输出 JSONL 以及详细日志供调试。


##  构建

需要 JDK 11+ 与 Maven：

```bash
mvn clean package
```
## 运行

### 命令格式：
java -jar target/dji-sei-parser-1.0-SNAPSHOT.jar <input.h264> <output.jsonl> <parse.log>
示例：java -jar target/dji-sei-parser-1.0-SNAPSHOT.jar \
    D:\videos\input.h264 \
    D:\outputs\out.jsonl \
    D:\outputs\parse.log

### 实时流模式（方案A推荐）

命令格式：

```bash
java -jar target/dji-sei-parser-1.0-SNAPSHOT.jar --stdin <output.jsonl> <parse.log>
```

示例（从 RTMP 拉流，转 Annex-B H.264 到 stdin）：

```bash
ffmpeg -fflags nobuffer -flags low_delay \
  -i "rtmp://127.0.0.1/live/livestream" \
  -an -c:v copy -bsf:v h264_mp4toannexb -f h264 - \
| java -jar target/dji-sei-parser-1.0-SNAPSHOT.jar \
  --stdin "output/live.jsonl" "output/live-parse.log"
```

说明：

- `-c:v copy`：不解码不转码，尽量降低延迟。
- `-f h264 -`：输出原始 Annex-B 字节流到标准输出。
- Java 程序从 `stdin` 实时增量解析 NAL/SEI。

### WebSocket 网关模式（方案B，前端实时订阅）

命令格式：

```bash
java -cp "target/classes:target/dependency/*" demo.DjiAiSeiH264ParserDemo \
  --stdin-ws <host> <port> <parse.log>
```

示例（RTMP 拉流 -> 实时解析 -> WebSocket 推送）：

```bash
ffmpeg -fflags nobuffer -flags low_delay \
  -i "rtmp://127.0.0.1/live/livestream" \
  -an -c:v copy -bsf:v h264_mp4toannexb -f h264 - \
| java -cp "target/classes:target/dependency/*" demo.DjiAiSeiH264ParserDemo \
  --stdin-ws "0.0.0.0" "18080" "output/ws-parse.log"
```

前端连接地址：

```text
ws://127.0.0.1:18080
```

消息格式（只保留前端框选需要字段）：

```json
{
  "event": "sei_frame",
  "timeStampMs": 1428437,
  "trackId": 0,
  "targets": [
    {
      "id": 68,
      "type": 2,
      "state": 1,
      "cx": 5177,
      "cy": 8321,
      "w": 92,
      "h": 245,
      "cxNorm": 0.5177,
      "cyNorm": 0.8321,
      "wNorm": 0.0092,
      "hNorm": 0.0245
    }
  ]
}
```

### 使用本地 H.264 文件经 SRS 推流，再由后端解析（PoC）

1. 推流到 SRS（循环推）：

```bash
ffmpeg -re -stream_loop -1 \
  -f h264 -i "/Users/zhuwei/code/test/AISEI解析demo/测试文件及运行解析输出/dji_stream_drone_20251215_132402.h264" \
  -c:v copy -f flv "rtmp://127.0.0.1/live/livestream"
```

2. 后端从 SRS 拉流并实时解析：

```bash
ffmpeg -fflags nobuffer -flags low_delay \
  -i "rtmp://127.0.0.1/live/livestream" \
  -an -c:v copy -bsf:v h264_mp4toannexb -f h264 - \
| java -jar target/dji-sei-parser-1.0-SNAPSHOT.jar \
  --stdin "output/live.jsonl" "output/live-parse.log"
```
    
## 输出说明
output.jsonl

每行一个 JSON 对象，包含解析出的 AI 识别目标：
```json
{
  "version":1,
  "timeStampMs":1681072000,
  "frameType":1,
  "trackId":7,
  "objGroupCount":2,
  "groups": [...],
  "targets":[...],
  "typeCounts":[...]
}
```
parse.log

中文日志，记录每条 SEI 解析步骤、字段值和 HEX 数据，便于对齐协议和排查问题。
