package actron.verification;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles;
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment;
import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement;
import org.jetbrains.kotlin.config.CompilerConfiguration;
import org.jetbrains.kotlin.lexer.KtTokens;
import org.jetbrains.kotlin.psi.*;

/** Syntax guard for Actron-owned Kotlin library sources, including test-tool modules. */
public final class NullabilityGuard {
    private static final String BASELINE = ".actron/nullability/baseline.tsv";
    private static final String STRICT = ".actron/nullability/strict";
    private static final Set<String> OPTIONAL_NAMES = Set.of("Optional", "OptionalInt", "OptionalLong",
        "OptionalDouble", "Option", "Maybe", "Some", "None");

    private record Finding(String kind, String path, int line, String source) {
        String key() {
            return kind + "\t" + path + "\t" + source.strip().replaceAll("\\s+", " ");
        }
        String diagnostic() { return path + ":" + line + ": " + kind + ": " + source.strip(); }
    }

    public static void main(String[] args) throws Exception {
        Disposable disposable = Disposer.newDisposable();
        try {
            var environment = KotlinCoreEnvironment.createForProduction(disposable,
                new CompilerConfiguration(), EnvironmentConfigFiles.JVM_CONFIG_FILES);
            var parser = new KtPsiFactory(environment.getProject(), false);
            if (args.length == 1 && args[0].equals("--self-test")) {
                selfTest(parser);
                return;
            }
            if (args.length == 0) throw new IllegalArgumentException("Expected --check, --prune or --finish");
            int rootIndex = List.of(args).indexOf("--root");
            if (rootIndex >= 0 && rootIndex + 1 >= args.length) throw new IllegalArgumentException("Missing root path");
            Path root = Path.of(rootIndex >= 0 ? args[rootIndex + 1] : "").toAbsolutePath();
            List<Finding> findings = scanRepository(root, parser);
            List<String> current = findings.stream().map(Finding::key).sorted().toList();
            boolean strict = Files.exists(root.resolve(STRICT));
            List<String> baseline = readBaseline(root.resolve(BASELINE));
            if (strict && !baseline.isEmpty()) throw new IllegalStateException("Strict mode cannot have baseline debt");
            int baseIndex = List.of(args).indexOf("--base-ref");
            if (baseIndex >= 0) {
                if (baseIndex + 1 >= args.length) throw new IllegalArgumentException("Missing base ref");
                String ref = args[baseIndex + 1];
                if (!ref.matches("[0-9a-fA-F]{40,64}")) throw new IllegalArgumentException("Base must be a full commit SHA");
                Process verify = new ProcessBuilder("git", "cat-file", "-e", ref + "^{commit}")
                    .directory(root.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
                if (verify.waitFor() != 0) throw new IllegalStateException("Cannot verify the policy base commit " + ref);
                var previous = gitFile(root, ref, BASELINE);
                if (previous.exitCode == 0) {
                    requireSubset(baseline, lines(previous.text), "The baseline may only shrink relative to the PR base");
                }
                if (gitFile(root, ref, STRICT).exitCode == 0 && !strict) {
                    throw new IllegalStateException("Strict mode cannot be disabled once enabled on the base branch");
                }
            }
            switch (args[0]) {
                case "--report" -> {
                    Map<String, Integer> kinds = new java.util.TreeMap<>();
                    Map<String, Integer> modules = new java.util.TreeMap<>();
                    for (Finding finding : findings) {
                        kinds.merge(finding.kind, 1, Integer::sum);
                        modules.merge(finding.path.split("/")[0], 1, Integer::sum);
                    }
                    System.out.println("Remaining findings: " + findings.size());
                    System.out.println("Kinds: " + kinds);
                    System.out.println("Modules: " + modules);
                }
                case "--check" -> {
                    Map<String, Integer> remaining = counts(baseline);
                    List<Finding> added = new ArrayList<>();
                    for (Finding f : findings) {
                        int allowed = remaining.getOrDefault(f.key(), 0);
                        if (allowed == 0) added.add(f);
                        else remaining.put(f.key(), allowed - 1);
                    }
                    if (!added.isEmpty()) {
                        added.forEach(f -> System.err.println(f.diagnostic()));
                        throw new IllegalStateException(added.size() + " new forbidden constructs. Model behavior; do not expand the baseline.");
                    }
                    if (!current.equals(baseline)) {
                        throw new IllegalStateException("Resolved debt remains in the baseline; run :nullability-guard:pruneBaseline");
                    }
                    System.out.println(strict ? "Strict null/optional policy passed." :
                        "Null/optional ratchet passed; remaining migration findings: " + findings.size());
                }
                case "--prune" -> {
                    requireSubset(current, baseline, "Pruning cannot approve new constructs");
                    Files.write(root.resolve(BASELINE), current, StandardCharsets.UTF_8);
                    System.out.println("Baseline reduced to " + current.size() + " findings.");
                }
                case "--finish" -> {
                    if (!findings.isEmpty()) throw new IllegalStateException("Cannot enable strict mode: " + findings.size() + " findings remain");
                    Files.write(root.resolve(BASELINE), List.of(), StandardCharsets.UTF_8);
                    Files.writeString(root.resolve(STRICT), "No nullable constructs or optional containers in Actron-owned Kotlin code.\n");
                    System.out.println("Strict policy enabled; returning to a debt baseline is forbidden.");
                }
                default -> throw new IllegalArgumentException("Unknown mode " + args[0]);
            }
        } finally { Disposer.dispose(disposable); }
    }

    private static List<Finding> scanRepository(Path root, KtPsiFactory parser) throws IOException {
        List<Finding> result = new ArrayList<>();
        try (Stream<Path> modules = Files.list(root)) {
            for (Path module : modules.filter(p -> p.getFileName().toString().startsWith("actron-") && Files.isDirectory(p)).sorted().toList()) {
                Path src = module.resolve("src");
                if (!Files.isDirectory(src)) continue;
                try (Stream<Path> sets = Files.list(src)) {
                    for (Path set : sets.filter(p -> p.getFileName().toString().endsWith("Main") && Files.isDirectory(p)).sorted().toList()) {
                        try (Stream<Path> files = Files.walk(set)) {
                            for (Path file : files.filter(p -> p.toString().endsWith(".kt")).sorted().toList()) {
                                result.addAll(scan(parser, root.relativize(file).toString(), Files.readString(file)));
                            }
                        }
                    }
                }
            }
        }
        if (result.isEmpty() && !Files.isDirectory(root.resolve("actron-core/src/commonMain"))) {
            throw new IllegalStateException("Run the policy checker at the repository root");
        }
        return result;
    }

    private static List<Finding> scan(KtPsiFactory parser, String path, String source) {
        KtFile file = parser.createFile(path.substring(path.lastIndexOf('/') + 1), source);
        List<Finding> result = new ArrayList<>();
        file.accept(new KtTreeVisitorVoid() {
            private void report(String kind, PsiElement element) {
                int offset = element.getTextOffset();
                int start = source.lastIndexOf('\n', Math.max(0, offset - 1)) + 1;
                int end = source.indexOf('\n', offset);
                if (end < 0) end = source.length();
                result.add(new Finding(kind, path, 1 + (int) source.substring(0, offset).chars().filter(c -> c == '\n').count(), source.substring(start, end)));
            }
            @Override public void visitElement(PsiElement element) {
                if (element instanceof PsiErrorElement error) throw new IllegalStateException(path + ": invalid Kotlin: " + error.getErrorDescription());
                super.visitElement(element);
            }
            @Override public void visitNullableType(KtNullableType type) {
                if (!isKotlinEqualsParameter(type)) report("nullable-type", type);
                super.visitNullableType(type);
            }
            @Override public void visitTypeParameter(KtTypeParameter parameter) {
                if (parameter.getParent().getParent() instanceof KtTypeParameterListOwner owner
                    && !(owner instanceof KtTypeAlias) && parameter.getExtendsBound() == null
                    && owner.getTypeConstraints().stream().noneMatch(constraint ->
                        constraint.getSubjectTypeParameterName() != null
                        && constraint.getSubjectTypeParameterName().getReferencedName().equals(parameter.getName()))) {
                    report("unbounded-type-parameter", parameter);
                }
                super.visitTypeParameter(parameter);
            }
            @Override public void visitConstantExpression(KtConstantExpression expression) {
                if (expression.getText().equals("null")) report("null-literal", expression);
                super.visitConstantExpression(expression);
            }
            @Override public void visitPostfixExpression(KtPostfixExpression expression) {
                if (expression.getOperationToken() == KtTokens.EXCLEXCL) report("non-null-assertion", expression);
                super.visitPostfixExpression(expression);
            }
            @Override public void visitProperty(KtProperty property) {
                if (property.hasModifier(KtTokens.LATEINIT_KEYWORD)) report("lateinit", property);
                super.visitProperty(property);
            }
            @Override public void visitSimpleNameExpression(KtSimpleNameExpression expression) {
                if (OPTIONAL_NAMES.contains(expression.getReferencedName())) report("optional-container", expression);
                super.visitSimpleNameExpression(expression);
            }
            @Override public void visitClass(KtClass klass) {
                if (klass.getName() != null && OPTIONAL_NAMES.contains(klass.getName())) report("optional-container", klass);
                super.visitClass(klass);
            }
            @Override public void visitObjectDeclaration(KtObjectDeclaration object) {
                if (object.getName() != null && OPTIONAL_NAMES.contains(object.getName())) report("optional-container", object);
                super.visitObjectDeclaration(object);
            }
            @Override public void visitTypeAlias(KtTypeAlias alias) {
                if (alias.getName() != null && OPTIONAL_NAMES.contains(alias.getName())) report("optional-container", alias);
                super.visitTypeAlias(alias);
            }
        });
        return result;
    }

    /** Kotlin's fixed Any.equals signature; the exemption covers only the parameter's Any?. */
    private static boolean isKotlinEqualsParameter(KtNullableType type) {
        if (!type.getText().equals("Any?") && !type.getText().equals("kotlin.Any?")) return false;
        if (!(type.getParent() instanceof KtTypeReference ref) || !(ref.getParent() instanceof KtParameter parameter)) return false;
        if (!(parameter.getParent() instanceof KtParameterList parameters) || !(parameters.getParent() instanceof KtNamedFunction function)) return false;
        return "equals".equals(function.getName()) && function.hasModifier(KtTokens.OVERRIDE_KEYWORD) && function.getValueParameters().size() == 1;
    }

    private static List<String> readBaseline(Path path) throws IOException {
        if (!Files.isRegularFile(path)) throw new IllegalStateException("Missing reviewed nullability baseline: " + path);
        return lines(Files.readString(path));
    }
    private static List<String> lines(String text) { return text.lines().filter(s -> !s.isBlank()).sorted().toList(); }
    private static Map<String, Integer> counts(List<String> values) {
        Map<String, Integer> result = new HashMap<>();
        values.forEach(v -> result.merge(v, 1, Integer::sum));
        return result;
    }
    private static void requireSubset(List<String> current, List<String> allowed, String message) {
        Map<String, Integer> remaining = counts(allowed);
        for (String value : current) {
            int n = remaining.getOrDefault(value, 0);
            if (n == 0) throw new IllegalStateException(message + ": " + value);
            remaining.put(value, n - 1);
        }
    }
    private record GitFile(int exitCode, String text) {}
    private static GitFile gitFile(Path root, String ref, String file) throws Exception {
        Process p = new ProcessBuilder("git", "show", ref + ":" + file).directory(root.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        String text = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new GitFile(p.waitFor(), text);
    }

    private static void selfTest(KtPsiFactory parser) throws Exception {
        assertKinds(parser, "val x: String? = null", List.of("nullable-type", "null-literal"));
        assertKinds(parser, "val x: ((String?) -> Int)? = null", List.of("nullable-type", "nullable-type", "null-literal"));
        assertKinds(parser, "fun f(x: String) = x!!", List.of("non-null-assertion"));
        assertKinds(parser, "lateinit var x: String", List.of("lateinit"));
        assertKinds(parser, "// null Optional !!\n/* outer /* null */ comment */\nval x = \"null Optional !! String?\"", List.of());
        assertKinds(parser, "val x = \"${null}\"", List.of("null-literal"));
        assertKinds(parser, "val x = \"\"\"${null}\"\"\"", List.of("null-literal"));
        assertKinds(parser, "import java.util.Optional as Hidden\nval x = Hidden.empty<String>()", List.of("optional-container"));
        assertKinds(parser, "typealias Hidden<T> = java.util.Optional<T>", List.of("optional-container"));
        assertKinds(parser, "class Maybe<T : Any>", List.of("optional-container"));
        assertKinds(parser, "data object None", List.of("optional-container"));
        assertKinds(parser, "class `Optional`<T : Any>", List.of("optional-container"));
        assertKinds(parser, "class Holder<T>", List.of("unbounded-type-parameter"));
        assertKinds(parser, "class Holder<T : Any>", List.of());
        assertKinds(parser, "class Holder<T> where T : Any", List.of());
        assertKinds(parser, "val x = object { val y: String? = null }", List.of("nullable-type", "null-literal"));
        assertKinds(parser, "class X { override fun equals(other: Any?): Boolean = other is X }", List.of());
        assertKinds(parser, "class X { override fun equals(other: Any?): Boolean { val x: Any? = null; return true } }", List.of("nullable-type", "null-literal"));
        requireSubset(List.of("a"), List.of("a", "b"), "unexpected");
        boolean failed = false;
        try { requireSubset(List.of("a", "a"), List.of("a"), "growth"); } catch (IllegalStateException expected) { failed = true; }
        if (!failed) throw new AssertionError("Duplicated debt was accepted");
        failed = false;
        try { scan(parser, "Broken.kt", "val x: = null"); } catch (IllegalStateException expected) { failed = true; }
        if (!failed) throw new AssertionError("Malformed Kotlin was silently accepted");
        integrationTest(parser);
        System.out.println("Nullability guard self-tests passed.");
    }

    /** Exercise the CLI against a real temporary repository, including attempted baseline bypasses. */
    private static void integrationTest(KtPsiFactory parser) throws Exception {
        Path root = Files.createTempDirectory("actron-null-policy-");
        try {
            Path source = root.resolve("actron-core/src/commonMain/kotlin/Fixture.kt");
            Files.createDirectories(source.getParent());
            Files.createDirectories(root.resolve(BASELINE).getParent());
            Files.writeString(source, "val old: String? = null\n");
            Files.write(root.resolve(BASELINE), scanRepository(root, parser).stream().map(Finding::key).sorted().toList());
            git(root, "init", "-q");
            git(root, "add", ".");
            git(root, "-c", "user.name=Policy Test", "-c", "user.email=policy@example.invalid", "commit", "-qm", "adopt policy");
            String base = git(root, "rev-parse", "HEAD").strip();
            main(new String[]{"--check", "--root", root.toString(), "--base-ref", base});
            expectFailure(() -> main(new String[]{"--finish", "--root", root.toString()}), "premature strict mode");
            Files.writeString(source, "val old: String? = null\nval added: Int? = null\n");
            expectFailure(() -> main(new String[]{"--prune", "--root", root.toString()}), "prune approved new debt");
            Files.write(root.resolve(BASELINE), scanRepository(root, parser).stream().map(Finding::key).sorted().toList());
            expectFailure(() -> main(new String[]{"--check", "--root", root.toString(), "--base-ref", base}), "baseline expansion");
            Files.writeString(source, "val value: String = \"ready\"\n");
            main(new String[]{"--finish", "--root", root.toString()});
            main(new String[]{"--check", "--root", root.toString(), "--base-ref", base});
            git(root, "add", ".");
            git(root, "-c", "user.name=Policy Test", "-c", "user.email=policy@example.invalid", "commit", "-qm", "strict policy");
            String strictBase = git(root, "rev-parse", "HEAD").strip();
            Files.delete(root.resolve(STRICT));
            expectFailure(() -> main(new String[]{"--check", "--root", root.toString(), "--base-ref", strictBase}), "strict rollback");
        } finally {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }
    private static void expectFailure(CheckedAction action, String scenario) throws Exception {
        try { action.run(); } catch (IllegalStateException expected) { return; }
        throw new AssertionError("Policy accepted " + scenario);
    }
    private static String git(Path root, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-c", "commit.gpgsign=false"));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new AssertionError(output);
        return output;
    }
    private static void assertKinds(KtPsiFactory parser, String code, List<String> expected) {
        List<String> actual = scan(parser, "Fixture.kt", code).stream().map(Finding::kind).sorted().toList();
        List<String> sorted = new ArrayList<>(expected);
        Collections.sort(sorted);
        if (!actual.equals(sorted)) throw new AssertionError(code + "\nexpected " + sorted + " but got " + actual);
    }
}
