package com.omnireader.app.core.epub

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * JS injected into the chapter WebView that turns a single (x)html document into
 * real, swipeable pages by measuring and slicing the content column to the viewport.
 *
 * Protocol (window.OmniBridge -> Android via @JavascriptInterface):
 *   onPageReady(totalPages)        after layout/pagination completes
 *   onScrollProgress(y)            throttled scroll position for continuous mode
 */
object EpubPaginator {

    /** CSS + JS bundle. `mode` is "paginated" or "scroll". */
    fun inject(css: String, mode: String): String = """
        <style id="omni-css">$css</style>
        <script id="omni-js">
        (function() {
          const MODE = '$mode';
          const bridge = window.OmniBridge;
          function report(kind, arg) { try { if (bridge) bridge[kind](arg); } catch(e){} }

          function pageHeightPx() {
            // leave a small gutter so text never touches the tap zones
            return Math.max(200, window.innerHeight - 24);
          }

          function paginate() {
            const body = document.body;
            if (!body || MODE !== 'paginated') {
              report('onPageReady', 1);
              return;
            }
            // Reset any previous pagination
            unwrapSlices();
            const ph = pageHeightPx();
            let node = body.firstChild;
            let pageCount = 0;
            while (node) {
              const next = node.nextSibling;
              if (node.nodeType === 1 && node.id === 'omni-css') { node = next; continue; }
              if (node.nodeType === 1 && node.id === 'omni-js') { node = next; continue; }
              sliceInto(node, ph);
              pageCount++;
              node = next;
            }
            if (pageCount === 0) pageCount = 1;
            applyColumnMode(ph, pageCount);
          }

          // Wrap each top-level block in a .omni-page sized exactly one viewport.
          function sliceInto(node, ph) {
            const wrap = document.createElement('div');
            wrap.className = 'omni-page';
            node.parentNode.insertBefore(wrap, node);
            wrap.appendChild(node);
            wrap.style.height = ph + 'px';
            wrap.style.overflow = 'hidden';
            // If the wrapped element overflows, split it further by lines.
            if (wrap.scrollHeight > ph + 2 && canSplit(wrap.firstChild)) {
              splitElement(wrap, wrap.firstChild, ph);
            }
          }

          function canSplit(el) {
            return el && (el.tagName === 'P' || el.tagName === 'DIV' || el.tagName === 'BLOCKQUOTE' || el.tagName === 'SECTION' || el.tagName === 'ARTICLE');
          }

          function splitElement(pageWrap, el, ph) {
            // Move overflowing child lines into following sibling pages.
            let current = pageWrap;
            let working = el;
            while (current.scrollHeight > ph + 2 && working.children.length > 0) {
              const overflowIdx = findOverflowIndex(working, ph);
              if (overflowIdx <= 0) break;
              const newPage = document.createElement('div');
              newPage.className = 'omni-page';
              newPage.style.height = ph + 'px';
              newPage.style.overflow = 'hidden';
              const clone = working.cloneNode(false);
              let moved = 0;
              while (working.children.length > overflowIdx) {
                clone.appendChild(working.children[overflowIdx]);
                moved++;
              }
              if (moved === 0) break;
              newPage.appendChild(clone);
              current.parentNode.insertBefore(newPage, current.nextSibling);
              current = newPage;
              working = clone;
            }
          }

          function findOverflowIndex(container, ph) {
            const kids = Array.from(container.children);
            for (let i = 0; i < kids.length; i++) {
              if (kids[i].offsetTop + kids[i].offsetHeight > ph) return i;
            }
            return kids.length;
          }

          function unwrapSlices() {
            document.querySelectorAll('.omni-page').forEach(p => {
              const parent = p.parentNode;
              while (p.firstChild) parent.insertBefore(p.firstChild, p);
              parent.removeChild(p);
            });
          }

          // Fallback for huge single blocks (images): use CSS columns for the whole doc.
          function applyColumnMode(ph, pageCount) {
            report('onPageReady', Math.max(1, Math.ceil(document.documentElement.scrollHeight / ph)));
          }

          function reportScroll() {
            report('onScrollProgress', Math.round(window.scrollY || document.documentElement.scrollTop || 0));
          }

          let scrollTimer = null;
          window.addEventListener('scroll', () => {
            if (scrollTimer) return;
            scrollTimer = setTimeout(() => { scrollTimer = null; reportScroll(); }, 300);
          }, { passive: true });

          window.addEventListener('resize', () => { clearTimeout(window.__omniR); window.__omniR = setTimeout(paginate, 250); });

          // Expose control surface for Android
          window.OmniReader = {
            repaginate: paginate,
            setCss: (css) => { const s = document.getElementById('omni-css'); if (s) s.textContent = css; },
            scrollToY: (y) => window.scrollTo(0, y),
            pageHeight: pageHeightPx,
          };

          if (document.readyState === 'complete' || document.readyState === 'interactive') paginate();
          else document.addEventListener('DOMContentLoaded', paginate);
        })();
        </script>
    """.trimIndent()
}
