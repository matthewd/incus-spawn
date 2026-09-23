package dev.incusspawn.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ShellCommandTest {

    @Test
    void acceptsOnlyAbsoluteNormalizedInitialDirectories() {
        assertNull(ShellCommand.normalizedInitialDirectory(null));
        assertEquals("/srv/workspaces/workspace-1",
                ShellCommand.normalizedInitialDirectory("/srv/workspaces/workspace-1"));

        assertThrows(IllegalArgumentException.class,
                () -> ShellCommand.normalizedInitialDirectory("relative/path"));
        assertThrows(IllegalArgumentException.class,
                () -> ShellCommand.normalizedInitialDirectory("/srv/../private"));
        assertThrows(IllegalArgumentException.class,
                () -> ShellCommand.normalizedInitialDirectory("/srv/work\nspace"));
    }
}
