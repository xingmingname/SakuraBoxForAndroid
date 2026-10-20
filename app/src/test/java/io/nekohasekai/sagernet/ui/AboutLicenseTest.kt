package io.nekohasekai.sagernet.ui

import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class AboutLicenseTest {
    private val app = if (File("src/main/AndroidManifest.xml").isFile) File(".") else File("app")
    private val notices get() = File(app, "src/main/assets/LICENSE").readText()
    private val projectLicense get() = File(app, "../LICENSE").readText()

    @Test fun cardContainsTheWholeOriginalCopyrightAndGrant() {
        val expected = """
            Copyright (C) 2021 by nekohasekai
            <contact-sagernet@sekai.icu>

            This program is free software: you can
            redistribute it and/or modify it under
            the terms of the GNU General Public License
            as published by the Free Software Foundation,
            either version 3 of the License,
            or (at your option) any later version.

            This program is distributed in the hope
            that it will be useful, but WITHOUT ANY WARRANTY;
            without even the implied warranty of
            MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
            See the GNU General Public License for more details.

            You should have received a copy of the
            GNU General Public License along with this program.
            If not, see <http://www.gnu.org/licenses/>.
        """.trimIndent()
        assertEquals(expected, licenseSummary(notices).replace("\r\n", "\n"))
    }

    @Test fun dialogPreservesEveryByteOfTheReadLegalTexts() {
        val details = licenseDetails(projectLicense, notices, "Third-party licenses")
        assertTrue(details.startsWith(projectLicense))
        val thirdParty = notices.substring(notices.indexOf("Third-party notice:"))
        assertTrue(thirdParty.isNotEmpty())
        assertTrue(details.endsWith(thirdParty))
        assertTrue(details.contains("GNU GENERAL PUBLIC LICENSE"))
        assertTrue(details.contains("17. Interpretation of Sections 15 and 16."))
        assertTrue(details.contains("YACD BUNDLED THIRD-PARTY NOTICES"))
    }

    @Test fun windowsNewlinesDoNotLoseThirdPartyNotices() {
        val input = "Grant\r\n\r\nThird-party notice: example\r\nMIT\r\n"
        assertEquals("Grant", licenseSummary(input))
        assertEquals("GPL\n\nThird party\n\nThird-party notice: example\r\nMIT\r\n",
            licenseDetails("GPL", input, "Third party"))
    }

    @Test fun shortNoticeWithoutAnAppendixStillHasFullProjectLicense() {
        assertEquals("Original notice", licenseSummary("Original notice\n"))
        assertEquals("Full GPL", licenseDetails("Full GPL", "Original notice\n", "Third party"))
    }

    private fun elements(path: String): List<Element> {
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.newDocumentBuilder().parse(File(app, path))
        val nodes = document.getElementsByTagName("*")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.android(name: String) = getAttributeNS("http://schemas.android.com/apk/res/android", name)

    @Test fun cardAndDialogAllowWrappedUntruncatedText() {
        val about = elements("src/main/res/layout/layout_about.xml")
        val card = about.single { it.android("id") == "@+id/license_card" }
        assertEquals("true", card.android("clickable"))
        assertEquals("true", card.android("focusable"))
        val summary = about.single { it.android("id") == "@+id/license" }
        assertEquals("match_parent", summary.android("layout_width"))
        assertEquals("wrap_content", summary.android("layout_height"))
        assertEquals("", summary.android("textIsSelectable"))
        val dialog = elements("src/main/res/layout/dialog_license.xml")
        assertEquals("ScrollView", dialog.first().tagName)
        for (text in listOf(summary, dialog.single { it.tagName == "TextView" })) {
            assertEquals("", text.android("maxLines"))
            assertEquals("", text.android("ellipsize"))
        }
    }
}
