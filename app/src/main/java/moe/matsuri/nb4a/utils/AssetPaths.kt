package moe.matsuri.nb4a.utils

import java.io.File
import java.io.IOException

object AssetPaths {
    @JvmStatic
    @Throws(IOException::class)
    fun destination(directory: File, name: String?): File {
        if (name.isNullOrEmpty() || name == "." || name == ".." ||
            '/' in name || '\\' in name || ':' in name || '\u0000' in name || File(name).isAbsolute
        ) {
            throw IOException("Invalid asset filename")
        }

        val root = directory.canonicalFile
        val result = File(root, name).canonicalFile
        if (result.parentFile != root) throw IOException("Asset path escapes its directory")
        return result
    }
}
