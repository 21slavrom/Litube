package com.hhst.youtubelite.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parses inject callback JSON, including icon verification fields. */
class InjectReportTest {

    @Test
    fun parse_nullOrEmpty_isFailure() {
        val empty = InjectReport.parse(null)
        assertFalse(empty.ok)
        assertEquals("empty_callback", empty.reason)

        val blank = InjectReport.parse("   ")
        assertFalse(blank.ok)
    }

    @Test
    fun parse_successfulInject_requiresIconState() {
        val raw =
            """{"ok":true,"skipped":false,"reason":"injected","buttonId":"extensionButton","failures":[],"steps":["start","set_viewBox","set_path","verify_icon"],"icon":{"viewBox":"0 -960 960 960","pathSet":true}}"""
        val report = InjectReport.parse(raw)

        assertTrue(report.ok)
        assertEquals("injected", report.reason)
        assertEquals(ExtensionInjector.ICON_VIEW_BOX, report.icon?.viewBox)
        assertTrue(report.icon?.pathSet == true)
        assertTrue(report.steps.contains("set_viewBox"))
        assertTrue(report.steps.contains("set_path"))
        assertTrue(report.steps.contains("verify_icon"))
    }

    @Test
    fun parse_iconFailed_isNotOk() {
        val raw =
            """{"ok":false,"reason":"icon_failed","buttonId":"extensionButton","failures":[{"element":"svg_viewBox","reason":"viewBox not applied"}],"steps":["start","found_template"],"icon":{"viewBox":null,"pathSet":false}}"""
        val report = InjectReport.parse(raw)

        assertFalse(report.ok)
        assertEquals("icon_failed", report.reason)
        assertTrue(report.failures.any { it.element.contains("svg") })
        assertFalse(report.icon?.pathSet == true)
    }

    @Test
    fun parse_verifyViewBoxMismatch_isFailure() {
        val raw =
            """{"ok":false,"reason":"icon_verify_failed","buttonId":"extensionButton","failures":[{"element":"verify_viewBox","reason":"expected 0 -960 960 960 got 0 0 24 24"}],"steps":["inserted"],"icon":{"viewBox":"0 0 24 24","pathSet":true}}"""
        val report = InjectReport.parse(raw)

        assertFalse(report.ok)
        assertTrue(report.failures.any { it.element == "verify_viewBox" })
        assertEquals("0 0 24 24", report.icon?.viewBox)
    }

    @Test
    fun parse_quotedJsonString_fromEvaluateJavascript() {
        val inner =
            """{"ok":true,"skipped":false,"reason":"injected","buttonId":"extensionButton","failures":[],"steps":["verify_icon"],"icon":{"viewBox":"0 -960 960 960","pathSet":true}}"""
        val raw = "\"${inner.replace("\"", "\\\"")}\""
        val report = InjectReport.parse(raw)

        assertTrue(report.ok)
        assertEquals(ExtensionInjector.ICON_VIEW_BOX, report.icon?.viewBox)
    }

    @Test
    fun parse_skippedNotSettings_isOk() {
        val raw =
            """{"ok":true,"skipped":true,"reason":"not_settings_page","buttonId":"extensionButton","failures":[],"steps":["skip_not_settings"]}"""
        val report = InjectReport.parse(raw)

        assertTrue(report.ok)
        assertTrue(report.skipped)
        assertEquals("skipped: not_settings_page", report.summary())
    }

    @Test
    fun parse_malformedJson_isParseError() {
        val report = InjectReport.parse("{not-json")
        assertFalse(report.ok)
        assertEquals("parse_error", report.reason)
    }

    @Test
    fun summary_includesIconOnSuccess() {
        val report = InjectReport(
            ok = true,
            buttonId = "extensionButton",
            icon = InjectIconState(viewBox = ExtensionInjector.ICON_VIEW_BOX, pathSet = true),
        )
        val text = report.summary()
        assertTrue(text.contains("viewBox=${ExtensionInjector.ICON_VIEW_BOX}"))
        assertTrue(text.contains("pathSet=true"))
    }
}
