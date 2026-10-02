package dev.incusspawn;

import dev.incusspawn.command.*;
import dev.incusspawn.config.WorkerPoolSelection;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import org.aesh.AeshRuntimeRunner;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Option;

@QuarkusMain
public class IncusSpawn implements QuarkusApplication {
    @Override
    public int run(String... args) {
        try {
            // Resolve static/materialized pool selection once before constructing services or paths.
            var selection = WorkerPoolSelection.current();
            if (selection.isMaterializedMaintenance()
                    && !maintenanceCommandAllowed(args)) {
                System.err.println("Error: materialized maintenance permits only "
                        + "'vm start --without-direct', 'vm stop', and 'automation delete'.");
                return 1;
            }
            if (args.length == 0) {
                return launchTui() ? 0 : 1;
            }
            if (looksLikeHelpQuestion(args)) {
                var question = new java.util.ArrayList<>(java.util.List.of(args));
                question.remove("--help");
                System.err.println("Did you mean: isx ask " + String.join(" ", question));
                System.err.println();
                System.err.println("Use 'isx ask <question>' for AI-powered help (uses AI tokens).");
                System.err.println("Use 'isx --help' for command usage.");
                return 1;
            }
            var topCommand = Platform.isMacOS()
                    ? IncusSpawnCommand.class
                    : IncusSpawnLinuxCommand.class;
            var result = AeshRuntimeRunner.builder()
                    .command(topCommand)
                    .args(args)
                    .execute();
            return result != null ? result.getResultValue() : 1;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    static boolean maintenanceCommandAllowed(String[] args) {
        if (args.length >= 2
                && "automation".equals(args[0])
                && "delete".equals(args[1])) {
            return true;
        }
        if (args.length == 2
                && "vm".equals(args[0])
                && "stop".equals(args[1])) {
            return true;
        }
        return args.length == 3
                && "vm".equals(args[0])
                && "start".equals(args[1])
                && "--without-direct".equals(args[2]);
    }

    private static boolean looksLikeHelpQuestion(String[] args) {
        if (args.length < 2) return false;
        var first = args[0];
        return first.equals("--help") && !args[1].startsWith("-");
    }

    static boolean launchTui() {
        if (!InitCommand.requireInit()) return false;
        new ListCommand().executeDirect();
        return true;
    }

    static CommandResult runTop(boolean versionRequested) {
        if (versionRequested) {
            var info = BuildInfo.instance();
            System.out.println("incus-spawn " + info.version() + " (" + info.gitSha() + ")");
            System.out.println("incus client " + info.incusClient() + ", server " + info.incusServer());
            System.out.println(info.runtime());
            return CommandResult.SUCCESS;
        }
        return launchTui() ? CommandResult.SUCCESS : CommandResult.valueOf(1);
    }

    // macOS variant: includes the `vm` and `pool` appliance command groups.
    @CommandDefinition(name = "incus-spawn",
            description = "Manage isolated Incus development environments",
            groupCommands = {
                InitCommand.class, BuildCommand.class, ProjectCommand.class,
                BranchCommand.class, ShellCommand.class, RunCommand.class, ListCommand.class,
                DestroyCommand.class, UpdateAllCommand.class, ProxyCommand.class,
                AutomationCommand.class, CleanCommand.class, CompletionCommand.class, TemplatesCommand.class,
                InstancesCommand.class, GitRemoteHelperCommand.class, SshProxyCommand.class,
                VmCommand.class, PoolCommand.class, UpdateBaseCommand.class, DoctorCommand.class,
                AskCommand.class
            }, generateHelp = true)
    public static class IncusSpawnCommand extends BaseCommand {
        @Option(shortName = 'V', name = "version", hasValue = false, description = "Display version info")
        boolean versionRequested;
        @Override
        protected CommandResult doExecute() {
            return runTop(versionRequested);
        }
    }

    // Linux variant: identical to {@link IncusSpawnCommand} without macOS appliance groups, so
    // `isx vm` and `isx pool` are unknown and absent from `isx --help` on Linux.
    @CommandDefinition(name = "incus-spawn",
            description = "Manage isolated Incus development environments",
            groupCommands = {
                InitCommand.class, BuildCommand.class, ProjectCommand.class,
                BranchCommand.class, ShellCommand.class, RunCommand.class, ListCommand.class,
                DestroyCommand.class, UpdateAllCommand.class, ProxyCommand.class,
                AutomationCommand.class, CleanCommand.class, CompletionCommand.class, TemplatesCommand.class,
                InstancesCommand.class, GitRemoteHelperCommand.class, SshProxyCommand.class,
                UpdateBaseCommand.class, DoctorCommand.class,
                AskCommand.class
            }, generateHelp = true)
    public static class IncusSpawnLinuxCommand extends BaseCommand {
        @Option(shortName = 'V', name = "version", hasValue = false, description = "Display version info")
        boolean versionRequested;
        @Override
        protected CommandResult doExecute() {
            return runTop(versionRequested);
        }
    }
}
