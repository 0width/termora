# Termora 项目结构分析

## 概览

**Termora** 是一个用 **Kotlin/JVM** 开发的跨平台终端模拟器与 SSH 客户端，支持 Windows、macOS、Linux。当前版本 `2.0.0-beta.16`，构建工具为 **Gradle**，UI 框架为 **Swing (FlatLaf)**。

***

## 顶层目录

| 目录/文件                 | 说明                                           |
| --------------------- | -------------------------------------------- |
| `src/`                | 主源码 + 测试                                     |
| `plugins/`            | 13 个独立 Gradle 子模块插件                          |
| `docs/`               | 截图文档                                         |
| `gradle/`             | Gradle wrapper 配置                            |
| `build.gradle.kts`    | 构建脚本 (\~900行)，含打包(jpackage)、签名、公证等           |
| `settings.gradle.kts` | 定义 rootProject + 所有 `include("plugins:xxx")` |
| `VERSION`             | 版本号                                          |
| `THIRDPARTY`          | 第三方开源许可声明                                    |

***

## 核心源码结构 (`src/main/kotlin/app/termora/`)

项目共约 **395 个 .kt 文件**，按功能分包：

### 1. 根包 — 应用生命周期与基础设施

| 文件                                                        | 职责                                                  |
| --------------------------------------------------------- | --------------------------------------------------- |
| `Main.kt` → `ApplicationInitializr` → `ApplicationRunner` | 启动入口链                                               |
| `Application.kt`                                          | 单例，管理版本、数据目录、HTTP 客户端、Shell 检测等全局状态                 |
| `ApplicationRunner.kt`                                    | 启动流程：打印系统信息 → 打开数据库 → 加载设置 → 初始化 LAF → 加载插件 → 启动主窗口 |
| `TermoraFrame.kt` / `TermoraFrameManager.kt`              | 主窗口与多窗口管理                                           |
| `TerminalTabbed.kt` / `MyTabbedPane.kt`                   | 终端多标签页容器                                            |
| `Laf.kt`                                                  | LookAndFeel 主题管理 (\~1000行)                          |
| `I18n.kt`                                                 | 国际化加载，资源在 `resources/i18n/messages_*.properties`    |
| `Crypto.kt`                                               | 加密工具                                                |
| `SettingsOptionsPane.kt`                                  | 设置面板 (\~1000行)                                      |

### 2. `terminal/` — XTerm 终端模拟核心 (最核心模块)

这是项目的**核心引擎**，实现了 XTerm 控制序列协议：

| 文件/概念                                   | 职责                                                                    |
| --------------------------------------- | --------------------------------------------------------------------- |
| `Terminal` (接口)                         | 终端抽象，组合 Document/FindModel/TerminalModel/SelectionModel/CursorModel 等 |
| `TerminalModel` / `TerminalModelImpl`   | 终端状态（行列、颜色板、DataKey 键值存储）                                             |
| `ControlSequenceIntroducerProcessor.kt` | **CSI 序列处理器** (\~38KB，最大的单文件)，处理 ANSI 转义序列                            |
| `EscapeSequenceProcessor.kt`            | ESC 序列处理                                                              |
| `OperatingSystemCommandProcessor.kt`    | OSC 序列（标题、通知等）                                                        |
| `TerminalLineBuffer.kt`                 | 终端行缓冲区 (\~25KB)                                                       |
| `Document` / `DocumentImpl`             | 终端文本内容模型                                                              |
| `SelectionModel`                        | 文本选择                                                                  |
| `CursorModel`                           | 光标状态                                                                  |
| `ScrollingModel`                        | 滚动与回滚缓冲区                                                              |
| `ColorPalette`                          | 终端颜色方案                                                                |
| `KeyEncoder`                            | 键盘输入编码                                                                |
| `VisualTerminal.kt`                     | 终端渲染层                                                                 |
| `KTerm.kt`                              | 终端键盘交互                                                                |
| `PtyConnector`                          | PTY 连接抽象（本地 PTY / SSH / Telnet 等）                                     |
| `panel/`                                | 终端面板 UI 组件                                                            |
| `panel/vw/`                             | 终端视图扩展（含 FloatingToolbar 等）                                           |

**设计模式**：基于接口的组合模式（Terminal 不直接实现，而是组合多个 Model），和 DataKey 事件总线。

### 3. `plugin/` — 插件系统

| 文件                    | 职责                                           |
| --------------------- | -------------------------------------------- |
| `Plugin` (接口)         | 插件抽象，提供 `getExtensions<T>()`                 |
| `Extension` (接口)      | 扩展点，支持 `ordered()` 排序和 `DispatchThread` 线程约束 |
| `PluginManager.kt`    | 插件生命周期：加载内部插件 → 系统插件 → 用户外部插件                |
| `PluginDescriptor.kt` | 插件元数据                                        |
| `ExtensionManager.kt` | 扩展注册与查找                                      |
| `internal/`           | **内置插件**：                                    |
| <br />                | `ssh/` — SSH 连接                              |
| <br />                | `telnet/` — Telnet 连接                        |
| <br />                | `rdp/` — RDP 远程桌面                            |
| <br />                | `wsl/` — WSL 集成                              |
| <br />                | `sftppty/` — SFTP + PTY                      |
| <br />                | `local/` — 本地终端                              |
| <br />                | `updater/` — 自动更新                            |
| <br />                | `badge/` — 徽章标记                              |
| <br />                | `extension/` — 动态扩展                          |
| <br />                | `plugin/` — 插件管理 UI                          |

