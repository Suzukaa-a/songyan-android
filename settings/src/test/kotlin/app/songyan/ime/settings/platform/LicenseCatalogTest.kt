package app.songyan.ime.settings.platform

import org.junit.Assert.assertEquals
import org.junit.Test

class LicenseCatalogTest {
    @Test
    fun `title drops the directory and extension`() {
        assertEquals(
            "librime-1.17.0-LICENSE",
            LicenseCatalog.titleOf("legal/licenses/librime-1.17.0-LICENSE.txt"),
        )
        assertEquals("GPL-3.0", LicenseCatalog.titleOf("legal/licenses/GPL-3.0.txt"))
    }
}
