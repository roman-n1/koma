package koma.ide

import com.intellij.json.psi.*
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.psi.PsiReferenceService
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.usageView.UsageInfo
import java.awt.image.BufferedImage
import java.awt.event.MouseEvent
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression

class KomaNavigationTest : BasePlatformTestCase() {
    private fun references(element: PsiElement) = PsiReferenceService.getService().getReferences(element, PsiReferenceService.Hints.NO_HINTS)
    private fun manifest(): JsonFile = myFixture.configureByText("model.koma.json", """
        {"format":1,"definition":"sample","version":"1","sources":{},"model":{
          "initial":"idle","states":[{"id":"idle"},{"id":"done"}],
          "transitions":[{"id":"transition:0","index":0,"source":"idle","target":"done","guard":"done"}]},
          "active":["done"],"selectedTransitions":["transition:0"]}
    """.trimIndent()) as JsonFile

    fun testStateAndTransitionReferencesResolveWithinOneDefinitionAndGuardLabelsDoNot() {
        val file = manifest()
        assertTrue(PluginManagerCore.getPlugin(PluginId.getId("io.github.roman-n1.koma"))?.isEnabled == true)
        val model = KomaModel.model(file)!!
        val edge = KomaModel.objects(model, "transitions").single()
        val target = edge.findProperty("target")!!.value as JsonStringLiteral
        assertEquals(KomaModel.declaration(file, "state:done"), references(target).single().resolve())
        val guard = edge.findProperty("guard")!!.value as JsonStringLiteral
        assertTrue(references(guard).isEmpty())
        val selected = (KomaModel.root(file)!!.findProperty("selectedTransitions")!!.value as JsonArray).valueList.single()
        assertEquals(KomaModel.declaration(file, "transition:0"), references(selected).single().resolve())
    }

    fun testFindUsagesIncludesEndpointsAndActiveStateReferences() {
        val file = manifest()
        val definition = KomaModel.declaration(file, "state:done")!!
        val factory = KomaFindUsagesHandlerFactory()
        assertTrue(factory.canFindUsages(definition))
        val usages = mutableListOf<UsageInfo>()
        factory.createFindUsagesHandler(definition, false).processElementUsages(definition, { usages += it; true }, FindUsagesOptions(project))
        assertEquals(2, usages.size)
    }

    fun testUnsupportedExportsDoNotInstallReferences() {
        val file = myFixture.configureByText("future.koma.json", """{"format":2,"model":{"states":[{"id":"done"}],"initial":"done"}}""") as JsonFile
        assertNull(KomaModel.root(file))
        assertTrue(PsiTreeUtil.findChildrenOfType(file, JsonStringLiteral::class.java).all { references(it).isEmpty() })
    }

    fun testGutterResolvesKomaDeclarationAndIgnoresUnrelatedSameNamedFunction() {
        myFixture.addFileToProject("koma/statechart/Definition.kt", """
            package koma.statechart
            class StateId(val value: String)
            fun Transition(source: StateId, target: StateId) = Unit
        """.trimIndent())
        myFixture.addFileToProject("other/Definition.kt", """
            package other
            fun Transition(source: koma.statechart.StateId, target: koma.statechart.StateId) = Unit
        """.trimIndent())
        val file = myFixture.configureByText("Workflow.kt", """
            import koma.statechart.StateId
            import koma.statechart.Transition
            val idle = StateId("idle")
            val done = StateId("done")
            fun workflow() {
                Transition(idle, done)
                other.Transition(idle, done)
            }
        """.trimIndent())
        val calls = PsiTreeUtil.findChildrenOfType(file, KtCallExpression::class.java)
            .filter { it.calleeExpression?.text == "Transition" }
        assertEquals(2, calls.size)
        val provider = KomaLineMarkerProvider()
        fun marker(call: KtCallExpression) = provider.getLineMarkerInfo((call.calleeExpression as KtNameReferenceExpression).firstChild)
        assertNotNull(marker(calls.first()))
        assertNull(marker(calls.last()))
    }

    fun testCanvasNavigatesStateByModelIdentityWithoutExecutingLabels() {
        val canvas = DiagramPanel()
        var selected: String? = null
        canvas.load(listOf("idle", "done"), listOf(DiagramEdge("transition:0", "idle", "done", "go")), setOf("done")) { selected = it }
        canvas.setSize(canvas.preferredSize)
        canvas.paint(BufferedImage(canvas.width, canvas.height, BufferedImage.TYPE_INT_ARGB).graphics)
        canvas.dispatchEvent(MouseEvent(canvas, MouseEvent.MOUSE_CLICKED, 1, 0, 50, 60, 2, false))
        assertEquals("state:idle", selected)
    }
}
