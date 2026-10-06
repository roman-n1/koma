package koma.ide

import com.intellij.find.FindManager
import com.intellij.json.psi.*
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.psi.PsiManager
import com.intellij.ui.content.ContentFactory
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

class KomaToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val container = JPanel(BorderLayout())
        val status = JLabel("Open a model.koma.json export, then Refresh.")
        val diagram = DiagramPanel()
        val rows = DefaultListModel<String>()
        val list = JList(rows)
        var file: JsonFile? = null
        var keys = emptyList<String>()
        fun navigate(key: String) {
            ReadAction.run<RuntimeException> {
                file?.let { model ->
                    val descriptor = KomaModel.source(project, model, key)
                    if (descriptor != null) descriptor.navigate(true) else KomaModel.declaration(model, key)?.let { OpenFileDescriptor(project, it.containingFile.virtualFile, it.textOffset).navigate(true) }
                }
            }
        }
        val refresh = JButton("Refresh").apply { addActionListener {
            ReadAction.run<RuntimeException> {
                val virtual = FileEditorManager.getInstance(project).selectedFiles.firstOrNull { it.name.endsWith(".koma.json") }
                file = virtual?.let { PsiManager.getInstance(project).findFile(it) as? JsonFile }
                val model = file?.let(KomaModel::model)
                if (model == null) { status.text = "Select a format-1 .koma.json export."; return@run }
                val nodes = KomaModel.objects(model, "states").mapNotNull { KomaModel.text(it, "id") }
                val edges = KomaModel.objects(model, "transitions").mapNotNull { entry ->
                    val id = KomaModel.text(entry, "id") ?: return@mapNotNull null
                    val source = KomaModel.text(entry, "source") ?: return@mapNotNull null
                    val target = KomaModel.text(entry, "target") ?: return@mapNotNull null
                    DiagramEdge(id, source, target, KomaModel.text(entry, "trigger") ?: "")
                }
                val active = ((file?.let(KomaModel::root)?.findProperty("active")?.value as? JsonArray)?.valueList.orEmpty()).filterIsInstance<JsonStringLiteral>().map { it.value }.toSet()
                diagram.load(nodes, edges, active, ::navigate)
                rows.clear(); edges.forEach { rows.addElement("${it.id}: ${it.source} —${it.label}→ ${it.target}") }; keys = edges.map { it.id }
                status.text = "${nodes.size} states, ${edges.size} transitions; double-click to navigate."
            }
        } }
        val usages = JButton("Find usages").apply { addActionListener {
            val key = keys.getOrNull(list.selectedIndex) ?: return@addActionListener
            file?.let { KomaModel.declaration(it, key) }?.let { FindManager.getInstance(project).findUsages(it) }
        } }
        list.addMouseListener(object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) {
            if (e.clickCount == 2) keys.getOrNull(list.selectedIndex)?.let(::navigate)
        } })
        val controls = JPanel(FlowLayout(FlowLayout.LEFT)).apply { add(refresh); add(usages); add(status) }
        container.add(controls, BorderLayout.NORTH)
        container.add(JSplitPane(JSplitPane.VERTICAL_SPLIT, JScrollPane(diagram), JScrollPane(list)).apply { resizeWeight = 0.8 }, BorderLayout.CENTER)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(container, "Behaviour", false))
    }
}

internal data class DiagramEdge(val id: String, val source: String, val target: String, val label: String)

/** Native drawing only: model labels are text, no HTML/JavaScript or external renderer is executed. */
internal class DiagramPanel : JPanel() {
    private var nodes = emptyList<String>(); private var edges = emptyList<DiagramEdge>(); private var active = emptySet<String>()
    private var onNavigate: (String) -> Unit = {}
    private fun bounds(index: Int) = Rectangle(30 + (index % 4) * 220, 40 + (index / 4) * 140, 160, 50)
    init { background = Color.WHITE; addMouseListener(object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) {
        if (e.clickCount == 2) nodes.indexOfFirst { bounds(nodes.indexOf(it)).contains(e.point) }.takeIf { it >= 0 }?.let { onNavigate("state:${nodes[it]}") }
    } }) }
    fun load(nodes: List<String>, edges: List<DiagramEdge>, active: Set<String>, navigate: (String) -> Unit) {
        this.nodes = nodes.take(200); this.edges = edges; this.active = active; this.onNavigate = navigate
        preferredSize = Dimension(920, maxOf(200, ((this.nodes.size + 3) / 4) * 140 + 80)); revalidate(); repaint()
    }
    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val graphics = g as Graphics2D
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        for (edge in edges) {
            val from = nodes.indexOf(edge.source); val to = nodes.indexOf(edge.target)
            if (from < 0 || to < 0) continue
            val a = bounds(from); val b = bounds(to)
            graphics.color = Color.GRAY
            graphics.drawLine(a.x + a.width / 2, a.y + a.height, b.x + b.width / 2, b.y)
            graphics.fillPolygon(intArrayOf(b.x + b.width / 2 - 4, b.x + b.width / 2 + 4, b.x + b.width / 2), intArrayOf(b.y - 8, b.y - 8, b.y), 3)
        }
        nodes.forEachIndexed { index, label ->
            val box = bounds(index)
            graphics.color = if (label in active) Color(201, 236, 212) else Color(235, 242, 250)
            graphics.fillRoundRect(box.x, box.y, box.width, box.height, 12, 12)
            graphics.color = Color.DARK_GRAY; graphics.drawRoundRect(box.x, box.y, box.width, box.height, 12, 12)
            graphics.drawString(label.take(22), box.x + 8, box.y + 29)
        }
        if (nodes.size == 200) graphics.drawString("Canvas shows the first 200 nodes; the transition list remains complete.", 20, 20)
    }
}
