package com.hhst.youtubelite.extension

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Contract checks for settings-button inject script. */
class ExtensionInjectScriptTest {

    private val script: String by lazy { readExtensionScript() }

    @Test
    fun assetExistsAndReturnsReport() {
        assertTrue(script.isNotBlank())
        assertTrue(script.trimStart().startsWith("(function"))
        assertTrue(script.contains("return result") || script.contains("return report"))
    }

    @Test
    fun appliesMaterialIconViewBox() {
        // Without this viewBox the path draws off-canvas on YouTube's svg.
        assertTrue(script.contains(ExtensionInjector.ICON_VIEW_BOX))
        assertTrue(script.contains("setAttribute('viewBox'"))
        assertTrue(script.contains("set_viewBox"))
    }

    @Test
    fun iconPathMustBeAppliedForSuccess() {
        assertTrue(script.contains("set_path"))
        assertTrue(script.contains("pathSet"))
        assertTrue(script.contains("icon_failed") || script.contains("verify_path"))
        assertTrue(script.contains("verify_icon") || script.contains("verify_viewBox"))
    }

    @Test
    fun successRequiresIconVerification() {
        assertTrue(script.contains("verifyIcon") || script.contains("verify_icon"))
        assertTrue(script.contains("M384-144"))
        assertTrue(script.contains(ExtensionInjector.BUTTON_ID))
    }

    @Test
    fun reportsNamedElementsWhenDomMissing() {
        val required = listOf(
            "ytm-settings",
            "template_button",
            "svg",
            "svg_path",
            "svg_viewBox",
            "label",
            "bridge",
            "insert",
            "verify",
        )
        for (element in required) {
            assertTrue("missing failure element `$element`", script.contains(element))
        }
    }

    @Test
    fun skipsNonSettingsPages() {
        assertTrue(script.contains("not_settings_page"))
        assertTrue(script.contains("skipped"))
        assertTrue(script.contains("select_site"))
    }

    @Test
    fun retriesWhenSettingsDomLate() {
        assertTrue(script.contains("MutationObserver") || script.contains("__liteExtObserver"))
        assertTrue(script.contains("scheduleRetries") || script.contains("RETRY_MS"))
    }

    @Test
    fun opensExtensionViaBridge() {
        assertTrue(script.contains("bridge.extension") || script.contains(".extension()"))
        assertTrue(script.contains("window.lite") || script.contains("window.Bridge"))
    }

    @Test
    fun doesNotBareThrowOnMissingDom() {
        val throws = script.lines().filter {
            it.contains("throw ") && !it.trimStart().startsWith("//")
        }
        assertTrue("unexpected throw: $throws", throws.isEmpty())
    }

    @Test
    fun injectorAssetPathMatchesFile() {
        assertTrue(
            File("src/main/assets", ExtensionInjector.ASSET).exists() ||
                File("app/src/main/assets", ExtensionInjector.ASSET).exists(),
        )
    }

    @Test
    fun repairsBrokenExistingButtonIcon() {
        assertTrue(script.contains("repair_failed") || script.contains("already_present"))
        assertTrue(script.contains("setPath") || script.contains("set_viewBox"))
    }

    private fun readExtensionScript(): String {
        val candidates = listOf(
            File("src/main/assets/${ExtensionInjector.ASSET}"),
            File("app/src/main/assets/${ExtensionInjector.ASSET}"),
            File("../src/main/assets/${ExtensionInjector.ASSET}"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("extension.js not found; tried $candidates")
        return file.readText(Charsets.UTF_8)
    }
}
