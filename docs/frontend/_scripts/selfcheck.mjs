/**
 * Automated frame acceptance for storyboard steps, run in-page by render.mjs.
 *
 * This exists so nobody has to read storyboard.png back just to confirm the
 * frames rendered — that read-back costs image tokens on every single run.
 * render.mjs prints PASS/FAIL text; only read the PNG when it says FAIL.
 */
export async function selfCheck(page, viewport) {
  return page.evaluate(({ w, h }) => {
    const problems = [];
    const notes = [];

    /* -- did anything actually render? -- */
    const text = (document.body.innerText || '').replace(/\s+/g, '');
    if (text.length < 60) problems.push(`only ${text.length} visible chars — frame looks blank`);
    const painted = [...document.body.querySelectorAll('*')].filter((el) => {
      const r = el.getBoundingClientRect();
      return r.width > 0 && r.height > 0;
    });
    if (painted.length < 15) problems.push(`only ${painted.length} painted elements — layout likely broken`);

    /* -- Chinese must not be mojibake --
       The real failure mode here is an encoding slip (a step file saved as
       Big5/Latin-1, or served without <meta charset>), which turns 台股 into
       ç¾åœ or into U+FFFD boxes. Both are detectable exactly; a font-width
       probe for tofu is not, so it is deliberately not attempted. */
    if (document.characterSet !== 'UTF-8') {
      problems.push(`document charset is ${document.characterSet}, not UTF-8 — add <meta charset="utf-8">`);
    }
    if (text.includes('�')) problems.push('text contains U+FFFD replacement chars (mojibake)');
    const mojibake = text.match(/[ÂÃåæç][-¿]/g);
    if (mojibake) {
      problems.push(`text looks like UTF-8 read as Latin-1 (e.g. "${mojibake[0]}") — check the file encoding`);
    }

    /* -- content must fit the canvas; body is overflow:hidden, so it clips silently -- */
    const de = document.documentElement;
    if (de.scrollWidth > w + 1) problems.push(`content overflows canvas width (${de.scrollWidth} > ${w})`);
    if (de.scrollHeight > h + 1) problems.push(`content overflows canvas height (${de.scrollHeight} > ${h})`);

    /* -- storyboard connector anchors -- */
    for (const attr of ['data-trigger', 'data-target']) {
      const els = document.querySelectorAll(`[${attr}]`);
      if (!els.length) {
        notes.push(`no ${attr}`);
        continue;
      }
      if (els.length > 1) problems.push(`${els.length} elements carry ${attr} — only the first is measured`);
      const r = els[0].getBoundingClientRect();
      if (r.width === 0 && r.height === 0) {
        problems.push(`[${attr}] has a zero-sized box, so the arrow cannot anchor to it`);
      }
    }
    return { problems, notes };
  }, { w: viewport.width, h: viewport.height });
}

/** Manifest-level checks that need no browser. */
export function checkManifest(manifest) {
  const problems = [];
  manifest.forEach((e, i) => {
    if (!e.file) problems.push(`entry ${i + 1} has no "file"`);
    if (!e.caption || !String(e.caption).trim()) problems.push(`entry ${i + 1} (${e.file}) has an empty caption`);
    /* -- every step must state which backend API it calls --
       The board is read to answer "what does this screen hit?", so `api` is
       mandatory, not optional. A step that genuinely calls nothing says so
       explicitly with [] — that way a forgotten field can't pass as "none". */
    if (!Array.isArray(e.api)) {
      problems.push(`entry ${i + 1} (${e.file}) has no "api" array — list the backend endpoints this step calls, or [] if it calls none`);
    } else {
      e.api.forEach((a, j) => {
        if (!/^(GET|POST|PUT|PATCH|DELETE)\s+\/\S*(\s+[—-]\s+\S.*)?$/.test(String(a).trim())) {
          problems.push(`entry ${i + 1} (${e.file}) api[${j}] is "${a}" — expected "<METHOD> /path" or "<METHOD> /path — 用途" (e.g. "GET /api/stocks/{id}/minute-bars?tradeDate= — 首次呼叫即向外部來源抓取當日分 K")`);
        }
      });
    }
  });
  const files = manifest.map((e) => e.file);
  if (new Set(files).size !== files.length) problems.push('manifest lists the same file twice');
  return problems;
}
