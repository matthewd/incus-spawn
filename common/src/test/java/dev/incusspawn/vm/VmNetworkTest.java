package dev.incusspawn.vm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VmNetworkTest {

    @Test
    void macAddressIsLocallyAdministered() {
        var firstOctet = Integer.parseInt(VmNetwork.ISX_VM_MAC.split(":")[0], 16);
        assertTrue((firstOctet & 0x02) != 0, "MAC should have locally-administered bit set");
    }

    @Test
    void legacyMacIsPreservedAndNamedPoolMacsAreStableAndDistinct() {
        assertEquals("4a:53:58:00:00:01", VmNetwork.macForPool(null));
        assertEquals(VmNetwork.macForPool("compile"), VmNetwork.macForPool("compile"));
        assertNotEquals(VmNetwork.ISX_VM_MAC, VmNetwork.macForPool("compile"));
        assertNotEquals(VmNetwork.macForPool("compile"), VmNetwork.macForPool("tests"));

        var firstOctet = Integer.parseInt(VmNetwork.macForPool("compile").split(":")[0], 16);
        assertEquals(0, firstOctet & 0x01, "named pool MAC must be unicast");
        assertNotEquals(0, firstOctet & 0x02, "named pool MAC must be locally administered");
    }

    @Test
    void macDerivationRejectsUnsafePoolNames() {
        assertThrows(IllegalArgumentException.class, () -> VmNetwork.macForPool("../escape"));
    }

    @Test
    void normalizeMacStripsLeadingZeros() {
        assertEquals("4a:53:58:0:0:1", VmNetwork.normalizeMac("4a:53:58:00:00:01"));
    }

    @Test
    void normalizeMacHandlesAlreadyNormalized() {
        assertEquals("4a:53:58:0:0:1", VmNetwork.normalizeMac("4a:53:58:0:0:1"));
    }

    @Test
    void normalizeMacIsCaseInsensitive() {
        assertEquals("4a:53:58:0:0:1", VmNetwork.normalizeMac("4A:53:58:00:00:01"));
    }

    @Test
    void discoveryMatchesTheRequestedPoolMac() throws Exception {
        var leases = java.nio.file.Files.createTempFile("isx-leases", ".txt");
        var compileMac = VmNetwork.macForPool("compile");
        var testMac = VmNetwork.macForPool("tests");
        java.nio.file.Files.writeString(leases, """
                {
                  ip_address=192.168.64.10
                  hw_address=1,%s
                }
                {
                  ip_address=192.168.64.11
                  hw_address=1,%s
                }
                """.formatted(compileMac, testMac));

        assertEquals("192.168.64.10", VmNetwork.discoverVmIp(leases, compileMac));
        assertEquals("192.168.64.11", VmNetwork.discoverVmIp(leases, testMac));
    }

    @Test
    void discoverVmIpReturnsNullOnLinux() {
        // /var/db/dhcpd_leases doesn't exist on Linux
        if (dev.incusspawn.Platform.isLinux()) {
            assertNull(VmNetwork.discoverVmIp());
        }
    }
}
