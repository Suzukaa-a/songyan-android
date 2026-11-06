package app.songyan.ime.layout

/** Reads the synced author-facing layout examples from the test classpath. */
object LayoutFixtures {
    const val QWERTY26 = "layouts/qwerty26.json"
    const val MERGED14 = "layouts/merged14.json"
    const val MERGED18 = "layouts/merged18.json"
    const val PHONE9 = "layouts/phone9.json"
    const val ZHUYIN41 = "layouts/zhuyin41.json"
    const val CANGJIE26 = "layouts/cangjie26.json"

    /** Every synced author example; the two scheme-driven pads arrive with the iOS layout commit. */
    val all: List<String> = listOf(QWERTY26, MERGED14, MERGED18, PHONE9, ZHUYIN41, CANGJIE26)

    fun readText(path: String): String =
        LayoutFixtures::class.java.classLoader!!.getResourceAsStream(path)?.use {
            it.readBytes().decodeToString()
        } ?: error("missing test resource: " + path)

    fun readBytes(path: String): ByteArray = readText(path).encodeToByteArray()

    fun readDefinition(path: String): LayoutDefinition = LayoutJson.decode(readBytes(path))
}
