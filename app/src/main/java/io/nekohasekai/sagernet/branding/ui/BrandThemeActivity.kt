package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.branding.BrandAppearance
import io.nekohasekai.sagernet.branding.BrandTheme
import io.nekohasekai.sagernet.branding.BrandThemeManager
import io.nekohasekai.sagernet.databinding.LayoutAppbarBinding
import io.nekohasekai.sagernet.databinding.LayoutBrandThemeBinding
import io.nekohasekai.sagernet.databinding.LayoutBrandThemeCardBinding
import io.nekohasekai.sagernet.ktx.Logs

class BrandThemeActivity : ThemedActivity() {
    private lateinit var binding: LayoutBrandThemeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = LayoutBrandThemeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configureBrandToolbar(binding.brandAppbar, binding.root, R.string.brand_theme_menu)
        binding.customBrandCard.setOnClickListener {
            startActivity(Intent(this, CustomBrandActivity::class.java))
        }
        for ((card, brand) in builtInCards()) {
            bindCard(card, brand)
            card.root.setOnClickListener {
                try {
                    BrandThemeManager.select(this, brand)
                } catch (error: RuntimeException) {
                    Logs.w(error)
                    Snackbar.make(binding.root, R.string.brand_theme_failure, Snackbar.LENGTH_LONG).show()
                }
                render()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun builtInCards() =
        listOf(binding.brandSakura to BrandTheme.SAKURA, binding.brandNeko to BrandTheme.NEKO)

    private fun render() {
        val selected = BrandThemeManager.current(this)
        val accent = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimary)
        val outline = MaterialColors.layer(
            binding.root,
            com.google.android.material.R.attr.colorSurface,
            com.google.android.material.R.attr.colorOnSurface,
            0.24f,
        )
        val stroke = resources.getDimensionPixelSize(R.dimen.brand_theme_card_stroke)
        for ((card, brand) in builtInCards()) {
            val checked = brand == selected
            card.root.isChecked = checked
            card.root.strokeColor = if (checked) accent else outline
            card.root.strokeWidth = if (checked) stroke * 2 else stroke
            card.brandCheck.visibility = if (checked) View.VISIBLE else View.INVISIBLE
        }

        val customSelected = selected == BrandTheme.CUSTOM
        val custom = BrandThemeManager.custom(this)
        val card = binding.customBrandCard
        card.isChecked = customSelected
        card.strokeColor = if (customSelected) accent else outline
        card.strokeWidth = if (customSelected) stroke * 2 else stroke
        BrandAppearance(BrandTheme.CUSTOM, custom).showLogo(binding.customBrandIcon)
        binding.customBrandCheck.visibility = if (customSelected) View.VISIBLE else View.INVISIBLE
        binding.customBrandDescription.text = custom.themeName
        binding.customBrandDescription.visibility = if (custom.themeName.isEmpty()) View.GONE else View.VISIBLE
        card.contentDescription = if (custom.themeName.isBlank()) getString(R.string.brand_custom)
            else getString(R.string.brand_theme_card_description, getString(R.string.brand_custom), custom.themeName)
    }

    private fun bindCard(card: LayoutBrandThemeCardBinding, brand: BrandTheme) {
        val description = when (brand) {
            BrandTheme.SAKURA -> R.string.brand_theme_sakura
            BrandTheme.NEKO -> R.string.brand_theme_neko
            BrandTheme.CUSTOM -> error("Use the custom theme card")
        }
        card.brandIcon.setImageResource(brand.previewIcon)
        card.brandName.setText(brand.label)
        card.brandDescription.setText(description)
        card.root.contentDescription = getString(R.string.brand_theme_card_description, getString(brand.label), getString(description))
    }
}

internal fun ThemedActivity.configureBrandToolbar(appbar: LayoutAppbarBinding, content: View, @StringRes title: Int) {
    val toolbar = appbar.toolbar
    val barSize = TypedValue()
    if (toolbar.context.theme.resolveAttribute(androidx.appcompat.R.attr.actionBarSize, barSize, true)) {
        toolbar.updateLayoutParams<ViewGroup.LayoutParams> {
            height = TypedValue.complexToDimensionPixelSize(barSize.data, resources.displayMetrics)
        }
    }
    toolbar.setTitle(title)
    toolbar.setNavigationIcon(R.drawable.baseline_arrow_back_24)
    toolbar.setNavigationContentDescription(androidx.appcompat.R.string.abc_action_bar_up_description)
    toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

    // The app bar wraps its toolbar and the status-bar inset, even on edge-to-edge windows.
    ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
        val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        view.updatePadding(left = safe.left, right = safe.right, bottom = maxOf(safe.bottom, keyboard))
        if (Build.VERSION.SDK_INT >= 35) appbar.root.updatePadding(top = safe.top)
        insets
    }
}
