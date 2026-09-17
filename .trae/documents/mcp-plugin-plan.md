# Termora MCP 插件实现计划

## Context（背景）

让外部 AI（Claude Code、Cursor、Cline 等）能通过 MCP 协议操作 Termora 的终端：枚举标签页、读取屏幕输出、发送命令、创建/关闭/切换连接。方案为新增插件子模块 `plugins/mcp`，在应用内嵌 HTTP 服务器（仅监听 127.0.0.1，Bearer Token 鉴权，默认关闭，设置页开启）。

**已确认的决策**：

- 同时支持两种传输：新版 Streamable HTTP（`/mcp`）+ 旧版 HTTP+SSE（`/sse` + `/messages`），共享同一协议处理核心
- JDK 内置 `com.sun.net.httpserver.HttpServer` + 手写 JSON-RPC/MCP 协议层，零新增服务器依赖
- 插件 id `mcp`

**已核实的关键事实**：

- 插件样板 [plugins/serial/build.gradle.kts](c:\Users\xios\codes\open\termora\plugins\serial\build.gradle.kts)：`compileOnly(project(":"))` + `apply(from = "$rootDir/plugins/common.gradle.kts")`；kotlinx-serialization-json 经根项目 `api` 传递可用，无需显式声明
- 终端操作链：`ApplicationScope.windowScopes()` → `windowScope.get(TerminalTabbedManager)` → `getTerminalTabs()`；发输入 `(tab as PtyHostTerminalTab).getPtyConnector().write(bytes)`（任意线程）；读屏 `terminal.getDocument().getText()`（EDT）
- [OpenHostAction.kt](c:\Users\xios\codes\open\termora\src\main\kotlin\app\termora\actions\OpenHostAction.kt#L21-22) 要求 `AnActionEvent` 的 source 是能提供 `DataProviders.TerminalTabbedManager` 与 `DataProviders.WindowScope` 的组件——`TerminalTabbed` 自身满足（[TerminalTabbed.kt:239](c:\Users\xios\codes\open\termora\src\main\kotlin\app\termora\TerminalTabbed.kt#L239) 以 `this` 为 source）
- 本地终端创建参考 [OpenLocalTerminalAction.kt](c:\Users\xios\codes\open\termora\src\main\kotlin\app\termora\actions\OpenLocalTerminalAction.kt#L24-34)：`Host(id="local", protocol="Local")`
- 输出监听参考 [TerminalLoggerDataListener.kt](c:\Users\xios\codes\open\termora\src\main\kotlin\app\termora\tlog\TerminalLoggerDataListener.kt)：`terminalModel.addDataListener` 过滤 `TextProcessor.Written` DataKey（纯文本）
- HttpServer 先例 [AccountOption.kt:291](c:\Users\xios\codes\open\termora\src\main\kotlin\app\termora\account\AccountOption.kt#L291)
- 插件代码直接调用主工程 `I18n.getString`，i18n 键必须放主工程 `resources/i18n/messages*.properties`
- 配置存取：`DatabaseManager` 的 IProperties + 属性委托（`BooleanPropertyDelegate`/`IntPropertyDelegate`/`StringPropertyDelegate`）
- Token 生成：`AES.randomBytes(32).encodeBase64String()`（[Crypto.kt](c:\Users\xios\codes\open\termora\src\main\kotlin\app\termora\Crypto.kt)）

## 修改点（主工程，3 处）

1. `settings.gradle.kts`：追加 `include("plugins:mcp")`
2. `src/main/resources/i18n/messages.properties`、`messages_zh_CN.properties`、`messages_zh_TW.properties`：追加 `termora.settings.mcp.*` 键（标题、enable、port、token、token.reset、token.copied、restart-hint）
3. （无其他主工程改动；不引入新依赖）

## 新建文件（plugins/mcp）

```
plugins/mcp/
  build.gradle.kts                          # 仿 serial，version "0.0.1"
  src/main/resources/META-INF/plugin.xml    # <id>mcp</id> <entry>app.termora.plugins.mcp.McpPlugin</entry>
  src/main/kotlin/app/termora/plugins/mcp/
    McpPlugin.kt            # Plugin 实现，ExtensionSupport 注册 SettingsOptionExtension + ApplicationRunnerExtension（ready 时按配置启动服务器）
    McpConfig.kt            # IProperties 子类（scope "Plugin.MCP"）：enabled(默认false)、port(默认8920)、token(懒生成)
    McpOptionExtension.kt   # SettingsOptionExtension 实现
    McpOptionPane.kt        # 设置页 JPanel：启用开关、端口 Spinner、token 显示/重置按钮；开关/端口变更触发 ServerManager.start/stop/restart
    McpServerManager.kt     # HttpServer 生命周期：create(InetSocketAddress("127.0.0.1", port)) + daemon cachedThreadPool executor + 三个 createContext("/mcp"、"/sse"、"/messages") + start/stop/restart/isRunning
    McpSessionManager.kt    # 会话表（UUID → McpSession），McpSession 持有 protocolVersion 与 SSE 输出队列/流引用；关闭时清理
    protocol/JsonRpc.kt             # JSON-RPC 2.0：parse / result / error / notification（buildJsonObject 手写，不用 @Serializable——与现有插件风格一致）
    protocol/McpMessageHandler.kt   # 协议分发核心（两传输共用）：initialize（版本协商，支持 2025-03-26 / 2025-06-18，回显客户端版本）、notifications/initialized、ping、tools/list、tools/call；未知 method → -32601
    protocol/McpToolRegistry.kt     # 工具表：listTools()（name/description/inputSchema）、call()（统一 try/catch → isError content；未知工具 → isError:true）
    transport/SseWriter.kt          # SSE 帧写入（event:/data:，flush）
    transport/StreamableHttpEndpoint.kt  # POST /mcp（Bearer 校验 → handler → JSON 或 SSE 响应，initialize 后下发 Mcp-Session-Id 头）；GET /mcp（SSE 通知流）；DELETE /mcp（结束会话）
    transport/LegacySseEndpoint.kt  # GET /sse（先发 event: endpoint 带 /messages?sessionId=x，然后阻塞推 message 事件）；POST /messages?sessionId=x（校验后入队，HTTP 回 202）
    terminal/McpTabRegistry.kt      # tabId(UUID) ↔ TerminalTab 映射；tab 关闭时清理（参考 TerminalLoggerDataListener 的 tab 关闭监听）
    terminal/McpTabBuffer.kt        # 每 tab 增量输出缓冲：DataListener 拦截 TextProcessor.Written，synchronized StringBuilder（上限 256KB 裁剪）+ 游标 readIncremental()
    terminal/McpTools.kt            # 7 个工具实现
```

## MCP Tools 设计

| 工具              | 参数                                           | 行为                                                                                                                                                                                                                                                                                                                         |
| --------------- | -------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `list_tabs`     | —                                            | 遍历 windowScopes × getTerminalTabs()，未注册的惰性注册；返回 `[{id,title,host,protocol,active}]`                                                                                                                                                                                                                                        |
| `send_command`  | tabId, command, waitMs=300                   | activateTab（EDT）→ `ptyConnector.write((command+换行).toByteArray())`（换行用 `terminal.getKeyEncoder().encode(TerminalKeyEvent(VK_ENTER))` 编码，参考 MacroAction.kt:109）→ sleep(waitMs) → 返回增量输出                                                                                                                                     |
| `read_terminal` | tabId, full=false, rows=50                   | EDT：full=true → `Document.getText()`（含 scrollback）；否则可视区 `getScreenLine(row)` 拼接最近 N 行                                                                                                                                                                                                                                     |
| `send_keys`     | tabId, keys                                  | 原始字节直写（支持 `\u0003` 等控制字符）                                                                                                                                                                                                                                                                                                  |
| `create_tab`    | protocol, host?, port?, username?, password? | local → 仿 OpenLocalTerminalAction；ssh → `Host(protocol="SSH", host, port?:22, username, authentication=password 非空时 Password 否则 No)`；EDT 中 `ActionManager.getAction(OPEN_HOST).actionPerformed(OpenHostActionEvent(tabbedManager 作为 source, host, event))`（source 必须是 TerminalTabbed 以满足 DataProvider 链）；连接异步进行，立即返回 tabId |
| `close_tab`     | tabId                                        | EDT：`closeTerminalTab(tab, disposable=true, reconnect=false)`                                                                                                                                                                                                                                                              |
| `activate_tab`  | tabId                                        | EDT：`setSelectedTerminalTab(tab)`                                                                                                                                                                                                                                                                                          |

## 线程模型

- HttpServer 用 daemon cachedThreadPool；Bearer 校验、body 解析在线程池
- **EDT 桥接**：统一 `edt(timeout=5s) { ... }` 封装（CompletableFuture + invokeAndWait + 超时回 -32603）
- `McpTabBuffer` 监听回调仅做 synchronized append；读方 synchronized 拷贝
- Legacy SSE 每会话一个线程阻塞在队列 take()，会话关闭时中断

## 错误处理

| 场景            | 响应                                                  |
| ------------- | --------------------------------------------------- |
| JSON 解析失败     | `-32700`                                            |
| 未知 method     | `-32601`                                            |
| 参数缺失/tabId 无效 | `-32602`                                            |
| 工具执行异常        | result + `isError:true` + 错误文本（MCP 规范）              |
| 鉴权失败          | HTTP 401 + `WWW-Authenticate: Bearer`（常量时间比较 token） |

## 实现顺序

1. 骨架：build.gradle.kts + plugin.xml + settings.gradle.kts + McpPlugin（空扩展）→ `:plugins:mcp:build` 通过
2. i18n 键 + McpConfig + 设置页
3. JsonRpc + McpMessageHandler + StreamableHttpEndpoint（最小 initialize/ping）+ McpServerManager + 鉴权 → curl 验证
4. McpTabRegistry + 7 个工具 + McpTabBuffer
5. LegacySseEndpoint
6. 打磨：环形缓冲上限、DELETE 端点、异常路径

## 验证

```bash
./gradlew :plugins:mcp:build && ./gradlew :plugins:mcp:run-plugin   # 隔离数据目录运行调试
# Streamable HTTP：initialize 应 200 + Mcp-Session-Id 头
curl -s -X POST http://127.0.0.1:8920/mcp -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"curl","version":"0"}}}'
# 后续请求带 -H "Mcp-Session-Id: <id>"，测 tools/list、tools/call(list_tabs / read_terminal / send_command)
# 无 token 应 401；坏 JSON 应 -32700
# Legacy SSE：curl -N http://127.0.0.1:8920/sse 观察 endpoint 事件，再 POST /messages?sessionId=x
npx @modelcontextprotocol/inspector   # 两种 transport 各验证一轮
```

最终在 Claude Code / Cursor 中配置连接*实测：`list_tabs`* *→* *`read_terminal`* *→* *`send_command("ls")`* *收到增*量输出 → `create_tab` / `close_tab`。
