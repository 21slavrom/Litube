package com.hhst.youtubelite.browser

import com.hhst.youtubelite.core.Constants

data class Tab(
    val id: Long,
    val kind: String,
    val url: String,
)

data class TabState(
    val tabs: List<Tab>,
    val activeId: Long,
) {
    val active: Tab? get() = tabs.find { it.id == activeId }
}

/**
 * Pure tab routing (no Android views).
 *
 * - [PageKind.NAV] kinds reuse one tab each; watch reuses one stack tab; others push.
 * - **Home is the root** (kept while size > 0); it is usually at the front of [tabs],
 *   not the end.
 * - **Active is always [tabs]`.last()`** (stack top). After [pop], active becomes the
 *   new last entry; when only one tab remains it is home and [canPop] is false.
 */
class TabController(
    homeUrl: String = Constants.HOME_URL,
) {
    private var nextId = 1L
    private val tabs = mutableListOf<Tab>()
    private var activeId: Long

    init {
        val home = Tab(id = nextId++, kind = Constants.PAGE_HOME, url = homeUrl)
        tabs += home
        activeId = home.id
    }

    fun state(): TabState = TabState(tabs = tabs.toList(), activeId = activeId)

    fun canPop(): Boolean = tabs.size > 1

    /**
     * Routes [url] into an existing or new tab.
     *
     * @return state and optional `(tabId, url)` the host should load.
     */
    fun openTab(url: String): Pair<TabState, Pair<Long, String>?> {
        val kind = PageKind.of(url)
        val active = tabs.find { it.id == activeId }

        if (active != null && active.kind == kind && kind in PageKind.NAV) {
            return loadOrShow(active, url)
        }

        if (kind == Constants.PAGE_WATCH) {
            val watch = tabs.find { it.kind == Constants.PAGE_WATCH }
            if (watch != null) {
                moveToEnd(watch.id)
                activeId = watch.id
                return loadOrShow(watch, url)
            }
        }

        return if (kind in PageKind.NAV) openNav(url, kind) else openStack(url, kind)
    }

    fun updateUrl(tabId: Long, url: String) {
        val tab = tabs.find { it.id == tabId } ?: return
        if (tab.url == url) return
        // kind is sticky routing identity.
        replace(tabId, tab.copy(url = url))
    }

    /**
     * Drops the active (stack-top) tab.
     *
     * @return null when only home is left ([canPop] is false).
     */
    fun pop(): TabState? {
        if (!canPop()) return null
        val dropIndex = tabs.indexOfFirst { it.id == activeId }.takeIf { it >= 0 } ?: tabs.lastIndex
        tabs.removeAt(dropIndex)
        activeId = tabs.last().id
        return state()
    }

    private fun openNav(url: String, kind: String): Pair<TabState, Pair<Long, String>?> {
        val home = homeTab()
        val existing = if (kind == Constants.PAGE_HOME) home else tabs.find { it.kind == kind }
        val keepIds = buildSet {
            add(home.id)
            existing?.let { add(it.id) }
        }
        tabs.removeAll { it.id !in keepIds }

        if (existing != null) {
            moveToEnd(existing.id)
            activeId = existing.id
            return loadOrShow(existing, url)
        }
        return create(kind, url)
    }

    private fun openStack(url: String, kind: String): Pair<TabState, Pair<Long, String>?> {
        homeTab()
        return create(kind, url)
    }

    private fun loadOrShow(tab: Tab, url: String): Pair<TabState, Pair<Long, String>?> {
        if (tab.url == url) return state() to null
        replace(tab.id, tab.copy(url = url))
        return state() to (tab.id to url)
    }

    private fun create(kind: String, url: String): Pair<TabState, Pair<Long, String>?> {
        val created = Tab(id = nextId++, kind = kind, url = url)
        tabs += created
        activeId = created.id
        return state() to (created.id to url)
    }

    private fun homeTab(): Tab {
        tabs.find { it.kind == Constants.PAGE_HOME }?.let { return it }
        return Tab(id = nextId++, kind = Constants.PAGE_HOME, url = Constants.HOME_URL)
            .also { tabs.add(0, it) }
    }

    private fun moveToEnd(id: Long) {
        val i = tabs.indexOfFirst { it.id == id }
        if (i < 0 || i == tabs.lastIndex) return
        tabs.add(tabs.removeAt(i))
    }

    private fun replace(id: Long, tab: Tab) {
        val i = tabs.indexOfFirst { it.id == id }
        if (i >= 0) tabs[i] = tab
    }
}
