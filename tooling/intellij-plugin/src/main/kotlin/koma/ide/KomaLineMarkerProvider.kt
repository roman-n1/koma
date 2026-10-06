package koma.ide

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.*

/** Kotlin resolves references normally; names alone never mark another library's on()/after(). */
class KomaLineMarkerProvider : LineMarkerProvider {
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element.firstChild != null) return null
        val reference = element.parent as? KtNameReferenceExpression ?: return null
        val call = reference.parent as? KtCallExpression ?: return null
        if (call.calleeExpression != reference || reference.getReferencedName() !in setOf("Transition", "on", "always", "after", "onDone", "internal")) return null
        val resolved = reference.references.firstNotNullOfOrNull { it.resolve() } ?: return null
        val namespace = (resolved.containingFile as? KtFile)?.packageFqName?.asString() ?: return null
        if (namespace != "koma.statechart") return null
        val targets = call.valueArguments.mapNotNull { it.getArgumentExpression() }.flatMap { expression ->
            expression.references.mapNotNull { it.resolve() }
        }.filterIsInstance<KtProperty>().filter { property ->
            (property.initializer as? KtCallExpression)?.calleeExpression?.text == "StateId"
        }.distinct()
        if (targets.isEmpty()) return null
        return NavigationGutterIconBuilder.create(AllIcons.Nodes.Method).setTargets(targets)
            .setTooltipText("Navigate Koma transition states").createLineMarkerInfo(element)
    }
}
