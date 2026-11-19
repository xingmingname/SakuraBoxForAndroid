package io.nekohasekai.sagernet.fmt

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser

private val exportJson = GsonBuilder()
    .setPrettyPrinting()
    .serializeNulls()
    .disableHtmlEscaping()
    .create()

/** Presentation boundary shared by clipboard and file exports, never core input. */
internal fun formatExportJson(config: String): String =
    exportJson.toJson(JsonParser.parseString(config))
