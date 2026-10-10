package actron.verification;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import kotlin.Unit;
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension;
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys;
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation;
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity;
import org.jetbrains.kotlin.cli.common.messages.MessageCollector;
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar;
import org.jetbrains.kotlin.config.CompilerConfiguration;
import org.jetbrains.kotlin.config.KotlinCompilerVersion;
import org.jetbrains.kotlin.ir.IrElement;
import org.jetbrains.kotlin.ir.declarations.*;
import org.jetbrains.kotlin.ir.expressions.IrCall;
import org.jetbrains.kotlin.ir.expressions.IrExpression;
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression;
import org.jetbrains.kotlin.ir.expressions.IrReturn;
import org.jetbrains.kotlin.ir.types.*;
import org.jetbrains.kotlin.ir.util.IrUtilsKt;
import org.jetbrains.kotlin.ir.visitors.IrVisitor;
import org.jetbrains.kotlin.name.FqName;

/** Audits resolved types, including inferred locals and nested arguments, before IR lowering. */
public final class NullabilityCompilerPlugin extends CompilerPluginRegistrar {
    @Override public String getPluginId() { return NullabilityOptions.ID; }
    @Override public boolean getSupportsK2() { return true; }

    @Override public void registerExtensions(ExtensionStorage storage, CompilerConfiguration configuration) {
        if (!"2.3.20".equals(KotlinCompilerVersion.getVersion())) {
            throw new IllegalStateException("Revalidate Actron's nullability compiler plugin before changing Kotlin 2.3.20");
        }
        String configuredRoot = configuration.get(NullabilityOptions.ROOT);
        if (configuredRoot == null) throw new IllegalStateException("Actron nullability plugin requires its repository root");
        Path root = Path.of(configuredRoot).toAbsolutePath().normalize();
        boolean strict = Files.isRegularFile(root.resolve(".actron/nullability/strict"))
            || Boolean.TRUE.equals(configuration.get(NullabilityOptions.ENFORCE));
        MessageCollector messages = configuration.getNotNull(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY);
        storage.registerExtension(IrGenerationExtension.Companion, (module, context) -> {
            for (IrFile file : module.getFiles()) {
                String path = file.getFileEntry().getName().replace('\\', '/');
                if (!Path.of(path).toAbsolutePath().normalize().startsWith(root)
                    || !path.matches(".*?/actron-[^/]+/src/[^/]+Main/.*\\.kt")) continue;
                file.accept(new Audit(messages, strict), file);
            }
        });
        // Inspect authored resolved types before Compose/serialization rewrite signatures or
        // introduce ABI temporaries. Kotlin 2.3.20 shares this ordered registry across registrars.
        var extensions = storage.getRegisteredExtensions().get(IrGenerationExtension.Companion);
        extensions.add(0, extensions.remove(extensions.size() - 1));
    }

    private static final class Audit extends IrVisitor<Unit, IrFile> {
        private final MessageCollector messages;
        private final boolean strict;
        private final Set<IrFunction> completionLambdas = Collections.newSetFromMap(new IdentityHashMap<>());

        Audit(MessageCollector messages, boolean strict) { this.messages = messages; this.strict = strict; }

        @Override public Unit visitElement(IrElement element, IrFile file) {
            element.acceptChildren(this, file);
            return Unit.INSTANCE;
        }

        private boolean authored(IrDeclaration declaration) {
            String origin = declaration.getOrigin().getName();
            return declaration.getStartOffset() >= 0 && !origin.equals("FAKE_OVERRIDE")
                && !origin.startsWith("GENERATED") && !origin.equals("IR_TEMPORARY_VARIABLE")
                && !origin.equals("SYNTHETIC_HELPER_FOR_ENUM_VALUES")
                && !origin.equals("SYNTHETIC_HELPER_FOR_ENUM_ENTRIES");
        }

        private boolean nullable(IrType type) {
            if (IrTypeUtilsKt.isNullable(type)) return true;
            if (type instanceof IrSimpleType simple) {
                for (int i = 0; i < simple.getArguments().size(); i++) {
                    IrTypeArgument argument = simple.getArguments().get(i);
                    if (argument instanceof IrTypeProjection projection && nullable(projection.getType())) return true;
                    if (argument instanceof IrStarProjection && simple.getClassifier().getOwner() instanceof IrClass klass
                        && i < klass.getTypeParameters().size()
                        && klass.getTypeParameters().get(i).getSuperTypes().stream().anyMatch(this::nullable)) return true;
                }
            }
            return false;
        }

