package dev.incusspawn.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SshProxyCommandTest {

    @Test
    void automationOwnershipMustMatchExactly() {
        assertTrue(SshProxyCommand.ownershipMatches("thread/key-17", "thread/key-17"));
        assertFalse(SshProxyCommand.ownershipMatches("thread/key-17", "thread/key-18"));
        assertFalse(SshProxyCommand.ownershipMatches("thread/key-17", ""));
        assertFalse(SshProxyCommand.ownershipMatches(null, "thread/key-17"));
    }
}
