package dev.incusspawn;

import dev.incusspawn.command.AutomationCommand;
import dev.incusspawn.command.PoolCommand;
import dev.incusspawn.command.VmCommand;
import org.aesh.command.CommandDefinition;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the platform-specific command tree in {@link IncusSpawn}. Because aesh bakes
 * {@code groupCommands} into the annotation at compile time, the macOS and Linux top commands
 * duplicate the same command list — Linux omits the macOS appliance commands. This test asserts
 * the two lists stay in step so adding a command to one but forgetting the other fails loudly
 * rather than silently dropping it from {@code isx --help} on Linux.
 */
class IncusSpawnCommandTreeTest {

    private static List<Class<?>> groupCommands(Class<?> topCommand) {
        return Arrays.asList(topCommand.getAnnotation(CommandDefinition.class).groupCommands());
    }

    @Test
    void linuxTreeEqualsMacTreeMinusMacApplianceCommands() {
        var mac = groupCommands(IncusSpawn.IncusSpawnCommand.class);
        var linux = groupCommands(IncusSpawn.IncusSpawnLinuxCommand.class);

        assertTrue(mac.contains(VmCommand.class), "macOS tree must include the vm appliance command");
        assertFalse(linux.contains(VmCommand.class), "Linux tree must not include the vm appliance command");
        assertTrue(mac.contains(PoolCommand.class), "macOS tree must include pool management");
        assertFalse(linux.contains(PoolCommand.class), "Linux tree must not include macOS pool management");
        assertTrue(mac.contains(AutomationCommand.class),
                "macOS tree must include the shared automation command");
        assertTrue(linux.contains(AutomationCommand.class),
                "Linux tree must include the shared automation command");

        var expectedLinux = mac.stream()
                .filter(c -> c != VmCommand.class && c != PoolCommand.class).toList();
        assertEquals(expectedLinux, linux,
                "Linux command tree must equal the macOS tree minus appliance commands — a command was added to one but not the other");
    }

    @Test
    void materializedMaintenanceAllowsOnlyItsExactCleanupCommands() {
        assertTrue(IncusSpawn.maintenanceCommandAllowed(new String[]{
                "automation", "delete", "--key", "thread-key", "--template", "tpl-bb"
        }));
        assertTrue(IncusSpawn.maintenanceCommandAllowed(
                new String[]{"vm", "start", "--without-direct"}));
        assertTrue(IncusSpawn.maintenanceCommandAllowed(new String[]{"vm", "stop"}));

        assertFalse(IncusSpawn.maintenanceCommandAllowed(
                new String[]{"automation", "exec"}));
        assertFalse(IncusSpawn.maintenanceCommandAllowed(
                new String[]{"destroy", "another-instance"}));
        assertFalse(IncusSpawn.maintenanceCommandAllowed(
                new String[]{"vm", "restart"}));
        assertFalse(IncusSpawn.maintenanceCommandAllowed(
                new String[]{"vm", "start"}));
    }

    @Test
    void automationRegistersTheVersionOneSurface() {
        var commands = groupCommands(AutomationCommand.class);
        assertEquals(List.of(
                AutomationCommand.Create.class,
                AutomationCommand.Inspect.class,
                AutomationCommand.Start.class,
                AutomationCommand.Stop.class,
                AutomationCommand.Mount.class,
                AutomationCommand.Unmount.class,
                AutomationCommand.Delete.class,
                AutomationCommand.Exec.class), commands);
        assertEquals(List.of("create", "inspect", "start", "stop", "mount", "unmount",
                        "delete", "exec"),
                commands.stream()
                        .map(command -> command.getAnnotation(CommandDefinition.class).name())
                        .toList());
    }
}
