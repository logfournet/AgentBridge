# AgentBridge

Cầu nối TCP tốc độ cao giữa **OpenCode AI** và **máy chủ Minecraft**, cho phép AI
đọc / ghi / sửa / upload / download file trực tiếp trên host — kể cả khi máy chủ
Minecraft **không mở được port**.

```
┌──────────────┐        TCP (outbound)         ┌────────────────────┐
│  Minecraft   │  ───────────────────────────► │  AgentBridgeServer │
│  + Plugin    │        :7000 (plugin port)    │  (VPS / host)      │
└──────────────┘                               └─────────┬──────────┘
                                                         │ :7001 (client port)
                                                         ▼
                                              ┌────────────────────┐
                                              │  OpenCode (MCP)    │
                                              │  hoặc CLI          │
                                              └────────────────────┘
```

- **Plugin luôn kết nối ra ngoài** tới `AgentBridgeServer` → không cần mở port
  trên máy chủ Minecraft.
- `AgentBridgeServer` chỉ **relay frame** (không giải mã nội dung file) → tốc độ
  cao, RAM thấp.
- Toàn bộ là **Java 21 + Netty**.

## Thành phần

| Module | Mô tả | File build |
|---|---|---|
| `common` | Protocol (frame, codec, path sandbox) | — |
| `server` | Server trung gian (2 listener: plugin + client) | `server/target/AgentBridgeServer.jar` |
| `plugin` | Paper plugin kết nối outbound | `plugin/target/AgentBridgePlugin.jar` |
| `client` | CLI + MCP stdio server | `client/target/AgentBridge.jar` |

> `plugin/target/AgentBridgePlugin.jar` là **bản fat jar** (đã shade `common` + Netty + Gson,
> ~2.9 MB) — đây là file duy nhất để cài. File `original-*.jar` (~20 KB) là bản thin,
> **không** copy vào server.

## Build

```powershell
cd F:\AgentBridge
.\build.ps1
```

hoặc nếu `mvn` đã có trong PATH:

```powershell
mvn -DskipTests package
```

Yêu cầu: JDK 21+, Maven 3.9+. `build.ps1` tự tìm `mvn` trong PATH, nếu không có thì
dùng bản trong `%LOCALAPPDATA%\Temp\maven`.

## Nhiều máy chủ Minecraft (multi-agent)

Một `AgentBridgeServer` phục vụ **nhiều plugin** cùng lúc. Mỗi plugin đặt `agentId`
khác nhau trong `config.yml` (`survival`, `skyblock`, `test`…). Client chọn agent bằng
`--agent <id>`, MCP dùng tham số `agent` trong từng tool call (mặc định
`defaultAgent` trong `client.json`).

Plugin tự **reconnect** khi mất kết nối (backoff 2s → 30s). Nếu cùng `agentId`
kết nối lại, kết nối cũ bị thay thế và mọi request đang chờ nhận lỗi `503`.

## 1. Chạy AgentBridgeServer (VPS / host trung gian)

```powershell
java -jar server\target\AgentBridgeServer.jar --config server.json
```

Lần đầu sẽ tự tạo `server.json`:

```json
{
  "bindHost": "0.0.0.0",
  "pluginPort": 7000,
  "clientPort": 7001,
  "pluginApiKey": "change-me-plugin-key",
  "clientToken": "change-me-client-token",
  "allowedAgents": [],
  "maxFrameMB": 256,
  "chunkSize": 262144,
  "debug": false
}
```

> Đổi `pluginApiKey` và `clientToken` trước khi dùng thật.
> Nếu chỉ dùng nội bộ, đặt `bindHost` = `127.0.0.1`.

## 2. Cài plugin vào máy chủ Minecraft

Copy `plugin/target/AgentBridgePlugin.jar` vào thư mục `plugins/`.

Lần đầu chạy sẽ tạo `plugins/AgentBridge/config.yml`:

```yaml
host: "ip-cua-agentbridge-server"
port: 7000
apiKey: "change-me-plugin-key"     # trùng pluginApiKey của server
agentId: "survival"                 # tên định danh máy chủ
sandboxRoot: "."                    # thư mục gốc được phép truy cập (mặc định = server dir)
exec:
  enabled: true
  timeoutMs: 30000
  whitelist: [git, mvn, java, python, node, npm, echo, dir, type]
chunkSize: 262144
debug: false
```

