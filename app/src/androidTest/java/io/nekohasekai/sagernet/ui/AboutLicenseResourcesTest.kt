package io.nekohasekai.sagernet.ui

import android.content.res.Configuration
import android.test.InstrumentationTestCase
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import io.nekohasekai.sagernet.R

@Suppress("DEPRECATION")
class AboutLicenseResourcesTest : InstrumentationTestCase() {
    fun testCardWrapsAtLargeFontInBothThemes() = instrumentation.runOnMainSync {
        for (mode in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
            val config = Configuration(instrumentation.targetContext.resources.configuration).apply {
                fontScale = 2f
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or mode
            }
            val context = ContextThemeWrapper(instrumentation.targetContext.createConfigurationContext(config),
                R.style.Theme_SagerNet)
            val view = LayoutInflater.from(context).inflate(R.layout.layout_about, null)
            val card = view.findViewById<View>(R.id.license_card)
            val text = view.findViewById<TextView>(R.id.license)
            text.text = context.assets.open("LICENSE").bufferedReader().use { licenseSummary(it.readText()) }
            val width = (320 * context.resources.displayMetrics.density).toInt()
            card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            card.layout(0, 0, card.measuredWidth, card.measuredHeight)
            assertTrue(card.isClickable)
            assertTrue(card.isFocusable)
            assertTrue(text.lineCount > 20)
            assertTrue(text.layout.getEllipsisCount(text.lineCount - 1) == 0)
            assertTrue(text.height >= text.layout.height)
        }
    }

    fun testOfflineFullLicenseAndThirdPartyTextCanScroll() = instrumentation.runOnMainSync {
        val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_SagerNet)
        val scroll = LayoutInflater.from(context).inflate(R.layout.dialog_license, null) as ScrollView
        val text = scroll.findViewById<TextView>(R.id.license_text)
        val license = context.assets.open("project-license/LICENSE").bufferedReader().use { it.readText() }
        val notices = context.assets.open("LICENSE").bufferedReader().use { it.readText() }
        text.text = licenseDetails(license, notices, context.getString(R.string.oss_licenses))
        assertTrue(text.text.contains("17. Interpretation of Sections 15 and 16."))
        assertTrue(text.text.contains("YACD BUNDLED THIRD-PARTY NOTICES"))
        scroll.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY))
        scroll.layout(0, 0, 320, 240)
        scroll.fullScroll(View.FOCUS_DOWN)
        assertTrue(scroll.scrollY > 0)
        assertTrue(scroll.canScrollVertically(-1))
    }
}
