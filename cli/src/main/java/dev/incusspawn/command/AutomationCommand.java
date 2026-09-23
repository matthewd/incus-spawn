package dev.incusspawn.command;

import dev.incusspawn.RuntimeServices;
import dev.incusspawn.automation.AutomationProtocol;
import dev.incusspawn.automation.AutomationService;
import dev.incusspawn.automation.AutomationTransport;
import dev.incusspawn.automation.IncusAutomationTransport;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Option;

/** Versioned, non-interactive machine API. */
@CommandDefinition(
        name = "automation",
        description = "Versioned non-interactive automation API",
        generateHelp = true,
        groupCommands = {
                AutomationCommand.Create.class,
                AutomationCommand.Inspect.class,
                AutomationCommand.Start.class,
                AutomationCommand.Stop.class,
                AutomationCommand.Mount.class,
                AutomationCommand.Unmount.class,
                AutomationCommand.Delete.class,
                AutomationCommand.Exec.class
        }
)
public class AutomationCommand extends BaseCommand {

    @Override
    protected CommandResult doExecute() {
        System.out.println(commandInvocation.getHelpInfo());
        return CommandResult.SUCCESS;
    }

    private static AutomationService service() {
        return new AutomationService(new IncusAutomationTransport(RuntimeServices.incus()));
    }

    @FunctionalInterface
    private interface LifecycleCall {
        AutomationService.LifecycleResult run();
    }

    private static CommandResult lifecycle(String operation, LifecycleCall call) {
        try {
            System.out.println(AutomationProtocol.lifecycleLine(call.run()));
            return CommandResult.SUCCESS;
        } catch (Exception e) {
            System.out.println(AutomationProtocol.lifecycleErrorLine(operation, e));
            return CommandResult.valueOf(1);
        }
    }

    @CommandDefinition(name = "create", description = "Create a stopped owned CoW instance",
            generateHelp = true)
    public static class Create extends BaseCommand {
        @Option(name = "name", description = "New instance name")
        String name;

        @Option(name = "template", description = "Existing stopped template")
        String template;

        @Option(name = "key", description = "Caller ownership key")
        String key;

        @Override
        protected CommandResult doExecute() {
            return lifecycle("create", () -> service().create(name, template, key));
        }
    }

    @CommandDefinition(name = "inspect", description = "Inspect whitelisted instance state",
            generateHelp = true)
    public static class Inspect extends BaseCommand {
        @Option(name = "name", description = "Instance name")
        String name;

        @Option(name = "key", description = "Optional ownership key to verify")
        String key;

        @Override
        protected CommandResult doExecute() {
            return lifecycle("inspect", () -> service().inspect(name, key));
        }
    }

    @CommandDefinition(name = "start", description = "Idempotently start an owned instance",
            generateHelp = true)
    public static class Start extends BaseCommand {
        @Option(name = "name", description = "Instance name")
        String name;

        @Option(name = "key", description = "Caller ownership key")
        String key;

        @Override
        protected CommandResult doExecute() {
            return lifecycle("start", () -> service().start(name, key));
        }
    }

    @CommandDefinition(name = "stop", description = "Idempotently stop an owned instance",
            generateHelp = true)
    public static class Stop extends BaseCommand {
        @Option(name = "name", description = "Instance name")
        String name;

        @Option(name = "key", description = "Caller ownership key")
        String key;

        @Override
        protected CommandResult doExecute() {
            return lifecycle("stop", () -> service().stop(name, key));
        }
    }

    @CommandDefinition(name = "mount",
            description = "Attach an exact owned host workspace device",
            generateHelp = true)
    public static class Mount extends BaseCommand {
        @Option(name = "name", description = "Instance name")
        String name;

        @Option(name = "key", description = "Caller ownership key")
        String key;

        @Option(name = "device", description = "Safe deterministic Incus device name")
        String device;

        @Option(name = "source", description = "Absolute physical host source path")
        String source;

        @Option(name = "target", description = "Absolute non-root container target path")
        String target;

        @Option(name = "access", description = "Access mode: read-only or read-write")
        String access;

        @Override
        protected CommandResult doExecute() {
            return lifecycle("mount",
                    () -> service().mount(name, key, device, source, target, access));
        }
    }

    @CommandDefinition(name = "unmount",
            description = "Detach only an exactly matching owned workspace device",
            generateHelp = true)
    public static class Unmount extends BaseCommand {
        @Option(name = "name", description = "Instance name")
        String name;

        @Option(name = "key", description = "Caller ownership key")
        String key;

        @Option(name = "device", description = "Safe deterministic Incus device name")
        String device;

        @Option(name = "source", description = "Expected absolute physical host source path")
        String source;

        @Option(name = "target", description = "Expected absolute non-root container target path")
        String target;

        @Option(name = "access", description = "Expected access: read-only or read-write")
        String access;

        @Override
        protected CommandResult doExecute() {
            return lifecycle("unmount",
                    () -> service().unmount(name, key, device, source, target, access));
        }
    }

    @CommandDefinition(name = "delete",
            description = "Idempotently delete by name and key, or reconcile by key only",
            generateHelp = true)
    public static class Delete extends BaseCommand {
        @Option(name = "name", description = "Instance name (omit for key-only reconciliation)")
        String name;

        @Option(name = "key", description = "Caller ownership key")
        String key;

        @Option(name = "template", description = "Expected source template")
        String template;

        @Override
        protected CommandResult doExecute() {
            return lifecycle("delete", () -> service().delete(name, key, template));
        }
    }

    @CommandDefinition(name = "exec", description = "Execute exact argv with NDJSON streaming",
            generateHelp = true)
    public static class Exec extends BaseCommand {
        @Option(name = "name", description = "Running instance name")
        String name;

        @Option(name = "key", description = "Caller ownership key")
        String key;

        @Option(name = "argv-json", description = "Non-empty JSON string array")
        String argvJson;

        @Option(name = "env-json", description = "JSON object of string environment values")
        String environmentJson;

        @Option(name = "uid", description = "Guest UID (default: 1000)")
        Integer uid;

        @Option(name = "gid", description = "Guest GID (default: 1000)")
        Integer gid;

        @Option(name = "cwd", description = "Guest working directory (default: /home/agentuser)")
        String cwd;

        @Option(name = "timeout-ms",
                description = "Finite timeout in milliseconds; zero means no caller timeout",
                defaultValue = {"0"})
        long timeoutMillis;

        @Override
        protected CommandResult doExecute() {
            var frames = new AutomationProtocol.FrameWriter(System.out);
            var cancellation = new AutomationTransport.Cancellation();
            var shutdownHook = new Thread(cancellation::request, "isx-automation-exec-cancel");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            try {
                var argv = AutomationProtocol.parseArgv(argvJson);
                var environment = AutomationProtocol.parseEnvironment(environmentJson);
                var outcome = service().exec(name, key, argv, environment, uid, gid, cwd,
                        timeoutMillis, System.in, frames.stdout(), frames.stderr(), cancellation);
                frames.result(outcome);
                return outcome.termination() == AutomationTransport.Termination.EXITED
                        && outcome.exitCode() == 0
                        ? CommandResult.SUCCESS : CommandResult.valueOf(1);
            } catch (Exception e) {
                frames.error(e);
                return CommandResult.valueOf(1);
            } finally {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (IllegalStateException ignored) {
                    // JVM shutdown is already in progress; the hook is running or has run.
                }
            }
        }
    }
}