Reload/restart server. Plugin sẽ tự kết nối và tự **reconnect** (backoff tối đa 30s).

- `sandboxRoot: "."` = thư mục làm việc của server Minecraft (chứa `plugins/`,
  `world/`, `server.properties`…).
- Mọi đường dẫn từ client đều được **sandbox** trong `sandboxRoot`; `..` bị chặn.
- `exec.enabled`: bật/tắt chạy lệnh. `exec.whitelist`: chỉ các chương trình đầu
  tiên trong whitelist mới được chạy.

## 3. Cấu hình client

`client.json` (tự tạo cạnh file jar hoặc trỏ bằng `--config`). Mẫu có sẵn: copy `client.example.json` → `client.json` rồi sửa:

```powershell
Copy-Item client.example.json client.json
```

```json
{
  "host": "ip-cua-agentbridge-server",
  "port": 7001,
  "token": "change-me-client-token",
  "defaultAgent": "survival",
  "downloadDir": ".",
  "connectTimeoutMs": 10000,
  "chunkSize": 262144
}
```

## 4. Dùng với OpenCode (MCP)

OpenCode V2 cấu hình MCP dưới `mcp.servers`. Thêm vào `opencode.json(c)`:

```jsonc
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "servers": {
      "agentbridge": {
        "type": "local",
        "command": [
          "java",
          "-jar",
          "F:/AgentBridge/client/target/AgentBridge.jar",
          "mcp",
          "--config",
          "F:/AgentBridge/client.json"
        ],
        "cwd": "F:/AgentBridge"
      }
    }
  }
}
```

Hoặc qua CLI:

```powershell
opencode mcp add agentbridge -- java -jar F:/AgentBridge/client/target/AgentBridge.jar mcp --config F:/AgentBridge/client.json
opencode mcp list
```

> Mặc định OpenCode dùng **Code Mode**, nên tool sẽ hiện dưới dạng
> `tools.agentbridge.read_file(...)`, `tools.agentbridge.write_file(...)`.
> Muốn tool hiện trực tiếp, đặt `"codemode": false` trong server config.

### MCP tools

| Tool | Chức năng |
|---|---|
| `agents` | Liệt kê agent (server Minecraft) đang online |
| `read_file` | Đọc file (utf8 hoặc base64, hỗ trợ `offset`/`length`) |
| `write_file` | Ghi file (utf8/base64, `truncate`/`append`, tự tạo thư mục) |
| `edit_file` | Thay thế chính xác chuỗi (read-modify-write) |
| `list_dir` | Liệt kê thư mục |
| `stat` | Thông tin file/thư mục |
| `delete` | Xoá file/thư mục (`recursive`) |
| `move` | Di chuyển / đổi tên |
| `mkdir` | Tạo thư mục |
| `exec` | Chạy lệnh trên host (nếu bật + whitelist) |
| `download` | Tải file remote về máy chạy MCP |
| `upload` | Upload file local lên host |

## 5. CLI

```powershell
java -jar client\target\AgentBridge.jar --config client.json --agent survival <command>
```

| Lệnh | Ví dụ |
|---|---|
| `agents` | `... agents` |
| `ls [path]` | `... ls plugins` |
| `cat <path>` | `... cat server.properties` |
| `get <remote> [local]` | `... get logs/latest.log` |
| `put <local> [remote]` | `... put build.jar plugins/build.jar` |
| `write <path> [content]` | `... write note.txt "hello"` (bỏ content thì đọc stdin: `echo hello \| ... write note.txt`) |
| `edit <path> --old "a" --new "b" [--all]` | `... edit config.yml --old "old" --new "new"` |
| `stat` / `exists` | `... stat world/level.dat` |
| `rm <path> [--recursive]` | `... rm cache --recursive` |
| `mv <src> <dst>` | `... mv a.txt b.txt` |
| `mkdir <path> [--parents]` | `... mkdir logs/old --parents` |
| `exec <cmd...>` | `... exec mvn clean package` |
| `ping` | `... ping` |

Thêm `--host/--port/--token` để override nhanh.

## 6. Test nhanh không cần Paper

Harness nằm trong module `plugin` (`me.sfclog.agentbridge.plugin.dev.StandaloneAgent`)
và **không** được đóng vào jar phát hành.

