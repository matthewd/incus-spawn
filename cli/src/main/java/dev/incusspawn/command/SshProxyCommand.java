package dev.incusspawn.command;

import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;

@CommandDefinition(
        name = "ssh-proxy",
        description = "SSH ProxyCommand that tunnels through the Incus exec API",
        generateHelp = true
)
public class SshProxyCommand extends BaseCommand {

    @Argument(description = "Instance name", required = true)
    String instance;

    @Option(name = "key", description = "Expected automation ownership key")
    String key;

    @Override
    protected CommandResult doExecute() throws Exception {
        var incus = dev.incusspawn.RuntimeServices.incus();

        if (!checkInstanceRunning(incus) || !checkOwnership(incus)) {
            return CommandResult.valueOf(1);
        }

        int exitCode = incus.execBidirectional(instance, 0, 0, "/",
                new String[]{"nc", "localhost", "22"},
                System.in, System.out, System.err);
        return CommandResult.valueOf(exitCode);
    }

    private boolean checkOwnership(dev.incusspawn.incus.IncusClient incus) {
        if (key == null) return true;
        try {
            var owner = incus.configGet(instance, dev.incusspawn.incus.Metadata.AUTOMATION_KEY);
            if (ownershipMatches(key, owner)) return true;
            System.err.println("Error: instance automation ownership does not match.");
            return false;
        } catch (Exception e) {
            System.err.println("Error: could not verify instance automation ownership.");
            return false;
        }
    }

    static boolean ownershipMatches(String expected, String actual) {
        return expected != null && expected.equals(actual);
    }

    private boolean checkInstanceRunning(dev.incusspawn.incus.IncusClient incus) {
        try {
            var status = incus.getInstanceStatus(instance);
            if (status.isEmpty()) {
                System.err.println("Error: instance '" + instance + "' not found or not accessible.");
                return false;
            }
            if (!"Running".equalsIgnoreCase(status)) {
                System.err.println("Error: instance '" + instance + "' is not running (status: " + status + ").");
                return false;
            }
            return true;
        } catch (Exception e) {
            System.err.println("Error: could not check instance status: " + e.getMessage());
            return false;
        }
    }
}
