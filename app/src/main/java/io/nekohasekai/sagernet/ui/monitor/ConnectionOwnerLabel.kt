package io.nekohasekai.sagernet.ui.monitor

import android.content.pm.PackageManager
internal fun verifiedConnectionOwnerLabel(packages: PackageManager, uid: Int, packageName: String): String? =
    runCatching {
        if (uid <= 1000 || packages.getPackagesForUid(uid)?.singleOrNull() != packageName) return@runCatching null
        @Suppress("DEPRECATION")
        val info = packages.getApplicationInfo(packageName, 0)
        if (info.uid != uid) null else packages.getApplicationLabel(info).toString().ifBlank { packageName }
    }.getOrNull()
