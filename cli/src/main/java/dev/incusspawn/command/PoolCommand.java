package dev.incusspawn.command;

import dev.incusspawn.Environment;
import dev.incusspawn.config.WorkerPoolsFile;
import dev.incusspawn.pool.PoolProtocol;
import dev.incusspawn.pool.PoolStore;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Option;

/** Versioned, non-interactive management API for macOS direct worker pools. */
@CommandDefinition(
        name = "pool",
        description = "List, inspect, seal, materialize, and delete dedicated worker pools (macOS)",
        generateHelp = true,
        groupCommands = {
                PoolCommand.ListPools.class,
                PoolCommand.Inspect.class,
                PoolCommand.ListMaterialized.class,
                PoolCommand.Seal.class,
                PoolCommand.Materialize.class,
                PoolCommand.Delete.class
        })
public class PoolCommand extends BaseCommand {

    @Override
    protected CommandResult doExecute() {
        System.out.println(commandInvocation.getHelpInfo());
        return CommandResult.SUCCESS;
    }

    @CommandDefinition(
            name = "list",
            description = "List declared static worker pools and their shared roots",
            generateHelp = true)
    public static class ListPools extends BaseCommand {
        @Override
        protected CommandResult doExecute() {
            try {
                System.out.println(PoolProtocol.listLine(PoolStore.listStatic(
                        WorkerPoolsFile.defaultPath(), Environment.home())));
                return CommandResult.SUCCESS;
            } catch (Exception e) {
                System.out.println(PoolProtocol.errorLine("list", e));
                return CommandResult.valueOf(1);
            }
        }
    }

    @CommandDefinition(
            name = "list-materialized",
            description = "List materialized direct pools and the seal generation of each",
            generateHelp = true)
    public static class ListMaterialized extends BaseCommand {
        @Override
        protected CommandResult doExecute() {
            try {
                System.out.println(PoolProtocol.listMaterializedLine(
                        PoolStore.system().listMaterialized()));
                return CommandResult.SUCCESS;
            } catch (Exception e) {
                System.out.println(PoolProtocol.errorLine("list-materialized", e));
                return CommandResult.valueOf(1);
            }
        }
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

    @CommandDefinition(
            name = "delete",
            description = "Delete a stopped materialized pool of the selected seed, with its instances",
            generateHelp = true)
    public static class Delete extends BaseCommand {
        @Option(name = "name", description = "Materialized pool name")
        String name;

        @Option(name = "identity", description = "Full SHA-256 identity of the materialized pool")
        String identity;

        @Override
        protected CommandResult doExecute() {
            try {
                System.out.println(PoolProtocol.deleteLine(PoolStore.system().delete(name, identity)));
                return CommandResult.SUCCESS;
            } catch (Exception e) {
                System.out.println(PoolProtocol.errorLine("delete", e));
                return CommandResult.valueOf(1);
            }
        }
    }
}