```powershell
# 1) build + lấy classpath của plugin module
.\build.ps1
& "<maven>\bin\mvn.cmd" -q -pl plugin dependency:build-classpath "-Dmdep.outputFile=cp.txt"

# 2) server (terminal 1)
java -jar server\target\AgentBridgeServer.jar --config run\server.json

# 3) agent giả dùng đúng handler của plugin (terminal 2)
$cp = "plugin\target\classes;" + (Get-Content plugin\cp.txt -Raw)
java -cp $cp me.sfclog.agentbridge.plugin.dev.StandaloneAgent 127.0.0.1 7000 F:\AgentBridge\run\testroot

# 4) client (terminal 3)
java -jar client\target\AgentBridge.jar --config run\client.json --agent test ls
```

## Hiệu năng

Đo trên localhost (100 MB, 1 chunk = 256 KiB):

- Upload: **~88–101 MB/s**
- Download: **~89–94 MB/s**
- SHA-256 khớp sau round-trip.

Kiểm thử thêm: ghi/đọc file rỗng, `offset` vượt EOF, upload 2 MB qua stdin
(chunked), 6 client song song, agent offline → lỗi `503`, `..`/symlink → `403`.

Tối ưu đã áp dụng:
- Netty (NIO, direct buffer, `LengthFieldBasedFrameDecoder`).
- Server relay **zero-copy payload** (chỉ đọc 25 byte header để routing).
- Streaming theo chunk, có backpressure (`isWritable`, high-water-mark 4 MB).
- File I/O chạy trên `DefaultEventExecutorGroup`, không chặn I/O thread.
- `TCP_NODELAY`, `SO_KEEPALIVE`.

## Bảo mật

- Plugin kết nối **outbound** → không mở port trên máy Minecraft.
- Xác thực 2 lớp: `pluginApiKey` (plugin ↔ server) và `clientToken` (client ↔ server),
  so sánh constant-time.
- `allowedAgents` (tuỳ chọn) giới hạn id agent được phép.
- Mọi path bị sandbox trong `sandboxRoot`; chặn traversal, absolute path và symlink
  trỏ ra ngoài. `cwd` của `exec` cũng bị giới hạn trong `sandboxRoot`.
- `exec` mặc định có whitelist; tắt được bằng `exec.enabled: false`.
- Nên đặt `bindHost: 127.0.0.1` nếu server và client cùng máy, hoặc giới hạn
  firewall theo IP.

## Protocol (tóm tắt)

Mỗi frame:

```
[int32 frameLen][int32 metaLen][meta JSON][payload bytes]
```

- `meta` là JSON nhỏ (path, agent, offset…), `payload` là dữ liệu file thô.
- Frame body: `version(1) | op(4) | route(16) | metaLen(4) | meta | payload`.
- `route` (UUID) do client sinh, agent echo lại → server route response về đúng client.
- Streaming: `READ_START → READ_CHUNK* → READ_END`,
  `WRITE_START → WRITE_CHUNK* → WRITE_END`.

## Troubleshooting

| Triệu chứng | Nguyên nhân / cách xử lý |
|---|---|
| `Agent "x" is offline` | Plugin chưa kết nối / sai `agentId`, hoặc `clientToken`/`apiKey` sai |
| `Not connected to AgentBridgeServer` | Sai `host`/`port`, firewall, hoặc server chưa chạy |
| `Invalid plugin api key` | `pluginApiKey` trong `server.json` khác `apiKey` trong `config.yml` |
| `path escapes sandbox` (403) | Đường dẫn vượt `sandboxRoot`, hoặc symlink trỏ ra ngoài |
| Lỗi 409 khi `rm`/`mv` | Thư mục còn file (dùng `--recursive`) hoặc đích đã tồn tại |
| `exec` bị từ chối | `exec.enabled: false`, chương trình không có trong whitelist, hoặc `cwd` ngoài sandbox |
| OpenCode không thấy tool | Kiểm tra `mcp list`; đảm bảo đường dẫn jar tuyệt đối trong `command` |
| `NoClassDefFoundError: me/sfclog/agentbridge/common/Log` | Đang nạp nhầm **bản thin** (`original-*.jar` / jar ~20 KB). Dùng `plugin/target/AgentBridgePlugin.jar` (~2.9 MB) và xoá jar cũ trong `plugins/` |
