package io.nekohasekai.sagernet.fmt

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class ExportJsonTest {
    @Test fun usesTwoSpacesAndPreservesNullsAndEscapes() {
        val input = """{"a":{"n":null,"list":[1,"<测试>\nline"]}}"""
        val formatted = formatExportJson(input)
        assertEquals("""{
  "a": {
    "n": null,
    "list": [
      1,
      "<测试>\nline"
    ]
  }
}""", formatted)
        assertEquals(JsonParser.parseString(input), JsonParser.parseString(formatted))
    }

    @Test fun formattingPreservesConfigurationSemanticsAndLargeNumbers() {
        val input = """{"inbounds":[{"type":"tun","tag":"custom","auto_route":false}],"dns":{"servers":[{"type":"fakeip","tag":"fake","inet6_range":"fc00::/18"}]},"route":{"rules":[{"action":"reject","domain":["example.invalid"]}]},"unknown_number":9007199254740993,"negative_zero":-0,"optional":null}"""
        val formatted = formatExportJson(input)
        assertEquals(JsonParser.parseString(input), JsonParser.parseString(formatted))
        assertTrue(formatted.contains("9007199254740993"))
        assertTrue(formatted.contains("\"auto_route\": false"))
        assertEquals(formatted, formatExportJson(formatted))
        assertFalse(input.contains('\n')) // formatting did not mutate the core-input string
    }

    @Test fun formattingNeverAddsSFADefaultsToInternalJSON() {
        val input = """{"inbounds":[{"type":"tun","address":["172.19.0.1/28"]}],"outbounds":[{"type":"direct"}]}"""
        val formatted = formatExportJson(input)
        val inbound = JsonParser.parseString(formatted).asJsonObject["inbounds"].asJsonArray[0].asJsonObject
        assertFalse(inbound.has("auto_route"))
        assertFalse(inbound.has("dns_mode"))
        assertEquals(JsonParser.parseString(input), JsonParser.parseString(formatted))
    }
}
