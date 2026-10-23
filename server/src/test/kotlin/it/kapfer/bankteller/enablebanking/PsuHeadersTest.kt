package it.kapfer.bankteller.enablebanking

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * PsuHeaders.sanitizeIpAddress — EB rejects the Psu-Ip-Address header with
 * 422 unless it is a valid IPv4/IPv6 literal. Behind container port mappings
 * the engine's remote host can surface as a non-literal; anything that is
 * not a plain IP literal must be dropped (header omitted) rather than sent.
 */
class PsuHeadersTest {

    @Test
    fun `accepts plain IPv4 literals`() {
        assertEquals("192.168.1.10", PsuHeaders.sanitizeIpAddress("192.168.1.10"))
        assertEquals("0.0.0.0", PsuHeaders.sanitizeIpAddress("0.0.0.0"))
        assertEquals("255.255.255.255", PsuHeaders.sanitizeIpAddress("255.255.255.255"))
    }

    @Test
    fun `rejects out-of-range IPv4 octets`() {
        assertNull(PsuHeaders.sanitizeIpAddress("256.1.1.1"))
        assertNull(PsuHeaders.sanitizeIpAddress("1.2.3.999"))
    }

    @Test
    fun `accepts plain IPv6 literals`() {
        assertEquals("::1", PsuHeaders.sanitizeIpAddress("::1"))
        assertEquals("2001:db8::1", PsuHeaders.sanitizeIpAddress("2001:db8::1"))
        assertEquals("fe80::a", PsuHeaders.sanitizeIpAddress("fe80::a"))
    }

    @Test
    fun `strips bracketed IPv6 form`() {
        assertEquals("::1", PsuHeaders.sanitizeIpAddress("[::1]"))
    }

    @Test
    fun `rejects hostnames and garbage`() {
        assertNull(PsuHeaders.sanitizeIpAddress("localhost"))
        assertNull(PsuHeaders.sanitizeIpAddress("example.com"))
        assertNull(PsuHeaders.sanitizeIpAddress(""))
        assertNull(PsuHeaders.sanitizeIpAddress(null))
        assertNull(PsuHeaders.sanitizeIpAddress("   "))
    }

    @Test
    fun `rejects zone-scoped IPv6`() {
        // fe80::1%eth0 — a zone id is not a valid remote literal for EB
        assertNull(PsuHeaders.sanitizeIpAddress("fe80::1%eth0"))
    }
}
