package demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DJI 上云API - AI目标识别 SEI 解析 Demo（H.264 Annex-B）
 *
 * 1) Annex-B 拆 NAL
 * 2) 找 nal_unit_type=6(SEI)
 * 3) 不要对整段 RBSP 先去 EPB！！（否则长度会对不上）
 * 4) 直接从 RBSP(含EPB) 读取 sei_type(0xF5/0x65) 和 sei_len（0xFF 连续累加规则）
 * 5) 按 sei_len 从 RBSP(含EPB) 切出 extension(payload) 原始字节
 * 6) 仅对 extension(payload) 做 EPB 去除（00 00 03 -> 去掉 03）
 * 7) 在 extension 中解析 payloads： [type(2)] [len(2)] [data(len)]
 * 8) 找 type=0x0007 的 payload 解析目标识别结构
 *
 * 输出：
 * - JSONL：每个解析出的目标帧一行
 * - LOG：全链路中文日志（非常详细，单独文件）
 */
public class DjiAiSeiH264ParserDemo {

    // ======= Webrtc和声网两种 sei_type =======
    private static final int DJI_SEI_TYPE_PUBLIC_F5 = 0xF5;
    private static final int DJI_SEI_TYPE_AGORA_65  = 0x65;

    // ======= AI payload type = 0x07（2字节） =======
    private static final int DJI_PAYLOAD_TYPE_TARGET = 0x0007;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    // 日志最多打印多少字节的十六进制（避免日志爆炸）
    private static final int HEX_DUMP_MAX = 96;

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("用法1(文件模式): java demo.DjiAiSeiH264ParserDemo <input.h264> <output.jsonl> <parse.log>");
            System.out.println("示例1: java demo.DjiAiSeiH264ParserDemo D:\\DJISEI\\input.h264 D:\\DJISEI\\output.jsonl D:\\DJISEI\\parse.log");
            System.out.println("用法2(实时模式): java demo.DjiAiSeiH264ParserDemo --stdin <output.jsonl> <parse.log>");
            System.out.println("示例2: ffmpeg ... -f h264 - | java -jar dji-sei-parser.jar --stdin output.jsonl parse.log");
            System.out.println("用法3(WebSocket网关模式): java demo.DjiAiSeiH264ParserDemo --stdin-ws <host> <port> <parse.log>");
            System.out.println("示例3: ffmpeg ... -f h264 - | java -cp \"target/classes:target/dependency/*\" demo.DjiAiSeiH264ParserDemo --stdin-ws 0.0.0.0 18080 parse.log");
            return;
        }

        if ("--stdin".equalsIgnoreCase(args[0])) {
            Path outputJsonl = Path.of(args[1]);
            Path logFile = Path.of(args[2]);
            runStdinMode(System.in, outputJsonl, logFile);
            return;
        }

        if ("--stdin-ws".equalsIgnoreCase(args[0])) {
            if (args.length < 4) {
                throw new IllegalArgumentException("--stdin-ws 参数不足，期望: --stdin-ws <host> <port> <parse.log>");
            }
            String host = args[1];
            int port = Integer.parseInt(args[2]);
            Path logFile = Path.of(args[3]);
            runStdinWsMode(System.in, host, port, logFile);
            return;
        }

        Path input = Path.of(args[0]);
        Path outputJsonl = Path.of(args[1]);
        Path logFile = Path.of(args[2]);

        try (Log log = new Log(logFile);
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(outputJsonl.toFile()), "UTF-8"))) {

            log.i("== DJI 上云API AI目标识别 SEI 解析 Demo（H.264 Annex-B）==");
            log.i("输入文件: " + input);
            log.i("输出JSONL: " + outputJsonl);
            log.i("输出日志: " + logFile);

            byte[] bytes = Files.readAllBytes(input);
            log.i("[步骤1] 读取文件完成，字节数=" + bytes.length);

            List<NalUnit> nals = splitAnnexBNals(bytes, log);
            log.i("[步骤2] Annex-B 拆分 NAL 完成，总数=" + nals.size());

            ParseStats stats = new ParseStats();

            for (int i = 0; i < nals.size(); i++) {
                NalUnit nal = nals.get(i);
                processSingleNal(nal, i, out, log, stats, null);
            }

            log.i("");
            log.i("== 解析结束 ==");
            log.i("SEI NAL 数量=" + stats.seiNalCount);
            log.i("输出帧数(JSONL行数)=" + stats.outputFrames);
            log.i("如果输出仍为0：重点看日志 [步骤5][步骤6][步骤9] 是否出现 payloadType=0x0007。");
        }
    }

    private static void runStdinMode(InputStream in, Path outputJsonl, Path logFile) throws Exception {
        try (Log log = new Log(logFile);
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(outputJsonl.toFile()), "UTF-8"))) {

            log.i("== DJI 上云API AI目标识别 SEI 实时解析模式（stdin, H.264 Annex-B）==");
            log.i("输出JSONL: " + outputJsonl);
            log.i("输出日志: " + logFile);
            log.i("提示：stdin 应输入原始 H.264 Annex-B 字节流（例如 ffmpeg -f h264 - 输出）。");

            ParseStats stats = new ParseStats();
            AnnexBStreamSplitter splitter = new AnnexBStreamSplitter();

            byte[] chunk = new byte[64 * 1024];
            int n;
            while ((n = in.read(chunk)) != -1) {
                if (n == 0) {
                    continue;
                }
                splitter.append(chunk, n);

                NalUnit nal;
                while ((nal = splitter.pollNal()) != null) {
                    processSingleNal(nal, stats.totalNals, out, log, stats, null);
                    stats.totalNals++;
                }
            }

            NalUnit tail = splitter.finish();
            if (tail != null) {
                processSingleNal(tail, stats.totalNals, out, log, stats, null);
                stats.totalNals++;
            }

            log.i("");
            log.i("== 实时解析结束(stdin EOF) ==");
            log.i("总NAL数量=" + stats.totalNals);
            log.i("SEI NAL 数量=" + stats.seiNalCount);
            log.i("输出帧数(JSONL行数)=" + stats.outputFrames);
        }
    }

    private static void runStdinWsMode(InputStream in, String host, int port, Path logFile) throws Exception {
        try (Log log = new Log(logFile)) {
            log.i("== DJI 上云API AI目标识别 SEI WebSocket 网关模式（stdin, H.264 Annex-B）==");
            log.i("WebSocket 监听: ws://" + host + ":" + port);
            log.i("输出日志: " + logFile);

            FrameQueueBridge bridge = new FrameQueueBridge(4096);
            FrameWsServer wsServer = new FrameWsServer(new InetSocketAddress(host, port), log, bridge);
            wsServer.start();
            log.i("[网关] WebSocket 服务启动成功。");

            Thread broadcaster = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        String msg = bridge.queue.poll(500, TimeUnit.MILLISECONDS);
                        if (msg != null) {
                            wsServer.broadcast(msg);
                            bridge.broadcastCount.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }, "ws-broadcaster");
            broadcaster.setDaemon(true);
            broadcaster.start();

            ParseStats stats = new ParseStats();
            AnnexBStreamSplitter splitter = new AnnexBStreamSplitter();

            byte[] chunk = new byte[64 * 1024];
            int n;
            while ((n = in.read(chunk)) != -1) {
                if (n == 0) {
                    continue;
                }
                splitter.append(chunk, n);

                NalUnit nal;
                while ((nal = splitter.pollNal()) != null) {
                    processSingleNal(nal, stats.totalNals, null, log, stats, frame -> {
                        WsFramePayload payload = WsFramePayload.from(frame);
                        bridge.offer(payload);
                    });
                    stats.totalNals++;
                }
            }

            NalUnit tail = splitter.finish();
            if (tail != null) {
                processSingleNal(tail, stats.totalNals, null, log, stats, frame -> {
                    WsFramePayload payload = WsFramePayload.from(frame);
                    bridge.offer(payload);
                });
                stats.totalNals++;
            }

            broadcaster.interrupt();
            wsServer.stop(1000);

            log.i("");
            log.i("== WebSocket 网关解析结束(stdin EOF) ==");
            log.i("总NAL数量=" + stats.totalNals);
            log.i("SEI NAL 数量=" + stats.seiNalCount);
            log.i("输出帧数(解析到目标payload)=" + stats.outputFrames);
            log.i("WebSocket 广播消息数=" + bridge.broadcastCount.get());
            log.i("队列丢弃消息数=" + bridge.droppedCount.get());
        }
    }

    private static void processSingleNal(NalUnit nal,
                                         int index,
                                         BufferedWriter out,
                                         Log log,
                                         ParseStats stats,
                                         FrameSink frameSink) throws Exception {
        int nalType = nal.h264NalUnitType;

        log.i("");
        log.i("============================================================");
        log.i("[步骤3] 处理 NAL#" + index
                + "  起始偏移=" + nal.startOffset
                + "  startCodeLen=" + nal.startCodeLen
                + "  NAL总长度(不含startCode)=" + nal.payload.length
                + "  H264 nal_unit_type=" + nalType);

        if (nal.payload.length < 1) {
            log.w("NAL payload 为空，跳过");
            return;
        }

        int nalHeader = nal.payload[0] & 0xFF;
        int nalRefIdc = (nalHeader >> 5) & 0x03;
        log.i("NAL Header=0x" + hex2(nalHeader) + " (nal_ref_idc=" + nalRefIdc + ", nal_unit_type=" + nalType + ")");

        if (nalType != 6) {
            return; // 非 SEI
        }
        stats.seiNalCount++;

        byte[] rbspRaw = Arrays.copyOfRange(nal.payload, 1, nal.payload.length);
        log.i("[步骤4] 这是 SEI NAL：取 RBSP(含EPB) 长度=" + rbspRaw.length);
        log.i("  RBSP(含EPB) 前" + Math.min(HEX_DUMP_MAX, rbspRaw.length) + "字节HEX=" + hexDump(rbspRaw, HEX_DUMP_MAX));

        if (rbspRaw.length < 2) {
            log.w("RBSP长度<2，无法读取 sei_type + sei_len，跳过该SEI");
            return;
        }

        int seiType = rbspRaw[0] & 0xFF;
        log.i("[步骤5] 读取 sei_type=0x" + hex2(seiType) + "（期望0xF5或0x65）");

        if (seiType != DJI_SEI_TYPE_PUBLIC_F5 && seiType != DJI_SEI_TYPE_AGORA_65) {
            log.w("sei_type 不匹配（不是0xF5/0x65），跳过该SEI（可能是别的SEI类型）。");
            return;
        }

        int idx = 1;
        int seiLen = 0;
        log.i("[步骤6] 开始解析 sei_len（按 0xFF 连续累加规则，注意：这里仍在 RBSP(含EPB) 上解析）");
        while (idx < rbspRaw.length) {
            int b = rbspRaw[idx] & 0xFF;
            log.i("  读取长度字节: rbspRaw[" + idx + "]=0x" + hex2(b));
            idx++;
            if (b == 0xFF) {
                seiLen += 255;
                log.i("    命中0xFF，sei_len 累加255 => " + seiLen);
            } else {
                seiLen += b;
                log.i("    命中非0xFF(" + b + ")，sei_len 最终=" + seiLen);
                break;
            }
        }

        if (seiLen <= 0) {
            log.w("sei_len<=0，跳过");
            return;
        }

        if (idx + seiLen > rbspRaw.length) {
            log.w("sei_len 越界：idx=" + idx + " seiLen=" + seiLen + " rbspRawLen=" + rbspRaw.length);
            log.w("这通常表示：数据本身不完整，或你前面拆NAL/取RBSP方式不对。该SEI跳过，但视频帧可继续。");
            return;
        }

        byte[] extensionRaw = Arrays.copyOfRange(rbspRaw, idx, idx + seiLen);
        log.i("[步骤7] 从 RBSP(含EPB) 切出 extension 原始数据，长度=" + extensionRaw.length);
        log.i("  extensionRaw 前" + Math.min(HEX_DUMP_MAX, extensionRaw.length) + "字节HEX=" + hexDump(extensionRaw, HEX_DUMP_MAX));

        EpbRemoved extNoEpb = removeEmulationPreventionBytes(extensionRaw);
        log.i("[步骤8] extension EPB去除完成：移除0x03数量=" + extNoEpb.removedCount
                + " | 原长度=" + extensionRaw.length + " 新长度=" + extNoEpb.data.length);
        log.i("  extension(不含EPB) 前" + Math.min(HEX_DUMP_MAX, extNoEpb.data.length) + "字节HEX=" + hexDump(extNoEpb.data, HEX_DUMP_MAX));

        List<ParsedFrame> frames = parseExtensionPayloads(extNoEpb.data, log);

        for (ParsedFrame f : frames) {
            if (out != null) {
                out.write(MAPPER.writeValueAsString(f));
                out.write("\n");
            }
            stats.outputFrames++;
            if (frameSink != null) {
                frameSink.accept(f);
            }
        }
        if (out != null) {
            out.flush();
        }
    }

    // ============== extension payloads 解析 ==============
    private static List<ParsedFrame> parseExtensionPayloads(byte[] extension, Log log) {
        List<ParsedFrame> out = new ArrayList<>();

        log.i("[步骤9] 开始解析 extension 内部 payloads：假设格式为 [type(2字节)] [len(2字节)] [data(len)] ...");
        log.i("  extension 总长度=" + extension.length);

        int pos = 0;
        int payloadIndex = 0;

        while (pos + 4 <= extension.length) {
            int typeLE = u16le(extension, pos);
            int lenLE  = u16le(extension, pos + 2);

            int typeBE = u16be(extension, pos);
            int lenBE  = u16be(extension, pos + 2);

            log.i("  payload#" + payloadIndex + " 起始pos=" + pos
                    + " | typeLE=0x" + hex4(typeLE) + " lenLE=" + lenLE
                    + " | typeBE=0x" + hex4(typeBE) + " lenBE=" + lenBE);

            boolean leOk = (lenLE >= 0 && pos + 4 + lenLE <= extension.length);
            boolean beOk = (lenBE >= 0 && pos + 4 + lenBE <= extension.length);

            int type, len;
            boolean useLE;

            if (leOk && !beOk) useLE = true;
            else if (!leOk && beOk) useLE = false;
            else if (leOk) useLE = true; // 两个都OK优先LE
            else {
                log.w("    无法确定 type/len 端序：len会越界。停止解析 extension。");
                break;
            }

            type = useLE ? typeLE : typeBE;
            len  = useLE ? lenLE  : lenBE;

            log.i("    采用端序=" + (useLE ? "Little-Endian" : "Big-Endian")
                    + " => payloadType=0x" + hex4(type) + " payloadLen=" + len);

            int dataStart = pos + 4;
            int dataEnd = dataStart + len;
            if (dataEnd > extension.length) {
                log.w("    payloadLen 越界，停止解析");
                break;
            }

            byte[] data = Arrays.copyOfRange(extension, dataStart, dataEnd);
            log.i("    payloadData 前" + Math.min(HEX_DUMP_MAX, data.length) + "字节HEX=" + hexDump(data, HEX_DUMP_MAX));

            if (type == DJI_PAYLOAD_TYPE_TARGET) {
                log.i("    >>> 命中目标payload（type=0x0007），开始解析目标识别结构 <<<");
                ParsedFrame frame = parseTargetPayload(data, log);
                if (frame != null) out.add(frame);
            } else {
                log.i("    该payload type!=0x0007，跳过业务解析");
            }

            pos = dataEnd;
            payloadIndex++;
        }

        if (out.isEmpty()) log.w("[步骤9] extension 解析完成，但没有解析出任何 type=0x0007 的目标payload。");
        else log.i("[步骤9] extension 解析完成，解析出目标帧数=" + out.size());

        return out;
    }

    // ============== 目标 payload 解析（按你 TS：TargetManagerSEIParser.ts）=============
    private static ParsedFrame parseTargetPayload(byte[] payload, Log log) {
        try {
            Reader r = new Reader(payload, log);

            ParsedFrame frame = new ParsedFrame();
            frame.rawPayloadBytes = payload.length;

            frame.version = r.u8("version(版本号)");
            frame.timeStampMs = r.u32le("time_stamp(时间戳ms)");
            frame.frameType = r.u8("frame_type(帧类型 0=invalid 1=base)");

            if (frame.frameType == 1) {
                byte[] frameExt = r.bytes(12, "frame_ext[12](扩展区，仅frame_type=1存在)");
                frame.frameExtHex = hexDump(frameExt, 12);
            } else {
                log.i("  frame_type!=1，不读取 frame_ext[12]");
            }

            frame.trackId = r.u16le("track_id(跟踪ID)");
            int reserved2 = r.u8("reserved2(保留字段)");
            log.i("  reserved2=0x" + hex2(reserved2));

            frame.objGroupCount = r.u8("obj_group_count(分组数量)");

            frame.groups = new ArrayList<>();
            frame.targets = new ArrayList<>();
            frame.typeCounts = new ArrayList<>();

            log.i("  ==> 开始解析 obj_groups，共 " + frame.objGroupCount + " 组");
            for (int gi = 0; gi < frame.objGroupCount; gi++) {
                int groupType = r.u8("group[" + gi + "].type(分组类型)");
                int count = r.u8("group[" + gi + "].count(元素数量)");

                ParsedGroup g = new ParsedGroup();
                g.groupType = groupType;
                g.count = count;
                frame.groups.add(g);

                log.i("  ---- 分组#" + gi + " type=" + groupType + " count=" + count + " ----");

                if (groupType == 10) {
                    for (int k = 0; k < count; k++) {
                        ParsedTarget t = new ParsedTarget();
                        t.id = r.u16le("  target[" + k + "].id(uint16)");
                        t.type = r.u8("  target[" + k + "].type(uint8)");
                        t.state = r.u8("  target[" + k + "].state(uint8)");
                        t.cx = r.u16le("  target[" + k + "].cx(uint16)");
                        t.cy = r.u16le("  target[" + k + "].cy(uint16)");
                        t.w  = r.u16le("  target[" + k + "].w(uint16)");
                        t.h  = r.u16le("  target[" + k + "].h(uint16)");
                        t.distanceMm = r.u32le("  target[" + k + "].distance(uint32 mm)");

                        t.cxNorm = t.cx / 10000.0;
                        t.cyNorm = t.cy / 10000.0;
                        t.wNorm  = t.w  / 10000.0;
                        t.hNorm  = t.h  / 10000.0;

                        frame.targets.add(t);
                        log.i("  [解析结果] 目标#" + k
                                + " id=" + t.id
                                + " type=" + t.type
                                + " state=" + t.state
                                + " 框(cx,cy,w,h)=(" + t.cx + "," + t.cy + "," + t.w + "," + t.h + ")"
                                + " 归一化=(" + t.cxNorm + "," + t.cyNorm + "," + t.wNorm + "," + t.hNorm + ")"
                                + " distanceMm=" + t.distanceMm);
                    }
                } else if (groupType == 12) {
                    for (int k = 0; k < count; k++) {
                        ParsedTypeCount tc = new ParsedTypeCount();
                        tc.type = r.u8("  typeCount[" + k + "].type(uint8)");
                        tc.count = r.u16le("  typeCount[" + k + "].count(uint16)");
                        frame.typeCounts.add(tc);
                        log.i("  [解析结果] 类别统计#" + k + " type=" + tc.type + " count=" + tc.count);
                    }
                } else {
                    log.w("  未实现的 groupType=" + groupType + "：不知道每个元素长度，无法安全跳过。停止本payload解析，避免偏移全错。");
                    break;
                }
            }

            log.i("  ==> 目标payload解析完成：targets=" + frame.targets.size()
                    + " typeCounts=" + frame.typeCounts.size()
                    + " reader剩余字节=" + r.remaining());

            return frame;
        } catch (Exception e) {
            log.w("目标payload解析异常：" + e.getMessage());
            return null;
        }
    }

    // ============== Annex-B NAL 拆分 ==============
    private static List<NalUnit> splitAnnexBNals(byte[] data, Log log) {
        List<Integer> startCodes = new ArrayList<>();
        List<Integer> startCodeLens = new ArrayList<>();

        int i = 0;
        while (i + 3 < data.length) {
            int scLen = 0;
            if (data[i] == 0x00 && data[i + 1] == 0x00) {
                if (data[i + 2] == 0x01) scLen = 3;
                else if (i + 3 < data.length && data[i + 2] == 0x00 && data[i + 3] == 0x01) scLen = 4;
            }
            if (scLen > 0) {
                startCodes.add(i);
                startCodeLens.add(scLen);
                i += scLen;
            } else {
                i++;
            }
        }

        List<NalUnit> nals = new ArrayList<>();
        for (int k = 0; k < startCodes.size(); k++) {
            int scPos = startCodes.get(k);
            int scLen = startCodeLens.get(k);
            int nalStart = scPos + scLen;
            int nalEnd = (k + 1 < startCodes.size()) ? startCodes.get(k + 1) : data.length;
            if (nalStart >= nalEnd) continue;

            byte[] payload = Arrays.copyOfRange(data, nalStart, nalEnd);
            int nalType = (payload[0] & 0x1F);

            NalUnit nu = new NalUnit();
            nu.startOffset = scPos;
            nu.startCodeLen = scLen;
            nu.payload = payload;
            nu.h264NalUnitType = nalType;
            nals.add(nu);
        }

        log.i("[拆NAL] 检测到 startCode 数量=" + startCodes.size() + " => NAL数量=" + nals.size());
        return nals;
    }

    // ============== EPB 去除（返回移除数量，便于日志核对） ==============
    private static class EpbRemoved {
        final byte[] data;
        final int removedCount;
        EpbRemoved(byte[] data, int removedCount) {
            this.data = data;
            this.removedCount = removedCount;
        }
    }

    private static EpbRemoved removeEmulationPreventionBytes(byte[] src) {
        ByteArrayOutput out = new ByteArrayOutput(src.length);
        int zeros = 0;
        int removed = 0;

        for (int i = 0; i < src.length; i++) {
            int b = src[i] & 0xFF;

            if (zeros >= 2 && b == 0x03) {
                removed++;
                zeros = 0;
                continue;
            }
            out.write((byte) b);

            if (b == 0x00) zeros++;
            else zeros = 0;
        }
        return new EpbRemoved(out.toByteArray(), removed);
    }

    // ============== Reader（字段日志走 Log） ==============
    private static class Reader {
        private final byte[] buf;
        private final Log log;
        private int pos = 0;

        Reader(byte[] buf, Log log) { this.buf = buf; this.log = log; }

        int remaining() { return buf.length - pos; }

        int u8(String name) {
            ensure(1, name);
            int v = buf[pos] & 0xFF;
            log.i("  [读字段] " + name + " | 偏移=" + pos + " | 字节数=1 | 值=" + v + " | HEX=0x" + hex2(v));
            pos += 1;
            return v;
        }

        int u16le(String name) {
            ensure(2, name);
            int v = ((buf[pos] & 0xFF)) | ((buf[pos + 1] & 0xFF) << 8);
            log.i("  [读字段] " + name + " | 偏移=" + pos + " | 字节数=2 | 值=" + v + " | HEX=0x" + hex4(v));
            pos += 2;
            return v;
        }

        long u32le(String name) {
            ensure(4, name);
            long v = ((long)(buf[pos] & 0xFF))
                    | ((long)(buf[pos + 1] & 0xFF) << 8)
                    | ((long)(buf[pos + 2] & 0xFF) << 16)
                    | ((long)(buf[pos + 3] & 0xFF) << 24);
            v = v & 0xFFFFFFFFL;
            log.i("  [读字段] " + name + " | 偏移=" + pos + " | 字节数=4 | 值=" + v);
            pos += 4;
            return v;
        }

        byte[] bytes(int len, String name) {
            ensure(len, name);
            byte[] v = Arrays.copyOfRange(buf, pos, pos + len);
            log.i("  [读字段] " + name + " | 偏移=" + pos + " | 字节数=" + len + " | HEX=" + hexDump(v, Math.min(v.length, HEX_DUMP_MAX)));
            pos += len;
            return v;
        }

        private void ensure(int need, String name) {
            if (pos + need > buf.length) {
                throw new IllegalArgumentException("读取字段失败: " + name + "，需要 " + need + " 字节，但剩余 " + remaining() + " 字节");
            }
        }
    }

    // ============== 数据结构（用于 JSON 输出） ==============
    public static class ParsedFrame {
        public int version;
        public long timeStampMs;
        public int frameType;
        public String frameExtHex;
        public int trackId;
        public int objGroupCount;
        public List<ParsedGroup> groups;
        public List<ParsedTarget> targets;
        public List<ParsedTypeCount> typeCounts;
        public int rawPayloadBytes;
    }

    public static class ParsedGroup {
        public int groupType;
        public int count;
    }

    public static class ParsedTarget {
        public int id;
        public int type;
        public int state;
        public int cx;
        public int cy;
        public int w;
        public int h;
        public long distanceMm;
        public double cxNorm;
        public double cyNorm;
        public double wNorm;
        public double hNorm;
    }

    public static class ParsedTypeCount {
        public int type;
        public int count;
    }

    private static class NalUnit {
        int startOffset;
        int startCodeLen;
        byte[] payload;
        int h264NalUnitType;
    }

    private static class ParseStats {
        int totalNals;
        int seiNalCount;
        int outputFrames;
    }

    @FunctionalInterface
    private interface FrameSink {
        void accept(ParsedFrame frame) throws Exception;
    }

    private static class WsFramePayload {
        public String event = "sei_frame";
        public int version;
        public long timeStampMs;
        public int frameType;
        public String frameExtHex;
        public int trackId;
        public int objGroupCount;
        public List<ParsedGroup> groups;
        public List<ParsedTypeCount> typeCounts;
        public int rawPayloadBytes;
        public List<WsTargetPayload> targets;

        static WsFramePayload from(ParsedFrame frame) {
            WsFramePayload p = new WsFramePayload();
            p.version = frame.version;
            p.timeStampMs = frame.timeStampMs;
            p.frameType = frame.frameType;
            p.frameExtHex = frame.frameExtHex;
            p.trackId = frame.trackId;
            p.objGroupCount = frame.objGroupCount;
            p.groups = frame.groups;
            p.typeCounts = frame.typeCounts;
            p.rawPayloadBytes = frame.rawPayloadBytes;
            p.targets = new ArrayList<>();
            for (ParsedTarget t : frame.targets) {
                WsTargetPayload tp = new WsTargetPayload();
                tp.id = t.id;
                tp.type = t.type;
                tp.state = t.state;
                tp.cx = t.cx;
                tp.cy = t.cy;
                tp.w = t.w;
                tp.h = t.h;
                tp.distanceMm = t.distanceMm;
                tp.cxNorm = t.cxNorm;
                tp.cyNorm = t.cyNorm;
                tp.wNorm = t.wNorm;
                tp.hNorm = t.hNorm;
                p.targets.add(tp);
            }
            return p;
        }
    }

    private static class WsTargetPayload {
        public int id;
        public int type;
        public int state;
        public int cx;
        public int cy;
        public int w;
        public int h;
        public long distanceMm;
        public double cxNorm;
        public double cyNorm;
        public double wNorm;
        public double hNorm;
    }

    private static class FrameQueueBridge {
        private final BlockingQueue<String> queue;
        private final AtomicLong droppedCount = new AtomicLong(0);
        private final AtomicLong broadcastCount = new AtomicLong(0);

        FrameQueueBridge(int capacity) {
            this.queue = new ArrayBlockingQueue<>(capacity);
        }

        void offer(WsFramePayload payload) throws Exception {
            String json = MAPPER.writeValueAsString(payload);
            if (!queue.offer(json)) {
                queue.poll();
                if (!queue.offer(json)) {
                    droppedCount.incrementAndGet();
                    return;
                }
                droppedCount.incrementAndGet();
            }
        }
    }

    private static class FrameWsServer extends WebSocketServer {
        private final Log log;
        private final FrameQueueBridge bridge;
        private final AtomicBoolean firstOpenLogged = new AtomicBoolean(false);

        FrameWsServer(InetSocketAddress address, Log log, FrameQueueBridge bridge) {
            super(address);
            this.log = log;
            this.bridge = bridge;
            setReuseAddr(true);
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            log.i("[WS] 客户端连接: " + conn.getRemoteSocketAddress());
            if (firstOpenLogged.compareAndSet(false, true)) {
                log.i("[WS] 首个客户端已连接，开始接收广播。");
            }
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            log.i("[WS] 客户端断开: " + conn.getRemoteSocketAddress() + " code=" + code + " reason=" + reason);
        }

        @Override
        public void onMessage(WebSocket conn, String message) {
            // 仅推送，不处理客户端业务消息
        }

        @Override
        public void onError(WebSocket conn, Exception ex) {
            log.w("[WS] 错误: " + ex.getMessage());
        }

        @Override
        public void onStart() {
            log.i("[WS] 服务就绪，当前连接数=" + getConnections().size());
            log.i("[WS] 当前累计丢弃消息数=" + bridge.droppedCount.get());
        }
    }

    private static class AnnexBStreamSplitter {
        private byte[] buf = new byte[256 * 1024];
        private int size = 0;

        void append(byte[] src, int len) {
            ensure(size + len);
            System.arraycopy(src, 0, buf, size, len);
            size += len;
        }

        NalUnit pollNal() {
            int first = findStartCode(buf, 0, size);
            if (first < 0) {
                compactNoise();
                return null;
            }
            if (first > 0) {
                shiftLeft(first);
                first = 0;
            }

            int scLen = startCodeLen(buf, first, size);
            if (scLen == 0) return null;

            int second = findStartCode(buf, first + scLen, size);
            if (second < 0) {
                return null;
            }

            NalUnit nal = buildNal(0, scLen, second);
            shiftLeft(second);
            return nal;
        }

        NalUnit finish() {
            int first = findStartCode(buf, 0, size);
            if (first < 0) return null;
            int scLen = startCodeLen(buf, first, size);
            if (scLen == 0) return null;

            if (first > 0) {
                shiftLeft(first);
            }
            if (size <= scLen) return null;
            NalUnit nal = buildNal(0, scLen, size);
            size = 0;
            return nal;
        }

        private NalUnit buildNal(int start, int scLen, int endExclusive) {
            int nalStart = start + scLen;
            if (nalStart >= endExclusive) return null;
            byte[] payload = Arrays.copyOfRange(buf, nalStart, endExclusive);
            NalUnit nu = new NalUnit();
            nu.startOffset = 0;
            nu.startCodeLen = scLen;
            nu.payload = payload;
            nu.h264NalUnitType = payload[0] & 0x1F;
            return nu;
        }

        private void compactNoise() {
            if (size > 4) {
                shiftLeft(size - 4);
            }
        }

        private void shiftLeft(int n) {
            if (n <= 0) return;
            if (n >= size) {
                size = 0;
                return;
            }
            System.arraycopy(buf, n, buf, 0, size - n);
            size -= n;
        }

        private void ensure(int need) {
            if (need <= buf.length) return;
            int newCap = buf.length;
            while (newCap < need) newCap *= 2;
            buf = Arrays.copyOf(buf, newCap);
        }
    }

    // ============== 日志写文件（中文，单独文件） ==============
    private static class Log implements AutoCloseable {
        private final BufferedWriter w;

        Log(Path path) throws Exception {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(path.toFile()), "UTF-8"));
        }

        void i(String s) { write("[INFO] " + s); }
        void w(String s) { write("[WARN] " + s); }

        private void write(String s) {
            try {
                w.write(s);
                w.write("\n");
                w.flush();
            } catch (Exception ignored) {}
        }

        @Override public void close() throws Exception { w.close(); }
    }

    // ============== ByteArrayOutput ==============
    private static class ByteArrayOutput {
        private byte[] buf;
        private int size = 0;

        ByteArrayOutput(int cap) { buf = new byte[Math.max(16, cap)]; }
        void write(byte b) {
            if (size >= buf.length) buf = Arrays.copyOf(buf, buf.length * 2);
            buf[size++] = b;
        }
        byte[] toByteArray() { return Arrays.copyOf(buf, size); }
    }

    // ============== hex/byte utils ==============
    private static String hex2(int v) { return String.format("%02X", v & 0xFF); }
    private static String hex4(int v) { return String.format("%04X", v & 0xFFFF); }

    private static String hexDump(byte[] b, int max) {
        int n = Math.min(b.length, max);
        StringBuilder sb = new StringBuilder(n * 3);
        for (int i = 0; i < n; i++) {
            sb.append(String.format("%02X", b[i]));
            if (i + 1 < n) sb.append(" ");
        }
        if (b.length > max) sb.append(" ...");
        return sb.toString();
    }

    private static int u16le(byte[] b, int off) {
        return ((b[off] & 0xFF)) | ((b[off + 1] & 0xFF) << 8);
    }
    private static int u16be(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | ((b[off + 1] & 0xFF));
    }

    private static int findStartCode(byte[] data, int from, int size) {
        for (int i = Math.max(0, from); i + 3 < size; i++) {
            if (data[i] != 0x00 || data[i + 1] != 0x00) {
                continue;
            }
            if (data[i + 2] == 0x01) {
                return i;
            }
            if (i + 3 < size && data[i + 2] == 0x00 && data[i + 3] == 0x01) {
                return i;
            }
        }
        return -1;
    }

    private static int startCodeLen(byte[] data, int pos, int size) {
        if (pos + 2 < size && data[pos] == 0x00 && data[pos + 1] == 0x00 && data[pos + 2] == 0x01) {
            return 3;
        }
        if (pos + 3 < size && data[pos] == 0x00 && data[pos + 1] == 0x00 && data[pos + 2] == 0x00 && data[pos + 3] == 0x01) {
            return 4;
        }
        return 0;
    }
}
