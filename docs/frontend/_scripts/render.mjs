#!/usr/bin/env node
/**
 * Storyboard renderer for docs/frontend/<group>/.
 *
 * Usage: node docs/frontend/_scripts/render.mjs docs/frontend/<group>
 *
 * Reads <group>/manifest.json  ->  [{ file, caption, api }, ...]
 *
 * `api` is the list of backend endpoints that step fires (e.g.
 * ["GET /api/stocks?keyword=&page=&size="]), taken from the spec's API
 * integration table. It is printed under the caption so the board answers
 * "which backend API does this screen call?" without leaving the image.
 * A step that calls nothing carries an explicit empty array.
 *
 * For each step-N.html:
 *   - screenshots it at 1440x900, deviceScaleFactor 2  ->  step-N.png
 *   - measures [data-trigger] (the element that was clicked to produce the NEXT
 *     step) and [data-target] (the element in THIS step that resulted from the
 *     previous step's click), recording each as a fraction of the viewport so
 *     the board can draw a real line between two real points.
 *
 * Then composes three equivalent views of the same board:
 *   - storyboard.html  (open in a browser for native pan/zoom)
 *   - storyboard.png   (flat image, for embedding inline in the .md)
 *   - storyboard.pdf   (captions stay vector-crisp at any zoom)
 *
 * Board layout: rows of 3, zigzagging — row 1 reads left->right, row 2 reads
 * right->left, so the connector between rows is a short drop at whichever edge
 * the previous row ended on, never a long diagonal back across the page.
 *
 * ONE Chromium for the whole group — never one browser per step.
 */
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { basename, join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { chromium } from 'playwright';
import { selfCheck, checkManifest } from './selfcheck.mjs';

const VIEWPORT = { width: 1440, height: 900 };
const SCALE = 2;              // device pixel ratio for the step screenshots
const PER_ROW = 3;
const FRAME_W = 660;          // frame width inside the board
const GAP_X = 120;            // horizontal gap between frames (arrow lives here)
const GAP_Y = 150;            // vertical gap between rows
const PAD = 70;               // board padding
const CAPTION_MIN_H = 76;    // floor; the real height is measured (see below)
const CAPTION_PAD = 6;       // breathing room under the tallest caption block

const groupDir = process.argv[2];
if (!groupDir) {
  console.error('usage: render.mjs docs/frontend/<group>');
  process.exit(2);
}
const dir = resolve(groupDir);
const group = basename(dir);
const manifest = JSON.parse(await readFile(join(dir, 'manifest.json'), 'utf8'));
if (!Array.isArray(manifest) || manifest.length === 0) {
  console.error(`manifest.json in ${dir} is empty or not an array`);
  process.exit(2);
}

const frameH = Math.round((FRAME_W * VIEWPORT.height) / VIEWPORT.width);

const browser = await chromium.launch();
const page = await browser.newPage({
  viewport: VIEWPORT,
  deviceScaleFactor: SCALE,
});

/**
 * A missing stylesheet/script or a thrown exception leaves a half-rendered
 * frame that still looks plausible in the DOM, and on file:// `link.sheet` is
 * useless for detecting it (non-null even for a 404, and `cssRules` throws) —
 * so watch the page itself. Reset per step.
 */
let pageFaults = [];
page.on('requestfailed', (req) => {
  pageFaults.push(`${req.resourceType()} failed to load: ${basename(req.url())} (${req.failure()?.errorText ?? 'unknown'})`);
});
page.on('response', (res) => {
  if (res.status() >= 400) pageFaults.push(`${basename(res.url())} returned HTTP ${res.status()}`);
});
page.on('pageerror', (err) => {
  pageFaults.push(`uncaught ${String(err).split('\n')[0]}`);
});

const manifestProblems = checkManifest(manifest);

const steps = [];
let failed = 0;
const frameProblems = [];

for (const [i, entry] of manifest.entries()) {
  const htmlPath = join(dir, entry.file);
  if (!existsSync(htmlPath)) {
    console.error(`MISSING ${entry.file}`);
    failed++;
    continue;
  }
  const png = entry.file.replace(/\.html$/, '.png');
  pageFaults = [];
  await page.goto(pathToFileURL(htmlPath).href, { waitUntil: 'networkidle' });
  await page.screenshot({ path: join(dir, png) });

  // Measure the click source / click effect as viewport fractions. The whole
  // box is kept (not just its centre) so the board can ring the exact element
  // the arrow leaves from and the exact element it lands on.
  const anchors = await page.evaluate(({ w, h }) => {
    const box = (sel) => {
      const el = document.querySelector(sel);
      if (!el) return null;
      const r = el.getBoundingClientRect();
      if (r.width === 0 && r.height === 0) return null;
      return { x: r.left / w, y: r.top / h, w: r.width / w, h: r.height / h };
    };
    return { trigger: box('[data-trigger]'), target: box('[data-target]') };
  }, { w: VIEWPORT.width, h: VIEWPORT.height });

  // Automated acceptance, so the caller never has to read the PNG back to
  // confirm the frame is fine — only to diagnose a FAIL.
  const { problems, notes } = await selfCheck(page, VIEWPORT);
  problems.unshift(...pageFaults);
  if (problems.length) frameProblems.push([entry.file, problems]);
  const tail = notes.length ? `  (${notes.join(', ')})` : '';
  console.log(`${problems.length ? 'FAIL' : 'PASS'} ${entry.file} -> ${png}${tail}`);
  for (const m of problems) console.log(`       ! ${m}`);

  const api = Array.isArray(entry.api) ? entry.api.filter((a) => String(a).trim()) : [];
  steps.push({ index: i, png, caption: entry.caption ?? '', api, ...anchors });
}

if (failed) {
  await browser.close();
  console.error(`${failed} step file(s) missing — fix the manifest or author them.`);
  process.exit(1);
}

const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

/**
 * One backend endpoint under a caption: the HTTP verb as a solid badge, the
 * path in mono. Rendered from the manifest's `api` list so a reader of the
 * board can see which API each screen calls without leaving the image.
 */
function apiChip(entry) {
  const m = String(entry).trim().match(/^([A-Z]+)\s+(\S*)(?:\s+[—-]\s+(.*))?$/);
  const verb = m ? m[1] : 'API';
  const path = m ? m[2] : String(entry).trim();
  const note = m && m[3] ? m[3] : '';
  return `<div class="api"><span class="verb v-${esc(verb.toLowerCase())}">${esc(verb)}</span>` +
    `<span class="sig"><code>${esc(path)}</code>${note ? `<span class="note">${esc(note)}</span>` : ''}</span></div>`;
}

/** The text under one frame: numbered caption, then that step's backend APIs. */
function captionBlockHtml(s) {
  return `<div class="caption"><span class="num">${s.index + 1}</span>${esc(s.caption)}</div>` +
    (s.api.length ? `<div class="apis">${s.api.map((a) => apiChip(a)).join('')}</div>` : '');
}

/** Shared by the measuring page and the board itself, so what is measured is what is drawn. */
const CAPTION_CSS = `
  .caption { position:relative; z-index:6; margin-top:14px; font-size:19px; line-height:1.5; color:#33312d; }
  .caption .num { display:inline-block; min-width:26px; height:26px; line-height:26px; text-align:center;
                  background:#d6743a; color:#fff; border-radius:13px; font-size:15px; margin-right:9px; vertical-align:1px; }
  .apis { position:relative; z-index:6; margin-top:9px; }
  .api { display:flex; flex-wrap:wrap; align-items:baseline; gap:2px 8px; margin-top:5px;
         font-size:15px; line-height:1.45; }
  .api .verb { flex:none; display:inline-block; min-width:44px; text-align:center; padding:1px 7px;
               border-radius:4px; background:#5b6b7c; color:#fff; font-size:12px; letter-spacing:.4px; font-weight:600; }
  .api .verb.v-get { background:#2f6f4f; }
  .api .verb.v-post { background:#9a5a1e; }
  /* path and its purpose note share one wrapping block, so the note always
     stays right after the path it explains instead of drifting to the margin */
  .api .sig { flex:1 1 0; min-width:0; }
  .api code { font-family:"JetBrains Mono","Consolas","Courier New",monospace; color:#4a4842;
              word-break:break-all; }
  .api .note { color:#7a7770; font-size:14px; margin-left:8px; }`;

/**
 * How much room the text under a frame needs. Caption length varies (a wrapped
 * third line) and so does the API list, so this is MEASURED at the real 660px
 * frame width rather than guessed at with a constant — a guess that is too
 * small silently collides with the row below in the flat PNG.
 */
const measurePage = await browser.newPage({ viewport: { width: FRAME_W + 200, height: 600 } });
await measurePage.setContent(`<!doctype html><html lang="zh-Hant"><head><meta charset="utf-8"><style>
  body { margin:0; font-family:"Noto Sans TC","Microsoft JhengHei","PingFang TC",sans-serif; }
  /* flow-root, so .caption's top margin counts toward the block the way it
     does on the board (where an <img> sits above it) instead of collapsing out */
  .frame { width:${FRAME_W}px; display:flow-root; }${CAPTION_CSS}
</style></head><body>${steps.map((s) => `<div class="frame">${captionBlockHtml(s)}</div>`).join('')}</body></html>`);
const measured = await measurePage.evaluate(() =>
  [...document.querySelectorAll('.frame')].map((f) => f.getBoundingClientRect().height));
await measurePage.close();
const CAPTION_H = Math.max(CAPTION_MIN_H, Math.ceil(Math.max(...measured)) + CAPTION_PAD);

// ---- board geometry: rows of PER_ROW, zigzag (odd rows read right-to-left) ----
// Every caption block gets the same measured height, so frames across rows
// stay on a shared baseline no matter how many APIs each step lists.
const rows = Math.ceil(steps.length / PER_ROW);
const cols = Math.min(steps.length, PER_ROW);
const boardW = PAD * 2 + cols * FRAME_W + (cols - 1) * GAP_X;
const boardH = PAD * 2 + rows * (frameH + CAPTION_H) + (rows - 1) * GAP_Y;

for (const s of steps) {
  const row = Math.floor(s.index / PER_ROW);
  const posInRow = s.index % PER_ROW;
  // zigzag: even rows left->right, odd rows right->left
  const col = row % 2 === 0 ? posInRow : PER_ROW - 1 - posInRow;
  s.left = PAD + col * (FRAME_W + GAP_X);
  s.top = PAD + row * (frameH + CAPTION_H + GAP_Y);
  s.row = row;
}

/**
 * The connector runs from the ringed source element to the ringed destination
 * element, trimmed to stop just outside each ring. Both ends are therefore
 * unambiguous: you can see the exact control that was clicked and the exact
 * thing that click produced, with the arrow spanning between them.
 */
const MARK_PAD = 5;    // padding between an element and its ring
const END_GAP  = 7;    // breathing room between the ring and the arrow tip

function distToEdge(rect, p, ux, uy) {
  const cand = [];
  if (ux > 0) cand.push((rect.x + rect.w - p.x) / ux);
  if (ux < 0) cand.push((rect.x - p.x) / ux);
  if (uy > 0) cand.push((rect.y + rect.h - p.y) / uy);
  if (uy < 0) cand.push((rect.y - p.y) / uy);
  const pos = cand.filter((v) => v > 0);
  return pos.length ? Math.min(...pos) : 0;
}

const pad = (r, n) => ({ x: r.x - n, y: r.y - n, w: r.w + n * 2, h: r.h + n * 2 });

function trimToBoxes(from, to, boxA, boxB) {
  const dx = to.x - from.x, dy = to.y - from.y;
  const len = Math.hypot(dx, dy);
  if (!len) return { from, to };
  const ux = dx / len, uy = dy / len;

  let start = from, end = to;
  if (boxA) {
    const d = distToEdge(pad(boxA, MARK_PAD), from, ux, uy) + END_GAP;
    start = { x: from.x + ux * d, y: from.y + uy * d };
  }
  if (boxB) {
    const d = distToEdge(pad(boxB, MARK_PAD), to, -ux, -uy) + END_GAP;
    end = { x: to.x - ux * d, y: to.y - uy * d };
  }

  // Boxes almost touching: nothing sensible to trim, keep the raw span.
  if (Math.hypot(end.x - start.x, end.y - start.y) < 30) return { from, to };
  return { from: start, to: end };
}

// ---- connectors: from step N's trigger point to step N+1's target point ----
const arrows = [];
for (let i = 0; i < steps.length - 1; i++) {
  const a = steps[i];
  const b = steps[i + 1];
  const sameRow = a.row === b.row;

  // Default when a transition has no specific trigger/target: edge to edge.
  const aDefault = sameRow
    ? { x: a.left + FRAME_W, y: a.top + frameH / 2 }
    : { x: a.left + FRAME_W / 2, y: a.top + frameH };
  const bDefault = sameRow
    ? { x: b.left, y: b.top + frameH / 2 }
    : { x: b.left + FRAME_W / 2, y: b.top };

  const toBoard = (step, f) => ({
    x: step.left + f.x * FRAME_W, y: step.top + f.y * frameH,
    w: f.w * FRAME_W, h: f.h * frameH,
  });
  const boxA = a.trigger ? toBoard(a, a.trigger) : null;
  const boxB = b.target ? toBoard(b, b.target) : null;

  const rawFrom = boxA ? { x: boxA.x + boxA.w / 2, y: boxA.y + boxA.h / 2 } : aDefault;
  const rawTo = boxB ? { x: boxB.x + boxB.w / 2, y: boxB.y + boxB.h / 2 } : bDefault;

  arrows.push({ ...trimToBoxes(rawFrom, rawTo, boxA, boxB), boxA, boxB });
}


const framesHtml = steps.map((s) => `
  <div class="frame" style="left:${s.left}px; top:${s.top}px; width:${FRAME_W}px;">
    <img src="${s.png}" width="${FRAME_W}" height="${frameH}" alt="${esc(s.caption)}">
    ${captionBlockHtml(s)}
  </div>`).join('');

/**
 * Connector arrows are FILLED POLYGONS, never a stroked <line> with a small
 * marker — see the storyboard-arrow standing rule in .claude/rules/frontend.md.
 * A hairline between two 660px frames vanishes at the zoom a board is read at,
 * and the board stops looking like a sequence.
 */
const ARROW_RED = '#D62828';
const SHAFT_W = 5;    // shaft thickness
const HEAD_W  = 18;   // arrowhead width
const HEAD_L  = 20;   // arrowhead length

function arrowPolygon(from, to) {
  const dx = to.x - from.x, dy = to.y - from.y;
  const len = Math.hypot(dx, dy) || 1;
  const ux = dx / len, uy = dy / len;        // along the arrow
  const px = -uy, py = ux;                   // perpendicular
  const hl = Math.min(HEAD_L, len * 0.42);   // don't let the head eat a short arrow
  const sw = SHAFT_W / 2, hw = HEAD_W / 2;
  const bx = to.x - ux * hl, by = to.y - uy * hl;   // base of the head
  const pt = (x, y) => `${x.toFixed(1)},${y.toFixed(1)}`;
  return [
    pt(from.x + px * sw, from.y + py * sw),
    pt(bx + px * sw, by + py * sw),
    pt(bx + px * hw, by + py * hw),
    pt(to.x, to.y),
    pt(bx - px * hw, by - py * hw),
    pt(bx - px * sw, by - py * sw),
    pt(from.x - px * sw, from.y - py * sw),
  ].join(' ');
}

/**
 * Ring the exact element each arrow leaves from and lands on. One element is
 * often BOTH — the row a click filtered into view is also the row clicked next
 * — so rings are deduped by rect and drawn once rather than stacked.
 */
const marks = new Map();
const addMark = (box, role) => {
  if (!box) return;
  const key = [box.x, box.y, box.w, box.h].map((v) => v.toFixed(1)).join(':');
  const m = marks.get(key) || { box, roles: new Set() };
  m.roles.add(role);
  marks.set(key, m);
};
for (const a of arrows) { addMark(a.boxA, 'from'); addMark(a.boxB, 'to'); }

const marksSvg = [...marks.values()].map(({ box }) => {
  const r = pad(box, MARK_PAD);
  return `
    <rect x="${r.x.toFixed(1)}" y="${r.y.toFixed(1)}" width="${r.w.toFixed(1)}" height="${r.h.toFixed(1)}"
          rx="6" fill="none" stroke="${ARROW_RED}" stroke-width="2" filter="url(#soft)"/>`;
}).join('');

const arrowsSvg = marksSvg + arrows.map((a) => `
    <polygon points="${arrowPolygon(a.from, a.to)}"
             fill="${ARROW_RED}" stroke="none" filter="url(#soft)"/>`).join('');

const boardHtml = `<!doctype html>
<html lang="zh-Hant">
<head>
<meta charset="utf-8">
<title>${esc(group)} storyboard</title>
<style>
  :root { color-scheme: light; }
  body { margin:0; background:#f2f1ee; font-family:"Noto Sans TC","Microsoft JhengHei","PingFang TC",sans-serif; }
  .board { position:relative; width:${boardW}px; height:${boardH}px; }
  .frame { position:absolute; }
  .frame img { display:block; position:relative; z-index:1; border:1px solid #cfcdc8; border-radius:6px; box-shadow:0 6px 18px rgba(0,0,0,.13); background:#fff; }
${CAPTION_CSS}
  svg.links { position:absolute; inset:0; width:${boardW}px; height:${boardH}px; pointer-events:none; z-index:5; }
</style>
</head>
<body>
<div class="board">
  <svg class="links" viewBox="0 0 ${boardW} ${boardH}">
    <defs>
      <filter id="soft" x="-30%" y="-30%" width="160%" height="160%">
        <feDropShadow dx="0" dy="2" stdDeviation="3" flood-color="#000000" flood-opacity="0.22"/>
      </filter>
    </defs>${arrowsSvg}
  </svg>
  ${framesHtml}
</div>
</body>
</html>`;

await mkdir(dir, { recursive: true });
await writeFile(join(dir, 'storyboard.html'), boardHtml, 'utf8');

const boardPage = await browser.newPage({
  viewport: { width: boardW, height: boardH },
  deviceScaleFactor: 1,
});
await boardPage.goto(pathToFileURL(join(dir, 'storyboard.html')).href, { waitUntil: 'networkidle' });
/**
 * Caption blocks now carry the step's backend APIs, so their height varies with
 * the manifest. Measure each one against the slot the geometry reserved for it
 * (CAPTION_H) — an API line spilling past its slot would otherwise collide with
 * the frame below, silently, in the flat PNG.
 */
const boardProblems = await boardPage.evaluate(({ frameH, captionH }) => {
  const out = [];
  document.querySelectorAll('.frame').forEach((f, i) => {
    const slot = f.getBoundingClientRect().top + frameH + captionH;
    const last = f.lastElementChild.getBoundingClientRect().bottom;
    if (last > slot + 1) {
      out.push(`step ${i + 1}: caption + API lines overflow their slot by ${Math.ceil(last - slot)}px`);
    }
  });
  return out;
}, { frameH, captionH: CAPTION_H });
for (const m of boardProblems) console.log(`       ! board: ${m}`);

await boardPage.screenshot({ path: join(dir, 'storyboard.png'), fullPage: true });
await boardPage.pdf({
  path: join(dir, 'storyboard.pdf'),
  width: `${boardW}px`,
  height: `${boardH}px`,
  printBackground: true,
  pageRanges: '1',
});
await boardPage.close();
await browser.close();

console.log(`board: ${steps.length} steps, ${rows} row(s), ${boardW}x${boardH}`);
console.log(`wrote storyboard.html / storyboard.png / storyboard.pdf in ${dir}`);

// ---- verdict -------------------------------------------------------------
const badFrames = frameProblems.length;
if (manifestProblems.length || badFrames || boardProblems.length) {
  for (const m of manifestProblems) console.error(`manifest.json: ${m}`);
  for (const m of boardProblems) console.error(`storyboard layout: ${m}`);
  console.error(
    `SELF-CHECK FAIL — ${badFrames} of ${steps.length} frame(s), ${manifestProblems.length} manifest issue(s), ` +
    `${boardProblems.length} board layout issue(s). ` +
    `Fix the HTML above and re-run; open storyboard.png only if a message needs eyes on it.`
  );
  process.exit(1);
}
console.log(`SELF-CHECK PASS — ${steps.length}/${steps.length} frames clean, captions present. No need to open the PNG.`);
