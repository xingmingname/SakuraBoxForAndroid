package io.nekohasekai.sagernet.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.core.widget.TextViewCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.branding.BrandAppearance
import io.nekohasekai.sagernet.branding.BrandImageKind
import io.nekohasekai.sagernet.branding.BrandImages
import io.nekohasekai.sagernet.branding.BrandTheme
import io.nekohasekai.sagernet.branding.BrandThemeManager
import io.nekohasekai.sagernet.branding.CustomBrand
import io.nekohasekai.sagernet.databinding.LayoutCustomBrandBinding
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class CustomBrandDraft : ViewModel() {
    var value: CustomBrand? = null
}

class CustomBrandActivity : ThemedActivity() {
    private val draft by viewModels<CustomBrandDraft>()
    private lateinit var binding: LayoutCustomBrandBinding
    private lateinit var saveButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = LayoutCustomBrandBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configureBrandToolbar(binding.brandAppbar, binding.root, R.string.brand_custom)
        if (draft.value == null) draft.value = BrandThemeManager.custom(this)

        val form = binding.brandFields
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logo = image(this, 48)
        header.addView(logo)
        val preview = TextView(this).apply {
            setPadding(dp(12), 0, 0, 0)
            TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_MaterialComponents_Subtitle1)
        }
        header.addView(preview, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        form.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        fun renderName() {
            preview.text = config().themeName.ifBlank { getString(R.string.brand_custom) }
        }

        val themeName = form.field(R.string.brand_custom_theme_name, config().themeName) {
            draft.value = config().copy(themeName = it)
            renderName()
        }
        val tileTitle = form.field(R.string.brand_custom_tile_title, config().tileTitle) {
            draft.value = config().copy(tileTitle = it)
        }
        renderName()

        val renderImages = listOf(
            addIconEditor(form, BrandImageKind.APP, R.string.brand_custom_app_icon) {
                BrandAppearance(BrandTheme.CUSTOM, config()).showLogo(logo)
            },
            addIconEditor(form, BrandImageKind.NOTIFICATION, R.string.brand_custom_notice_icon),
            addIconEditor(form, BrandImageKind.TILE, R.string.brand_custom_tile_icon),
        )
        fun refreshArtwork() {
            BrandAppearance(BrandTheme.CUSTOM, config()).showLogo(logo)
            renderImages.forEach { it() }
        }
        refreshArtwork()

        saveButton = form.button(R.string.brand_custom_save) { save() }
        form.button(R.string.brand_custom_reset) {
            if (!saveButton.isEnabled) return@button
            draft.value = CustomBrand()
            listOf(themeName, tileTitle).forEach { it.setText("") }
            renderName()
            refreshArtwork()
        }
    }

    private fun config() = checkNotNull(draft.value)

    private fun addIconEditor(
        parent: LinearLayout,
        kind: BrandImageKind,
        @StringRes title: Int,
        onImageChanged: () -> Unit = {},
    ): () -> Unit {
        parent.caption(title)
        val previews = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val light = image(this, 48)
        previews.addView(light)
        val dark = if (kind == BrandImageKind.APP) null else image(this, 48).also {
            val margin = LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) }
            previews.addView(it, margin)
        }
        parent.addView(previews, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        fun render() {
            val bitmap = when (kind) {
                BrandImageKind.APP -> config().appIcon
                BrandImageKind.NOTIFICATION -> config().notificationIcon
                BrandImageKind.TILE -> config().tileIcon
            }
            setPreview(light, bitmap, kind, false)
            dark?.let { setPreview(it, bitmap, kind, true) }
        }
        fun setImage(bitmap: Bitmap?) {
            draft.value = when (kind) {
                BrandImageKind.APP -> config().copy(appIcon = bitmap)
                BrandImageKind.NOTIFICATION -> config().copy(notificationIcon = bitmap)
                BrandImageKind.TILE -> config().copy(tileIcon = bitmap)
            }
            render()
            onImageChanged()
        }

        val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importImage(uri, kind, ::setImage)
        }
        parent.button(R.string.brand_custom_import) { picker.launch(arrayOf("image/*")) }
        parent.button(R.string.brand_custom_restore_icon) { setImage(null) }
        return ::render
    }

    private fun importImage(uri: Uri, kind: BrandImageKind, setImage: (Bitmap) -> Unit) {
        lifecycleScope.launch {
            val options = try {
                withContext(Dispatchers.IO) {
                    val source = BrandImages.import(this@CustomBrandActivity, uri)
                    listOf(BrandImages.adapt(source, kind, false), BrandImages.adapt(source, kind, true))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showMessage(R.string.brand_custom_image_error)
                return@launch
            }
            val controls = LinearLayout(this@CustomBrandActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(8), dp(24), 0)
            }
            val preview = image(this@CustomBrandActivity, 96)
            controls.addView(preview, LinearLayout.LayoutParams(dp(96), dp(96)).apply { gravity = Gravity.CENTER })
            val choice = RadioGroup(this@CustomBrandActivity)
            val fitId = View.generateViewId()
            val cropId = View.generateViewId()
            choice.addView(cropOption(fitId, R.string.brand_custom_fit))
            choice.addView(cropOption(cropId, R.string.brand_custom_crop))
            controls.addView(choice)
            var selected = 0
            fun updatePreview() = setPreview(preview, options[selected], kind, false)
            choice.check(fitId)
            choice.setOnCheckedChangeListener { _, checked ->
                selected = if (checked == cropId) 1 else 0
                updatePreview()
            }
            updatePreview()
            MaterialAlertDialogBuilder(this@CustomBrandActivity)
                .setTitle(R.string.brand_custom_image_title)
                .setView(controls)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val image = options[selected]
                    if (image != null) setImage(image) else showMessage(R.string.brand_custom_mask_empty)
                }
                .show()
        }
    }

    private fun cropOption(viewId: Int, @StringRes title: Int) = MaterialRadioButton(this).apply {
        id = viewId
        setText(title)
    }

    private fun save() {
        val snapshot = config()
        saveButton.isEnabled = false
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { BrandThemeManager.saveCustom(applicationContext, snapshot) }
                BrandThemeManager.select(this@CustomBrandActivity, BrandTheme.CUSTOM)
                finish()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Logs.w(error)
                showMessage(R.string.brand_theme_failure)
            } finally {
                saveButton.isEnabled = true
            }
        }
    }

    private fun setPreview(view: ImageView, bitmap: Bitmap?, kind: BrandImageKind, dark: Boolean) {
        if (bitmap != null) view.setImageBitmap(bitmap) else view.setImageResource(when (kind) {
            BrandImageKind.APP -> BrandTheme.CUSTOM.previewIcon
            BrandImageKind.NOTIFICATION -> BrandTheme.CUSTOM.notificationIcon
            BrandImageKind.TILE -> R.drawable.ic_service_idle
        })
        if (kind != BrandImageKind.APP) {
            view.setBackgroundColor(if (dark) 0xff202124.toInt() else Color.WHITE)
            view.imageTintList = ColorStateList.valueOf(if (dark) Color.WHITE else 0xff202124.toInt())
        }
    }

    private fun showMessage(@StringRes message: Int) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun image(context: Context, edge: Int) = ImageView(context).apply {
        layoutParams = LinearLayout.LayoutParams(dp(edge), dp(edge))
        scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun LinearLayout.button(@StringRes title: Int, action: () -> Unit): MaterialButton =
        MaterialButton(context).apply {
            setText(title)
            setOnClickListener { action() }
            addView(this, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

    private fun LinearLayout.caption(@StringRes title: Int) {
        addView(TextView(context).apply {
            setText(title)
            setPadding(0, dp(16), 0, dp(8))
        })
    }

    private fun LinearLayout.field(
        @StringRes label: Int,
        initial: String,
        changed: (String) -> Unit,
    ): TextInputEditText {
        val box = TextInputLayout(context).apply {
            hint = getString(label)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }
        val input = TextInputEditText(box.context).apply {
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(CustomBrand.MAX_NAME))
            setText(initial)
            doAfterTextChanged { changed(CustomBrand.cleanName(it.toString())) }
        }
        box.addView(input, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(box, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
        return input
    }
}
