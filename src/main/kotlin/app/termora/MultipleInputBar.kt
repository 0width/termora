package app.termora

import app.termora.actions.AnActionEvent
import app.termora.actions.DataProviders
import app.termora.actions.MultipleAction
import app.termora.terminal.panel.TerminalWriter
import com.formdev.flatlaf.FlatClientProperties
import org.apache.commons.lang3.StringUtils
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.beans.PropertyChangeListener
import java.util.*
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.KeyStroke
import javax.swing.SwingConstants
import javax.swing.UIManager

/**
 * 将命令发送到当前窗口会话的底部输入条
 *
 * 始终显示在窗口底部，作为一个常驻提示，避免忘记当前处于发送状态
 */
class MultipleInputBar(
    private val windowScope: WindowScope,
    private val terminalTabbedManager: TerminalTabbedManager,
) : JPanel(BorderLayout()) {

    companion object {
        private const val MIN_HEIGHT = 40

        /**
         * 拖动的时候至少给终端保留的高度
         */
        private const val MIN_TERMINAL_HEIGHT = 100

        private const val GRIP_HEIGHT = 8
    }

    private val multipleAction get() = MultipleAction.getInstance()
    private val label = JLabel(I18n.getString("termora.tools.multiple"), Icons.warning, SwingConstants.LEADING)
    private val textField = JTextArea(3, 0)
    private val scrollPane = JScrollPane(textField)

    /**
     * 拖动手柄，放在顶部，可以上下拖动调整输入条的高度
     */
    private val grip = GripPanel()

    private var dragStartY = 0
    private var dragStartHeight = 0

    init {
        initView()
        initEvents()
        updateView()
    }

    private fun initView() {
        isVisible = false

        // 左右不留边距，让分割栏可以通到窗口边缘；内边距放在内容面板上
        border = null

        label.foreground = UIManager.getColor("Component.error.foreground") ?: Color(0xE53E3E)
        label.toolTipText = I18n.getString("termora.tools.multiple.input.close.tooltip")
        label.verticalAlignment = SwingConstants.CENTER
        label.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        textField.isOpaque = false
        textField.lineWrap = true
        textField.wrapStyleWord = true
        textField.border = BorderFactory.createEmptyBorder()
        textField.putClientProperty(
            FlatClientProperties.PLACEHOLDER_TEXT,
            I18n.getString("termora.tools.multiple.input.placeholder")
        )

        scrollPane.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 0, 0, UIManager.getColor("Component.borderColor")),
            BorderFactory.createEmptyBorder(4, 8, 4, 8),
        )
        scrollPane.verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        scrollPane.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER

        val center = JPanel(BorderLayout())
        center.isOpaque = false
        center.border = BorderFactory.createEmptyBorder(0, 8, 4, 8)
        center.add(label, BorderLayout.WEST)
        center.add(scrollPane, BorderLayout.CENTER)

        add(grip, BorderLayout.NORTH)
        add(center, BorderLayout.CENTER)
    }

    private fun initEvents() {

        // Enter 换行，Shift + Enter 发送命令
        val inputMap = textField.getInputMap(JComponent.WHEN_FOCUSED)
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "MultipleInputBar.NEWLINE")
        inputMap.put(
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK),
            "MultipleInputBar.SEND"
        )

        textField.actionMap.put("MultipleInputBar.SEND", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                send()
            }
        })
        textField.actionMap.put("MultipleInputBar.NEWLINE", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                try {
                    textField.document.insertString(textField.caretPosition, "\n", null)
                } catch (_: Exception) {
                }
            }
        })

        // ESC 把焦点还给终端
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "MultipleInputBar.ESC")
        textField.actionMap.put("MultipleInputBar.ESC", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                terminalTabbedManager.getSelectedTerminalTab()
                    ?.getData(DataProviders.TerminalPanel)?.requestFocusInWindow()
            }
        })

        // 点击标题关闭发送状态
        label.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                multipleAction.actionPerformed(
                    AnActionEvent(this@MultipleInputBar, StringUtils.EMPTY, EventObject(this@MultipleInputBar))
                )
            }
        })

        // 拖动手柄调整高度
        grip.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                dragStartY = e.yOnScreen
                dragStartHeight = height
            }
        })
        grip.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseDragged(e: MouseEvent) {
                val parent = this@MultipleInputBar.parent ?: return
                // 向上拖动变高，向下拖动变矮
                val newHeight = dragStartHeight + dragStartY - e.yOnScreen
                preferredSize = Dimension(
                    width,
                    newHeight.coerceIn(MIN_HEIGHT, maxOf(MIN_HEIGHT, parent.height - MIN_TERMINAL_HEIGHT))
                )
                revalidate()
            }
        })

        // 监听开关状态，跟随显示/隐藏
        val listener = PropertyChangeListener { evt ->
            if (evt.propertyName == "MultipleAction.isSelected") {
                updateView()
            }
        }
        multipleAction.addPropertyChangeListener(listener)
        Disposer.register(windowScope, object : Disposable {
            override fun dispose() {
                multipleAction.removePropertyChangeListener(listener)
            }
        })
    }

    private fun updateView() {
        val selected = multipleAction.isSelected(windowScope)
        if (isVisible == selected) return
        isVisible = selected
        if (selected) {
            preferredSize = null
            textField.requestFocusInWindow()
        }
    }

    private fun send() {
        val text = textField.text
        if (text.isBlank()) return

        // 把每一行都转换成 CR，确保每一行都能被当作命令执行
        val content = text.replace("\r\n", "\n").replace("\n", "\r").let {
            if (it.endsWith("\r")) it else "$it\r"
        }

        // 发送到当前窗口的所有会话
        for (tab in terminalTabbedManager.getTerminalTabs()) {
            val writer = tab.getData(DataProviders.TerminalWriter) ?: continue
            writer.write(TerminalWriter.WriteRequest.fromBytes(content.toByteArray(writer.getCharset())))
        }

        textField.text = StringUtils.EMPTY
    }

    /**
     * 分割栏：中空圆角矩形抓手，内部点缀间距较大的圆点，悬停时高亮
     */
    private inner class GripPanel : JPanel() {
        private var hover = false

        init {
            isOpaque = false
            preferredSize = Dimension(Int.MAX_VALUE, GRIP_HEIGHT)
            maximumSize = Dimension(Int.MAX_VALUE, GRIP_HEIGHT)
            cursor = Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)

            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    hover = true
                    repaint()
                }

                override fun mouseExited(e: MouseEvent) {
                    hover = false
                    repaint()
                }
            })
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

                val lineColor = UIManager.getColor("Component.borderColor") ?: Color.GRAY
                g2.color = if (hover) UIManager.getColor("Component.focusColor") ?: lineColor else lineColor

                // 中空矩形，左右贯通到边缘，不留间隙；关掉抗锯齿，避免两端像素半透明显得缺一块
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
                val rectHeight = 6
                val y0 = (height - rectHeight) / 2
                g2.drawRect(0, y0, width - 1, rectHeight)
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

                // 内部的点，间距加大
                val dot = 2
                val gap = 6
                val dotsWidth = 3 * dot + 2 * gap
                val dx0 = (width - dotsWidth) / 2
                val dy0 = (height - dot) / 2
                for (col in 0 until 3) {
                    g2.fillRoundRect(dx0 + col * (dot + gap), dy0, dot, dot, dot, dot)
                }
            } finally {
                g2.dispose()
            }
        }
    }
}
