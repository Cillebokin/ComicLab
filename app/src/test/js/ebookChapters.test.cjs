const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const script = fs.readFileSync(path.join(__dirname, '../../main/assets/ebook-chapters.js'), 'utf8');

// Only DOM layout, transport and Android bridge are supplied; execute the production reader script.
function reader(count = 100, options = {}) {
  const listeners = {}, timers = [], reports = [], requests = [], blocked = new Map();
  const images = new Map(), heights = new Map();
  const header = { hidden: false }, buttons = {};
  let errors = 0, peak = 0;
  const height = i => heights.get(i) ?? options.height ?? 3000;
  const nodes = [];
  const viewport = 1000;
  const headerHeight = () => header.hidden ? 0 : 100;
  const documentHeight = () => headerHeight() + nodes.reduce((n, s) => n + s.offsetHeight, 0) + 60;
  const window = {
    scrollY: 0, innerHeight: viewport,
    scrollTo(_x, y) { this.scrollY = Math.max(0, Math.min(Math.max(0, documentHeight() - viewport), y)); },
    addEventListener(name, handler) { listeners[name] = handler; },
    ComicLabBridge: {
      reportProgress(index, fraction, token) { reports.push({ index, fraction, token }); },
      documentError() { errors++; }
    }
  };
  function makeSection(index) {
    const section = {
      dataset: { chapterIndex: String(index) },
      get offsetHeight() { return height(index); },
      getBoundingClientRect() {
        const preceding = nodes.slice(0, nodes.indexOf(this)).reduce((n, s) => n + s.offsetHeight, 0);
        return { top: headerHeight() + preceding - window.scrollY };
      },
      remove() { const at = nodes.indexOf(this); if (at >= 0) nodes.splice(at, 1); },
      querySelector(selector) { return options.anchors?.[selector.substring(1)] === undefined ? null : {}; },
      querySelectorAll() { return images.get(index) || []; }
    };
    return section;
  }
  const article = {
    dataset: { chapterCount: String(count), windowCharBudget: String(options.charBudget || 24000000), windowTagBudget: '150000' },
    querySelector() { return header; },
    querySelectorAll() { return nodes.slice(); },
    appendChild(section) { section.remove(); nodes.push(section); peak = Math.max(peak, nodes.length); },
    insertBefore(section, sibling) { section.remove(); nodes.splice(nodes.indexOf(sibling), 0, section); peak = Math.max(peak, nodes.length); }
  };
  const document = {
    documentElement: { get scrollHeight() { return documentHeight(); } },
    querySelector() { return article; },
    addEventListener(name, fn) { listeners['document:' + name] = fn; },
    createElement(name) {
      assert.equal(name, 'template');
      let html;
      return {
        set innerHTML(value) { html = value; },
        content: { querySelector(selector) {
          const index = Number(selector.substring('#chapter-'.length));
          return html === 'CHAPTER:' + index ? makeSection(index) : null;
        } }
      };
    },
    getElementById(id) {
      if (id.startsWith('chapter-previous') || id.startsWith('chapter-next')) {
        return buttons[id] ||= { addEventListener(_name, fn) { this.click = fn; } };
      }
      const match = /^chapter-(\d+)(-a-[0-9a-f]+)?$/.exec(id);
      const section = match && nodes.find(s => Number(s.dataset.chapterIndex) === Number(match[1]));
      if (!section) return null;
      if (!match[2]) return section;
      const offset = options.anchors?.[id];
      return offset === undefined ? null : { getBoundingClientRect() { return { top: section.getBoundingClientRect().top + offset }; } };
    }
  };
  vm.runInNewContext(script, {
    document, window, AbortController,
    async fetch(url, { signal }) {
      const index = Number(url.substring('/chapters/'.length));
      requests.push(index);
      if (options.block?.includes(index)) {
        await new Promise((resolve, reject) => {
          blocked.set(index, resolve);
          signal.addEventListener('abort', () => reject(new Error('aborted')), { once: true });
        });
      }
      return { ok: !options.fail?.includes(index), async text() { return 'CHAPTER:' + index; } };
    },
    setTimeout(fn, delay) { const timer = { fn, delay }; timers.push(timer); return timer; },
    clearTimeout(timer) { const i = timers.indexOf(timer); if (i >= 0) timers.splice(i, 1); }
  });
  async function flush(limit = 250) {
    for (let n = 0; n < 40; n++) {
      const timer = timers.find(t => t.delay <= limit);
      if (timer) { timers.splice(timers.indexOf(timer), 1); timer.fn(); }
      await Promise.resolve();
    }
  }
  return {
    window, requests, reports, flush, blocked, heights, buttons,
    get errors() { return errors; },
    get progress() { return reports.at(-1); },
    get chapters() { return nodes.map(s => Number(s.dataset.chapterIndex)); },
    get peak() { return peak; },
    async scroll(y) { window.scrollTo(0, y); listeners.scroll(); await flush(); },
    input() { listeners.touchstart(); },
    image(index) {
      const handlers = {};
      const image = { complete: false, addEventListener(name, fn) { handlers[name] = fn; } };
      images.set(index, [image]);
      return { load() { image.complete = true; handlers.load?.(); } };
    },
    click(href) {
      let prevented = false;
      listeners['document:click']({
        target: { closest() { return { getAttribute() { return href; } }; } },
        preventDefault() { prevented = true; }
      });
      return prevented;
    }
  };
}

