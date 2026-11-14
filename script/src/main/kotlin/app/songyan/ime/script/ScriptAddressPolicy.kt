package app.songyan.ime.script

/**
 * The shared address rule (songyan_script_address_allowed in the C contract), applied in Kotlin
 * because the proxy process never loads the engine library. A script may reach:
 *
 *  - https to a host its header declares with @connect;
 *  - https to the host of a value the user typed into one of its url settings;
 *  - plain http to such a user-typed host only when it is local: localhost, a *.local name, or a
 *    literal loopback, private or link-local IP address.
 *
 * Everything else is refused, ports are ignored and nothing is resolved through DNS, so the answer
 * depends on the text alone. The shared table tests/address-cases.txt pins both implementations.
 */
object ScriptAddressPolicy {
    data class Decision(
        val allowed: Boolean,
        val reason: String? = null,
    )

    fun authorize(
        url: String,
        declaredHosts: Collection<String>,
        settingUrls: Collection<String> = emptyList(),
    ): Decision {
        val target = split(url) ?: return Decision(false, "not an http(s) URL")
        for (value in settingUrls) {
            val typed = split(value) ?: continue
            if (typed.host == target.host && (target.https || isLocalHost(target.host))) {
                return Decision(true)
            }
        }
        if (!target.https) {
            return Decision(false, "plain http is only allowed to a local address you entered")
        }
        if (target.host in declaredHosts.map { it.lowercase() }) return Decision(true)
        return Decision(false, "the host " + target.host + " is not declared")
    }

    /** A url setting value is valid when the rule would let the script reach it. */
    fun isReachableSettingUrl(value: String): Boolean =
        authorize(value.trim(), emptyList(), listOf(value.trim())).allowed

    private class Target(
        val https: Boolean,
        val host: String,
    )

    private fun split(url: String): Target? {
        val https: Boolean
        val start: Int
        when {
            url.regionMatches(0, "https://", 0, 8, ignoreCase = true) -> {
                https = true
                start = 8
            }
            url.regionMatches(0, "http://", 0, 7, ignoreCase = true) -> {
                https = false
                start = 7
            }
            else -> return null
        }
        var end = start
        while (end < url.length && url[end] != '/' && url[end] != '?' && url[end] != '#') {
            val character = url[end]
            if (character == '@' || character == '\\' || character.isWhitespace()) return null
            end++
        }
        val authority = url.substring(start, end)
        val host =
            if (authority.startsWith("[")) {
                val close = authority.indexOf(']')
                if (close < 0) return null
                authority.substring(1, close)
            } else {
                authority.substringBefore(':')
            }
        if (host.isEmpty() || host.length >= 256) return null
        return Target(https, host.lowercase())
    }

    private fun isLocalHost(host: String): Boolean {
        if (host == "localhost") return true
        if (host.length > 6 && host.endsWith(".local")) return true
        parseIpv4(host)?.let { (a, b) ->
            return a == 127 ||
                a == 10 ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) ||
                (a == 169 && b == 254)
        }
        if (':' in host) {
            if (host == "::1") return true
            val first = host.substringBefore(':')
            return first.length == 4 &&
                (
                    first.startsWith("fc") ||
                        first.startsWith("fd") ||
                        first.substring(0, 3) in setOf("fe8", "fe9", "fea", "feb")
                )
        }
        return false
    }

    /** Four dotted decimal octets without leading zeros, which some resolvers read as octal. */
    private fun parseIpv4(host: String): Pair<Int, Int>? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets =
            parts.map { part ->
                if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
                if (part.length > 1 && part[0] == '0') return null
                part.toInt().takeIf { it <= 255 } ?: return null
            }
        return octets[0] to octets[1]
    }
}
