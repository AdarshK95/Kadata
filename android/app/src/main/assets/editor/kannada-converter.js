/**
 * KannadaDoc Offline Legacy Font Registry & Bidirectional ASCII <-> Unicode Converter (v2)
 * Supports Nudi (01 e..18 e), KGP, and Baraha ANSI (BRH Kannada) in both directions,
 * including ü mahaprana, base-u (ಣ), post-ottakshara ø/È (ೃ), ªÉÇ/ªÉÇÃ (ವೊ/ವೋ),
 * ö/÷ ottakshara spacers, short-u Å, and mixed English+Kannada script-splitting on PC export.
 */
(function (global) {
  'use strict';

  const FONT_PATTERNS = [
    { id: 'nudi', label: 'Nudi / KGP', regex: /(^nudi|nudi[\s\-_]*\d|kgp|kan[\-_]?nudi)/i },
    { id: 'baraha', label: 'Baraha (BRH)', regex: /(^brh|baraha|tunga[\s\-_]*brh)/i },
    { id: 'shreelipi', label: 'ShreeLipi', regex: /(shree[\-_]?kan|srilipi|shree.*deccan)/i },
    { id: 'akruti', label: 'Akruti', regex: /(akruti|akrutikan)/i },
    { id: 'prakashak', label: 'Prakashak', regex: /(prakashak|kn[\-_]?tt)/i },
    { id: 'surabhi', label: 'Surabhi / ISM', regex: /(surabhi|ism.*nandi|suchi|winkey|janna)/i }
  ];

  const UNICODE_FONT_REGEX = /(noto.*kannada|anek.*kannada|tunga|lohit|kedage|mallige|hubballi|baloo.*tamma)/i;
  const KANNADA_UNICODE_REGEX = /[\u0C80-\u0CFF]/;
  const NUDI_HIGH_ASCII_REGEX = /[\u00A2-\u00FF]/;

  const INDEPENDENT_VOWELS = {
    C: 'ಅ', D: 'ಆ', E: 'ಇ', F: 'ಈ', G: 'ಉ', H: 'ಊ',
    I: 'ಋ', J: 'ಎ', K: 'ಏ', L: 'ಐ', M: 'ಒ', N: 'ಓ', O: 'ಔ'
  };

  // Base consonants (includes 'u' -> ಣ used before Á/É/Ë/ï; ಣಿ is tÂ)
  const CONSONANT_WITH_A = {
    P: 'ಕ', U: 'ಗ', W: 'ಘ', Z: 'ಚ', b: 'ಛ',
    g: 'ರ', o: 'ಠ', q: 'ಡ', u: 'ಣ', v: 'ತ', x: 'ಥ',
    z: 'ದ', '£': 'ನ', '¥': 'ಪ', '¨': 'ಬ', 'ª': 'ವ',
    '±': 'ಶ', 'µ': 'ಷ', '¸': 'ಸ', 'º': 'ಹ', '¼': 'ಳ',
    S: 'ಖ', e: 'ಜ', m: 'ಟ', '¯': 'ಲ'
  };

  const CONSONANT_STANDALONE = {
    R: 'ಖ', Y: 'ಙ', d: 'ಜ', k: 'ಞ', l: 'ಟ',
    t: 'ಣ', '§': 'ಬ', '®': 'ಲ'
  };

  // Precomposed consonant + 'ಿ' ('u' removed: ಣಿ is tÂ)
  const CONSONANT_I = {
    Q: 'ಕ', T: 'ಖ', V: 'ಗ', X: 'ಘ', a: 'ಚ',
    c: 'ಛ', f: 'ಜ', j: 'ರ', n: 'ಟ', p: 'ಠ',
    r: 'ಡ', w: 'ತ', y: 'ಥ', '¢': 'ದ',
    '¤': 'ನ', '¦': 'ಪ', '©': 'ಬ', '«': 'ವ', '°': 'ಲ',
    '²': 'ಶ', '¶': 'ಷ', '¹': 'ಸ', '»': 'ಹ', '½': 'ಳ'
  };

  const MAHAPRANA = {
    'ಕ': 'ಖ', 'ಗ': 'ಘ', 'ಚ': 'ಛ', 'ಜ': 'ಝ',
    'ಟ': 'ಠ', 'ಡ': 'ಢ', 'ತ': 'ಥ', 'ದ': 'ಧ',
    'ಪ': 'ಫ', 'ಬ': 'ಭ'
  };

  const OTTAKSHARA = {
    'Ì': '್ಕ', 'Í': '್ಖ', 'Î': '್ಗ', 'Ï': '್ಘ', 'Ð': '್ಙ',
    'Ñ': '್ಚ', 'Ò': '್ಛ', 'Ó': '್ಜ', 'Ô': '್ಝ', 'Õ': '್ಞ',
    'Ö': '್ಟ', '×': '್ಠ', 'Ø': '್ಡ', 'Ù': '್ಢ', 'Ú': '್ಣ',
    'Û': '್ತ', 'Ü': '್ಥ', 'Ý': '್ದ', 'Þ': '್ಧ', 'ß': '್ನ',
    'à': '್ಪ', 'á': '್ಫ', 'â': '್ಬ', 'ã': '್ಭ', 'ä': '್ಮ',
    'å': '್ಯ', 'æ': '್ರ', 'ç': '್ರ', 'è': '್ಲ', 'é': '್ವ',
    'ê': '್ಶ', 'ë': '್ಷ', 'ì': '್ಸ', 'í': '್ಹ', 'î': '್ಳ'
  };

  // Invisible Nudi/Baraha ottakshara kerning spacers
  const IGNORE_SPACERS = new Set(['ö', '÷']);

  // Compound syllables for ಝ, ಯ, ಮ (NOTE: ªÉÇ / ªÉÇÃ are ವೊ/ವೋ, NOT ಮೊ/ಮೋ!)
  const COMPOUND_SYLLABLES = [
    ['gÉhÆÃ', 'ಝ', 'ೋ'], ['gÉhÆ', 'ಝ', 'ೊ'], ['gÉhÄÃ', 'ಝ', 'ೇ'],
    ['gÉhÄÊ', 'ಝ', 'ೈ'], ['gÉhÄ', 'ಝ', 'ೆ'], ['gÀhiÁ', 'ಝ', 'ಾ'],
    ['gÀhiË', 'ಝ', 'ೌ'], ['gÀhiï', 'ಝ', '್'], ['gÀhÄÄ', 'ಝ', 'ು'],
    ['gÀhÄÆ', 'ಝ', 'ೂ'], ['gÀhÄÈ', 'ಝ', 'ೃ'], ['gÀhÄø', 'ಝ', 'ೃ'],
    ['gÀhÄ', 'ಝ', ''], ['jhÄÃ', 'ಝ', 'ೀ'], ['jhÄ', 'ಝ', 'ಿ'],

    ['AiÉÆÃ', 'ಯ', 'ೋ'], ['AiÉÆ', 'ಯ', 'ೊ'], ['AiÉÄÃ', 'ಯ', 'ೇ'],
    ['AiÉÄÊ', 'ಯ', 'ೈ'], ['AiÉÄ', 'ಯ', 'ೆ'], ['AiÀiÁ', 'ಯ', 'ಾ'],
    ['AiÀiË', 'ಯ', 'ೌ'], ['AiÀiï', 'ಯ', '್'], ['AiÀÄÄ', 'ಯ', 'ು'],
    ['AiÀÄÆ', 'ಯ', 'ೂ'], ['AiÀÄÈ', 'ಯ', 'ೃ'], ['AiÀÄø', 'ಯ', 'ೃ'],
    ['AiÀÄ', 'ಯ', ''], ['¬ÄÃ', 'ಯ', 'ೀ'], ['¬Ä', 'ಯ', 'ಿ'],

    ['ªÉÆÃ', 'ಮ', 'ೋ'], ['ªÉÆ', 'ಮ', 'ೊ'], ['ªÉÄÃ', 'ಮ', 'ೇ'],
    ['ªÉÄÊ', 'ಮ', 'ೈ'], ['ªÉÄ', 'ಮ', 'ೆ'], ['ªÀiÁ', 'ಮ', 'ಾ'],
    ['ªÀiË', 'ಮ', 'ೌ'], ['ªÀiï', 'ಮ', '್'], ['ªÀÄÄ', 'ಮ', 'ು'],
    ['ªÀÄÆ', 'ಮ', 'ೂ'], ['ªÀÄÈ', 'ಮ', 'ೃ'], ['ªÀÄø', 'ಮ', 'ೃ'],
    ['ªÀÄ', 'ಮ', ''], ['«ÄÃ', 'ಮ', 'ೀ'], ['«Ä', 'ಮ', 'ಿ']
  ];

  // Vowel sign suffixes (Å = short ು, Ç = long ೂ / paired with É for ವೊ/ವೋ, ø = ೃ)
  const VOWEL_SUFFIXES = [
    ['ÉÆÃ', 'ೋ'], ['ÉÇÃ', 'ೋ'],
    ['ÉÆ', 'ೊ'], ['ÉÇ', 'ೊ'],
    ['ÉÃ', 'ೇ'], ['ÉÊ', 'ೈ'],
    ['ÀÄ', 'ು'], ['ÀÅ', 'ು'], ['ÀÆ', 'ೂ'], ['ÀÇ', 'ೂ'],
    ['ÀÈ', 'ೃ'], ['Àø', 'ೃ'], ['Àñ', 'ೄ'],
    ['ÂÃ', 'ೀ'],
    ['À', ''], ['Á', 'ಾ'], ['Â', 'ಿ'], ['Ã', 'ೀ'],
    ['Ä', 'ು'], ['Å', 'ು'], ['Æ', 'ೂ'], ['Ç', 'ೂ'],
    ['È', 'ೃ'], ['ø', 'ೃ'], ['É', 'ೆ'], ['Ê', 'ೈ'],
    ['Ë', 'ೌ'], ['ï', '್']
  ];

  function detectConverterForFont(fontFamily) {
    if (!fontFamily) return null;
    if (UNICODE_FONT_REGEX.test(fontFamily)) return 'unicode';
    for (const entry of FONT_PATTERNS) {
      if (entry.regex.test(fontFamily)) {
        return entry.id;
      }
    }
    return null;
  }

  function hasNudiSignature(text) {
    if (!text || KANNADA_UNICODE_REGEX.test(text)) return false;
    return NUDI_HIGH_ASCII_REGEX.test(text);
  }

  function consumeTrailingModifiers(text, startIdx, initialVowel) {
    const n = text.length;
    let i = startIdx;
    let vowel = initialVowel;
    let ottas = '';
    let arkavattu = false;

    while (i < n) {
      const c2 = text[i];
      if (IGNORE_SPACERS.has(c2)) {
        i++;
      } else if (OTTAKSHARA[c2]) {
        ottas += OTTAKSHARA[c2];
        i++;
      } else if (c2 === 'ð') {
        arkavattu = true;
        i++;
      } else if (c2 === 'Ã') {
        if (vowel === 'ಿ') vowel = 'ೀ';
        else if (vowel === 'ೆ') vowel = 'ೇ';
        else if (vowel === 'ೊ') vowel = 'ೋ';
        else if (vowel === '') vowel = 'ೀ';
        i++;
      } else if (c2 === 'Ê' && vowel === 'ೆ') {
        vowel = 'ೈ';
        i++;
      } else if (c2 === 'Æ' || c2 === 'Ç') {
        if (vowel === 'ೆ') vowel = 'ೊ';
        else if (vowel === '') vowel = 'ೂ';
        i++;
      } else if (c2 === 'È' || c2 === 'ø') {
        vowel = 'ೃ';
        i++;
      } else if ((c2 === 'Ä' || c2 === 'Å') && vowel === '') {
        vowel = 'ು';
        i++;
      } else {
        break;
      }
    }
    return { nextIdx: i, ottas, arkavattu, vowel };
  }

  function convertNudiToUnicode(text) {
    if (!text) return '';
    let out = '';
    let i = 0;
    const n = text.length;

    while (i < n) {
      // 1. Compound syllables (ಝ, ಯ, ಮ)
      let matchedCompound = null;
      for (let k = 0; k < COMPOUND_SYLLABLES.length; k++) {
        const [seq, cons, vow] = COMPOUND_SYLLABLES[k];
        if (text.startsWith(seq, i)) {
          matchedCompound = { len: seq.length, cons, vow };
          break;
        }
      }

      if (matchedCompound) {
        i += matchedCompound.len;
        const mod = consumeTrailingModifiers(text, i, matchedCompound.vow);
        i = mod.nextIdx;
        out += (mod.arkavattu ? 'ರ್' : '') + matchedCompound.cons + mod.ottas + mod.vowel;
        continue;
      }

      const ch = text[i];
      if (IGNORE_SPACERS.has(ch)) {
        i++;
        continue;
      }

      // 2. Precomposed consonant + 'ಿ'
      if (CONSONANT_I[ch]) {
        let base = CONSONANT_I[ch];
        let vowel = 'ಿ';
        i++;
        if (i < n && (text[i] === 's' || text[i] === 'ü') && MAHAPRANA[base]) {
          base = MAHAPRANA[base];
          i++;
        }
        const mod = consumeTrailingModifiers(text, i, vowel);
        i = mod.nextIdx;
        out += (mod.arkavattu ? 'ರ್' : '') + base + mod.ottas + mod.vowel;
        continue;
      }

      // 3. Regular consonant
      if (CONSONANT_WITH_A[ch] || CONSONANT_STANDALONE[ch]) {
        let base = CONSONANT_WITH_A[ch] || CONSONANT_STANDALONE[ch];
        i++;

        if (i < n && (text[i] === 's' || text[i] === 'ü') && MAHAPRANA[base]) {
          base = MAHAPRANA[base];
          i++;
        }

        let vowel = '';
        for (let k = 0; k < VOWEL_SUFFIXES.length; k++) {
          const [suf, vuni] = VOWEL_SUFFIXES[k];
          if (text.startsWith(suf, i)) {
            vowel = vuni;
            i += suf.length;
            break;
          }
        }

        if (i < n && (text[i] === 's' || text[i] === 'ü') && MAHAPRANA[base]) {
          base = MAHAPRANA[base];
          i++;
          if (!vowel) {
            for (let k = 0; k < VOWEL_SUFFIXES.length; k++) {
              const [suf, vuni] = VOWEL_SUFFIXES[k];
              if (text.startsWith(suf, i)) {
                vowel = vuni;
                i += suf.length;
                break;
              }
            }
          }
        }

        const mod = consumeTrailingModifiers(text, i, vowel);
        i = mod.nextIdx;
        out += (mod.arkavattu ? 'ರ್' : '') + base + mod.ottas + mod.vowel;
        continue;
      }

      // 4. Independent vowels
      if (text.startsWith('IÄ', i)) {
        out += 'ಋ';
        i += 2;
        continue;
      }
      if (text.startsWith('IÆ', i)) {
        out += 'ೠ';
        i += 2;
        continue;
      }
      if (INDEPENDENT_VOWELS[ch]) {
        out += INDEPENDENT_VOWELS[ch];
        i++;
        continue;
      }

      // 5. Yogavaha
      if (ch === 'A') { out += 'ಂ'; i++; continue; }
      if (ch === 'B') { out += 'ಃ'; i++; continue; }

      // 6. Stray ottakshara / halant
      if (OTTAKSHARA[ch]) { out += OTTAKSHARA[ch]; i++; continue; }
      if (ch === 'ï') { out += '್'; i++; continue; }

      // 7. Untouched characters
      out += ch;
      i++;
    }

    return out;
  }

  function convertMixedTokens(text) {
    if (!hasNudiSignature(text)) return text;
    return text.split(/(\s+)/).map(tok => (hasNudiSignature(tok) ? convertNudiToUnicode(tok) : tok)).join('');
  }

  // =========================================================================
  // Reverse Converter: Unicode Kannada -> Nudi 01 e / Baraha ANSI (KGP)
  // =========================================================================
  const REV_VOWELS = {
    'ಅ': 'C', 'ಆ': 'D', 'ಇ': 'E', 'ಈ': 'F', 'ಉ': 'G', 'ಊ': 'H',
    'ಋ': 'IÄ', 'ೠ': 'IÆ', 'ಎ': 'J', 'ಏ': 'K', 'ಐ': 'L', 'ಒ': 'M', 'ಓ': 'N', 'ಔ': 'O',
    'ಂ': 'A', 'ಃ': 'B'
  };

  const REV_CONSONANT_BASE = {
    'ಕ': ['P', true],  'ಖ': ['S', false], 'ಗ': ['U', true],  'ಘ': ['W', true],  'ಙ': ['Y', false],
    'ಚ': ['Z', true],  'ಛ': ['b', true],  'ಜ': ['e', false], 'ಞ': ['k', false],
    'ಟ': ['m', false], 'ಠ': ['o', true],  'ಡ': ['q', true],  'ಢ': ['qs', true], 'ಣ': ['u', false],
    'ತ': ['v', true],  'ಥ': ['x', true],  'ದ': ['z', true],  'ಧ': ['zs', true], 'ನ': ['£', true],
    'ಪ': ['¥', true],  'ಫ': ['¥s', true], 'ಬ': ['¨', true],  'ಭ': ['¨s', true],
    'ರ': ['g', true],  'ಲ': ['¯', false], 'ವ': ['ª', true],
    'ಶ': ['±', true],  'ಷ': ['µ', true],  'ಸ': ['¸', true],  'ಹ': ['º', true],  'ಳ': ['¼', true]
  };

  const REV_CONSONANT_STANDALONE = {
    'ಖ': 'R', 'ಜ': 'd', 'ಟ': 'l', 'ಣ': 't', 'ಬ': '§', 'ಲ': '®'
  };

  const REV_CONSONANT_I = {
    'ಕ': 'Q', 'ಖ': 'T', 'ಗ': 'V', 'ಘ': 'X', 'ಚ': 'a', 'ಛ': 'c', 'ಜ': 'f',
    'ಟ': 'n', 'ಠ': 'p', 'ಡ': 'r', 'ಢ': 'rü', 'ಣ': 'tÂ', 'ತ': 'w', 'ಥ': 'y',
    'ದ': '¢', 'ಧ': '¢ü', 'ನ': '¤', 'ಪ': '¦', 'ಫ': '¦ü', 'ಬ': '©', 'ಭ': '©ü',
    'ರ': 'j', 'ಲ': '°', 'ವ': '«', 'ಶ': '²', 'ಷ': '¶', 'ಸ': '¹', 'ಹ': '»', 'ಳ': '½'
  };

  const REV_OTTA = {
    'ಕ': 'Ì', 'ಖ': 'Í', 'ಗ': 'Î', 'ಘ': 'Ï', 'ಙ': 'Ð', 'ಚ': 'Ñ', 'ಛ': 'Ò',
    'ಜ': 'Ó', 'ಝ': 'Ô', 'ಞ': 'Õ', 'ಟ': 'Ö', 'ಠ': '×', 'ಡ': 'Ø', 'ಢ': 'Ù',
    'ಣ': 'Ú', 'ತ': 'Û', 'ಥ': 'Ü', 'ದ': 'Ý', 'ಧ': 'Þ', 'ನ': 'ß', 'ಪ': 'à',
    'ಫ': 'á', 'ಬ': 'â', 'ಭ': 'ã', 'ಮ': 'ä', 'ಯ': 'å', 'ರ': 'æ', 'ಲ': 'è',
    'ವ': 'é', 'ಶ': 'ê', 'ಷ': 'ë', 'ಸ': 'ì', 'ಹ': 'í', 'ಳ': 'î'
  };

  const REV_COMPOUND = {
    'ಮ': { '': 'ªÀÄ', 'ಾ': 'ªÀiÁ', 'ಿ': '«Ä', 'ೀ': '«ÄÃ', 'ು': 'ªÀÄÄ', 'ೂ': 'ªÀÄÆ', 'ೃ': 'ªÀÄÈ', 'ೆ': 'ªÉÄ', 'ೇ': 'ªÉÄÃ', 'ೈ': 'ªÉÄÊ', 'ೊ': 'ªÉÆ', 'ೋ': 'ªÉÆÃ', 'ೌ': 'ªÀiË', '್': 'ªÀiï' },
    'ಯ': { '': 'AiÀÄ', 'ಾ': 'AiÀiÁ', 'ಿ': '¬Ä', 'ೀ': '¬ÄÃ', 'ು': 'AiÀÄÄ', 'ೂ': 'AiÀÄÆ', 'ೃ': 'AiÀÄÈ', 'ೆ': 'AiÉÄ', 'ೇ': 'AiÉÄÃ', 'ೈ': 'AiÉÄÊ', 'ೊ': 'AiÉÆ', 'ೋ': 'AiÉÆÃ', 'ೌ': 'AiÀiË', '್': 'AiÀiï' },
    'ಝ': { '': 'gÀhÄ', 'ಾ': 'gÀhiÁ', 'ಿ': 'jhÄ', 'ೀ': 'jhÄÃ', 'ು': 'gÀhÄÄ', 'ೂ': 'gÀhÄÆ', 'ೃ': 'gÀhÄÈ', 'ೆ': 'gÉhÄ', 'ೇ': 'gÉhÄÃ', 'ೈ': 'gÉhÄÊ', 'ೊ': 'gÉhÆ', 'ೋ': 'gÉhÆÃ', 'ೌ': 'gÀhiË', '್': 'gÀhiï' }
  };

  const MATRAS = new Set(['ಾ', 'ಿ', 'ೀ', 'ು', 'ೂ', 'ೃ', 'ೄ', 'ೆ', 'ೇ', 'ೈ', 'ೊ', 'ೋ', 'ೌ']);

  function isKannadaConsonant(ch) {
    if (!ch) return false;
    const code = ch.charCodeAt(0);
    return code >= 0x0C95 && code <= 0x0CB9;
  }

  function encodeConsonantWithVowel(cons, vowel, hasOtta) {
    if (REV_COMPOUND[cons] && REV_COMPOUND[cons][vowel] !== undefined) {
      return REV_COMPOUND[cons][vowel];
    }
    if ((vowel === 'ಿ' || vowel === 'ೀ') && REV_CONSONANT_I[cons]) {
      return REV_CONSONANT_I[cons] + (vowel === 'ೀ' ? 'Ã' : '');
    }
    if (vowel === '' && REV_CONSONANT_STANDALONE[cons]) {
      return REV_CONSONANT_STANDALONE[cons];
    }
    if (cons === 'ವ') {
      if (vowel === 'ು') return 'ªÀÅ';
      if (vowel === 'ೂ') return 'ªÀÇ';
      if (vowel === 'ೊ') return 'ªÉÇ';
      if (vowel === 'ೋ') return 'ªÉÇÃ';
    }
    if (cons === 'ಪ' || cons === 'ಫ') {
      const baseG = cons === 'ಪ' ? '¥' : '¥s';
      if (vowel === 'ು') return baseG + 'ÀÅ';
      if (vowel === 'ೂ') return baseG + 'ÀÇ';
    }
    const info = REV_CONSONANT_BASE[cons];
    if (!info) return cons + vowel;
    const [glyph, needsA] = info;
    const baseWithA = needsA ? glyph + 'À' : (REV_CONSONANT_STANDALONE[cons] || glyph);
    switch (vowel) {
      case '': return needsA ? glyph + 'À' : glyph;
      case 'ಾ': return glyph + 'Á';
      case 'ು': return baseWithA + 'Ä';
      case 'ೂ': return baseWithA + 'Æ';
      case 'ೃ': return baseWithA + (hasOtta ? 'ø' : 'È');
      case 'ೄ': return baseWithA + 'Àñ';
      case 'ೆ': return glyph + 'É';
      case 'ೇ': return glyph + 'ÉÃ';
      case 'ೈ': return glyph + 'ÉÊ';
      case 'ೊ': return glyph + 'ÉÆ';
      case 'ೋ': return glyph + 'ÉÆÃ';
      case 'ೌ': return glyph + 'Ë';
      case '್': return glyph + 'ï';
      default: return glyph + vowel;
    }
  }

  function convertUnicodeToNudi(text) {
    if (!text || !KANNADA_UNICODE_REGEX.test(text)) return text || '';
    let out = '';
    let i = 0;
    const n = text.length;

    while (i < n) {
      const ch = text[i];

      if (REV_VOWELS[ch]) {
        out += REV_VOWELS[ch];
        i++;
        continue;
      }

      if (isKannadaConsonant(ch)) {
        let arkavattu = false;
        let base = ch;
        if (ch === 'ರ' && i + 2 < n && text[i + 1] === '್' && isKannadaConsonant(text[i + 2])) {
          arkavattu = true;
          base = text[i + 2];
          i += 3;
        } else {
          i++;
        }

        let ottas = '';
        let trailingHalant = false;
        while (i < n && text[i] === '್') {
          if (i + 1 < n && isKannadaConsonant(text[i + 1])) {
            ottas += REV_OTTA[text[i + 1]] || '';
            i += 2;
          } else {
            trailingHalant = true;
            i++;
            break;
          }
        }

        let vowel = trailingHalant ? '್' : '';
        if (!trailingHalant && i < n && MATRAS.has(text[i])) {
          vowel = text[i];
          i++;
        }

        if (vowel === 'ೃ' && ottas.length > 0) {
          out += encodeConsonantWithVowel(base, '', true) + ottas + 'ø' + (arkavattu ? 'ð' : '');
        } else {
          out += encodeConsonantWithVowel(base, vowel, ottas.length > 0) + ottas + (arkavattu ? 'ð' : '');
        }
        continue;
      }

      out += ch;
      i++;
    }
    return out;
  }

  /**
   * Splits mixed English + Kannada Unicode text into script-isolated runs for PC export.
   * - Contiguous Kannada spans (including spaces between Kannada words) convert to legacy ASCII
   *   and receive the target legacyFont ("Nudi 01 e" or "BRH Kannada").
   * - English words, numbers, and symbols stay untouched in "Calibri".
   */
  function splitRunsForLegacyExport(text, legacyFont) {
    if (!text) return [];
    const targetFont = legacyFont || 'Nudi 01 e';
    const regex = /([\u0C80-\u0CFF](?:[\u0C80-\u0CFF\u200C\u200D ]*[\u0C80-\u0CFF])?)/g;
    const parts = text.split(regex);
    const result = [];

    for (let idx = 0; idx < parts.length; idx++) {
      const part = parts[idx];
      if (!part) continue;
      if (KANNADA_UNICODE_REGEX.test(part)) {
        result.push({
          text: convertUnicodeToNudi(part),
          fontFamily: targetFont
        });
      } else {
        result.push({
          text: part,
          fontFamily: 'Calibri'
        });
      }
    }
    return result;
  }

  function convertRun(run, converterOverride) {
    const rawText = run.text || '';
    if (!rawText) return { text: '', converted: false, detectedLabel: null };

    if (converterOverride === 'none') {
      return { text: rawText, converted: false, detectedLabel: null };
    }

    if (KANNADA_UNICODE_REGEX.test(rawText)) {
      return { text: rawText, converted: false, detectedLabel: null };
    }

    if (converterOverride && converterOverride !== 'auto') {
      const convertedText = hasNudiSignature(rawText)
        ? convertNudiToUnicode(rawText)
        : rawText;
      return {
        text: convertedText,
        converted: convertedText !== rawText,
        detectedLabel: converterOverride.toUpperCase()
      };
    }

    const detected = detectConverterForFont(run.fontFamily);
    if (detected === 'unicode') {
      return { text: rawText, converted: false, detectedLabel: null };
    }

    if (detected) {
      const labelObj = FONT_PATTERNS.find(p => p.id === detected);
      return {
        text: convertNudiToUnicode(rawText),
        converted: true,
        detectedLabel: labelObj ? labelObj.label : detected
      };
    }

    if (hasNudiSignature(rawText)) {
      const convertedText = convertMixedTokens(rawText);
      return {
        text: convertedText,
        converted: convertedText !== rawText,
        detectedLabel: 'Nudi / ASCII (Auto-Detected)'
      };
    }

    return { text: rawText, converted: false, detectedLabel: null };
  }

  global.KannadaConverter = {
    detectConverterForFont,
    hasNudiSignature,
    convertNudiToUnicode,
    convertUnicodeToNudi,
    splitRunsForLegacyExport,
    convertRun
  };
})(window);