        private void check(IrDeclaration declaration, IrType type, IrFile file, String kind) {
            if (!authored(declaration) || !nullable(type)) return;
            int offset = declaration.getStartOffset();
            String name = declaration instanceof IrDeclarationWithName named ? named.getName().asString() : kind;
            messages.report(strict ? CompilerMessageSeverity.ERROR : CompilerMessageSeverity.WARNING,
                "[Actron nullability] " + kind + " '" + name + "' has a nullable resolved type",
                CompilerMessageLocation.create(file.getFileEntry().getName(), file.getFileEntry().getLineNumber(offset) + 1,
                    file.getFileEntry().getColumnNumber(offset) + 1, null));
        }

        @Override public Unit visitVariable(IrVariable variable, IrFile file) {
            check(variable, variable.getType(), file, "local");
            return visitElement(variable, file);
        }
        @Override public Unit visitField(IrField field, IrFile file) {
            check(field, field.getType(), file, "field");
            return visitElement(field, file);
        }
        @Override public Unit visitFunction(IrFunction function, IrFile file) {
            if (!authored(function)) return Unit.INSTANCE;
            // A library selector such as compareBy has a nullable expected return, even when
            // the authored lambda only returns non-null values. Inspect those actual returns;
            // inferred nullable results (map lookup, firstOrNull, etc.) still fail.
            if (!function.getOrigin().getName().equals("LOCAL_FUNCTION_FOR_LAMBDA") || !returnsNonNull(function)) {
                check(function, function.getReturnType(), file, "return");
            }
            return visitElement(function, file);
        }
        private boolean returnsNonNull(IrFunction function) {
            boolean[] found = {false};
            boolean[] safe = {true};
            function.acceptChildren(new IrVisitor<Unit, Void>() {
                @Override public Unit visitElement(IrElement element, Void unused) {
                    element.acceptChildren(this, unused);
                    return Unit.INSTANCE;
                }
                @Override public Unit visitReturn(IrReturn returned, Void unused) {
                    if (returned.getReturnTargetSymbol().getOwner() == function) {
                        found[0] = true;
                        if (nullable(returned.getValue().getType())) safe[0] = false;
                    }
                    return visitElement(returned, unused);
                }
            }, null);
            return found[0] && safe[0];
        }
        private boolean overridesAnyEquals(IrSimpleFunction function, Set<IrSimpleFunction> seen) {
            if (!seen.add(function)) return false;
            FqName name = IrUtilsKt.getFqNameWhenAvailable(function);
            if (name != null && name.asString().equals("kotlin.Any.equals")) return true;
            return function.getOverriddenSymbols().stream().anyMatch(symbol -> overridesAnyEquals(symbol.getOwner(), seen));
        }
        @Override public Unit visitValueParameter(IrValueParameter parameter, IrFile file) {
            IrDeclarationParent parent = parameter.getParent();
            boolean intrinsicEquals = parent instanceof IrSimpleFunction function
                && function.getName().asString().equals("equals") && function.getValueParameters().size() == 1
                && overridesAnyEquals(function, Collections.newSetFromMap(new IdentityHashMap<>()));
            // Job's external SPI uses Throwable? for successful completion. The exception is
            // only the parameter of a lambda supplied directly to that SPI; its owned locals,
            // fields, return values and copied callbacks remain audited.
            boolean completionParameter = parent instanceof IrFunction function && completionLambdas.contains(function);
            if (!intrinsicEquals && !completionParameter) check(parameter, parameter.getType(), file, "parameter");
            return visitElement(parameter, file);
        }
        @Override public Unit visitCall(IrCall call, IrFile file) {
            FqName name = IrUtilsKt.getFqNameWhenAvailable(call.getSymbol().getOwner());
            if (name != null && name.asString().equals("kotlinx.coroutines.Job.invokeOnCompletion")) {
                for (int i = 0; i < call.getValueArgumentsCount(); i++) {
                    IrExpression argument = call.getValueArgument(i);
                    if (argument instanceof IrFunctionExpression lambda) completionLambdas.add(lambda.getFunction());
                }
            }
            return visitElement(call, file);
        }
    }
}
