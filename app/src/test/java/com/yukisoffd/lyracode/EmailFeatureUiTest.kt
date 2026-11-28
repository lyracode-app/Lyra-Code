package com.yukisoffd.lyracode

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EmailFeatureUiTest {
    @Test
    fun emailDependencyLicenseIsBundled() {
        val licenses = File("src/main/assets/licenses")
        val license = licenses.resolve("jakarta-mail-LICENSE.md").readText()
        assertTrue(license.contains("Eclipse Public License - v 2.0"))
        assertTrue(license.contains("7. GENERAL"))
        val notice = licenses.resolve("jakarta-mail-NOTICE.md").readText()
        assertTrue(notice.contains("Notices for Jakarta Mail"))
    }
}
