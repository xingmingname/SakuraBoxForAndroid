package io.nekohasekai.sagernet.ui

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.DialogLicenseBinding

internal fun licenseSummary(notices: String): String =
    notices.substringBefore("\nThird-party notice:").trimEnd()

internal fun licenseDetails(projectLicense: String, notices: String, thirdPartyTitle: String): String {
    val summary = licenseSummary(notices)
    val thirdPartyNotices = notices.removePrefix(summary).trimStart()
    return if (thirdPartyNotices.isEmpty()) projectLicense else
        "$projectLicense\n\n$thirdPartyTitle\n\n$thirdPartyNotices"
}

class LicenseDialogFragment : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val builder = MaterialAlertDialogBuilder(requireContext())
        val binding = DialogLicenseBinding.inflate(LayoutInflater.from(builder.context))
        val assets = requireContext().assets
        val projectLicense = assets.open("project-license/LICENSE").bufferedReader().use { it.readText() }
        val notices = assets.open("LICENSE").bufferedReader().use { it.readText() }
        binding.licenseText.text = licenseDetails(projectLicense, notices, getString(R.string.oss_licenses))
        return builder.setTitle(R.string.license)
            .setView(binding.root)
            .setPositiveButton(R.string.license_close, null)
            .create()
    }

    companion object {
        const val TAG = "about-license"
    }
}
