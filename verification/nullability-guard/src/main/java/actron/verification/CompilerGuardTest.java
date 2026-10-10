package actron.verification;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jetbrains.kotlin.cli.common.ExitCode;
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler;

/** Real compiler regressions: syntax-only scanning cannot find inferred nullable types. */
public final class CompilerGuardTest {
    public static void main(String[] args) throws Exception {
        Path plugin = Path.of(args[0]).toAbsolutePath();
        Path root = Files.createTempDirectory("actron-compiler-policy-");
        try {
            Path policy = root.resolve(".actron/nullability");
            Files.createDirectories(policy);
            Files.writeString(policy.resolve("strict"), "strict\n");
            reject(plugin, root, "val inferred = mapOf(\"one\" to \"two\")[\"absent\"]", "local inferred property");
            reject(plugin, root, "fun lookup() = listOf(\"one\").firstOrNull()", "inferred return");
            reject(plugin, root, "val nested = listOf(\"one\").map { mapOf(\"x\" to \"y\")[it] }", "nested inferred argument");
            reject(plugin, root, "typealias Text = String?; val aliased: Text = \"x\"", "type alias");
            reject(plugin, root, "val callback: (String?) -> Unit = {}", "nested callback");
            reject(plugin, root, "val erased: List<*> = listOf(\"one\")", "star projection with nullable bound");
            reject(plugin, root, "val callback: kotlinx.coroutines.CompletionHandler = {}", "an owned external callback alias");
            reject(plugin, root, "fun <T> identity(value: T): T = value", "nullable generic upper bound");
            reject(plugin, root, "class C { override fun equals(other: Any?): Boolean { val copied = other; return copied is C }; override fun hashCode() = 0 }", "equals cannot exempt a copied local");
            accept(plugin, root, "fun <T : Any> identity(value: T): T = value");
            accept(plugin, root, "class C { override fun equals(other: Any?): Boolean = other is C; override fun hashCode() = 0 }");
            accept(plugin, root, "val normalized = mapOf(\"one\" to \"two\")[\"absent\"] ?: \"default\"");
            accept(plugin, root, "fun copy(values: List<String>) = values.map { it.length }");
            accept(plugin, root, "fun install(job: kotlinx.coroutines.Job) { job.invokeOnCompletion { cause -> if (cause is IllegalStateException) println(cause.message.orEmpty()) } }");
            reject(plugin, root, "fun install(job: kotlinx.coroutines.Job) { job.invokeOnCompletion { cause -> val copied = cause; println(copied) } }", "SPI parameter copied into owned nullable local");
            // Tests may deliberately supply invalid/nullable values to verify the boundary.
            compile(plugin, root, "val adversarial: String? = null", false, "commonTest");
            Files.delete(policy.resolve("strict"));
            compile(plugin, root, "fun lookup() = listOf(\"one\").firstOrNull()", false, "commonMain");
            System.out.println("Resolved nullability compiler tests passed.");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void reject(Path plugin, Path root, String source, String caseName) throws Exception {
        String output = compile(plugin, root, source, true, "commonMain");
        if (!output.contains("[Actron nullability]")) throw new AssertionError("No policy diagnostic: " + caseName + "\n" + output);
    }
    private static void accept(Path plugin, Path root, String source) throws Exception {
        compile(plugin, root, source, false, "commonMain");
    }
    private static String compile(Path plugin, Path root, String source, boolean reject, String set) throws Exception {
        Path file = root.resolve("actron-fixture/src/" + set + "/kotlin/Fixture.kt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        ExitCode result;
        try (PrintStream output = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
            result = new K2JVMCompiler().exec(output,
                "-no-stdlib", "-no-reflect", "-classpath", System.getProperty("java.class.path"),
                "-Xplugin=" + plugin, "-P", "plugin:actron-nullability:root=" + root,
                "-P", "plugin:actron-nullability:enforce=false",
                "-d", root.resolve("compiled").toString(), file.toString());
        }
        String output = captured.toString(StandardCharsets.UTF_8);
        if ((result == ExitCode.OK) == reject) throw new AssertionError("Unexpected compiler result " + result + "\n" + source + "\n" + output);
        return output;
    }
}
