package actron.ide

import com.intellij.json.psi.*
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import java.io.File

internal object ActronModel {
    fun root(file: JsonFile): JsonObject? {
        if (!file.name.endsWith(".actron.json")) return null
        val root = file.topLevelValue as? JsonObject ?: return null
        if (root.findProperty("format")?.value?.text != "1") return null
        return root.takeIf { it.findProperty("model")?.value is JsonObject }
    }
    fun model(file: JsonFile): JsonObject? = root(file)?.findProperty("model")?.value as? JsonObject
    fun objects(model: JsonObject, name: String): List<JsonObject> =
        ((model.findProperty(name)?.value as? JsonArray)?.valueList.orEmpty()).filterIsInstance<JsonObject>()
    fun text(value: JsonObject, key: String): String? = (value.findProperty(key)?.value as? JsonStringLiteral)?.value
    fun declaration(file: JsonFile, key: String): JsonStringLiteral? {
        val model = model(file) ?: return null
        val list = if (key.startsWith("transition:")) objects(model, "transitions") else objects(model, "states")
        val id = key.removePrefix("state:")
        return list.mapNotNull { it.findProperty("id")?.value as? JsonStringLiteral }.firstOrNull { it.value == id }
    }
    fun declarationKey(element: PsiElement): String? {
        val literal = element as? JsonStringLiteral ?: return null
        val property = literal.parent as? JsonProperty ?: return null
        if (property.name != "id" || property.value != literal) return null
        val file = literal.containingFile as? JsonFile ?: return null
        val kind = ((property.parent as? JsonObject)?.parent as? JsonArray)?.parent as? JsonProperty
        val key = if (kind?.name == "transitions") literal.value else "state:${literal.value}"
        return key.takeIf { declaration(file, key) == element }
    }
    /** Source data cannot navigate outside the project, including through symlinks. */
    fun source(project: Project, file: JsonFile, key: String): OpenFileDescriptor? {
        val source = (root(file)?.findProperty("sources")?.value as? JsonObject)?.findProperty(key)?.value as? JsonObject ?: return null
        val path = text(source, "path") ?: return null
        val line = source.findProperty("line")?.value?.text?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        if (path.isBlank() || path.any { it < ' ' } || path.startsWith('/') || path.contains(':') || path.contains('\\') || path.split('/').any { it == ".." || it == "." || it.isBlank() }) return null
        val base = project.basePath?.let { runCatching { File(it).canonicalFile }.getOrNull() } ?: return null
        val target = runCatching { File(base, path).canonicalFile }.getOrNull() ?: return null
        if (!target.toPath().startsWith(base.toPath())) return null
        val virtual = LocalFileSystem.getInstance().findFileByIoFile(target) ?: return null
        return OpenFileDescriptor(project, virtual, line - 1, 0)
    }
}