### 4. `transfer/` — 文件传输 (SFTP/S3)

| 文件                                  | 职责                     |
| ----------------------------------- | ---------------------- |
| `TransportPanel.kt`                 | 传输面板主界面 (\~56KB，最大单文件) |
| `DefaultInternalTransferManager.kt` | 传输任务管理                 |
| `TransferTableModel.kt`             | 传输任务表格                 |
| `TransportTabbed.kt`                | 传输标签页容器                |
| `internal/local/`                   | 本地文件系统                 |
| `internal/sftp/`                    | SFTP 远程文件系统            |
| `s3/`                               | S3 对象存储                |
| 支持功能：文件浏览、编辑、权限修改、拖拽传输、最多6并发        | <br />                 |

### 5. `tree/` — 主机树

| 文件                    | 职责             |
| --------------------- | -------------- |
| `NewHostTree.kt`      | 主机树组件 (\~50KB) |
| `NewHostTreeModel.kt` | 主机树数据模型        |
| `HostTreeNode.kt`     | 树节点            |

### 6. 其他模块

| 包                      | 职责                                 |
| ---------------------- | ---------------------------------- |
| `database/`            | SQLite (Exposed ORM) 数据持久层，含加密字段支持 |
| `host/` (根包 `Host.kt`) | 主机连接配置（SSH、代理、认证方式等）               |
| `actions/`             | 全局 Action 常量定义                     |
| `keymgr/`              | SSH 密钥管理器                          |
| `keymap/`              | 快捷键映射                              |
| `macro/`               | 命令宏                                |
| `snippet/`             | 代码片段                               |
| `highlight/`           | 关键词高亮                              |
| `tlog/`                | 终端日志记录                             |
| `findeverywhere/`      | 全局搜索                               |
| `x11/`                 | X11 转发                             |
| `account/`             | 账号管理                               |
| `addons/`              | 附加功能（含 ZModem 协议支持）                |

***

## 插件子模块 (`plugins/`)

13 个独立 Gradle 子项目，编译为独立 JAR 加载：

| 插件          | 功能                 |
| ----------- | ------------------ |
| `s3`        | AWS S3 对象存储        |
| `oss`       | 阿里云 OSS            |
| `cos`       | 腾讯云 COS            |
| `obs`       | 华为云 OBS            |
| `ftp`       | FTP 协议             |
| `webdav`    | WebDAV 协议          |
| `smb`       | SMB 文件共享           |
| `sync`      | 配置同步 (Gist/WebDAV) |
| `editor`    | SFTP 内建文件编辑器       |
| `geo`       | 主机地理信息             |
| `migration` | 从其他软件导入配置          |
| `bg`        | 背景图?               |
| `serial`    | 串口通信               |
| `vnc`       | VNC 远程桌面           |

***

## 关键依赖

| 库                         | 用途                   |
| ------------------------- | -------------------- |
| FlatLaf                   | Swing 现代 LookAndFeel |
| pty4j                     | 伪终端 (PTY)            |
| Apache SSHD / sshj / JSch | SSH 客户端/服务端          |
| Exposed + SQLite          | ORM 数据库              |
| OkHttp                    | HTTP 客户端             |
| kotlinx-serialization     | JSON 序列化             |
| kotlinx-coroutines        | 协程                   |
| JGit                      | Git 操作               |
| OSHI                      | 系统信息                 |
| JNA                       | 本地系统调用               |

***

## 启动流程

```
Main.kt main()
  → ApplicationInitializr.run()
    → ApplicationRunner.run()
      1. 虚拟线程加载插件 PluginManager
      2. 打印系统信息
      3. 打开 SQLite 数据库
      4. 加载设置
      5. 启动分析统计
      6. 设置 FlatLaf 主题
      7. 清理临时目录
      8. 等待插件加载完成
      9. 触发 ApplicationRunnerExtension.ready()
      10. EDT 线程启动 TermoraFrame 主窗口
```

***

## 开发建议

- **运行**：`./gradlew :run`
- **JDK**：jdk25, java\_home: C:/Users/xios/.jdks/jbrsdk-25.0.3-windows-x64-b496.62
- **MCP 插件编译**：`.\gradlew.bat :plugins:mcp:build`（产物在 `build/plugins/mcp/`）
- **调试**：已内置 5005 端口远程调试
- **核心关注点**：`terminal/` 包是最核心的终端仿真引擎；`plugin/` 是扩展架构；`transfer/` 是文件管理模块
- **新增协议**：实现 `PtyConnector` + `Plugin` 接口即可
- **新增存储后端**：在 `plugins/` 下新建子模块，实现传输相关接口

