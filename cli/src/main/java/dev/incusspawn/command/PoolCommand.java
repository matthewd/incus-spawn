package dev.incusspawn.command;

import dev.incusspawn.pool.PoolProtocol;
import dev.incusspawn.pool.PoolStore;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Option;

/** Versioned, non-interactive management API for macOS direct worker pools. */
@CommandDefinition(
        name = "pool",
        description = "Inspect, seal, and materialize dedicated worker pools (macOS)",
        generateHelp = true,
        groupCommands = {
                PoolCommand.Inspect.class,
                PoolCommand.Seal.class,
                PoolCommand.Materialize.class
        })
public class PoolCommand extends BaseCommand {

    @Override
    protected CommandResult doExecute() {
        System.out.println(commandInvocation.getHelpInfo());
        return CommandResult.SUCCESS;
    }

    @CommandDefinition(
            name = "inspect",
            description = "Inspect the selected static seed pool",
            generateHelp = true)
    public static class Inspect extends BaseCommand {
        @Override
        protected CommandResult doExecute() {
            try {
                System.out.println(PoolProtocol.inspectLine(PoolStore.system().inspect()));
                return CommandResult.SUCCESS;
            } catch (Exception e) {
                System.out.println(PoolProtocol.errorLine("inspect", e));
                return CommandResult.valueOf(1);
            }
        }
    }

    @CommandDefinition(
            name = "seal",
            description = "Seal the stopped VM disks of the selected static named pool",
            generateHelp = true)
    public static class Seal extends BaseCommand {
        @Override
        protected CommandResult doExecute() {
            try {
                System.out.println(PoolProtocol.sealLine(PoolStore.system().seal()));
                return CommandResult.SUCCESS;
            } catch (Exception e) {
                System.out.println(PoolProtocol.errorLine("seal", e));
                return CommandResult.valueOf(1);
            }
        }
    }

    @CommandDefinition(
            name = "materialize",
            description = "Materialize a protected direct pool from a sealed generation",
            generateHelp = true)
    public static class Materialize extends BaseCommand {
        @Option(name = "name", description = "New materialized pool name")
        String name;

        @Option(name = "direct-root", description = "Pre-existing canonical project directory")
        String directRoot;

        @Option(name = "generation",
                description = "Exact sealed generation (omit both generation options for latest)")
        String generation;

        @Option(name = "generation-identity",
                description = "Full SHA-256 identity of the exact sealed generation")
        String generationIdentity;

        @Override
        protected CommandResult doExecute() {
            try {
                var result = PoolStore.system().materialize(
                        name, directRoot, generation, generationIdentity);
                System.out.println(PoolProtocol.materializeLine(result));
                return CommandResult.SUCCESS;
            } catch (Exception e) {
                System.out.println(PoolProtocol.errorLine("materialize", e));
                return CommandResult.valueOf(1);
            }
        }
    }
}
