/**
 * Kadata Offline Rich-Text Editor Controller & AndroidBridge (v13)
 * - Normalized cross-span Find & Replace (handles pasted whitespace, NBSP, zero-width chars,
 *   and multi-span DOCX runs cleanly)
 * - Seamless cursor visibility on both OS Keyboard (visualViewport) & built-in Kannada Keyboard
 * - Optional app-wide Manual Keyboard Block mode + explicit OS keyboard trigger
 * - Script-split legacy PC export (Nudi 01 e / BRH Kannada + Calibri for English)
 */
(function (global) {
  'use strict';

  const paper = document.getElementById('paper');
  const originalBanner = document.getElementById('original-banner');

  let sourceDoc = null;
  let activeConverter = 'auto';
  let isOriginalView = false;
  let savedEditableHtml = null;
  let autoSaveTimer = null;

  // Find & Replace state: each entry in searchMatches is an Array of <mark> elements
  // (a single logical match may span across multiple inline <span> nodes)
  let searchMatches = [];
  let activeMatchIndex = -1;
  let lastSearchQuery = '';

  // Caret & Keyboard state
  let savedCaretRange = null;
  let keyboardTopLimitPx = 0;
  let virtualKbOpen = false;
  let manualKeyboardBlock = false;
  let osKeyboardAllowedOnce = false;
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

  function captureSelectionRange() {
    const sel = window.getSelection();
    if (!sel || sel.rangeCount === 0) return;
    const range = sel.getRangeAt(0);
    if (isNodeInsidePaper(range.startContainer) && isNodeInsidePaper(range.endContainer)) {
      savedCaretRange = range.cloneRange();
    }
  }

  function placeCaretAtVisibleLine() {
    const sel = window.getSelection();
    if (!sel) return null;

    // Pick the first visible content block currently on screen instead of jumping to the end of the doc
    const viewH = getVisibleViewportHeight();
    const candidates = Array.from(paper.querySelectorAll('p, h1, h2, h3, li, td'));
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

  function restoreCaretInPaper() {
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
    return placeCaretAtVisibleLine();
  }

  /**
   * Accurately measures the caret's viewport rect — NEVER falls back to #paper itself
   * (whose height includes 2400px of bottom scroll padding and caused overshoot jumps).
   */
  function measureCaretClientRect(range) {
    if (!range) return null;
    const rects = range.getClientRects();
    if (rects && rects.length > 0) {
      const r = rects[rects.length - 1];
      if (r.height > 0 && r.height < 220) return r;
    }
    const bRect = range.getBoundingClientRect();
    if (bRect && bRect.height > 0 && bRect.height < 220) return bRect;

    // Collapsed caret on an empty line or between nodes: measure nearest block element (not #paper)
    let el = range.startContainer.nodeType === Node.ELEMENT_NODE
      ? range.startContainer
      : range.startContainer.parentElement;
    if (el && el !== paper) {
      const elRect = el.getBoundingClientRect();
      if (elRect && elRect.height > 0 && elRect.height < 320) return elRect;
    }

    // Last-resort exact measurement using a transient zero-width marker span
    try {
      const marker = document.createElement('span');
      marker.textContent = '\u200B';
      const clone = range.cloneRange();
      clone.collapse(true);
      clone.insertNode(marker);
      const mRect = marker.getBoundingClientRect();
      const parent = marker.parentNode;
      if (parent) {
        parent.removeChild(marker);
        parent.normalize();
      }
      if (mRect && (mRect.height > 0 || mRect.top !== 0)) return mRect;
    } catch (_) {
      // Ignore marker failure
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

  /**
   * Idempotent, clamped absolute scroll that puts the caret cleanly into view
   * without ever overshooting into the blank 2400px bottom cushion.
   */
  function scrollCursorToSafePosition(preferNearTop) {
    try {
      const range = restoreCaretInPaper();
      const rect = measureCaretClientRect(range);
      if (!rect) return;

      const viewHeight = getVisibleViewportHeight();
      const caretPageTop = window.scrollY + rect.top;
      const contentBottom = getRealContentBottomPageY();
      // Never scroll further down than keeping at least ~80px of real document content visible
      const maxReasonableScroll = Math.max(0, contentBottom - Math.min(80, Math.round(viewHeight * 0.25)));

      let targetScrollY = null;
      if (virtualKbOpen) {
        const safeBottom = (keyboardTopLimitPx > 80)
          ? Math.min(viewHeight - 24, keyboardTopLimitPx - 16)
          : Math.max(96, viewHeight - 28);
        if (preferNearTop || rect.top < 12 || rect.bottom > safeBottom) {
          // Place active line cleanly near the top (24px margin) of the visible pane
          targetScrollY = caretPageTop - 24;
        }
      } else {
        const safeBottom = Math.max(90, viewHeight - 52);
        if (rect.top < 16 || rect.bottom > safeBottom) {
          targetScrollY = caretPageTop - Math.min(110, Math.round(viewHeight * 0.30));
        }
      }

      if (targetScrollY !== null) {
        const clampedY = Math.max(0, Math.min(targetScrollY, maxReasonableScroll));
        if (Math.abs(clampedY - window.scrollY) > 6) {
          window.scrollTo({ top: clampedY, left: 0, behavior: 'auto' });
        }
      }
    } catch (_) {
      // Ignore scrolling errors
    }
  }

  function scrollCursorLineToTop() {
    scrollCursorToSafePosition(true);
  }

  function scrollCaretIntoView(forceCenterIfHidden) {
    scrollCursorToSafePosition(!!forceCenterIfHidden);
  }

  document.addEventListener('selectionchange', captureSelectionRange);
  paper.addEventListener('keyup', () => {
    captureSelectionRange();
    scrollCaretIntoView(false);
  });
  paper.addEventListener('mouseup', captureSelectionRange);
  paper.addEventListener('touchend', () => {
    captureSelectionRange();
    if (!manualKeyboardBlock && !virtualKbOpen) {
      setTimeout(() => scrollCaretIntoView(true), 220);
    }
  });
  paper.addEventListener('focus', () => {
    captureSelectionRange();
    setTimeout(() => scrollCaretIntoView(true), 180);
  });

  if (window.visualViewport) {
    window.visualViewport.addEventListener('resize', () => {
      setTimeout(() => scrollCaretIntoView(true), 60);
    });
  }
  window.addEventListener('resize', () => {
    setTimeout(() => scrollCaretIntoView(true), 80);
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

  function collectSearchBlocks() {
    const blocks = Array.from(paper.querySelectorAll('p, h1, h2, h3, h4, li, td, th'));
    // Filter out container elements that wrap inner <p> blocks (like <td> containing <p>)
    const leafBlocks = blocks.filter(el => !el.querySelector('p, h1, h2, h3, h4, li'));
    return leafBlocks.length > 0 ? leafBlocks : [paper];
  }

  /**
   * Builds a normalized string for a block where runs of whitespace/NBSP collapse to ' '
   * and zero-width characters are ignored, while keeping exact (node, startOffset, endOffset)
   * coordinates for every normalized character.
   */
  function buildNormalizedBlockMap(blockEl) {
    const textNodes = collectTextNodes(blockEl);
    let normChars = '';
    const map = []; // Each entry: { node, start, end }
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
        // Group contiguous segments per text node for this logical match
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

  function wrapMatchSegments(segments) {
    const createdMarks = [];
    // Process segments in reverse order if multiple segments share a node (safety),
    // though each logical match has distinct nodes per segment.
    for (let s = 0; s < segments.length; s++) {
      const seg = segments[s];
      const node = seg.node;
      if (!node || !node.parentNode) continue;
      const text = node.nodeValue || '';
      const start = Math.max(0, Math.min(seg.start, text.length));
      const end = Math.max(start, Math.min(seg.end, text.length));
      if (end <= start) continue;

      const frag = document.createDocumentFragment();
      if (start > 0) frag.appendChild(document.createTextNode(text.slice(0, start)));
      const mark = document.createElement('mark');
      mark.className = 'kd-search-match';
      mark.textContent = text.slice(start, end);
      frag.appendChild(mark);
      if (end < text.length) frag.appendChild(document.createTextNode(text.slice(end)));

      node.parentNode.replaceChild(frag, node);
      createdMarks.push(mark);
    }
    return createdMarks;
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
    const blocks = collectSearchBlocks();

    for (let b = 0; b < blocks.length; b++) {
      const blockMatches = findMatchesInBlock(blocks[b], needleLower);
      if (blockMatches.length === 0) continue;

      // Wrap in reverse order within the block so earlier textNode offsets stay valid!
      const blockGroups = new Array(blockMatches.length);
      for (let m = blockMatches.length - 1; m >= 0; m--) {
        blockGroups[m] = wrapMatchSegments(blockMatches[m]);
      }
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
      if (useOriginalFont) {
        el.appendChild(renderRunElement(run, run.text || '', true));
      } else {
        const res = global.KannadaConverter.convertRun(run, converterOverride);
        if (res.converted) {
          stats.convertedRuns++;
          if (res.detectedLabel) stats.labels.add(res.detectedLabel);
        }
        el.appendChild(renderRunElement(run, res.text, false));
      }
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
        if (legacyFontName) {
          const splitSegments = global.KannadaConverter.splitRunsForLegacyExport(raw, legacyFontName);
          for (const seg of splitSegments) {
            outRuns.push({
              text: seg.text,
              fontFamily: seg.fontFamily,
              bold: !!inheritedStyle.bold,
              italic: !!inheritedStyle.italic,
              underline: !!inheritedStyle.underline,
              fontSizePt: null,
              colorHex: inheritedStyle.colorHex || null
            });
          }
        } else {
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
        const rows = [];
        for (const tr of child.querySelectorAll('tr')) {
          const cells = [];
          for (const cell of tr.querySelectorAll('td, th')) {
            const cellParas = [];
            const pChildren = Array.from(cell.children).filter(c => ['P', 'DIV', 'H1', 'H2', 'H3'].includes(c.tagName.toUpperCase()));
            if (pChildren.length > 0) {
              for (const pc of pChildren) {
                cellParas.push(extractParagraphFromElement(pc, null, legacyFontName));
              }
            } else {
              cellParas.push(extractParagraphFromElement(cell, null, legacyFontName));
            }
            cells.push(cellParas);
          }
          if (cells.length > 0) rows.push(cells);
        }
        blocks.push({ type: 'table', rows });
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
    if (isOriginalView) return;
    if (autoSaveTimer) clearTimeout(autoSaveTimer);
    autoSaveTimer = setTimeout(() => {
      flushDraftNow();
    }, 1800);
  }

  function flushDraftNow() {
    if (isOriginalView) return;
    const cleanClone = getCleanPaperClone();
    const html = cleanClone.innerHTML;
    const idmJson = JSON.stringify(serializeRootToIdm(cleanClone, null));
    notifyAndroid('onDraftChanged', html, idmJson);
  }

  function markDirtyAndScheduleDraft() {
    if (isOriginalView) return;
    notifyAndroid('onContentDirty');
    scheduleAutoSave();
    scrollCaretIntoView(false);
  }

  paper.addEventListener('input', markDirtyAndScheduleDraft);

  global.KannadaDocEditor = {
    loadDocument(parsedDocJson, converterOverride, savedDraftHtml) {
      try {
        sourceDoc = JSON.parse(parsedDocJson);
        activeConverter = converterOverride || 'auto';
        isOriginalView = false;
        originalBanner.classList.add('hidden');
        paper.classList.remove('read-only-original');
        paper.setAttribute('contenteditable', 'true');
        applyInputModeState();

        if (savedDraftHtml && savedDraftHtml.trim().length > 0) {
          paper.innerHTML = savedDraftHtml;
          unwrapMarksIn(paper);
          notifyAndroid('onDocumentLoaded', 0, '', true);
          return;
        }

        const report = renderDocument(sourceDoc, activeConverter, false);
        notifyAndroid('onDocumentLoaded', report.convertedCount, report.detectedSummary, false);
      } catch (err) {
        notifyAndroid('onEditorError', String(err));
      }
    },

    switchConverter(converterId) {
      if (!sourceDoc) return;
      activeConverter = converterId || 'auto';
      isOriginalView = false;
      originalBanner.classList.add('hidden');
      paper.classList.remove('read-only-original');
      paper.setAttribute('contenteditable', 'true');
      const report = renderDocument(sourceDoc, activeConverter, false);
      flushDraftNow();
      notifyAndroid('onDocumentLoaded', report.convertedCount, report.detectedSummary, false);
    },

    setOriginalView(enabled) {
      if (!sourceDoc) return;
      clearSearchHighlights(true);
      if (enabled && !isOriginalView) {
        savedEditableHtml = getCleanPaperClone().innerHTML;
        isOriginalView = true;
        originalBanner.classList.remove('hidden');
        paper.classList.add('read-only-original');
        paper.setAttribute('contenteditable', 'false');
        renderDocument(sourceDoc, 'none', true);
      } else if (!enabled && isOriginalView) {
        isOriginalView = false;
        originalBanner.classList.add('hidden');
        paper.classList.remove('read-only-original');
        paper.setAttribute('contenteditable', 'true');
        applyInputModeState();
        if (savedEditableHtml !== null) {
          paper.innerHTML = savedEditableHtml;
        } else {
          renderDocument(sourceDoc, activeConverter, false);
        }
      }
    },

    setFontSize(sizePt) {
      document.documentElement.style.setProperty('--editor-font-size', `${sizePt}pt`);
    },

    setDarkTheme(enabled) {
      document.body.classList.toggle('dark-theme', !!enabled);
    },

    // --- Manual Keyboard Block & Explicit System Keyboard Trigger ---
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
      paper.removeAttribute('inputmode');
      restoreCaretInPaper();
      setTimeout(() => scrollCursorToSafePosition(false), 220);
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

      if (group && group.length > 0) {
        const firstMark = group[0];
        if (firstMark.parentNode) {
          firstMark.parentNode.replaceChild(document.createTextNode(repText), firstMark);
        }
        for (let i = 1; i < group.length; i++) {
          if (group[i].parentNode) {
            group[i].parentNode.removeChild(group[i]);
          }
        }
      }

      const queryToKeep = lastSearchQuery;
      unwrapMarksIn(paper);
      markDirtyAndScheduleDraft();
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
      const blocks = collectSearchBlocks();
      let count = 0;

      for (let b = 0; b < blocks.length; b++) {
        const blockMatches = findMatchesInBlock(blocks[b], needleLower);
        if (blockMatches.length === 0) continue;

        // Replace in reverse order so earlier node offsets remain valid
        for (let m = blockMatches.length - 1; m >= 0; m--) {
          const segs = blockMatches[m];
          const marks = wrapMatchSegments(segs);
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
        markDirtyAndScheduleDraft();
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
      if (virtualKbOpen) {
        osKeyboardAllowedOnce = false;
        document.body.classList.add('virtual-kb-open');
        document.body.classList.toggle('tablet-kb-open', !!isTabletSideMode);
        keyboardTopLimitPx = (typeof topLimitPx === 'number' && topLimitPx > 0) ? topLimitPx : 0;
        applyInputModeState();
        requestAnimationFrame(() => scrollCursorToSafePosition(true));
        setTimeout(() => scrollCursorToSafePosition(true), 180);
      } else {
        document.body.classList.remove('virtual-kb-open');
        document.body.classList.remove('tablet-kb-open');
        keyboardTopLimitPx = 0;
        applyInputModeState();
      }
    },

    updateKeyboardTopLimit(topLimitPx) {
      keyboardTopLimitPx = (typeof topLimitPx === 'number' && topLimitPx > 0) ? topLimitPx : 0;
      if (virtualKbOpen) {
        scrollCursorToSafePosition(false);
      }
    },

    scrollCursorToTop() {
      scrollCursorLineToTop(true);
    },

    insertTextAtCursor(text, isSubscriptModifier) {
      if (isOriginalView || !text) return;
      const range = restoreCaretInPaper();
      if (!range) return;

      const str = String(text);
      let insertedBeforeMatra = false;

      if (isSubscriptModifier && range.collapsed && range.startContainer.nodeType === Node.TEXT_NODE) {
        const textNode = range.startContainer;
        const offset = range.startOffset;
        if (offset >= 2) {
          const prevChar = textNode.nodeValue.slice(offset - 1, offset);
          const beforePrev = textNode.nodeValue.slice(offset - 2, offset - 1);
          const beforeCode = beforePrev.charCodeAt(0);
          const isPrevConsonant = beforeCode >= 0x0C95 && beforeCode <= 0x0CB9;
          if (isPrevConsonant && KANNADA_TRAILING_SIGN_RE.test(prevChar)) {
            const sel = window.getSelection();
            const preMatraRange = document.createRange();
            preMatraRange.setStart(textNode, offset - 1);
            preMatraRange.setEnd(textNode, offset - 1);
            sel.removeAllRanges();
            sel.addRange(preMatraRange);

            const ok = document.execCommand('insertText', false, str);
            if (!ok) {
              const cur = textNode.nodeValue;
              textNode.nodeValue = cur.slice(0, offset - 1) + str + cur.slice(offset - 1);
            }
            const curSel = window.getSelection();
            if (curSel && curSel.rangeCount > 0) {
              const r2 = curSel.getRangeAt(0);
              if (r2.startContainer.nodeType === Node.TEXT_NODE) {
                const newOff = Math.min(r2.startOffset + 1, r2.startContainer.nodeValue.length);
                const afterMatra = document.createRange();
                afterMatra.setStart(r2.startContainer, newOff);
                afterMatra.setEnd(r2.startContainer, newOff);
                curSel.removeAllRanges();
                curSel.addRange(afterMatra);
              }
            }
            insertedBeforeMatra = true;
          }
        }
      }

      if (!insertedBeforeMatra) {
        const ok = document.execCommand('insertText', false, str);
        if (!ok) {
          range.deleteContents();
          const tNode = document.createTextNode(str);
          range.insertNode(tNode);
          range.setStartAfter(tNode);
          range.collapse(true);
          const sel = window.getSelection();
          sel.removeAllRanges();
          sel.addRange(range);
        }
      }

      captureSelectionRange();
      scrollCaretIntoView(false);
      markDirtyAndScheduleDraft();
    },

    deleteBackAtCursor() {
      if (isOriginalView) return;
      restoreCaretInPaper();
      document.execCommand('delete', false, null);
      captureSelectionRange();
      markDirtyAndScheduleDraft();
    },

    insertNewLineAtCursor() {
      if (isOriginalView) return;
      restoreCaretInPaper();
      const ok = document.execCommand('insertParagraph', false, null);
      if (!ok) {
        document.execCommand('insertLineBreak', false, null);
      }
      captureSelectionRange();
      scrollCaretIntoView(false);
      markDirtyAndScheduleDraft();
    },

    execFormat(command) {
      if (isOriginalView) return;
      paper.focus();
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
        case 'undo': document.execCommand('undo'); break;
        case 'redo': document.execCommand('redo'); break;
      }
      markDirtyAndScheduleDraft();
    },

    flushDraft() {
      flushDraftNow();
    },

    requestExport(exportMode, isAutoSave) {
      let legacyFont = null;
      const mode = String(exportMode || '');
      const isPdfTarget = mode.endsWith('_pdf') || mode === 'share_pdf';
      if (!isPdfTarget) {
        if (mode.includes('nudi')) {
          legacyFont = 'Nudi 01 e';
        } else if (mode.includes('baraha')) {
          legacyFont = 'BRH Kannada';
        }
      }
      const cleanClone = getCleanPaperClone();
      const idmJson = JSON.stringify(serializeRootToIdm(cleanClone, legacyFont));
      notifyAndroid('onExportReady', mode, idmJson, !!isAutoSave);
    }
  };

  notifyAndroid('onEditorReady');
})(window);