test('restoring a distant chapter loads only its neighbours and retains the chapter fraction', async () => {
  const page = reader(10000);
  assert.equal(page.reports.length, 0, 'shell must not replace saved progress with zero');
  assert.equal(await page.window.restoreComicLabProgress(5000, 0.75, 2), true);
  assert.deepEqual(page.requests, [4999, 5000, 5001]);
  assert.deepEqual(page.chapters, [4999, 5000, 5001]);
  assert.equal(page.progress.index, 5000);
  assert.equal(page.progress.fraction, 0.75);
});

test('scrolling forward and back evicts distant chapters without moving the visible paragraph', async () => {
  const page = reader();
  await page.window.restoreComicLabProgress(20, 0.9, 2);
  await page.scroll(6300); // 300px into chapter 21
  assert.deepEqual(page.chapters, [20, 21, 22]);
  assert.equal(page.window.scrollY, 3300);
  assert.equal(page.progress.index, 21);
  assert.equal(page.progress.fraction, 0.1);
  await page.scroll(2800); // 2800px into chapter 20
  assert.deepEqual(page.chapters, [19, 20, 21]);
  assert.equal(page.window.scrollY, 5800);
  assert.equal(page.progress.index, 20);
  assert.ok(Math.abs(page.progress.fraction - 2800 / 3000) < 1e-10);
  assert.equal(page.peak, 3);
});

test('cross-chapter footnotes and return links share the bounded loading path', async () => {
  const page = reader(100, { anchors: { 'chapter-70-a-6e': 500, 'chapter-2-a-72': 900 } });
  await page.window.restoreComicLabProgress(2, 0.3, 2);
  assert.equal(page.click('#chapter-70-a-6e'), true);
  await page.flush();
  assert.deepEqual(page.chapters, [69, 70, 71]);
  assert.equal(page.progress.index, 70);
  assert.equal(page.window.scrollY, 3500);
  await page.window.jumpToComicLabAnchor('chapter-2-a-72');
  assert.equal(page.progress.index, 2);
  assert.equal(page.window.scrollY, 3900);
  assert.equal(page.peak, 3);
});

test('legacy coordinates and final-book end restore with their original meaning', async () => {
  const page = reader(10);
  await page.window.restoreComicLabProgress(5, 0.75, 1);
  assert.equal(page.window.scrollY, 4500);
  await page.window.restoreComicLabProgress(9, 1, 2);
  assert.deepEqual(page.chapters, [8, 9]);
  assert.equal(page.progress.index, 9);
  assert.equal(page.progress.fraction, 1);
});

test('a newer navigation cancels a slow older request and prevents progress rollback', async () => {
  const page = reader(100, { block: [40] });
  const old = page.window.restoreComicLabProgress(40, 0.7, 2);
  await page.flush();
  await page.window.restoreComicLabProgress(80, 0.4, 2);
  page.blocked.get(40)();
  assert.equal(await old, false);
  assert.equal(page.progress.index, 80);
  assert.equal(page.progress.fraction, 0.4);
  assert.deepEqual(page.chapters, [79, 80, 81]);
  assert.equal(page.errors, 0);
});

