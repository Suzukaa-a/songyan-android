package app.songyan.ime.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The proxy is the only place a script's network reach is decided, so the rules are pinned by
 *  JVM tests: declared hosts over HTTPS yes, undeclared no, plain HTTP only on the LAN. */
class ScriptAddressPolicyTest {
    private val declared = listOf("api.example.com", "cdn.example.com")

    @Test
    fun declaredHttpsHostIsAllowed() {
        assertEquals(true, ScriptAddressPolicy.authorize("https://api.example.com/v1", declared).allowed)
    }

    @Test
    fun undeclaredHostIsDeniedEvenOnHttps() {
        val decision = ScriptAddressPolicy.authorize("https://evil.example.com/x", declared)
        assertEquals(false, decision.allowed)
        assertNotNull(decision.reason)
    }

    @Test
    fun subDomainIsNotTheDeclaredHost() {
        assertEquals(false, ScriptAddressPolicy.authorize("https://x.api.example.com/", declared).allowed)
    }

    @Test
    fun portAndUserInfoDoNotHideTheHost() {
        assertEquals(true, ScriptAddressPolicy.authorize("https://api.example.com:8443/t", declared).allowed)
        assertEquals(false, ScriptAddressPolicy.authorize("https://user@api.example.com/", declared).allowed)
    }

    @Test
    fun loopbackHttpIsAllowedWhenDeclared() {
        assertEquals(true, ScriptAddressPolicy.authorize("http://127.0.0.1:8080/hook", listOf("127.0.0.1")).allowed)
    }

    @Test
    fun publicHttpIsDeniedEvenWhenDeclared() {
        val decision = ScriptAddressPolicy.authorize("http://api.example.com/v1", declared)
        assertEquals(false, decision.allowed)
        assertNotNull(decision.reason)
    }

    @Test
    fun otherSchemesAreDenied() {
        assertEquals(false, ScriptAddressPolicy.authorize("ftp://api.example.com/f", declared).allowed)
        assertEquals(false, ScriptAddressPolicy.authorize("file:///etc/hosts", declared).allowed)
    }

    @Test
    fun garbageIsDeniedNotThrown() {
        val decision = ScriptAddressPolicy.authorize("not a url at all", declared)
        assertEquals(false, decision.allowed)
        assertNull(null)
    }
}
