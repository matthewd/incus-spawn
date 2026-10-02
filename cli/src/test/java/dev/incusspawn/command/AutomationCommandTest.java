package dev.incusspawn.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutomationCommandTest {

    @Test
    void maintenanceSelectionPermitsOnlyDeletion() {
        assertTrue(AutomationCommand.operationAllowedInMaintenance("delete"));
        for (var operation : new String[]{
                "create", "inspect", "start", "stop", "mount", "unmount", "exec"
        }) {
            assertFalse(AutomationCommand.operationAllowedInMaintenance(operation));
        }
    }
}
