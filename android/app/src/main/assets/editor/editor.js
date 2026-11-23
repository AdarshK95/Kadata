/**
 * Kadata Offline Rich-Text Editor Controller & AndroidBridge (v16)
 * - Rock-solid forward typing (fixed caret rewind bug by preserving live selection on input
 *   and guarding IME composition)
 * - Custom 100-Undo / 100-Redo history manager with automatic scroll-to-section & brief highlight
 * - Guaranteed visible blinking virtual caret + line-aware scroll (1 line phone / 2-3 lines tablet)
 * - Normalized cross-span Find & Replace
 * - Script-split legacy PC export (Nudi 01 e / BRH Kannada + Calibri for English)
 */
(function (global) {
  'use strict';

  const paper = document.getElementById('paper');
  const originalBanner = document.getElementById('original-banner');
  let virtualCaretEl = document.getElementById('kd-virtual-caret');
  function ensureVirtualCaretEl() {
    if (!virtualCaretEl) {
      virtualCaretEl = document.createElement('div');
      virtualCaretEl.id = 'kd-virtual-caret';
      virtualCaretEl.setAttribute('aria-hidden', 'true');
      document.body.appendChild(virtualCaretEl);
    }
    return virtualCaretEl;
  }
  let caretSyncInterval = null;
  let caretSolidTimer = null;

  let sourceDoc = null; // Pre-converted Modern Universal Kannada IDM from Kotlin
  let originalDoc = null; // Raw pre-conversion IDM strictly for Read-Only Original View
  let activeConverter = 'auto';
  let isOriginalView = false;
  let isExternalReadOnly = false;
  let hasUserEdited = false;
  let lastTrackedTextLen = 0;
  let maxPasteChars = 1500000;
  let savedEditableHtml = null;
  let autoSaveTimer = null;

  // Find & Replace state
  let searchMatches = [];
  let activeMatchIndex = -1;
  let lastSearchQuery = '';

  // Caret & Keyboard state
  let savedCaretRange = null;
  let keyboardTopLimitPx = 0;
  let virtualKbOpen = false;
  let isTabletLayout = false;
  let manualKeyboardBlock = false;
  let osKeyboardAllowedOnce = false;
  let isComposing = false;

  // Universal document zoom state (50% - 300%, works in Editable & Read-Only Original views)
  let currentZoomPercent = 100;
  let pinchStartDist = 0;
  let pinchStartZoom = 100;
  let pinchAnchorScrollRatio = 0;
  let isPinching = false;

  // Custom 100-Undo / 100-Redo History Manager state (bounded by BOTH 100 steps AND 10 MB budget)
  const MAX_HISTORY_STEPS = 100;
  const MAX_HISTORY_CHARS = 6 * 1024 * 1024; // ~6 MB budget across undo + redo stacks (OOM-safe)
  let undoStack = [];
  let redoStack = [];
  let currentBaseline = null; // { html: string, caret: { blockIndex, charOffset } | null }
  let historyDebounceTimer = null;
  let isRestoringHistory = false;
  let notifiedHistoryTrim = false;

  const KANNADA_TRAILING_SIGN_RE = /[\u0CBE-\u0CCC\u0C82\u0C83]$/;
  const ZERO_WIDTH_RE = /[\u200B\uFEFF\u00AD\u2060]/g;

  function notifyAndroid(method, ...args) {
    if (global.AndroidBridge && typeof global.AndroidBridge[method] === 'function') {
      global.AndroidBridge[method](...args);
    }
  }

  function applyInputModeState() {
    if (virtualKbOpen || (manualKeyboardBlock && !osKeyboardAllowedOnce)) {
      paper.setAttribute('inputmode', 'none');
    } else {
      paper.removeAttribute('inputmode');
    }
  }

  function isNodeInsidePaper(node) {
    return !!node && (node === paper || paper.contains(node));
  }

  function getLiveSelectionRangeInPaper() {
    const sel = window.getSelection();
    if (!sel || sel.rangeCount === 0) return null;
    const range = sel.getRangeAt(0);
    if (isNodeInsidePaper(range.startContainer) && isNodeInsidePaper(range.endContainer)) {
      return range;
    }
    return null;
  }

  function captureSelectionRange() {
    const live = getLiveSelectionRangeInPaper();
    if (live) {
      savedCaretRange = live.cloneRange();
    }
  }

  function getEditableBlocks() {
    const blocks = Array.from(paper.querySelectorAll('p, h1, h2, h3, h4, li, td, th'));
    const leafBlocks = blocks.filter(el => !el.querySelector('p, h1, h2, h3, h4, li, td, th'));
    return leafBlocks.length > 0 ? leafBlocks : [paper];
  }

  function findContainingBlock(node, blocks) {
    let cur = node;
    while (cur && cur !== paper) {
      if (cur.nodeType === Node.ELEMENT_NODE) {
        const idx = blocks.indexOf(cur);
        if (idx !== -1) return { block: cur, index: idx };
      }
      cur = cur.parentNode;
    }
    return blocks.length > 0 ? { block: blocks[0], index: 0 } : null;
  }

  /**
   * Serializes the current caret position as { blockIndex, charOffset } so Undo / Redo
   * can restore the exact cursor location after replacing innerHTML.
   */
  function serializeCaretPosition() {
    const range = getLiveSelectionRangeInPaper() || savedCaretRange;
    if (!range || !isNodeInsidePaper(range.startContainer)) return null;

    const blocks = getEditableBlocks();
    const found = findContainingBlock(range.startContainer, blocks);
    if (!found) return null;

    try {
      const pre = document.createRange();
      pre.setStart(found.block, 0);
      pre.setEnd(range.startContainer, range.startOffset);
      const charOffset = pre.toString().length;
      return { blockIndex: found.index, charOffset };
    } catch (_) {
      return { blockIndex: found.index, charOffset: 0 };
    }
  }

  /**
   * Restores a serialized { blockIndex, charOffset } caret into the DOM and returns { range, block }.
   */
  function restoreSerializedCaret(caretPos) {
    const blocks = getEditableBlocks();
    if (blocks.length === 0) return null;

    const safeIdx = caretPos
      ? Math.max(0, Math.min(caretPos.blockIndex, blocks.length - 1))
      : 0;
    const block = blocks[safeIdx];
    const targetOffset = caretPos ? Math.max(0, caretPos.charOffset) : 0;

    const range = document.createRange();
    const walker = document.createTreeWalker(block, NodeFilter.SHOW_TEXT, null);
    let remaining = targetOffset;
    let placed = false;
    let lastTextNode = null;
    let node;

    while ((node = walker.nextNode())) {
      lastTextNode = node;
      const len = (node.nodeValue || '').length;
      if (remaining <= len) {
        range.setStart(node, remaining);
        range.collapse(true);
        placed = true;
        break;
      }
      remaining -= len;
    }

    if (!placed) {
      if (lastTextNode) {
        range.setStart(lastTextNode, (lastTextNode.nodeValue || '').length);
        range.collapse(true);
      } else {
        range.selectNodeContents(block);
        range.collapse(false);
      }
    }

    const sel = window.getSelection();
    if (sel) {
      sel.removeAllRanges();
      sel.addRange(range);
    }
    savedCaretRange = range.cloneRange();
    return { range, block };
  }

  function placeCaretAtVisibleLine() {
    const sel = window.getSelection();
    if (!sel) return null;

    const viewH = getVisibleViewportHeight();
    const candidates = getEditableBlocks();
    let target = null;
    for (let i = 0; i < candidates.length; i++) {
      const r = candidates[i].getBoundingClientRect();
      if (r.height > 0 && r.bottom >= 8 && r.top <= viewH - 20) {
        target = candidates[i];
        break;
      }
    }
    if (!target) {
      target = candidates[0] || paper.firstChild;
    }
    if (!target) {
      target = document.createElement('p');
      target.appendChild(document.createElement('br'));
      paper.appendChild(target);
    }

    const range = document.createRange();
    range.selectNodeContents(target);
    range.collapse(false);
    sel.removeAllRanges();
    sel.addRange(range);
    savedCaretRange = range.cloneRange();
    return range;
  }

  /**
   * Restores savedCaretRange ONLY for external Compose actions (like Varnamala keyboard taps).
   * NEVER call this during native OS typing (input event), because live selection is already ahead!
   */
  function restoreCaretForExternalAction() {
    paper.focus({ preventScroll: true });
    const sel = window.getSelection();
    if (!sel) return null;
    if (savedCaretRange && isNodeInsidePaper(savedCaretRange.startContainer)) {
      try {
        sel.removeAllRanges();
        sel.addRange(savedCaretRange);
        return sel.getRangeAt(0);
      } catch (_) {
        // Fall through to visible line
      }
    }
    const live = getLiveSelectionRangeInPaper();
    if (live) return live;
    return placeCaretAtVisibleLine();
  }

  function findFirstTextNode(root) {
    if (!root) return null;
    if (root.nodeType === Node.TEXT_NODE && (root.nodeValue || '').length > 0) return root;
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null);
    let n;
    while ((n = walker.nextNode())) {
      if ((n.nodeValue || '').length > 0) return n;
    }
    return null;
  }

  function findLastTextNode(root) {
    if (!root) return null;
    if (root.nodeType === Node.TEXT_NODE && (root.nodeValue || '').length > 0) return root;
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null);
    let last = null;
    let n;
    while ((n = walker.nextNode())) {
      if ((n.nodeValue || '').length > 0) last = n;
    }
    return last;
  }

  function measureCharBoundaryInTextNode(textNode, offset) {
    const len = (textNode.nodeValue || '').length;
    if (len === 0) return null;
    try {
      const r = document.createRange();
      if (offset > 0) {
        const clamped = Math.min(offset, len);
        r.setStart(textNode, clamped - 1);
        r.setEnd(textNode, clamped);
        const rects = r.getClientRects();
        for (let i = rects.length - 1; i >= 0; i--) {
          const box = rects[i];
          if (box.height > 0 && box.height < 240) {
            return { left: box.right, top: box.top, bottom: box.bottom, height: box.height };
          }
        }
      } else {
        r.setStart(textNode, 0);
        r.setEnd(textNode, 1);
        const rects = r.getClientRects();
        for (let i = 0; i < rects.length; i++) {
          const box = rects[i];
          if (box.height > 0 && box.height < 240) {
            return { left: box.left, top: box.top, bottom: box.bottom, height: box.height };
          }
        }
      }
    } catch (_) {
      // Fall through
    }
    return null;
  }

  /**
   * Zero-mutation, pixel-exact viewport caret measurement for Android Chromium WebView.
   * Collapsed Ranges in Chromium often return empty getClientRects() — measuring an adjacent
   * 1-character non-collapsed Range gives exact (left, top, bottom, height) with zero DOM mutation!
   */
  function measureCaretClientRect(range) {
    if (!range) return null;
    const sc = range.startContainer;
    const so = range.startOffset;

    // 1. Inside a TextNode: measure 1 adjacent character (exact X & Y across wrapped lines!)
    if (sc && sc.nodeType === Node.TEXT_NODE) {
      const charHit = measureCharBoundaryInTextNode(sc, so);
      if (charHit) return charHit;
    }

    // 2. Inside an Element node (e.g. <p> or <span>): inspect adjacent child text node
    if (sc && sc.nodeType === Node.ELEMENT_NODE) {
      const children = sc.childNodes;
      if (so > 0 && so <= children.length) {
        const prevText = findLastTextNode(children[so - 1]);
        if (prevText) {
          const hit = measureCharBoundaryInTextNode(prevText, (prevText.nodeValue || '').length);
          if (hit) return hit;
        }
      }
      if (so < children.length) {
        const nextText = findFirstTextNode(children[so]);
        if (nextText) {
          const hit = measureCharBoundaryInTextNode(nextText, 0);
          if (hit) return hit;
        }
      }
    }

    // 3. Native collapsed range rect if Chromium provides a valid non-zero box
    try {
      const rects = range.getClientRects();
      if (rects && rects.length > 0) {
        const r = rects[rects.length - 1];
        if (r.height > 0 && r.height < 240) {
          return { left: r.left, top: r.top, bottom: r.bottom, height: r.height };
        }
      }
      const bRect = range.getBoundingClientRect();
      if (bRect && bRect.height > 0 && bRect.height < 240) {
        return { left: bRect.left, top: bRect.top, bottom: bRect.bottom, height: bRect.height };
      }
    } catch (_) {
      // Fall through
    }

    // 4. Empty paragraph (<p><br></p>): compute exact inner start-of-line position
    const el = sc && sc.nodeType === Node.ELEMENT_NODE ? sc : (sc ? sc.parentElement : null);
    if (el && el !== paper) {
      const elRect = el.getBoundingClientRect();
      if (elRect && (elRect.height > 0 || elRect.top !== 0)) {
        const cs = window.getComputedStyle(el);
        const padL = parseFloat(cs.paddingLeft) || 0;
        const padT = parseFloat(cs.paddingTop) || 0;
        const lh = getEditorLineHeightPx();
        const h = Math.round(lh * 0.80);
        return {
          left: elRect.left + padL,
          top: elRect.top + padT,
          bottom: elRect.top + padT + h,
          height: h
        };
      }
    }
    return null;
  }

  function getRealContentBottomPageY() {
    let bottom = 0;
    const children = paper.children;
    for (let i = children.length - 1; i >= 0; i--) {
      const r = children[i].getBoundingClientRect();
      if (r.height > 0) {
        bottom = window.scrollY + r.bottom;
        break;
      }
    }
    return bottom;
  }

  function getVisibleViewportHeight() {
    if (window.visualViewport && window.visualViewport.height > 0) {
      return window.visualViewport.height;
    }
    return window.innerHeight || document.documentElement.clientHeight || 500;
  }

  function getEditorLineHeightPx() {
    try {
      const cs = window.getComputedStyle(paper);
      const lh = parseFloat(cs.lineHeight);
      if (!isNaN(lh) && lh > 12) return lh;
      const fs = parseFloat(cs.fontSize);
      if (!isNaN(fs) && fs > 8) return fs * 1.75;
    } catch (_) {
      // Fallback
    }
    return 38;
  }

  /**
   * Keeps 1 full line above cursor on phones (cursor on line 2) and 2-3 full lines on tablets.
   */
  function getDesiredTopContextPx() {
    const lh = getEditorLineHeightPx();
    const viewH = getVisibleViewportHeight();
    if (isTabletLayout) {
      return Math.min(Math.round(lh * 2.65) + 12, Math.round(viewH * 0.38));
    }
    return Math.min(Math.round(lh * 1.15) + 10, Math.round(viewH * 0.32));
  }

  function startCaretSyncLoop() {
    if (caretSyncInterval) return;
    caretSyncInterval = setInterval(() => {
      if (virtualKbOpen && !isOriginalView) {
        updateVirtualCaretVisual(false);
      }
    }, 220);
  }

  function stopCaretSyncLoop() {
    if (caretSyncInterval) {
      clearInterval(caretSyncInterval);
      caretSyncInterval = null;
    }
  }

  function hideVirtualCaret() {
    stopCaretSyncLoop();
    if (virtualCaretEl) {
      virtualCaretEl.classList.remove('visible');
      virtualCaretEl.classList.remove('solid');
    }
  }

  function updateVirtualCaretVisual(holdSolidBriefly) {
    const el = ensureVirtualCaretEl();
    if (!virtualKbOpen || isOriginalView) {
      el.classList.remove('visible');
      return;
    }
    let range = savedCaretRange;
    if (!range || !isNodeInsidePaper(range.startContainer)) {
      const live = getLiveSelectionRangeInPaper();
      if (live) {
        range = live.cloneRange();
        savedCaretRange = range;
      } else {
        range = placeCaretAtVisibleLine();
      }
    }
    if (!range) {
      el.classList.remove('visible');
      return;
    }

    const rect = measureCaretClientRect(range);
    if (!rect) {
      el.classList.remove('visible');
      return;
    }

    const viewH = getVisibleViewportHeight();
    const viewW = window.innerWidth || document.documentElement.clientWidth || 400;
    if (rect.bottom < 0 || rect.top > viewH + 8) {
      el.classList.remove('visible');
      return;
    }

    const lh = getEditorLineHeightPx();
    const caretHeight = (rect.height > 10 && rect.height < lh * 1.8)
      ? Math.round(rect.height)
      : Math.round(lh * 0.78);
    // Position using fixed viewport coordinates directly (immune to scrollY/body offset quirks!)
    const caretLeft = Math.max(2, Math.min(Math.round(rect.left), viewW - 6));
    const caretTop = Math.round(rect.top + Math.max(0, (rect.height - caretHeight) / 2));

    el.style.left = `${caretLeft}px`;
    el.style.top = `${caretTop}px`;
    el.style.height = `${caretHeight}px`;
    el.classList.add('visible');

    if (holdSolidBriefly) {
      el.classList.add('solid');
      if (caretSolidTimer) clearTimeout(caretSolidTimer);
      caretSolidTimer = setTimeout(() => {
        if (virtualCaretEl) virtualCaretEl.classList.remove('solid');
      }, 550);
    }
  }

  /**
   * Pure read-only scroll adjustment — NEVER overwrites window.getSelection()!
   */
  function scrollCaretIntoSafeView(alignToComfortBand) {
    try {
      const range = getLiveSelectionRangeInPaper()
        || (savedCaretRange && isNodeInsidePaper(savedCaretRange.startContainer) ? savedCaretRange : null);
      if (!range) {
        if (virtualKbOpen) updateVirtualCaretVisual();
        return;
      }

      const rect = measureCaretClientRect(range, virtualKbOpen);
      if (!rect) {
        if (virtualKbOpen) updateVirtualCaretVisual();
        return;
      }

      const viewHeight = getVisibleViewportHeight();
      const desiredTop = getDesiredTopContextPx();
      const caretPageTop = window.scrollY + rect.top;
      const contentBottom = getRealContentBottomPageY();
      const maxReasonableScroll = Math.max(0, contentBottom - Math.min(90, Math.round(viewHeight * 0.30)));

      let targetScrollY = null;
      if (virtualKbOpen) {
        const safeBottom = (keyboardTopLimitPx > 90)
          ? Math.min(viewHeight - 28, keyboardTopLimitPx - 18)
          : Math.max(desiredTop + 56, viewHeight - 32);

        const tooCloseToTop = rect.top < Math.max(18, desiredTop - 16);
        const tooCloseToBottom = rect.bottom > safeBottom;
        if (tooCloseToTop || tooCloseToBottom || (alignToComfortBand && rect.top > safeBottom - 36)) {
          targetScrollY = caretPageTop - desiredTop;
        }
      } else {
        const safeBottom = Math.max(desiredTop + 48, viewHeight - 52);
        if (rect.top < 16 || rect.bottom > safeBottom) {
          targetScrollY = caretPageTop - desiredTop;
        }
      }

      if (targetScrollY !== null) {
        const clampedY = Math.max(0, Math.min(targetScrollY, maxReasonableScroll));
        if (Math.abs(clampedY - window.scrollY) > 6) {
          window.scrollTo({ top: clampedY, left: 0, behavior: 'auto' });
        }
      }
      if (virtualKbOpen) updateVirtualCaretVisual();
    } catch (_) {
      if (virtualKbOpen) updateVirtualCaretVisual();
    }
  }

  // =========================================================================
  // Custom 100-Undo / 100-Redo History Manager with Scroll-to-Section
  // =========================================================================
  function takeCleanSnapshot(caretOverride) {
    const cleanClone = getCleanPaperClone();
    return {
      html: cleanClone.innerHTML,
      caret: caretOverride !== undefined ? caretOverride : serializeCaretPosition()
    };
  }

  function enforceHistoryMemoryBudget() {
    let totalChars = currentBaseline && currentBaseline.html ? currentBaseline.html.length : 0;
    for (let i = 0; i < undoStack.length; i++) {
      totalChars += undoStack[i].html ? undoStack[i].html.length : 0;
    }
    for (let i = 0; i < redoStack.length; i++) {
      totalChars += redoStack[i].html ? redoStack[i].html.length : 0;
    }
    let evicted = false;
    while (totalChars > MAX_HISTORY_CHARS && (undoStack.length > 1 || redoStack.length > 1)) {
      if (redoStack.length > 1) {
        const dropped = redoStack.shift();
        totalChars -= dropped && dropped.html ? dropped.html.length : 0;
        evicted = true;
      } else if (undoStack.length > 1) {
        const dropped = undoStack.shift();
        totalChars -= dropped && dropped.html ? dropped.html.length : 0;
        evicted = true;
      } else {
        break;
      }
    }
    if (evicted && !notifiedHistoryTrim) {
      notifiedHistoryTrim = true;
      notifyAndroid(
        'onEditorWarning',
        'Large document: oldest undo steps were trimmed to stay within the 10 MB memory limit. Please save regularly.'
      );
    }
  }

  function resetHistoryToCurrent() {
    if (historyDebounceTimer) {
      clearTimeout(historyDebounceTimer);
      historyDebounceTimer = null;
    }
    undoStack = [];
    redoStack = [];
    notifiedHistoryTrim = false;
    currentBaseline = takeCleanSnapshot(null);
  }

  function commitHistoryStep(preEditCaret) {
    if (isOriginalView || isRestoringHistory) return;
    if (historyDebounceTimer) {
      clearTimeout(historyDebounceTimer);
      historyDebounceTimer = null;
    }
    const nextSnap = takeCleanSnapshot();
    if (!currentBaseline) {
      currentBaseline = nextSnap;
      return;
    }
    if (nextSnap.html === currentBaseline.html) {
      currentBaseline.caret = nextSnap.caret;
      return;
    }
    const prevEntry = {
      html: currentBaseline.html,
      caret: preEditCaret || nextSnap.caret || currentBaseline.caret
    };
    undoStack.push(prevEntry);
    if (undoStack.length > MAX_HISTORY_STEPS) {
      undoStack.shift();
    }
    redoStack = [];
    currentBaseline = nextSnap;
    enforceHistoryMemoryBudget();
  }

  function scheduleHistoryCommit(immediate) {
    if (isOriginalView || isRestoringHistory || isComposing) return;
    if (historyDebounceTimer) clearTimeout(historyDebounceTimer);
    if (immediate) {
      commitHistoryStep(null);
    } else {
      historyDebounceTimer = setTimeout(() => {
        historyDebounceTimer = null;
        commitHistoryStep(null);
      }, 450);
    }
  }

  function scrollToRestoredHistoryTarget(restored) {
    if (!restored) return;
    try {
      const { range, block } = restored;
      const rect = measureCaretClientRect(range, true) || (block ? block.getBoundingClientRect() : null);
      if (rect) {
        const viewH = getVisibleViewportHeight();
        const targetTop = Math.round(viewH * 0.32);
        const destY = Math.max(0, window.scrollY + rect.top - targetTop);
        window.scrollTo({ top: destY, left: 0, behavior: 'smooth' });
      } else if (block && block.scrollIntoView) {
        block.scrollIntoView({ behavior: 'smooth', block: 'center' });
      }

      if (block && block !== paper) {
        block.classList.remove('kd-history-flash');
        void block.offsetWidth;
        block.classList.add('kd-history-flash');
        setTimeout(() => block.classList.remove('kd-history-flash'), 900);
      }
      if (virtualKbOpen) {
        setTimeout(() => updateVirtualCaretVisual(), 120);
      }
    } catch (_) {
      // Ignore scroll errors
    }
  }

  function performUndo() {
    if (isOriginalView) return;
    if (historyDebounceTimer) {
      clearTimeout(historyDebounceTimer);
      historyDebounceTimer = null;
    }
    const liveSnap = takeCleanSnapshot();
    if (currentBaseline && liveSnap.html !== currentBaseline.html) {
      commitHistoryStep(null);
    }

    if (undoStack.length === 0) return;

    isRestoringHistory = true;
    try {
      clearSearchHighlights(true);
      if (currentBaseline) {
        redoStack.push(currentBaseline);
        if (redoStack.length > MAX_HISTORY_STEPS) {
          redoStack.shift();
        }
      }
      const prev = undoStack.pop();
      currentBaseline = prev;
      paper.innerHTML = prev.html;
      const restored = restoreSerializedCaret(prev.caret);
      scrollToRestoredHistoryTarget(restored);
    } finally {
      isRestoringHistory = false;
    }

    notifyAndroid('onContentDirty');
    scheduleAutoSave();
  }

  function performRedo() {
    if (isOriginalView) return;
    if (redoStack.length === 0) return;

    isRestoringHistory = true;
    try {
      clearSearchHighlights(true);
      if (currentBaseline) {
        undoStack.push(currentBaseline);
        if (undoStack.length > MAX_HISTORY_STEPS) {
          undoStack.shift();
        }
      }
      const next = redoStack.pop();
      currentBaseline = next;
      paper.innerHTML = next.html;
      const restored = restoreSerializedCaret(next.caret);
      scrollToRestoredHistoryTarget(restored);
    } finally {
      isRestoringHistory = false;
    }

    notifyAndroid('onContentDirty');
    scheduleAutoSave();
  }

  // =========================================================================
  // Event Listeners (IME-safe & Never Rewinds Caret!)
  // =========================================================================
  paper.addEventListener('compositionstart', () => {
    isComposing = true;
  });

  paper.addEventListener('compositionend', () => {
    isComposing = false;
    captureSelectionRange();
    scheduleHistoryCommit(false);
    scrollCaretIntoSafeView(false);
  });

  document.addEventListener('selectionchange', () => {
    if (isRestoringHistory) return;
    captureSelectionRange();
    if (virtualKbOpen) updateVirtualCaretVisual();
  });

  window.addEventListener('scroll', () => {
    if (virtualKbOpen) updateVirtualCaretVisual();
  }, { passive: true });

  paper.addEventListener('keyup', () => {
    captureSelectionRange();
    if (!isComposing) scrollCaretIntoSafeView(false);
  });

  paper.addEventListener('mouseup', captureSelectionRange);

  paper.addEventListener('touchend', () => {
    captureSelectionRange();
    if (virtualKbOpen) {
      setTimeout(() => updateVirtualCaretVisual(), 40);
    } else if (!manualKeyboardBlock) {
      setTimeout(() => scrollCaretIntoSafeView(false), 220);
    }
  });

  paper.addEventListener('focus', () => {
    captureSelectionRange();
    if (!virtualKbOpen) {
      setTimeout(() => scrollCaretIntoSafeView(false), 180);
    }
  });

  if (window.visualViewport) {
    window.visualViewport.addEventListener('resize', () => {
      if (!isComposing) {
        setTimeout(() => scrollCaretIntoSafeView(false), 60);
      }
    });
  }

  window.addEventListener('resize', () => {
    if (!isComposing) {
      setTimeout(() => scrollCaretIntoSafeView(false), 80);
    }
  });

  function applyDocumentZoom(nextPercent, anchorViewportY) {
    const clamped = Math.max(50, Math.min(300, Math.round(nextPercent)));
    if (clamped === currentZoomPercent) return clamped;

    const prevScrollHeight = Math.max(1, document.documentElement.scrollHeight);
    const focusY = (typeof anchorViewportY === 'number')
      ? anchorViewportY
      : Math.round(getVisibleViewportHeight() * 0.35);
    const prevRatio = (window.scrollY + focusY) / prevScrollHeight;

    currentZoomPercent = clamped;
    document.documentElement.style.setProperty('--doc-zoom', String(clamped / 100));

    // Keep the reading line smoothly anchored near the pinch midpoint
    const newScrollHeight = Math.max(1, document.documentElement.scrollHeight);
    const nextScrollY = Math.max(0, Math.round(prevRatio * newScrollHeight - focusY));
    if (Math.abs(nextScrollY - window.scrollY) > 4) {
      window.scrollTo({ top: nextScrollY, left: 0, behavior: 'auto' });
    }
    if (virtualKbOpen) {
      updateVirtualCaretVisual(false);
    }
    notifyAndroid('onZoomChanged', currentZoomPercent);
    return currentZoomPercent;
  }

  function getTouchDistance(t1, t2) {
    const dx = t1.clientX - t2.clientX;
    const dy = t1.clientY - t2.clientY;
    return Math.sqrt(dx * dx + dy * dy);
  }

  document.addEventListener('touchstart', (e) => {
    if (e.touches && e.touches.length === 2) {
      isPinching = true;
      pinchStartDist = getTouchDistance(e.touches[0], e.touches[1]);
      pinchStartZoom = currentZoomPercent;
      const midY = (e.touches[0].clientY + e.touches[1].clientY) / 2;
      const sh = Math.max(1, document.documentElement.scrollHeight);
      pinchAnchorScrollRatio = (window.scrollY + midY) / sh;
    }
  }, { passive: true });

  document.addEventListener('touchmove', (e) => {
    if (isPinching && e.touches && e.touches.length === 2 && pinchStartDist > 12) {
      e.preventDefault();
      const curDist = getTouchDistance(e.touches[0], e.touches[1]);
      const ratio = curDist / pinchStartDist;
      const targetZoom = Math.max(50, Math.min(300, Math.round(pinchStartZoom * ratio)));
      if (Math.abs(targetZoom - currentZoomPercent) >= 2) {
        const midY = (e.touches[0].clientY + e.touches[1].clientY) / 2;
        currentZoomPercent = targetZoom;
        document.documentElement.style.setProperty('--doc-zoom', String(targetZoom / 100));
        const newSh = Math.max(1, document.documentElement.scrollHeight);
        const destY = Math.max(0, Math.round(pinchAnchorScrollRatio * newSh - midY));
        window.scrollTo({ top: destY, left: 0, behavior: 'auto' });
        if (virtualKbOpen) updateVirtualCaretVisual(false);
        notifyAndroid('onZoomChanged', currentZoomPercent);
      }
    }
  }, { passive: false });

  document.addEventListener('touchend', (e) => {
    if (isPinching && (!e.touches || e.touches.length < 2)) {
      isPinching = false;
      pinchStartDist = 0;
    }
  }, { passive: true });

  document.addEventListener('touchcancel', () => {
    isPinching = false;
    pinchStartDist = 0;
  }, { passive: true });

  function reportEditDelta(explicitDelta, isLargePaste) {
    const curLen = (paper.textContent || '').length;
    const delta = explicitDelta !== undefined
      ? Math.abs(explicitDelta)
      : Math.max(1, Math.abs(curLen - lastTrackedTextLen));
    lastTrackedTextLen = curLen;
    hasUserEdited = true;
    notifyAndroid('onEditDelta', delta, !!isLargePaste, curLen);
  }

  paper.addEventListener('paste', (e) => {
    if (isOriginalView || isExternalReadOnly) {
      e.preventDefault();
      return;
    }
    try {
      const cd = e.clipboardData || window.clipboardData;
      if (!cd) return;
      const plain = cd.getData('text/plain') || '';
      if (!plain) return;
      e.preventDefault();

      if (plain.length > maxPasteChars) {
        const limitKb = Math.round(maxPasteChars / 1000);
        notifyAndroid(
          'onEditorWarning',
          'Paste too large for device memory (' + Math.round(plain.length / 1000) +
            'K chars; max ' + limitKb + 'K chars). Please paste in smaller sections to avoid crashes.'
        );
        return;
      }

      if (plain.length >= 150000 && hasUserEdited) {
        flushDraftNow(true);
      }

      const preCaret = serializeCaretPosition();
      const normalized = plain.replace(/\r\n/g, '\n').replace(/\r/g, '\n');
      const lines = normalized.split('\n');

      if (lines.length === 1) {
        document.execCommand('insertText', false, lines[0]);
      } else {
        const fragHtml = lines.map((line) => {
          const escaped = line
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;');
          return '<p>' + (escaped || '<br>') + '</p>';
        }).join('');
        document.execCommand('insertHTML', false, fragHtml);
      }

      captureSelectionRange();
      commitHistoryStep(preCaret);
      notifyAndroid('onContentDirty');
      reportEditDelta(plain.length, plain.length >= 300);
      scheduleAutoSave();
      scrollCaretIntoSafeView(false);
    } catch (err) {
      notifyAndroid('onEditorWarning', 'Paste could not be processed cleanly: ' + String(err));
    }
  });

  paper.addEventListener('input', (e) => {
    if (isOriginalView || isExternalReadOnly || isRestoringHistory) return;
    captureSelectionRange();
    notifyAndroid('onContentDirty');
    reportEditDelta(undefined, (e && e.inputType === 'insertFromPaste'));
    scheduleAutoSave();
    if (!isComposing) {
      const iType = (e && e.inputType) || '';
      const data = (e && e.data) || '';
      const isBoundary =
        iType === 'insertParagraph' ||
        iType === 'insertLineBreak' ||
        iType === 'insertFromPaste' ||
        data === ' ';
      scheduleHistoryCommit(isBoundary);
      scrollCaretIntoSafeView(false);
    }
  });

  // =========================================================================
  // Normalized Cross-Span Find & Replace Engine
  // =========================================================================
  function normalizeSearchQuery(raw) {
    if (!raw) return '';
    return String(raw)
      .normalize('NFC')
      .replace(ZERO_WIDTH_RE, '')
      .replace(/[\u00A0\s]+/g, ' ')
      .trim();
  }

  function unwrapMarksIn(rootEl) {
    const marks = rootEl.querySelectorAll('mark.kd-search-match');
    for (let i = 0; i < marks.length; i++) {
      const mark = marks[i];
      const parent = mark.parentNode;
      if (!parent) continue;
      while (mark.firstChild) {
        parent.insertBefore(mark.firstChild, mark);
      }
      parent.removeChild(mark);
    }
    rootEl.normalize();
  }

  function clearSearchHighlights(notify) {
    unwrapMarksIn(paper);
    searchMatches = [];
    activeMatchIndex = -1;
    lastSearchQuery = '';
    if (notify) {
      notifyAndroid('onSearchResults', 0, 0);
    }
  }

  function setActiveMatch(index) {
    if (searchMatches.length === 0) {
      activeMatchIndex = -1;
      notifyAndroid('onSearchResults', 0, 0);
      return;
    }
    if (activeMatchIndex >= 0 && activeMatchIndex < searchMatches.length) {
      const prevGroup = searchMatches[activeMatchIndex];
      for (let i = 0; i < prevGroup.length; i++) {
        prevGroup[i].classList.remove('kd-search-active');
      }
    }
    const len = searchMatches.length;
    activeMatchIndex = ((index % len) + len) % len;
    const activeGroup = searchMatches[activeMatchIndex];
    for (let i = 0; i < activeGroup.length; i++) {
      activeGroup[i].classList.add('kd-search-active');
    }
    const primaryEl = activeGroup[0];
    if (primaryEl) {
      try {
        primaryEl.scrollIntoView({ behavior: 'smooth', block: 'center', inline: 'nearest' });
      } catch (_) {
        primaryEl.scrollIntoView();
      }
    }
    notifyAndroid('onSearchResults', activeMatchIndex + 1, len);
  }

  function collectTextNodes(rootEl) {
    const nodes = [];
    const walker = document.createTreeWalker(rootEl, NodeFilter.SHOW_TEXT, null);
    let node;
    while ((node = walker.nextNode())) {
      if (node.nodeValue && node.nodeValue.length > 0) {
        nodes.push(node);
      }
    }
    return nodes;
  }

  function buildNormalizedBlockMap(blockEl) {
    const textNodes = collectTextNodes(blockEl);
    let normChars = '';
    const map = [];
    let inSpace = false;

    for (let t = 0; t < textNodes.length; t++) {
      const node = textNodes[t];
      const val = node.nodeValue || '';
      for (let i = 0; i < val.length; i++) {
        const ch = val[i];
        if (ch === '\u200B' || ch === '\uFEFF' || ch === '\u00AD' || ch === '\u2060') {
          continue;
        }
        const isWs = ch === '\u00A0' || /\s/.test(ch);
        if (isWs) {
          if (!inSpace) {
            normChars += ' ';
            map.push({ node, start: i, end: i + 1 });
            inSpace = true;
          } else if (map.length > 0 && map[map.length - 1].node === node) {
            map[map.length - 1].end = i + 1;
          }
        } else {
          inSpace = false;
          normChars += ch.toLowerCase();
          map.push({ node, start: i, end: i + 1 });
        }
      }
    }
    return { normLower: normChars, map };
  }

  function findMatchesInBlock(blockEl, normNeedleLower) {
    const { normLower, map } = buildNormalizedBlockMap(blockEl);
    if (!normLower || !normNeedleLower) return [];
    const matches = [];
    const needleLen = normNeedleLower.length;
    let pos = normLower.indexOf(normNeedleLower, 0);
    while (pos !== -1) {
      const startEntry = map[pos];
      const endEntry = map[pos + needleLen - 1];
      if (startEntry && endEntry) {
        const segments = [];
        for (let k = pos; k < pos + needleLen; k++) {
          const entry = map[k];
          const lastSeg = segments[segments.length - 1];
          if (lastSeg && lastSeg.node === entry.node) {
            lastSeg.end = Math.max(lastSeg.end, entry.end);
          } else {
            segments.push({ node: entry.node, start: entry.start, end: entry.end });
          }
        }
        matches.push(segments);
      }
      pos = normLower.indexOf(normNeedleLower, pos + needleLen);
    }
    return matches;
  }

  function wrapAllBlockMatches(blockMatches) {
    const matchGroups = blockMatches.map(() => []);
    const nodeSlices = new Map();

    for (let m = 0; m < blockMatches.length; m++) {
      const segs = blockMatches[m];
      for (let s = 0; s < segs.length; s++) {
        const seg = segs[s];
        const node = seg.node;
        if (!node || !node.parentNode) continue;
        let list = nodeSlices.get(node);
        if (!list) {
          list = [];
          nodeSlices.set(node, list);
        }
        list.push({ matchIdx: m, start: seg.start, end: seg.end });
      }
    }

    nodeSlices.forEach((slices, node) => {
      if (!node || !node.parentNode) return;
      const text = node.nodeValue || '';
      slices.sort((a, b) => a.start - b.start);

      const frag = document.createDocumentFragment();
      let cursor = 0;
      for (let i = 0; i < slices.length; i++) {
        const sl = slices[i];
        const start = Math.max(cursor, Math.min(sl.start, text.length));
        const end = Math.max(start, Math.min(sl.end, text.length));
        if (end <= start) continue;

        if (start > cursor) {
          frag.appendChild(document.createTextNode(text.slice(cursor, start)));
        }
        const mark = document.createElement('mark');
        mark.className = 'kd-search-match';
        mark.textContent = text.slice(start, end);
        frag.appendChild(mark);
        matchGroups[sl.matchIdx].push(mark);
        cursor = end;
      }
      if (cursor < text.length) {
        frag.appendChild(document.createTextNode(text.slice(cursor)));
      }
      node.parentNode.replaceChild(frag, node);
    });

    return matchGroups;
  }

  function performSearch(rawQuery, preferredIndex) {
    unwrapMarksIn(paper);
    searchMatches = [];
    activeMatchIndex = -1;
    const normalizedQuery = normalizeSearchQuery(rawQuery);
    lastSearchQuery = normalizedQuery;

    if (!normalizedQuery) {
      notifyAndroid('onSearchResults', 0, 0);
      return;
    }

    const needleLower = normalizedQuery.toLowerCase();
    const blocks = getEditableBlocks();

    for (let b = 0; b < blocks.length; b++) {
      const blockMatches = findMatchesInBlock(blocks[b], needleLower);
      if (blockMatches.length === 0) continue;

      const blockGroups = wrapAllBlockMatches(blockMatches);
      for (let m = 0; m < blockGroups.length; m++) {
        if (blockGroups[m] && blockGroups[m].length > 0) {
          searchMatches.push(blockGroups[m]);
        }
      }
    }

    if (searchMatches.length > 0) {
      const targetIdx = typeof preferredIndex === 'number'
        ? Math.min(Math.max(preferredIndex, 0), searchMatches.length - 1)
        : 0;
      setActiveMatch(targetIdx);
    } else {
      notifyAndroid('onSearchResults', 0, 0);
    }
  }

  function getCleanPaperClone() {
    const clone = paper.cloneNode(true);
    unwrapMarksIn(clone);
    return clone;
  }

  // =========================================================================
  // Document Renderer
  // =========================================================================
  function renderRunElement(run, text, useOriginalFont) {
    const span = document.createElement('span');
    span.textContent = text;
    if (run.bold) span.style.fontWeight = '700';
    if (run.italic) span.style.fontStyle = 'italic';
    if (run.underline) span.style.textDecoration = 'underline';
    if (run.colorHex) span.style.color = run.colorHex;
    if (useOriginalFont && run.fontFamily) {
      span.style.fontFamily = `'${run.fontFamily}', serif`;
    }
    return span;
  }

  function renderParagraphElement(para, converterOverride, useOriginalFont, stats) {
    let tag = 'p';
    if (para.headingLevel === 1) tag = 'h1';
    else if (para.headingLevel === 2) tag = 'h2';
    else if (para.headingLevel === 3) tag = 'h3';

    const el = document.createElement(tag);
    if (para.alignment && para.alignment !== 'left') {
      el.style.textAlign = para.alignment;
    }

    const runs = para.runs || [];
    if (runs.length === 0) {
      el.appendChild(document.createElement('br'));
      return el;
    }

    for (const run of runs) {
      el.appendChild(renderRunElement(run, run.text || '', useOriginalFont));
    }
    return el;
  }

  function renderDocument(doc, converterOverride, useOriginalFont) {
    searchMatches = [];
    activeMatchIndex = -1;
    lastSearchQuery = '';
    paper.innerHTML = '';
    const stats = { convertedRuns: 0, labels: new Set() };
    const blocks = (doc && doc.blocks) || [];

    let activeListEl = null;
    let activeListType = null;

    function closeList() {
      activeListEl = null;
      activeListType = null;
    }

    for (const block of blocks) {
      if (block.type === 'table') {
        closeList();
        const table = document.createElement('table');
        const tbody = document.createElement('tbody');
        for (const row of (block.rows || [])) {
          const tr = document.createElement('tr');
          for (const cellParas of row) {
            const td = document.createElement('td');
            const paras = cellParas && cellParas.length ? cellParas : [{ runs: [] }];
            for (const p of paras) {
              td.appendChild(renderParagraphElement(p, converterOverride, useOriginalFont, stats));
            }
            tr.appendChild(td);
          }
          tbody.appendChild(tr);
        }
        table.appendChild(tbody);
        paper.appendChild(table);
      } else {
        if (block.listType === 'bullet' || block.listType === 'ordered') {
          if (activeListType !== block.listType) {
            activeListEl = document.createElement(block.listType === 'ordered' ? 'ol' : 'ul');
            activeListType = block.listType;
            paper.appendChild(activeListEl);
          }
          const li = document.createElement('li');
          const pEl = renderParagraphElement(block, converterOverride, useOriginalFont, stats);
          while (pEl.firstChild) li.appendChild(pEl.firstChild);
          activeListEl.appendChild(li);
        } else {
          closeList();
          paper.appendChild(renderParagraphElement(block, converterOverride, useOriginalFont, stats));
        }
      }
    }

    if (paper.childNodes.length === 0) {
      const emptyP = document.createElement('p');
      emptyP.appendChild(document.createElement('br'));
      paper.appendChild(emptyP);
    }

    return {
      convertedCount: stats.convertedRuns,
      detectedSummary: Array.from(stats.labels).join(', ')
    };
  }

  // =========================================================================
  // DOM -> Intermediate Document Model (IDM) Serializer
  // =========================================================================
  function collectRunsFromNode(node, inheritedStyle, outRuns, legacyFontName) {
    if (node.nodeType === Node.TEXT_NODE) {
      const raw = node.nodeValue || '';
      if (raw.length > 0) {
        // Always emit pure Modern Universal Kannada IDM; Kotlin KannadaTextConverter
        // performs all legacy PC script-splitting on export as the single source of truth.
        outRuns.push({
          text: raw,
          fontFamily: 'Noto Sans Kannada',
          bold: !!inheritedStyle.bold,
          italic: !!inheritedStyle.italic,
          underline: !!inheritedStyle.underline,
          fontSizePt: null,
          colorHex: inheritedStyle.colorHex || null
        });
      }
      return;
    }

    if (node.nodeType !== Node.ELEMENT_NODE) return;
    const tag = node.tagName.toUpperCase();
    if (tag === 'BR') {
      outRuns.push({
        text: '\n',
        fontFamily: legacyFontName ? 'Calibri' : 'Noto Sans Kannada',
        bold: false, italic: false, underline: false, fontSizePt: null, colorHex: null
      });
      return;
    }

    const nextStyle = Object.assign({}, inheritedStyle);
    if (tag === 'B' || tag === 'STRONG' || node.style.fontWeight === '700' || node.style.fontWeight === 'bold') {
      nextStyle.bold = true;
    }
    if (tag === 'I' || tag === 'EM' || node.style.fontStyle === 'italic') {
      nextStyle.italic = true;
    }
    if (tag === 'U' || (node.style.textDecoration && node.style.textDecoration.includes('underline'))) {
      nextStyle.underline = true;
    }

    for (const child of node.childNodes) {
      collectRunsFromNode(child, nextStyle, outRuns, legacyFontName);
    }
  }

  function extractParagraphFromElement(el, listType, legacyFontName) {
    const tag = el.tagName ? el.tagName.toUpperCase() : 'P';
    let headingLevel = 0;
    if (tag === 'H1') headingLevel = 1;
    else if (tag === 'H2') headingLevel = 2;
    else if (tag === 'H3') headingLevel = 3;

    const align = (el.style && el.style.textAlign) ? el.style.textAlign.toLowerCase() : 'left';
    const runs = [];
    collectRunsFromNode(el, { bold: headingLevel > 0 }, runs, legacyFontName);

    if (runs.length === 1 && runs[0].text === '\n') {
      runs.length = 0;
    }

    return {
      type: 'paragraph',
      runs,
      headingLevel,
      alignment: ['left', 'center', 'right', 'justify'].includes(align) ? align : 'left',
      listType: listType || null
    };
  }

  function getDirectTableRowElements(tableEl) {
    const trs = [];
    for (const ch of tableEl.children) {
      const t = ch.tagName.toUpperCase();
      if (t === 'TR') {
        trs.push(ch);
      } else if (t === 'THEAD' || t === 'TBODY' || t === 'TFOOT') {
        for (const rowEl of ch.children) {
          if (rowEl.tagName.toUpperCase() === 'TR') {
            trs.push(rowEl);
          }
        }
      }
    }
    return trs;
  }

  function getDirectRowCellElements(trEl) {
    const cells = [];
    for (const ch of trEl.children) {
      const t = ch.tagName.toUpperCase();
      if (t === 'TD' || t === 'TH') {
        cells.push(ch);
      }
    }
    return cells;
  }

  function flattenNestedTableToParagraphs(nestedTableEl, legacyFontName, depth) {
    if (depth > 16) return [];
    const out = [];
    const trs = getDirectTableRowElements(nestedTableEl);
    for (const tr of trs) {
      const cells = getDirectRowCellElements(tr);
      const nonEmptyCellParas = [];
      for (const cell of cells) {
        const paras = extractCellParagraphs(cell, legacyFontName, depth + 1)
          .filter(p => p.runs && p.runs.some(r => r.text && r.text.length > 0));
        if (paras.length > 0) nonEmptyCellParas.push(paras);
      }
      if (nonEmptyCellParas.length === 0) continue;

      if (nonEmptyCellParas.length > 1 && nonEmptyCellParas.every(ps => ps.length === 1)) {
        const combinedRuns = [];
        for (let i = 0; i < nonEmptyCellParas.length; i++) {
          if (i > 0) {
            combinedRuns.push({
              text: '  |  ',
              fontFamily: null, bold: false, italic: false, underline: false,
              fontSizePt: null, colorHex: null
            });
          }
          for (const r of nonEmptyCellParas[i][0].runs) combinedRuns.push(r);
        }
        out.push({
          type: 'paragraph',
          runs: combinedRuns,
          headingLevel: 0,
          alignment: nonEmptyCellParas[0][0].alignment || 'left',
          listType: null
        });
      } else {
        for (const paras of nonEmptyCellParas) {
          for (const p of paras) out.push(p);
        }
      }
    }
    return out;
  }

  function extractCellParagraphs(cellEl, legacyFontName, depth) {
    const cellParas = [];
    let inlineRuns = [];

    function flushInlineRuns() {
      if (inlineRuns.some(r => r.text && r.text.trim().length > 0)) {
        cellParas.push({
          type: 'paragraph',
          runs: inlineRuns,
          headingLevel: 0,
          alignment: 'left',
          listType: null
        });
      }
      inlineRuns = [];
    }

    const BLOCK_TAGS = new Set(['P', 'DIV', 'H1', 'H2', 'H3', 'H4', 'BLOCKQUOTE', 'PRE']);
    for (const node of cellEl.childNodes) {
      if (node.nodeType === Node.TEXT_NODE) {
        collectRunsFromNode(node, {}, inlineRuns, legacyFontName);
        continue;
      }
      if (node.nodeType !== Node.ELEMENT_NODE) continue;
      const t = node.tagName.toUpperCase();
      if (t === 'TABLE') {
        flushInlineRuns();
        const nestedParas = flattenNestedTableToParagraphs(node, legacyFontName, depth + 1);
        for (const np of nestedParas) cellParas.push(np);
      } else if (t === 'UL' || t === 'OL') {
        flushInlineRuns();
        for (const li of node.children) {
          if (li.tagName.toUpperCase() === 'LI') {
            cellParas.push(extractParagraphFromElement(li, null, legacyFontName));
          }
        }
      } else if (BLOCK_TAGS.has(t)) {
        flushInlineRuns();
        if (node.querySelector('table')) {
          const innerParas = extractCellParagraphs(node, legacyFontName, depth + 1);
          for (const ip of innerParas) cellParas.push(ip);
        } else {
          cellParas.push(extractParagraphFromElement(node, null, legacyFontName));
        }
      } else {
        collectRunsFromNode(node, {}, inlineRuns, legacyFontName);
      }
    }
    flushInlineRuns();

    if (cellParas.length === 0) {
      cellParas.push({
        type: 'paragraph',
        runs: [],
        headingLevel: 0,
        alignment: 'left',
        listType: null
      });
    }
    return cellParas;
  }

  function extractDirectTableRowsToIdm(tableEl, legacyFontName) {
    const rows = [];
    for (const tr of getDirectTableRowElements(tableEl)) {
      const cells = [];
      for (const cell of getDirectRowCellElements(tr)) {
        cells.push(extractCellParagraphs(cell, legacyFontName, 0));
      }
      if (cells.length > 0) rows.push(cells);
    }
    return rows;
  }

  function serializeRootToIdm(rootEl, legacyFontName) {
    const blocks = [];

    for (const child of rootEl.childNodes) {
      if (child.nodeType === Node.TEXT_NODE) {
        const text = (child.nodeValue || '').trim();
        if (text) {
          const runs = [];
          collectRunsFromNode(child, {}, runs, legacyFontName);
          blocks.push({
            type: 'paragraph',
            runs,
            headingLevel: 0, alignment: 'left', listType: null
          });
        }
        continue;
      }

      if (child.nodeType !== Node.ELEMENT_NODE) continue;
      const tag = child.tagName.toUpperCase();

      if (tag === 'TABLE') {
        const rows = extractDirectTableRowsToIdm(child, legacyFontName);
        if (rows.length > 0) {
          blocks.push({ type: 'table', rows });
        }
      } else if (tag === 'UL' || tag === 'OL') {
        const listType = tag === 'OL' ? 'ordered' : 'bullet';
        for (const li of child.children) {
          if (li.tagName.toUpperCase() === 'LI') {
            blocks.push(extractParagraphFromElement(li, listType, legacyFontName));
          }
        }
      } else {
        blocks.push(extractParagraphFromElement(child, null, legacyFontName));
      }
    }

    return {
      sourceUri: (sourceDoc && sourceDoc.sourceUri) || '',
      fileName: (sourceDoc && sourceDoc.fileName) || 'Untitled.docx',
      sourceFormat: (sourceDoc && sourceDoc.sourceFormat) || 'DOCX',
      blocks,
      warningMessage: null
    };
  }

  function scheduleAutoSave() {
    if (isOriginalView || isExternalReadOnly) return;
    if (autoSaveTimer) clearTimeout(autoSaveTimer);
    autoSaveTimer = setTimeout(() => {
      flushDraftNow(false);
    }, 2800);
  }

  function flushDraftNow(force) {
    if (isOriginalView || isExternalReadOnly) return;
    if (!force && !hasUserEdited) return;
    try {
      const cleanClone = getCleanPaperClone();
      const idmObj = serializeRootToIdm(cleanClone, null);
      const idmJson = JSON.stringify(idmObj);
      const charCount = (cleanClone.textContent || '').length;
      notifyAndroid('onCheckpointReady', idmJson, charCount);
    } catch (err) {
      notifyAndroid('onEditorWarning', 'Auto-recovery checkpoint error: ' + String(err));
    }
  }

  // =========================================================================
  // Public Android -> WebView Editor Controller API
  // =========================================================================
  global.KannadaDocEditor = {
    loadDocument(unicodeDocJson, originalDocJson, savedDraftHtml, convertedCount, detectedSummary) {
      try {
        sourceDoc = JSON.parse(unicodeDocJson);
        originalDoc = originalDocJson ? JSON.parse(originalDocJson) : sourceDoc;
        isOriginalView = false;
        hasUserEdited = false;
        savedCaretRange = null;
        currentZoomPercent = 100;
        document.documentElement.style.setProperty('--doc-zoom', '1');
        notifyAndroid('onZoomChanged', 100);
        originalBanner.classList.add('hidden');
        paper.classList.remove('read-only-original');
        paper.setAttribute('contenteditable', isExternalReadOnly ? 'false' : 'true');
        applyInputModeState();

        if (savedDraftHtml && savedDraftHtml.trim().length > 0) {
          paper.innerHTML = savedDraftHtml;
          unwrapMarksIn(paper);
          resetHistoryToCurrent();
          lastTrackedTextLen = (paper.textContent || '').length;
          notifyAndroid('onDocumentLoaded', 0, '', true);
          return;
        }

        renderDocument(sourceDoc, 'none', false);
        resetHistoryToCurrent();
        lastTrackedTextLen = (paper.textContent || '').length;
        notifyAndroid('onDocumentLoaded', convertedCount || 0, detectedSummary || '', false);
      } catch (err) {
        notifyAndroid('onEditorError', String(err));
      }
    },

    setOriginalView(enabled) {
      if (!sourceDoc) return;
      clearSearchHighlights(true);
      if (enabled && !isOriginalView) {
        savedEditableHtml = getCleanPaperClone().innerHTML;
        isOriginalView = true;
        hideVirtualCaret();
        originalBanner.classList.remove('hidden');
        paper.classList.add('read-only-original');
        paper.setAttribute('contenteditable', 'false');
        renderDocument(originalDoc || sourceDoc, 'none', true);
      } else if (!enabled && isOriginalView) {
        isOriginalView = false;
        originalBanner.classList.add('hidden');
        paper.classList.remove('read-only-original');
        paper.setAttribute('contenteditable', 'true');
        applyInputModeState();
        if (savedEditableHtml !== null) {
          paper.innerHTML = savedEditableHtml;
        } else {
          renderDocument(sourceDoc, 'none', false);
        }
      }
    },

    setFontSize(sizePt) {
      document.documentElement.style.setProperty('--editor-font-size', `${sizePt}pt`);
      if (virtualKbOpen) updateVirtualCaretVisual();
    },

    setZoomPercent(percent) {
      applyDocumentZoom(Number(percent) || 100);
    },

    adjustZoomPercent(delta) {
      applyDocumentZoom(currentZoomPercent + (Number(delta) || 0));
    },

    setDarkTheme(enabled) {
      document.body.classList.toggle('dark-theme', !!enabled);
    },

    setManualKeyboardBlock(blocked) {
      manualKeyboardBlock = !!blocked;
      if (manualKeyboardBlock) {
        osKeyboardAllowedOnce = false;
      }
      applyInputModeState();
    },

    focusForSystemKeyboard() {
      if (isOriginalView) return;
      virtualKbOpen = false;
      osKeyboardAllowedOnce = true;
      document.body.classList.remove('virtual-kb-open');
      document.body.classList.remove('tablet-kb-open');
      hideVirtualCaret();
      paper.removeAttribute('inputmode');
      restoreCaretForExternalAction();
      setTimeout(() => scrollCaretIntoSafeView(false), 220);
    },

    dismissSystemKeyboard() {
      osKeyboardAllowedOnce = false;
      applyInputModeState();
      captureSelectionRange();
      paper.blur();
    },

    // --- Find & Replace API ---
    findText(query) {
      performSearch(query, 0);
    },

    findNext() {
      if (searchMatches.length === 0) return;
      setActiveMatch(activeMatchIndex + 1);
    },

    findPrev() {
      if (searchMatches.length === 0) return;
      setActiveMatch(activeMatchIndex - 1);
    },

    replaceCurrent(replacement) {
      if (isOriginalView) return;
      if (searchMatches.length === 0 || activeMatchIndex < 0 || activeMatchIndex >= searchMatches.length) {
        notifyAndroid('onReplaceDone', 0);
        return;
      }
      const group = searchMatches[activeMatchIndex];
      const repText = replacement == null ? '' : String(replacement);
      const nextIdx = activeMatchIndex;

      let preCaret = null;
      if (group && group.length > 0) {
        const blocks = getEditableBlocks();
        const found = findContainingBlock(group[0], blocks);
        if (found) preCaret = { blockIndex: found.index, charOffset: 0 };

        const firstMark = group[0];
        if (firstMark.parentNode) {
          const repNode = document.createTextNode(repText);
          firstMark.parentNode.replaceChild(repNode, firstMark);
          const r = document.createRange();
          r.setStartAfter(repNode);
          r.collapse(true);
          savedCaretRange = r;
        }
        for (let i = 1; i < group.length; i++) {
          if (group[i].parentNode) {
            group[i].parentNode.removeChild(group[i]);
          }
        }
      }

      const queryToKeep = lastSearchQuery;
      unwrapMarksIn(paper);
      commitHistoryStep(preCaret);
      notifyAndroid('onContentDirty');
      reportEditDelta(undefined, false);
      scheduleAutoSave();
      if (queryToKeep) {
        performSearch(queryToKeep, nextIdx);
      }
      notifyAndroid('onReplaceDone', 1);
    },

    replaceAll(rawQuery, replacement) {
      const normalizedQuery = normalizeSearchQuery(rawQuery);
      if (isOriginalView || !normalizedQuery) {
        notifyAndroid('onReplaceDone', 0);
        return;
      }
      unwrapMarksIn(paper);
      const needleLower = normalizedQuery.toLowerCase();
      const repText = replacement == null ? '' : String(replacement);
      const blocks = getEditableBlocks();
      let count = 0;
      let firstChangedBlockIdx = -1;

      for (let b = 0; b < blocks.length; b++) {
        const blockMatches = findMatchesInBlock(blocks[b], needleLower);
        if (blockMatches.length === 0) continue;
        if (firstChangedBlockIdx === -1) firstChangedBlockIdx = b;

        const blockGroups = wrapAllBlockMatches(blockMatches);
        for (let m = 0; m < blockGroups.length; m++) {
          const marks = blockGroups[m];
          if (marks.length > 0) {
            if (marks[0].parentNode) {
              marks[0].parentNode.replaceChild(document.createTextNode(repText), marks[0]);
            }
            for (let k = 1; k < marks.length; k++) {
              if (marks[k].parentNode) {
                marks[k].parentNode.removeChild(marks[k]);
              }
            }
            count++;
          }
        }
      }

      paper.normalize();
      searchMatches = [];
      activeMatchIndex = -1;
      lastSearchQuery = '';
      if (count > 0) {
        const preCaret = firstChangedBlockIdx >= 0
          ? { blockIndex: firstChangedBlockIdx, charOffset: 0 }
          : null;
        commitHistoryStep(preCaret);
        notifyAndroid('onContentDirty');
        scheduleAutoSave();
      }
      notifyAndroid('onSearchResults', 0, 0);
      notifyAndroid('onReplaceDone', count);
    },

    clearSearch() {
      clearSearchHighlights(true);
    },

    // --- Floating Kannada Varnamala Keyboard API ---
    setVirtualKeyboardMode(enabled, isTabletSideMode, topLimitPx) {
      virtualKbOpen = !!enabled;
      isTabletLayout = !!isTabletSideMode;
      if (virtualKbOpen) {
        osKeyboardAllowedOnce = false;
        document.body.classList.add('virtual-kb-open');
        document.body.classList.toggle('tablet-kb-open', isTabletLayout);
        keyboardTopLimitPx = (typeof topLimitPx === 'number' && topLimitPx > 0) ? topLimitPx : 0;
        applyInputModeState();
        captureSelectionRange();
        startCaretSyncLoop();
        requestAnimationFrame(() => {
          scrollCaretIntoSafeView(true);
          updateVirtualCaretVisual(true);
        });
        setTimeout(() => {
          scrollCaretIntoSafeView(false);
          updateVirtualCaretVisual(true);
        }, 200);
      } else {
        document.body.classList.remove('virtual-kb-open');
        document.body.classList.remove('tablet-kb-open');
        keyboardTopLimitPx = 0;
        hideVirtualCaret();
        applyInputModeState();
      }
    },

    updateKeyboardTopLimit(topLimitPx) {
      keyboardTopLimitPx = (typeof topLimitPx === 'number' && topLimitPx > 0) ? topLimitPx : 0;
      if (virtualKbOpen) {
        scrollCaretIntoSafeView(false);
      }
    },

    scrollCursorToTop() {
      scrollCaretIntoSafeView(true);
    },

    insertTextAtCursor(text, isSubscriptModifier) {
      if (isOriginalView || !text) return;
      const range = restoreCaretForExternalAction();
      if (!range) return;

      const preCaret = serializeCaretPosition();
      const str = String(text);
      let insertedBeforeMatra = false;

      if (isSubscriptModifier && range.collapsed && range.startContainer.nodeType === Node.TEXT_NODE) {
        const textNode = range.startContainer;
        const offset = range.startOffset;
        if (offset >= 2) {
          const prevChar = textNode.nodeValue.slice(offset - 1, offset);
          const beforePrev = textNode.nodeValue.slice(offset - 2, offset - 1);
          const beforeCode = beforePrev.charCodeAt(0);
          const isPrevConsonant = (beforeCode >= 0x0C95 && beforeCode <= 0x0CB9) || beforeCode === 0x0CB1 || beforeCode === 0x0CDE;
          if (isPrevConsonant && KANNADA_TRAILING_SIGN_RE.test(prevChar)) {
            const cur = textNode.nodeValue;
            textNode.nodeValue = cur.slice(0, offset - 1) + str + cur.slice(offset - 1);
            const newOff = offset + str.length;
            const sel = window.getSelection();
            const afterMatra = document.createRange();
            afterMatra.setStart(textNode, Math.min(newOff, textNode.nodeValue.length));
            afterMatra.collapse(true);
            if (sel) {
              sel.removeAllRanges();
              sel.addRange(afterMatra);
            }
            savedCaretRange = afterMatra.cloneRange();
            insertedBeforeMatra = true;
          }
        }
      }

      if (!insertedBeforeMatra) {
        // Direct deterministic Range insertion guarantees caret ALWAYS advances forward to the RIGHT!
        range.deleteContents();
        if (range.startContainer.nodeType === Node.TEXT_NODE) {
          const textNode = range.startContainer;
          const off = range.startOffset;
          const val = textNode.nodeValue || '';
          textNode.nodeValue = val.slice(0, off) + str + val.slice(off);
          const nextRange = document.createRange();
          nextRange.setStart(textNode, off + str.length);
          nextRange.collapse(true);
          const sel = window.getSelection();
          if (sel) {
            sel.removeAllRanges();
            sel.addRange(nextRange);
          }
          savedCaretRange = nextRange.cloneRange();
        } else {
          const tNode = document.createTextNode(str);
          range.insertNode(tNode);
          const nextRange = document.createRange();
          nextRange.setStart(tNode, str.length);
          nextRange.collapse(true);
          const sel = window.getSelection();
          if (sel) {
            sel.removeAllRanges();
            sel.addRange(nextRange);
          }
          savedCaretRange = nextRange.cloneRange();
        }
      }

      commitHistoryStep(preCaret);
      scrollCaretIntoSafeView(false);
      updateVirtualCaretVisual(true);
      notifyAndroid('onContentDirty');
      reportEditDelta(undefined, false);
      scheduleAutoSave();
    },

    deleteBackAtCursor() {
      if (isOriginalView) return;
      restoreCaretForExternalAction();
      const preCaret = serializeCaretPosition();
      document.execCommand('delete', false, null);
      captureSelectionRange();
      commitHistoryStep(preCaret);
      scrollCaretIntoSafeView(false);
      updateVirtualCaretVisual(true);
      notifyAndroid('onContentDirty');
      reportEditDelta(undefined, false);
      scheduleAutoSave();
    },

    insertNewLineAtCursor() {
      if (isOriginalView) return;
      restoreCaretForExternalAction();
      const preCaret = serializeCaretPosition();
      const ok = document.execCommand('insertParagraph', false, null);
      if (!ok) {
        document.execCommand('insertLineBreak', false, null);
      }
      captureSelectionRange();
      commitHistoryStep(preCaret);
      scrollCaretIntoSafeView(false);
      updateVirtualCaretVisual(true);
      notifyAndroid('onContentDirty');
      reportEditDelta(undefined, false);
      scheduleAutoSave();
    },

    execFormat(command) {
      if (isOriginalView) return;
      if (command === 'undo') {
        performUndo();
        return;
      }
      if (command === 'redo') {
        performRedo();
        return;
      }
      paper.focus();
      const preCaret = serializeCaretPosition();
      switch (command) {
        case 'bold': document.execCommand('bold'); break;
        case 'italic': document.execCommand('italic'); break;
        case 'underline': document.execCommand('underline'); break;
        case 'heading1': document.execCommand('formatBlock', false, 'H1'); break;
        case 'heading2': document.execCommand('formatBlock', false, 'H2'); break;
        case 'paragraph': document.execCommand('formatBlock', false, 'P'); break;
        case 'bulletList': document.execCommand('insertUnorderedList'); break;
        case 'orderedList': document.execCommand('insertOrderedList'); break;
        case 'alignLeft': document.execCommand('justifyLeft'); break;
        case 'alignCenter': document.execCommand('justifyCenter'); break;
        case 'alignRight': document.execCommand('justifyRight'); break;
      }
      captureSelectionRange();
      commitHistoryStep(preCaret);
      notifyAndroid('onContentDirty');
      reportEditDelta(undefined, false);
      scheduleAutoSave();
    },

    setExternalReadOnly(enabled) {
      isExternalReadOnly = !!enabled;
      if (!isOriginalView) {
        paper.setAttribute('contenteditable', isExternalReadOnly ? 'false' : 'true');
        if (isExternalReadOnly) {
          hideVirtualCaret();
          paper.blur();
        }
        applyInputModeState();
      }
    },

    setMaxPasteChars(limit) {
      const n = Number(limit);
      if (Number.isFinite(n) && n >= 50000) maxPasteChars = Math.round(n);
    },

    markSavedClean() {
      hasUserEdited = false;
      lastTrackedTextLen = (paper.textContent || '').length;
    },

    trimHistoryForMemoryPressure() {
      redoStack = [];
      if (undoStack.length > 5) undoStack = undoStack.slice(undoStack.length - 5);
    },

    loadDocumentFromUrl(payloadUrl, convertedCount, detectedSummary, restoredFromRecovery) {
      fetch(String(payloadUrl), { cache: 'no-store' })
        .then((resp) => {
          if (!resp.ok) throw new Error('HTTP ' + resp.status);
          return resp.json();
        })
        .then((payload) => {
          const uJson = JSON.stringify(payload.unicodeDoc);
          const oJson = payload.originalDoc ? JSON.stringify(payload.originalDoc) : null;
          global.KannadaDocEditor.loadDocument(uJson, oJson, '', convertedCount || 0, detectedSummary || '');
          if (restoredFromRecovery) {
            hasUserEdited = true;
            notifyAndroid('onContentDirty');
          }
        })
        .catch((err) => {
          notifyAndroid('onEditorError', 'Failed to stream large document: ' + String(err));
        });
    },

    flushDraft(force) {
      flushDraftNow(!!force);
    },

    requestExport(exportMode, isAutoSave) {
      try {
        const mode = String(exportMode || '');
        const cleanClone = getCleanPaperClone();
        const idmObj = serializeRootToIdm(cleanClone, null);
        const idmJson = JSON.stringify(idmObj);
        const charCount = (cleanClone.textContent || '').length;
        notifyAndroid('onExportReady', mode, idmJson, !!isAutoSave, charCount);
      } catch (err) {
        notifyAndroid('onEditorError', 'Unable to prepare document for saving: ' + String(err));
      }
    }
  };

  notifyAndroid('onEditorReady');
})(window);
