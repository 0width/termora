package app.termora.plugins.mcp.protocol

import app.termora.plugins.mcp.terminal.McpTools
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * MCP 工具注册表：tools/list 与 tools/call
 */
internal object McpToolRegistry {
    private val log = LoggerFactory.getLogger(McpToolRegistry::class.java)

    private class Tool(
        val name: String,
        val description: String,
        val inputSchema: JsonObject,
        val handler: (JsonObject) -> String,
    )

    private fun schema(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject {
        return buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject(block))
        }
    }

    private fun param(type: String, description: String): JsonObject {
        return buildJsonObject {
            put("type", type)
            put("description", description)
        }
    }

    private fun JsonObject.string(name: String): String? {
        return (this[name] as? JsonPrimitive)?.contentOrNull
    }

    private fun JsonObject.long(name: String): Long? {
        return (this[name] as? JsonPrimitive)?.content?.toLongOrNull()
    }

    private fun JsonObject.int(name: String): Int? {
        return (this[name] as? JsonPrimitive)?.content?.toIntOrNull()
    }

    private fun JsonObject.boolean(name: String, defaultValue: Boolean): Boolean {
        val primitive = this[name] as? JsonPrimitive ?: return defaultValue
        return primitive.content.toBooleanStrictOrNull() ?: defaultValue
    }

    private val tools = listOf(
        Tool(
            "list_tabs",
            "List all open terminal tabs in Termora. Returns id, title, host, protocol, username and whether the tab is active.",
            schema { },
            { _ -> McpTools.listTabs() },
        ),
        Tool(
            "read_terminal",
            "Read the screen content of a terminal tab. By default returns the last visible rows; set full=true to read the entire scrollback buffer. Use this only to inspect the screen (e.g. TUI / full-screen applications); to check the output or result of a command, prefer the send_command return value or the read_output tool.",
            schema {
                put("tabId", param("string", "Terminal tab id from list_tabs"))
                put("full", param("boolean", "true to read the entire scrollback buffer, default false"))
                put("rows", param("integer", "Number of visible rows to read, default 50"))
            },
            { args ->
                val tabId = args.string("tabId") ?: throw IllegalArgumentException("Missing required parameter: tabId")
                val full = args.boolean("full", false)
                val rows = args.int("rows") ?: 50
                McpTools.readTerminal(tabId, full, rows)
            },
        ),
        Tool(
            "send_command",
            "Activate a terminal tab, type a command and press Enter, then return the output produced by this command only (output from earlier commands is discarded). Waits until the output is quiet (no new output for a moment) or waitMs is reached; if the command is still running afterwards, use read_output to get further output. e.g. send_command(tabId, \"ls -l\"). Dangerous commands are blocked by the safety guard in Termora settings.",
            schema {
                put("tabId", param("string", "Terminal tab id from list_tabs"))
                put("command", param("string", "The command to execute"))
                put("waitMs", param("integer", "Max milliseconds to wait for output before returning, default 500"))
            },
            { args ->
                val tabId = args.string("tabId") ?: throw IllegalArgumentException("Missing required parameter: tabId")
                val command = args.string("command") ?: throw IllegalArgumentException("Missing required parameter: command")
                val waitMs = args.long("waitMs") ?: 500L
                McpTools.sendCommand(tabId, command, waitMs)
            },
        ),
        Tool(
            "read_output",
            "Read the new output produced since the last read in a terminal tab, without sending any input. Use this to check the progress or result of a running command instead of read_terminal. Returns once the output is quiet (no new output for quietMs) or waitMs is reached.",
            schema {
                put("tabId", param("string", "Terminal tab id from list_tabs"))
                put("waitMs", param("integer", "Max milliseconds to wait for output, default 5000"))
                put("quietMs", param("integer", "Return once no new output for this many milliseconds, default 300"))
            },
            { args ->
                val tabId = args.string("tabId") ?: throw IllegalArgumentException("Missing required parameter: tabId")
                val waitMs = args.long("waitMs") ?: 5_000L
                val quietMs = args.long("quietMs") ?: 300L
                McpTools.readOutput(tabId, waitMs, quietMs)
            },
        ),
        Tool(
            "exec_command",
            "Execute a command directly on the remote host via the SSH exec channel (same connection as the terminal tab) and return the exit code with stdout/stderr. Runs in a fresh shell: it does NOT inherit the interactive session's state (current directory, exported env vars, venv), interactive parts of .bashrc/.zshrc are not loaded, and it cannot handle interactive prompts (sudo password, y/n) or TUI apps. SSH tabs only — for other tabs or stateful/interactive commands use send_command. Best for one-off commands like \"docker ps\", \"systemctl status xxx\", or running a script and capturing its result. Dangerous commands are blocked by the safety guard in Termora settings.",
            schema {
                put("tabId", param("string", "Terminal tab id from list_tabs"))
                put("command", param("string", "The command to execute"))
                put("timeoutMs", param("integer", "Max milliseconds to wait for the command to finish, default 60000"))
            },
            { args ->
                val tabId = args.string("tabId") ?: throw IllegalArgumentException("Missing required parameter: tabId")
                val command = args.string("command") ?: throw IllegalArgumentException("Missing required parameter: command")
                val timeoutMs = args.long("timeoutMs") ?: 60_000L
                McpTools.execCommand(tabId, command, timeoutMs)
            },
        ),
        Tool(
            "send_keys",
            "Send raw keystrokes to a terminal tab without pressing Enter. Supports control characters like \\u0003 (Ctrl+C). Dangerous commands are blocked by the safety guard in Termora settings.",
            schema {
                put("tabId", param("string", "Terminal tab id from list_tabs"))
                put("keys", param("string", "Raw keys to send, may contain control characters"))
            },
            { args ->
                val tabId = args.string("tabId") ?: throw IllegalArgumentException("Missing required parameter: tabId")
                val keys = args.string("keys") ?: throw IllegalArgumentException("Missing required parameter: keys")
                McpTools.sendKeys(tabId, keys)
            },
        ),
        Tool(
            "create_tab",
            "Create a new terminal tab. protocol=local opens a local terminal; protocol=ssh connects to a remote host (password authentication).",
            schema {
                put("protocol", param("string", "Connection protocol: local or ssh"))
                put("host", param("string", "Remote host address, required for ssh"))
                put("port", param("integer", "Remote host port, default 22 for ssh"))
                put("username", param("string", "Username for ssh"))
                put("password", param("string", "Password for ssh, omit to authenticate interactively in the terminal"))
            },
            { args ->
                McpTools.createTab(
                    protocol = args.string("protocol")
                        ?: throw IllegalArgumentException("Missing required parameter: protocol"),
                    host = args.string("host"),
                    port = args.int("port"),
                    username = args.string("username"),
                    password = args.string("password"),
                )
            },
        ),
        Tool(
            "list_hosts",
            "List all saved hosts in Termora (excluding folders). Returns id, name, protocol, host, port, username.",
            schema { },
            { _ -> McpTools.listHosts() },
        ),
        Tool(
            "open_host",
            "Open a saved host by its name or id. Uses the host's full saved configuration (authentication, proxy, jump hosts, etc.).",
            schema {
                put("nameOrId", param("string", "Host name (exact or fuzzy match) or host id from list_hosts"))
            },
            { args ->
                val nameOrId = args.string("nameOrId")
                    ?: throw IllegalArgumentException("Missing required parameter: nameOrId")
                McpTools.openHost(nameOrId)
            },
        ),
        Tool(
            "close_tab",
            "Close a terminal tab.",
            schema {
                put("tabId", param("string", "Terminal tab id from list_tabs"))
            },
            { args ->
                val tabId = args.string("tabId") ?: throw IllegalArgumentException("Missing required parameter: tabId")
                McpTools.closeTab(tabId)
            },
        ),
        Tool(
            "activate_tab",
            "Bring a terminal tab to the front.",
            schema {
                put("tabId", param("string", "Terminal tab id from list_tabs"))
            },
            { args ->
                val tabId = args.string("tabId") ?: throw IllegalArgumentException("Missing required parameter: tabId")
                McpTools.activateTab(tabId)
            },
        ),
    )

    private val toolsByName = tools.associateBy { it.name }

    fun listTools(): JsonObject {
        return buildJsonObject {
            put("tools", buildJsonArray {
                for (tool in tools) {
                    add(buildJsonObject {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("inputSchema", tool.inputSchema)
                    })
                }
            })
        }
    }

    /**
     * 执行工具调用。执行错误通过 isError:true 返回（MCP 规范），不使用 JSON-RPC 错误
     */
    fun call(params: JsonObject): JsonObject {
        val name = params.string("name")
        if (name.isNullOrBlank()) {
            return errorResult("Missing tool name")
        }

        val tool = toolsByName[name] ?: return errorResult("Unknown tool: $name")
        val arguments = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())

        return try {
            okResult(tool.handler(arguments))
        } catch (e: Exception) {
            if (log.isErrorEnabled) {
                log.error("Unable to execute MCP tool: {}", name, e)
            }
            errorResult(e.message ?: e.toString())
        }
    }

    private fun okResult(text: String): JsonObject {
        return buildJsonObject {
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", text)
                })
            })
            put("isError", false)
        }
    }

    private fun errorResult(text: String): JsonObject {
        return buildJsonObject {
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", text)
                })
            })
            put("isError", true)
        }
    }
}
