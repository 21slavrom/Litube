package com.hhst.youtubelite.extension

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName

/** Result of the settings-button inject script. */
data class InjectReport(
    val ok: Boolean = false,
    val skipped: Boolean = false,
    val reason: String? = null,
    val buttonId: String? = null,
    val failures: List<InjectFailure> = emptyList(),
    val steps: List<String> = emptyList(),
    val icon: InjectIconState? = null,
) {
    val hasFailures: Boolean get() = failures.isNotEmpty()

    fun summary(): String {
        if (skipped) return "skipped: ${reason ?: "n/a"}"
        if (ok && !hasFailures) {
            return "ok buttonId=$buttonId viewBox=${icon?.viewBox} pathSet=${icon?.pathSet}"
        }
        val failed = failures.joinToString("; ") { "${it.element}: ${it.reason}" }
        return "ok=$ok buttonId=$buttonId failures=[$failed]"
    }

    companion object {
        private val gson = Gson()

        fun parse(raw: String?): InjectReport {
            if (raw.isNullOrBlank() || raw == "null") {
                return InjectReport(
                    ok = false,
                    reason = "empty_callback",
                    failures = listOf(
                        InjectFailure(element = "callback", reason = "empty or null result"),
                    ),
                )
            }
            val json = unwrapJsString(raw.trim())
            return try {
                gson.fromJson(json, InjectReport::class.java)
                    ?: InjectReport(
                        ok = false,
                        reason = "null_json",
                        failures = listOf(
                            InjectFailure(element = "json", reason = "decoded null"),
                        ),
                    )
            } catch (e: JsonSyntaxException) {
                InjectReport(
                    ok = false,
                    reason = "parse_error",
                    failures = listOf(
                        InjectFailure(
                            element = "json",
                            reason = e.message ?: "syntax error",
                        ),
                    ),
                )
            }
        }

        internal fun unwrapJsString(raw: String): String {
            if (raw.length < 2 || raw.first() != '"' || raw.last() != '"') {
                return raw
            }
            return try {
                gson.fromJson(raw, String::class.java) ?: raw
            } catch (_: JsonSyntaxException) {
                raw
            }
        }
    }
}

data class InjectFailure(
    @SerializedName("element") val element: String,
    @SerializedName("reason") val reason: String,
)

data class InjectIconState(
    val viewBox: String? = null,
    val pathSet: Boolean = false,
)
