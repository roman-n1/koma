package actron.verification;

import java.util.Collection;
import java.util.List;
import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption;
import org.jetbrains.kotlin.compiler.plugin.CliOption;
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor;
import org.jetbrains.kotlin.config.CompilerConfiguration;
import org.jetbrains.kotlin.config.CompilerConfigurationKey;

public final class NullabilityOptions implements CommandLineProcessor {
    static final String ID = "actron-nullability";
    static final CompilerConfigurationKey<String> ROOT = CompilerConfigurationKey.create("Actron repository root");
    static final CompilerConfigurationKey<Boolean> ENFORCE = CompilerConfigurationKey.create("Enforce before migration completion");
    private static final Collection<AbstractCliOption> OPTIONS =
        List.of(new CliOption("root", "path", "Repository containing the strict policy marker", true, false),
            new CliOption("enforce", "true|false", "Require zero resolved findings while finishing migration", false, false));

    @Override public String getPluginId() { return ID; }
    @Override public Collection<AbstractCliOption> getPluginOptions() {
        return OPTIONS;
    }
    @Override public void processOption(AbstractCliOption option, String value, CompilerConfiguration configuration) {
        switch (option.getOptionName()) {
            case "root" -> configuration.put(ROOT, value);
            case "enforce" -> {
                if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException("Expected true or false");
                configuration.put(ENFORCE, Boolean.parseBoolean(value));
            }
            default -> throw new IllegalArgumentException("Unknown nullability option");
        }
    }
}
