const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

// Execute the actual reader script, with only layout and bridge APIs supplied by the fixture.
const renderer = fs.readFileSync(path.join(__dirname, '../../main/java/com/example/comiclab/ebook/html/EbookHtmlRenderer.kt'), 'utf8');
const script = renderer.match(/<\/article><script>([\s\S]*?)<\/script>/)[1];
function reader(tops, heights, viewport = 1000, anchors = {}) {
  let reported;
  const listeners = {};
  const timers = [];
  const maxScroll = Math.max(0, tops.at(-1) + heights.at(-1) - viewport);
  const window = {
    scrollY: 0, innerHeight: viewport,
    scrollTo(_x, y) { this.scrollY = Math.max(0, Math.min(maxScroll, y)); },
    addEventListener(name, fn) { listeners[name] = fn; },
    ComicLabBridge: { reportProgress(index, fraction) { reported = { index, fraction }; } }
  };
  const sections = tops.map((top, i) => ({
    offsetTop: top, offsetHeight: heights[i],
    getBoundingClientRect() { return { top: top - window.scrollY }; }
  }));
  vm.runInNewContext(script, {
    window,
    document: {
      documentElement: { scrollHeight: maxScroll + viewport },
      addEventListener(name, fn) { listeners['document:' + name] = fn; },
      querySelectorAll: () => sections,
      getElementById: id => anchors[id] !== undefined
        ? { getBoundingClientRect() { return { top: anchors[id] - window.scrollY }; } }
        : /^chapter-\d+$/.test(id) ? sections[Number(id.split('-')[1])] : null
    },
    setTimeout(fn) { timers.push(fn); return fn; },
    clearTimeout(fn) { const i = timers.indexOf(fn); if (i >= 0) timers.splice(i, 1); }
  });
  function flush() { while (timers.length) timers.shift()(); }
  return {
    window, flush,
    get progress() { return reported; },
    click(href) {
      let prevented = false;
      listeners['document:click']({
        target: { closest() { return { getAttribute() { return href; } }; } },
        preventDefault() { prevented = true; }
      });
      return prevented;
    },
    capture(y) { window.scrollTo(0, y); listeners.scroll(); flush(); return reported; },
    restore(progress, version = 2) {
      window.restoreComicLabProgress(progress.index, progress.fraction, version);
      flush();
      return window.scrollY;
    }
  };
}

test('positions in the final viewport of an intermediate chapter round trip', () => {
  const page = reader([100, 3100], [3000, 3000]);
  for (const y of [100, 1200, 2100, 2600, 3099, 3100, 4100, 5100]) {
    const progress = page.capture(y);
    assert.ok(Math.abs(page.restore(progress) - y) < 0.01, `position ${y} moved on restore`);
  }
});

test('short chapters and the physical end of the book restore without jumping back', () => {
  const page = reader([100, 600, 3600], [500, 3000, 300]);
  for (const y of [200, 550, 1000, 2500, 2900]) {
    const progress = page.capture(y);
    assert.ok(Math.abs(page.restore(progress) - y) < 0.01, `position ${y} moved on restore`);
  }
});

test('legacy saved fractions retain their original viewport-based meaning', () => {
  const page = reader([100, 3100], [3000, 3000]);
  assert.equal(page.restore({ index: 0, fraction: 0.75 }, 1), 1600);
});

test('footnote and return jumps update progress immediately and missing targets do not move', () => {
  const page = reader([100, 3100], [3000, 3000], 1000, {
    'chapter-1-a-note': 3400, 'chapter-0-a-source': 1400
  });
  assert.equal(page.window.jumpToComicLabAnchor('chapter-1-a-note'), true);
  assert.equal(page.window.scrollY, 3400);
  assert.equal(page.progress.index, 1);
  assert.equal(page.window.jumpToComicLabAnchor('chapter-0-a-source'), true);
  assert.equal(page.window.scrollY, 1400);
  assert.equal(page.progress.index, 0);
  assert.equal(page.window.jumpToComicLabAnchor('missing'), false);
  assert.equal(page.window.scrollY, 1400);
});

test('clicking a book link prevents native navigation and reports the new chapter', () => {
  const page = reader([100, 3100], [3000, 3000], 1000, { 'chapter-1-a-6e6f7465': 3400 });
  assert.equal(page.click('#chapter-1-a-6e6f7465'), true);
  assert.equal(page.window.scrollY, 3400);
  assert.equal(page.progress.index, 1);
  assert.equal(page.click('https://external/'), false);
  assert.equal(page.window.scrollY, 3400);
});
