package app.songyan.ime.script

import java.net.InetAddress
import java.net.URI

/**
 * Which addresses a script may reach, decided by its confirmed @connect declarations:
 *
 *  - an HTTPS URL whose host appears in the declarations is allowed;
 *  - plain HTTP is allowed only for loopback / link-local / site-local (LAN) addresses - the
 *    script request path is the one place cleartext is tolerated, and only on the local network;
 *  - everything else is denied, and denial is what the contract reports as addressNotAllowed.
 *
 * Deciding policy here keeps the shared C bridge platform-neutral: the host callback answers.
 */
object ScriptAddressPolicy {
    data class Decision(
        val allowed: Boolean,
        val reason: String? = null,
    )

    fun authorize(
        url: String,
        declaredHosts: Collection<String>,
    ): Decision {
        val uri = runCatching { URI(url) }.getOrElse { return Decision(false, "not a URL") }
        val scheme = uri.scheme?.lowercase() ?: return Decision(false, "no scheme")
        val host = uri.host?.lowercase() ?: return Decision(false, "no host")
        if (!uri.userInfo.isNullOrEmpty()) {
            return Decision(false, "embedded credentials are not allowed")
        }
        val declared = declaredHosts.map { it.lowercase() }.toSet()
        return when (scheme) {
            "https" -> {
                if (host in declared) {
                    Decision(true)
                } else {
                    Decision(false, "the host " + host + " is not declared")
                }
            }
            "http" -> {
                if (host !in declared) return Decision(false, "the host " + host + " is not declared")
                // A name may resolve to a public address later; only literal LAN/loopback hosts
                // qualify for cleartext, exactly like the plan's LAN exception.
                val address =
                    runCatching { InetAddress.getByName(host) }.getOrNull()
                        ?: return Decision(false, "the host does not resolve")
                when {
                    address.isLoopbackAddress -> Decision(true)
                    address.isLinkLocalAddress -> Decision(true)
                    address.isSiteLocalAddress -> Decision(true)
                    else -> Decision(false, "plain HTTP is only allowed on the local network")
                }
            }
            else -> Decision(false, "the scheme " + scheme + " is not allowed")
        }
    }
}
