#!/usr/bin/env node
/**
 * Batch Mermaid renderer — ONE Chromium for the whole batch.
 *
 * Usage: node docs/_scripts/render-mermaid.mjs <jobs.json>
 *
 * jobs.json: [{ "src": "...mmd", "out": "...png",
 *               "background": "white", "width": 1600, "scale": 2 }, ...]
 *
 * Exists so no command ever shells out to `npx mmdc` once per diagram —
 * that spawns a separate npm shell + Chromium per image.
 */
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import puppeteer from 'puppeteer';
import { renderMermaid } from '@mermaid-js/mermaid-cli';

const jobsPath = process.argv[2];
if (!jobsPath) {
  console.error('usage: render-mermaid.mjs <jobs.json>');
  process.exit(2);
}

const jobs = JSON.parse(await readFile(jobsPath, 'utf8'));
const browser = await puppeteer.launch({
  headless: 'new',
  args: ['--no-sandbox', '--disable-dev-shm-usage'],
});

let failed = 0;
for (const job of jobs) {
  try {
    const definition = await readFile(job.src, 'utf8');
    const { data } = await renderMermaid(browser, definition, 'png', {
      backgroundColor: job.background ?? 'white',
      viewport: {
        width: job.width ?? 1600,
        height: job.height ?? 900,
        deviceScaleFactor: job.scale ?? 2,
      },
    });
    await mkdir(dirname(resolve(job.out)), { recursive: true });
    await writeFile(job.out, data);
    console.log(`OK   ${job.out}`);
  } catch (err) {
    failed++;
    console.error(`FAIL ${job.out}\n     ${err.message.split('\n')[0]}`);
  }
}

await browser.close();
console.log(`\n${jobs.length - failed}/${jobs.length} rendered`);
process.exit(failed ? 1 : 0);
