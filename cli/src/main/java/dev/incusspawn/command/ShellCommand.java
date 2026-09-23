package dev.incusspawn.command;

import dev.incusspawn.RuntimeServices;
import dev.incusspawn.automation.AutomationService;
import dev.incusspawn.incus.IncusClient;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

@CommandDefinition(
        name = "shell",
        description = "Open a shell in an existing clone",
        generateHelp = true
)
public class ShellCommand extends BaseCommand {

    @Argument(description = "Name of the clone to connect to", required = true)
    String name;

    @Option(name = "root", description = "Open a root shell in an automation-owned instance",
            hasValue = false)
    boolean root;

    @Option(name = "ownership-digest",
            description = "SHA-256 digest of the expected automation ownership key")
    String ownershipDigest;

    @Option(name = "cwd", description = "Absolute initial directory inside the instance")
    String cwd;

    @Override
    protected CommandResult doExecute() throws Exception {
        var incus = RuntimeServices.incus();
        if (root) {
            if (ownershipDigest == null) {
                System.err.println("Error: --root requires --ownership-digest.");
                return CommandResult.valueOf(1);
            }
            new AutomationService(
                    new dev.incusspawn.automation.IncusAutomationTransport(incus))
                    .inspectByOwnershipDigest(name, ownershipDigest);
        } else if (ownershipDigest != null) {
            System.err.println("Error: --ownership-digest is valid only with --root.");
            return CommandResult.valueOf(1);
        }

        var initialDirectory = normalizedInitialDirectory(cwd);
        var parent = InstancePrep.prepareInstance(incus, name);
        if (parent == null) {
            return CommandResult.valueOf(1);
        }

        System.out.println("Connecting to " + name + "...\n");
        if (root) {
            new AutomationService(
                    new dev.incusspawn.automation.IncusAutomationTransport(incus))
                    .inspectByOwnershipDigest(name, ownershipDigest);
            var discovered = IncusClient.ShellPrep.from(incus, name);
            var rootPrep = new IncusClient.ShellPrep(
                    initialDirectory,
                    null,
                    false,
                    false,
                    discovered.subnetDiagnostic(),
                    discovered.terminfoHandled());
            incus.interactiveShell(name, "root", rootPrep);
        } else if (initialDirectory != null) {
            var discovered = IncusClient.ShellPrep.from(incus, name);
            var selected = new IncusClient.ShellPrep(
                    initialDirectory,
                    discovered.shellCommand(),
                    discovered.autoAttachTmux(),
                    discovered.autoAttachZmx(),
                    discovered.subnetDiagnostic(),
                    discovered.terminfoHandled());
            incus.interactiveShell(name, "agentuser", selected);
        } else {
            incus.interactiveShell(name, "agentuser");
        }
        return CommandResult.SUCCESS;
    }

    static String normalizedInitialDirectory(String value) {
        if (value == null) return null;
        if (value.isBlank() || value.indexOf('\0') >= 0 || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("--cwd must be an absolute normalized path");
        }
        final Path path;
        try {
            path = Path.of(value);
        } catch (InvalidPathException error) {
            throw new IllegalArgumentException("--cwd must be an absolute normalized path", error);
        }
        if (!path.isAbsolute() || !path.normalize().toString().equals(value)) {
            throw new IllegalArgumentException("--cwd must be an absolute normalized path");
        }
        return value;
    }

}