test('failed or invalid navigation preserves the displayed content and last valid progress', async () => {
  const page = reader(100, { fail: [60] });
  await page.window.restoreComicLabProgress(10, 0.2, 2);
  const before = page.progress;
  assert.equal(await page.window.restoreComicLabProgress(60, 0.5, 2), false);
  assert.deepEqual(page.chapters, [9, 10, 11]);
  assert.equal(page.progress, before);
  assert.equal(page.errors, 1);
  assert.equal(await page.window.jumpToComicLabAnchor('chapter-50-a-6e6f'), false);
  assert.deepEqual(page.chapters, [9, 10, 11]);
  assert.equal(await page.window.jumpToComicLabAnchor('javascript:alert(1)'), false);
  assert.equal(await page.window.restoreComicLabProgress(100, 0, 2), false);
  assert.equal(await page.window.restoreComicLabProgress(1, NaN, 2), false);
  assert.equal(page.progress, before);
});

test('late image layout reapplies the restore target before progress is persisted', async () => {
  const page = reader(100);
  const image = page.image(30);
  await page.window.restoreComicLabProgress(30, 0.5, 2);
  assert.equal(page.reports.length, 0);
  page.heights.set(30, 6000);
  image.load();
  assert.equal(page.window.scrollY, 6000);
  assert.equal(page.progress.index, 30);
  assert.equal(page.progress.fraction, 0.5);
});

test('touch input ends image restoration so a late image cannot pull the reader back', async () => {
  const page = reader(100);
  const image = page.image(30);
  await page.window.restoreComicLabProgress(30, 0.5, 2);
  page.input();
  await page.scroll(4900);
  const before = page.window.scrollY;
  page.heights.set(30, 6000);
  image.load();
  assert.equal(page.window.scrollY, before);
});

test('very short chapters remain reachable through adjacent chapter controls', async () => {
  const page = reader(100, { height: 100 });
  await page.window.restoreComicLabProgress(0, 0, 2);
  for (let n = 1; n <= 20; n++) {
    await page.buttons['chapter-next'].click();
    assert.equal(page.progress.index, n);
    assert.ok(page.chapters.length <= 64);
  }
});

test('a short trailing neighbour does not trap continuous scrolling in the previous chapter', async () => {
  const page = reader(6, { height: 100 });
  page.heights.set(0, 3000);
  await page.window.restoreComicLabProgress(0, 0, 2);
  await page.scroll(1e8);
  assert.ok(page.requests.includes(2), 'the short neighbour must trigger more reading content before its top reaches the viewport');
  for (let n = 0; n < 6; n++) await page.scroll(1e8);
  assert.equal(page.progress.index, 5);
  assert.equal(page.progress.fraction, 1);
});

test('a clamped tail-chapter restore preserves the requested chapter and previous navigation works', async () => {
  const page = reader(5, { height: 100 });
  page.heights.set(2, 3000);
  await page.window.restoreComicLabProgress(3, 0, 2);
  assert.equal(page.progress.index, 3);
  assert.equal(page.progress.fraction, 0);
  await page.buttons['chapter-previous'].click();
  assert.equal(page.progress.index, 2);
});

test('filling short neighbours has both a chapter-count and aggregate-character budget', async () => {
  const page = reader(10000, { height: 1, charBudget: 100 });
  await page.window.restoreComicLabProgress(5000, 0, 2);
  assert.ok(page.chapters.length <= 64);
  assert.ok(page.chapters.length > 3, 'short chapters should fill a viewport when the budget allows');
  assert.ok(page.chapters.length < 10, 'short layout cannot bypass the aggregate byte budget');
  assert.equal(page.progress.index, 5000);
  const requests = page.requests.length;
  await page.scroll(0);
  await page.scroll(0);
  assert.equal(page.requests.length, requests, 'a full window budget must not cause repeated prefetch requests');
});

test('touching during a slow short-chapter fill preserves user scrolling and eventually reports it', async () => {
  const page = reader(10, { height: 100, block: [3] });
  page.heights.set(1, 3000);
  const navigation = page.window.restoreComicLabProgress(1, 0.9, 2);
  await page.flush();
  assert.ok(page.blocked.has(3));
  page.input();
  await page.scroll(500);
  page.blocked.get(3)();
  await navigation;
  assert.equal(page.window.scrollY, 500, 'late neighbour must not reapply an interrupted restore');
  assert.equal(page.progress.index, 1);
  assert.equal(page.progress.fraction, 0.1);
});
