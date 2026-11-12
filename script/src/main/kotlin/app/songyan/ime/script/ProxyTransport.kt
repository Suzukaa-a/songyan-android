package app.songyan.ime.script

/** One fetch answer, shared by the proxy transport and the isolated host bridge. */
internal object ProxyTransport {
    data class Reply(
        val accepted: Boolean,
        val status: Int,
        val body: ByteArray?,
        val contentType: String?,
        val finalUrl: String?,
        val headersJson: String?,
        val error: String?,
    )
}
