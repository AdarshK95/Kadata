/**
 * KannadaDoc Offline Rich-Text Editor Controller & AndroidBridge (v3)
 * Includes script-split legacy PC export (Nudi 01 e / BRH Kannada + Calibri for English)
 * and full Find & Replace (Search, Next/Prev, Replace, Replace All) with clean DOM unwrapping.
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

  // Find & Replace state
  let searchMatches = [];
  let activeMatchIndex = -1;
  let lastSearchQuery = '';

  // Saved caret Range for the floating Kannada Varnamala keyboard
  let savedCaretRange = null;
  let keyboardTopLimitPx = 0; // On tablet: Y limit (px) of the keyboard's top edge
  const KANNADA_TRAILING_SIGN_RE = /[\u0CBE-\u0CCC\u0C82\u0C83]$/;

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

  function placeCaretAtEndOfPaper() {
    const sel = window.getSelection();
    if (!sel) return null;
    const range = document.createRange();
    let target = paper.lastChild;
    if (!target) {
      target = document.createElement('p');
      target.appendChild(document.createElement('br'));
      paper.appendChild(target);
    }
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
        // Fall through to end of paper
      }
    }
    return placeCaretAtEndOfPaper();
  }

  function measureCaretClientRect(range) {
    if (!range) return null;
    const rects = range.getClientRects();
    if (rects && rects.length > 0) {
      const r = rects[rects.length - 1];
      if (r.height > 0 || r.top !== 0) return r;
    }
    const bRect = range.getBoundingClientRect();
    if (bRect && (bRect.height > 0 || bRect.top !== 0)) return bRect;

    // Collapsed caret on an empty line (<p><br></p>) often reports (0,0,0,0):
    // measure the containing block element's top instead.
    const el = range.startContainer.nodeType === Node.ELEMENT_NODE
      ? range.startContainer
      : range.startContainer.parentElement;
    return el ? el.getBoundingClientRect() : null;
  }

  function scrollCursorLineToTop(smooth) {
    try {
      const range = restoreCaretInPaper();
      const rect = measureCaretClientRect(range);
      if (!rect) return;
      const desiredTop = 18;
      const deltaY = rect.top - desiredTop;
      if (Math.abs(deltaY) > 6) {
        window.scrollBy({
          top: deltaY,
          left: 0,
          behavior: smooth ? 'smooth' : 'auto'
        });
      }
    } catch (_) {
      // Ignore scrolling errors
    }
  }

  function scrollCaretIntoView() {
    try {
      const sel = window.getSelection();
      if (!sel || sel.rangeCount === 0) return;
      const range = sel.getRangeAt(0);
      const rect = measureCaretClientRect(range);
      if (!rect) return;
      const viewHeight = window.innerHeight || document.documentElement.clientHeight;
      if (document.body.classList.contains('virtual-kb-open')) {
        // Keep the cursor strictly above the keyboard's top limit (or near top of pane)
        const maxAllowedBottom = (keyboardTopLimitPx > 72)
          ? Math.min(viewHeight - 28, keyboardTopLimitPx - 12)
          : Math.min(viewHeight - 28, 120);
        if (rect.top < 10 || rect.bottom > maxAllowedBottom) {
          window.scrollBy({ top: rect.top - 18, left: 0, behavior: 'auto' });
        }
      } else if (rect.top < 32 || rect.bottom > viewHeight - 32) {
        window.scrollBy({ top: rect.top - viewHeight / 3, left: 0, behavior: 'smooth' });
      }
    } catch (_) {
      // Ignore scrolling errors
    }
  }

  document.addEventListener('selectionchange', captureSelectionRange);
  paper.addEventListener('keyup', captureSelectionRange);
  paper.addEventListener('mouseup', captureSelectionRange);
  paper.addEventListener('touchend', captureSelectionRange);
  paper.addEventListener('focus', captureSelectionRange);

  function notifyAndroid(method, ...args) {
    if (global.AndroidBridge && typeof global.AndroidBridge[method] === 'function') {
      global.AndroidBridge[method](...args);
    }
  }

  // =========================================================================
  // Find & Replace Engine (Text-Node Safe, Preserves Formatting & Clean Exports)
  // =========================================================================
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
      searchMatches[activeMatchIndex].classList.remove('kd-search-active');
    }
    const len = searchMatches.length;
    activeMatchIndex = ((index % len) + len) % len;
    const activeEl = searchMatches[activeMatchIndex];
    activeEl.classList.add('kd-search-active');
    try {
      activeEl.scrollIntoView({ behavior: 'smooth', block: 'center', inline: 'nearest' });
    } catch (_) {
      activeEl.scrollIntoView();
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

  function performSearch(query, preferredIndex) {
    unwrapMarksIn(paper);
    searchMatches = [];
    activeMatchIndex = -1;
    lastSearchQuery = query || '';

    if (!lastSearchQuery) {
      notifyAndroid('onSearchResults', 0, 0);
      return;
    }

    const needle = lastSearchQuery.toLowerCase();
    const needleLen = lastSearchQuery.length;
    const textNodes = collectTextNodes(paper);

    for (let t = 0; t < textNodes.length; t++) {
      const node = textNodes[t];
      const text = node.nodeValue;
      const lower = text.toLowerCase();
      let startIdx = 0;
      let matchIdx = lower.indexOf(needle, startIdx);
      if (matchIdx === -1) continue;

      const frag = document.createDocumentFragment();
      while (matchIdx !== -1) {
        if (matchIdx > startIdx) {
          frag.appendChild(document.createTextNode(text.slice(startIdx, matchIdx)));
        }
        const mark = document.createElement('mark');
        mark.className = 'kd-search-match';
        mark.textContent = text.slice(matchIdx, matchIdx + needleLen);
        frag.appendChild(mark);
        searchMatches.push(mark);

        startIdx = matchIdx + needleLen;
        matchIdx = lower.indexOf(needle, startIdx);
      }
      if (startIdx < text.length) {
        frag.appendChild(document.createTextNode(text.slice(startIdx)));
      }
      if (node.parentNode) {
        node.parentNode.replaceChild(frag, node);
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
      const targetMark = searchMatches[activeMatchIndex];
      const repText = replacement == null ? '' : String(replacement);
      const nextIdx = activeMatchIndex;
      if (targetMark.parentNode) {
        targetMark.parentNode.replaceChild(document.createTextNode(repText), targetMark);
      }
      const queryToKeep = lastSearchQuery;
      unwrapMarksIn(paper);
      markDirtyAndScheduleDraft();
      if (queryToKeep) {
        performSearch(queryToKeep, nextIdx);
      }
      notifyAndroid('onReplaceDone', 1);
    },

    replaceAll(query, replacement) {
      if (isOriginalView || !query) {
        notifyAndroid('onReplaceDone', 0);
        return;
      }
      unwrapMarksIn(paper);
      const needle = query.toLowerCase();
      const needleLen = query.length;
      const repText = replacement == null ? '' : String(replacement);
      const textNodes = collectTextNodes(paper);
      let count = 0;

      for (let t = 0; t < textNodes.length; t++) {
        const node = textNodes[t];
        const text = node.nodeValue;
        const lower = text.toLowerCase();
        let startIdx = 0;
        let matchIdx = lower.indexOf(needle, startIdx);
        if (matchIdx === -1) continue;

        let out = '';
        while (matchIdx !== -1) {
          out += text.slice(startIdx, matchIdx) + repText;
          count++;
          startIdx = matchIdx + needleLen;
          matchIdx = lower.indexOf(needle, startIdx);
        }
        out += text.slice(startIdx);
        node.nodeValue = out;
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
      if (enabled) {
        paper.setAttribute('inputmode', 'none');
        document.body.classList.add('virtual-kb-open');
        document.body.classList.toggle('tablet-kb-open', !!isTabletSideMode);
        keyboardTopLimitPx = (typeof topLimitPx === 'number' && topLimitPx > 0) ? topLimitPx : 0;
        // Bring the cursor's active line right to the top of the visible document pane
        setTimeout(() => scrollCursorLineToTop(true), 90);
        setTimeout(() => scrollCursorLineToTop(false), 260);
      } else {
        paper.removeAttribute('inputmode');
        document.body.classList.remove('virtual-kb-open');
        document.body.classList.remove('tablet-kb-open');
        keyboardTopLimitPx = 0;
      }
    },

    updateKeyboardTopLimit(topLimitPx) {
      keyboardTopLimitPx = (typeof topLimitPx === 'number' && topLimitPx > 0) ? topLimitPx : 0;
      if (document.body.classList.contains('virtual-kb-open')) {
        scrollCaretIntoView();
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

      // Smart Ottakshara attachment: if user taps a subscript modifier (e.g. ್ರ, ್ಯ, ್ಕ)
      // right after a consonant+matra (e.g. ಕಾ|), place the subscript before the trailing matra -> ಕ್ರಾ|
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
            // Move caret back past the trailing matra so user can continue typing naturally
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
      scrollCaretIntoView();
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
      scrollCaretIntoView();
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
        case 'insertTable': {
          const tableHtml =
            '<table><tbody>' +
            '<tr><td><p><br></p></td><td><p><br></p></td></tr>' +
            '<tr><td><p><br></p></td><td><p><br></p></td></tr>' +
            '</tbody></table><p><br></p>';
          document.execCommand('insertHTML', false, tableHtml);
          break;
        }
      }
      markDirtyAndScheduleDraft();
    },

    flushDraft() {
      flushDraftNow();
    },

    requestExport(exportMode, isAutoSave) {
      let legacyFont = null;
      const mode = String(exportMode || '');
      // PDF exports always render clean Unicode Kannada so PDF viewers never show garbled glyphs
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
