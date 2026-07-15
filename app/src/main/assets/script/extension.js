(function () {
  'use strict';

  var BUTTON_ID = 'extensionButton';
  var VIEW_BOX = '0 -960 960 960';
  var ICON_PATH =
    'M384-144H216q-29.7 0-50.85-21.15Q144-186.3 144-216v-168q40-2 68-29.5t28-66.5q0-39-28-66.5T144-576v-168q0-29.7 21.15-50.85Q186.3-816 216-816h168q0-40 27.77-68 27.78-28 68-28Q520-912 548-884.16q28 27.84 28 68.16h168q29.7 0 50.85 21.15Q816-773.7 816-744v168q40 0 68 27.77 28 27.78 28 68Q912-440 884.16-412q-27.84 28-68.16 28v168q0 29.7-21.15 50.85Q773.7-144 744-144H576q-2-40-29.38-68t-66.5-28q-39.12 0-66.62 28-27.5 28-29.5 68Zm-168-72h112q20-45 61.5-70.5T480-312q49 0 90.5 25.5T632-216h112v-240h72q9.6 0 16.8-7.2 7.2-7.2 7.2-16.8 0-9.6-7.2-16.8-7.2-7.2-16.8-7.2h-72v-240H504v-72q0-9.6-7.2-16.8-7.2-7.2-16.8-7.2-9.6 0-16.8 7.2-7.2 7.2-7.2 16.8v72H216v112q45 20 70.5 61.5T312-480q0 50.21-25.5 91.6Q261-347 216-328v112Zm264-264Z';
  var LABELS = {
    zh: '扩展', zt: '擴充功能', en: 'Extension', ja: '拡張機能',
    ko: '플러그인', fr: 'Extension', ru: 'Расширение', tr: 'Uzantı'
  };
  var RETRY_MS = [120, 240, 480, 960, 1600];

  function reportBase() {
    return {
      ok: false,
      skipped: false,
      reason: null,
      buttonId: BUTTON_ID,
      failures: [],
      steps: [],
      icon: { viewBox: null, pathSet: false }
    };
  }

  function label() {
    var lang = (document.documentElement && document.documentElement.lang || 'en').toLowerCase();
    var key = lang.substring(0, 2);
    if (lang.indexOf('tw') >= 0 || lang.indexOf('hk') >= 0 ||
        lang.indexOf('mo') >= 0 || lang.indexOf('hant') >= 0) {
      key = 'zt';
    }
    return LABELS[key] || LABELS.en;
  }

  function isSettingsPage() {
    try {
      if (document.querySelector('ytm-settings')) return true;
      if (document.querySelector('[data-mode="settings"]')) return true;
      var path = (location.pathname || '').toLowerCase();
      return path.indexOf('/select_site') >= 0;
    } catch (e) {
      return false;
    }
  }

  function stripNav(button) {
    if (!(button instanceof Element)) return;
    button.removeAttribute('href');
    button.removeAttribute('target');
    var anchors = button.querySelectorAll('a[href]');
    for (var i = 0; i < anchors.length; i++) {
      anchors[i].removeAttribute('href');
      anchors[i].removeAttribute('target');
    }
  }

  function setPath(root, pathData, viewBox, report) {
    var svg = root && root.querySelector ? root.querySelector('svg') : null;
    if (!(svg instanceof Element)) {
      report.failures.push({ element: 'svg', reason: 'template has no svg' });
      return false;
    }
    try {
      svg.setAttribute('viewBox', viewBox);
      report.icon.viewBox = svg.getAttribute('viewBox');
      report.steps.push('set_viewBox');
    } catch (e) {
      report.failures.push({ element: 'svg_viewBox', reason: String(e && e.message || e) });
      return false;
    }
    var pathEl = svg.querySelector('path');
    if (!(pathEl instanceof Element)) {
      report.failures.push({ element: 'svg_path', reason: 'template svg has no path' });
      return false;
    }
    try {
      pathEl.setAttribute('d', pathData);
      report.icon.pathSet = pathEl.getAttribute('d') === pathData;
      if (!report.icon.pathSet) {
        report.failures.push({ element: 'svg_path', reason: 'path d not applied' });
        return false;
      }
      report.steps.push('set_path');
      return true;
    } catch (e) {
      report.failures.push({ element: 'svg_path', reason: String(e && e.message || e) });
      return false;
    }
  }

  function fitIcon(root, report) {
    var size = 24;
    var px = size + 'px';
    try {
      var icon = root.querySelector('c3-icon');
      var host = root.querySelector('.yt-icon-shape');
      var svg = root.querySelector('svg');
      if (icon) {
        icon.style.width = px;
        icon.style.height = px;
      }
      if (host) {
        host.style.width = px;
        host.style.height = px;
      }
      if (svg) {
        svg.setAttribute('width', String(size));
        svg.setAttribute('height', String(size));
        svg.style.width = px;
        svg.style.height = px;
      }
      report.steps.push('fit_icon');
    } catch (e) {
      report.failures.push({ element: 'svg_fit', reason: String(e && e.message || e) });
    }
  }

  function setText(button, text, report) {
    var selectors = [
      '.ytAttributedStringHost',
      '.ytSpecButtonShapeNextButtonTextContent',
      '.yt-core-attributed-string',
      'span'
    ];
    for (var i = 0; i < selectors.length; i++) {
      var el = button.querySelector(selectors[i]);
      if (el && typeof el.innerText === 'string') {
        el.innerText = text;
        report.steps.push('label');
        return true;
      }
    }
    report.failures.push({
      element: 'label',
      reason: 'no text host matched'
    });
    return false;
  }

  function bindClick(button, report) {
    var bridge = window.lite || window.Bridge;
    if (!bridge || typeof bridge.extension !== 'function') {
      report.failures.push({
        element: 'bridge',
        reason: 'extension() unavailable'
      });
      return false;
    }
    var handler = function (event) {
      try {
        if (event) {
          event.preventDefault();
          event.stopImmediatePropagation();
        }
        bridge.extension();
      } catch (e) {
        console.error('[extension] open failed', e);
      }
    };
    try {
      button.removeEventListener('click', button.__liteExtensionClick, true);
    } catch (e) { /* ignore */ }
    button.__liteExtensionClick = handler;
    button.addEventListener('click', handler, true);
    report.steps.push('bind_click');
    return true;
  }

  function findTemplate(settings) {
    var child = settings.firstElementChild;
    while (child) {
      if (child.querySelector && child.querySelector('svg')) return child;
      child = child.nextElementSibling;
    }
    return null;
  }

  function verifyIcon(button, report) {
    var svg = button.querySelector('svg');
    if (!svg) {
      report.failures.push({ element: 'verify_svg', reason: 'injected button missing svg' });
      return false;
    }
    var vb = svg.getAttribute('viewBox');
    if (vb !== VIEW_BOX) {
      report.failures.push({
        element: 'verify_viewBox',
        reason: 'expected ' + VIEW_BOX + ' got ' + vb
      });
      return false;
    }
    var pathEl = svg.querySelector('path');
    var d = pathEl && pathEl.getAttribute('d');
    if (!d || d.indexOf('M384-144') !== 0) {
      report.failures.push({
        element: 'verify_path',
        reason: 'extension icon path not present'
      });
      return false;
    }
    report.steps.push('verify_icon');
    return true;
  }

  function injectOnce() {
    var report = reportBase();
    report.steps.push('start');

    if (!isSettingsPage()) {
      report.ok = true;
      report.skipped = true;
      report.reason = 'not_settings_page';
      report.steps.push('skip_not_settings');
      return report;
    }
    report.steps.push('is_settings');

    var existing = document.getElementById(BUTTON_ID);
    if (existing) {
      bindClick(existing, report);
      var iconOk = verifyIcon(existing, report);
      if (!iconOk) {
        // Repair a previously broken inject (wrong viewBox / path).
        if (setPath(existing, ICON_PATH, VIEW_BOX, report)) {
          fitIcon(existing, report);
          iconOk = verifyIcon(existing, report);
        }
      }
      report.ok = iconOk;
      report.reason = iconOk ? 'already_present' : 'repair_failed';
      return report;
    }

    var settings = document.querySelector('ytm-settings');
    if (!(settings instanceof Element)) {
      report.failures.push({ element: 'ytm-settings', reason: 'settings list root missing' });
      report.reason = 'missing_settings_root';
      return report;
    }
    report.steps.push('found_settings');

    var template = findTemplate(settings);
    if (!(template instanceof Element)) {
      report.failures.push({ element: 'template_button', reason: 'no icon row in settings' });
      report.reason = 'missing_template';
      return report;
    }
    report.steps.push('found_template');

    var button;
    try {
      button = template.cloneNode(true);
    } catch (e) {
      report.failures.push({ element: 'clone', reason: String(e && e.message || e) });
      report.reason = 'clone_failed';
      return report;
    }
    button.id = BUTTON_ID;
    stripNav(button);
    setText(button, label(), report);
    if (!setPath(button, ICON_PATH, VIEW_BOX, report)) {
      report.reason = 'icon_failed';
      return report;
    }
    fitIcon(button, report);
    var bridgeOk = bindClick(button, report);

    try {
      settings.insertBefore(button, template);
      report.steps.push('inserted');
    } catch (e) {
      report.failures.push({ element: 'insert', reason: String(e && e.message || e) });
      report.reason = 'insert_failed';
      return report;
    }

    var node = document.getElementById(BUTTON_ID);
    if (!node) {
      report.failures.push({ element: 'verify', reason: 'button missing after insert' });
      report.reason = 'verify_failed';
      return report;
    }
    if (!verifyIcon(node, report)) {
      report.reason = 'icon_verify_failed';
      return report;
    }

    report.ok = true;
    report.reason = bridgeOk ? 'injected' : 'injected_without_bridge';
    return report;
  }

  function scheduleRetries() {
    if (window.__liteExtRetrying) return;
    window.__liteExtRetrying = true;
    var i = 0;
    function tick() {
      var r = injectOnce();
      if (r.ok && !r.skipped) {
        window.__liteExtRetrying = false;
        return;
      }
      if (r.skipped) {
        window.__liteExtRetrying = false;
        return;
      }
      if (i >= RETRY_MS.length) {
        window.__liteExtRetrying = false;
        return;
      }
      setTimeout(tick, RETRY_MS[i++]);
    }
    setTimeout(tick, RETRY_MS[i++]);
  }

  function ensureObserver() {
    if (window.__liteExtObserver || typeof MutationObserver !== 'function') return;
    var target = document.documentElement || document.body;
    if (!target) return;
    window.__liteExtObserver = new MutationObserver(function () {
      if (!isSettingsPage()) return;
      if (document.getElementById(BUTTON_ID)) {
        var check = injectOnce();
        if (check.ok) return;
      }
      scheduleRetries();
    });
    window.__liteExtObserver.observe(target, { childList: true, subtree: true });
  }

  var result = injectOnce();
  ensureObserver();
  if (!result.ok && !result.skipped) {
    scheduleRetries();
  }
  return result;
})()
