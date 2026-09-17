package app.termora.plugins.mcp

import app.termora.IntSpinner
import app.termora.Icons
import app.termora.OptionPane
import app.termora.OptionsPane
import app.termora.YesOrNoComboBox
import com.jgoodies.forms.builder.FormBuilder
import com.jgoodies.forms.layout.FormLayout
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.ScrollPaneConstants
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

internal class McpOptionPane : JPanel(BorderLayout()), OptionsPane.PluginOption {
    private val config get() = McpConfig.instance
    private val enabledComboBox = YesOrNoComboBox()
    private val portSpinner = IntSpinner(8920, 1024, 65535)
    private val guardTextArea = JTextArea(10, 46)
    private val resetGuardButton = JButton()

    init {
        initView()
        initEvents()
        add(getCenterComponent(), BorderLayout.CENTER)
    }

    private fun initView() {
        enabledComboBox.selectedItem = config.enabled
        portSpinner.value = config.port
        guardTextArea.text = config.dangerousCommands
        resetGuardButton.text = McpI18n.getString("termora.settings.mcp.guard.reset")
    }

    private fun initEvents() {
        enabledComboBox.addActionListener {
            val enabled = enabledComboBox.selectedItem == true
            config.enabled = enabled
            runCatching {
                if (enabled) {
                    McpServerManager.start(config.port)
                } else {
                    McpServerManager.stop()
                }
            }.onFailure { e ->
                config.enabled = false
                enabledComboBox.selectedItem = false
                OptionPane.showMessageDialog(
                    this,
                    e.message ?: e.toString(),
                    messageType = JOptionPane.ERROR_MESSAGE,
                )
            }
        }

        portSpinner.addChangeListener {
            val port = portSpinner.value as Int
            config.port = port
            if (McpServerManager.isRunning) {
                runCatching { McpServerManager.start(port) }.onFailure { e ->
                    OptionPane.showMessageDialog(
                        this,
                        e.message ?: e.toString(),
                        messageType = JOptionPane.ERROR_MESSAGE,
                    )
                }
            }
        }

        guardTextArea.document.addDocumentListener(object : DocumentListener {
            private fun save() {
                config.dangerousCommands = guardTextArea.text
            }

            override fun insertUpdate(e: DocumentEvent) = save()
            override fun removeUpdate(e: DocumentEvent) = save()
            override fun changedUpdate(e: DocumentEvent) = save()
        })

        // 恢复默认规则，DocumentListener 会负责保存
        resetGuardButton.addActionListener {
            guardTextArea.text = McpConfig.DEFAULT_DANGEROUS_COMMANDS
        }
    }

    private fun getCenterComponent(): JComponent {
        val formMargin = OptionsPane.FORM_MARGIN
        val layout = FormLayout(
            "left:pref, $formMargin, default:grow",
            "pref, $formMargin, pref, $formMargin, fill:pref:grow, $formMargin, pref, $formMargin, pref, $formMargin, pref",
        )

        val guardScroll = JScrollPane(guardTextArea)
        guardScroll.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        guardScroll.preferredSize = Dimension(-1, 200)

        var rows = 1
        val step = 2
        val builder = FormBuilder.create().layout(layout).debug(false)
        builder.add("${McpI18n.getString("termora.settings.mcp.enable")}:").xy(1, rows)
        builder.add(enabledComboBox).xy(3, rows).apply { rows += step }
        builder.add("${McpI18n.getString("termora.settings.mcp.port")}:").xy(1, rows)
        builder.add(portSpinner).xy(3, rows).apply { rows += step }
        builder.add("${McpI18n.getString("termora.settings.mcp.guard")}:").xy(1, rows)
        builder.add(guardScroll).xy(3, rows).apply { rows += step }
        builder.add(JLabel(McpI18n.getString("termora.settings.mcp.guard.hint")))
            .xyw(1, rows, 3).apply { rows += step }
        builder.add(resetGuardButton).xy(3, rows, "left, center").apply { rows += step }
        builder.add(JLabel(McpI18n.getString("termora.settings.mcp.hint", (portSpinner.value as Int).toString())))
            .xyw(1, rows, 3)

        return builder.build()
    }

    override fun getIcon(isSelected: Boolean): Icon {
        return Icons.terminal
    }

    override fun getTitle(): String {
        return McpI18n.getString("termora.settings.mcp")
    }

    override fun getJComponent(): JComponent {
        return this
    }
}
