package actron.ide

import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.json.psi.*
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.psi.*
import com.intellij.util.ProcessingContext
import com.intellij.util.Processor
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usageView.UsageInfo

/** References are scoped to one exported definition; unrelated labels/guard strings are ignored. */
class ActronReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(psiElement(JsonStringLiteral::class.java), object : PsiReferenceProvider() {
            override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
                val literal = element as JsonStringLiteral
                val file = literal.containingFile as? JsonFile ?: return emptyArray()
                if (ActronModel.root(file) == null || ActronModel.declarationKey(literal) != null) return emptyArray()
                val property = literal.parent as? JsonProperty
                val arrayProperty = (literal.parent as? JsonArray)?.parent as? JsonProperty
                if (property != null && (property.value != literal || property.name !in setOf("source", "target", "parent", "initial", "default"))) return emptyArray()
                if (property == null && arrayProperty?.name !in setOf("active", "coveredStates", "selectedTransitions")) return emptyArray()
                val key = if (arrayProperty?.name == "selectedTransitions") literal.value else "state:${literal.value}"
                return arrayOf(object : PsiReferenceBase<JsonStringLiteral>(literal) {
                    override fun resolve(): PsiElement? = ActronModel.declaration(file, key)
                    override fun getVariants(): Array<Any> = emptyArray()
                })
            }
        })
    }
}

class ActronFindUsagesHandlerFactory : FindUsagesHandlerFactory() {
    override fun canFindUsages(element: PsiElement): Boolean = ActronModel.declarationKey(element) != null
    override fun createFindUsagesHandler(element: PsiElement, forHighlightUsages: Boolean): FindUsagesHandler = object : FindUsagesHandler(element) {
        override fun processElementUsages(element: PsiElement, processor: Processor<in UsageInfo>, options: FindUsagesOptions): Boolean {
            // A definition is local to one versioned export; identical ids in other machines are unrelated.
            for (literal in PsiTreeUtil.findChildrenOfType(element.containingFile, JsonStringLiteral::class.java)) {
                for (reference in PsiReferenceService.getService().getReferences(literal, PsiReferenceService.Hints.NO_HINTS)) if (reference.isReferenceTo(element) && !processor.process(UsageInfo(reference))) return false
            }
            return true
        }
    }
}
