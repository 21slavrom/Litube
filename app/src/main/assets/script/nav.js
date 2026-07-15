(() => {
  'use strict';
  if (window.__navTabs) return;
  window.__navTabs = true;

  const BRIDGE = 'Bridge';

  function kind(url) {
    try {
      const u = new URL(String(url).toLowerCase(), location.href);
      if (!u.hostname.includes('youtube.com') && u.hostname !== 'youtu.be') return 'unknown';
      if (u.hostname === 'youtu.be') {
        const segs = u.pathname.split('/').filter(Boolean);
        return segs.length ? 'watch' : 'unknown';
      }
      const segments = u.pathname.split('/').filter(Boolean);
      if (segments.length === 0) return 'home';
      const first = segments[0];
      if (first === 'shorts') return 'shorts';
      if (first === 'watch') return 'watch';
      if (first === 'channel') return 'channel';
      if (first === 'gaming') return 'gaming';
      if (first === 'select_site') return 'select_site';
      if (first === 'results') return 'searching';
      if (first.startsWith('@')) return '@';
      if (first === 'feed' && segments.length > 1) {
        const second = segments[1];
        if (second === 'subscriptions') return 'subscriptions';
        if (second === 'library') return 'library';
        if (second === 'history') return 'history';
        if (second === 'channels') return 'channels';
        if (second === 'playlists') return 'playlists';
        return segments.join('/');
      }
      return segments.join('/');
    } catch (e) {
      return 'unknown';
    }
  }

  function absolute(href) {
    try {
      return new URL(href, location.href).toString();
    } catch (e) {
      return href;
    }
  }

  function openTab(url) {
    const bridge = window[BRIDGE];
    if (!bridge || typeof bridge.openTab !== 'function') return;
    // YouTube may push a transient hash (e.g. #searching) on the source page
    // before cross-tab navigation; strip it so back lands on a clean page.
    cleanSourceHash();
    bridge.openTab(url);
  }

  function cleanSourceHash() {
    if (!location.hash) return;
    const clean = location.href.split('#')[0];
    if (clean === location.href) return;
    try {
      originalReplaceState.call(history, null, '', clean);
    } catch (e) {
      // Best-effort: replaceState can throw on cross-origin documents.
    }
  }

  const originalPushState = history.pushState;
  const originalReplaceState = history.replaceState;

  // Opens [url] in a new tab unless it matches [location.href]'s kind.
  // Returns true when routed cross-tab, false to let navigation proceed in-page.
  function maybeOpen(url) {
    if (typeof url !== 'string') return false;
    const nextUrl = absolute(url);
    const nextKind = kind(nextUrl);
    if (nextKind === 'unknown' || nextKind === kind(location.href)) return false;
    openTab(nextUrl);
    return true;
  }

  history.pushState = function (data, title, url) {
    if (url != null && maybeOpen(url)) return;
    return originalPushState.apply(this, arguments);
  };

  history.replaceState = function (data, title, url) {
    if (url != null && maybeOpen(url)) return;
    return originalReplaceState.apply(this, arguments);
  };

  document.addEventListener('click', (event) => {
    const anchor = event.target.closest && event.target.closest('a');
    const logo = event.target.closest && event.target.closest('ytm-home-logo');
    const nav = event.target.closest && event.target.closest('ytm-pivot-bar-item-renderer');

    let href;
    if (nav && nav.data && nav.data.navigationEndpoint) {
      href = nav.data.navigationEndpoint.commandMetadata
        && nav.data.navigationEndpoint.commandMetadata.webCommandMetadata
        && nav.data.navigationEndpoint.commandMetadata.webCommandMetadata.url;
    } else if (anchor && anchor.href) {
      href = anchor.getAttribute('href');
    } else if (logo) {
      href = '/';
    }
    if (!href) return;

    if (maybeOpen(href)) {
      event.preventDefault();
      event.stopImmediatePropagation();
    }
  }, true);
})();
