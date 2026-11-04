/* Keep a bounded chapter window; short neighbours may fill a screen within the same content budget. */
(function () {
    'use strict';
    const article = document.querySelector('article');
    const count = Number(article.dataset.chapterCount);
    const charBudget = Number(article.dataset.windowCharBudget);
    const tagBudget = Number(article.dataset.windowTagBudget);
    const chapterLimit = 64;
    const header = article.querySelector('header');
    const previous = document.getElementById('chapter-previous');
    const next = document.getElementById('chapter-next');
    let generation = 0;
    let controller = null;
    let ready = false;
    let busy = false;
    let focused = 0;
    let reportTimer = null;
    let pendingTarget = null;
    let pinnedTarget = null;
    let boundaryBlocked = null;
    let restoreTimer = null;
    const sections = () => Array.from(article.querySelectorAll('[data-chapter-index]'));
    const indexOf = section => Number(section.dataset.chapterIndex);
    const top = node => node.getBoundingClientRect().top + window.scrollY;
    const maxScroll = () => Math.max(document.documentElement.scrollHeight - window.innerHeight, 0);
    function span(list, i, version) {
        if (version === 1) return Math.max(list[i].offsetHeight - window.innerHeight, 0);
        const end = i + 1 < list.length ? top(list[i + 1]) :
            indexOf(list[i]) === count - 1 ? maxScroll() : top(list[i]) + list[i].offsetHeight;
        return Math.max(end - top(list[i]), 1);
    }
    function position() {
        const list = sections();
        if (!list.length) return null;
        if (pinnedTarget && Math.abs(window.scrollY - pinnedTarget.y) < 0.5) {
            return { index: pinnedTarget.index, fraction: pinnedTarget.fraction };
        }
        pinnedTarget = null;
        let i = 0;
        for (let n = 0; n < list.length; n++) if (top(list[n]) <= window.scrollY + 0.5) i = n;
        if (maxScroll() === 0) {
            const selected = list.findIndex(section => indexOf(section) === focused);
            if (selected >= 0) i = selected;
        }
        const atBookEnd = indexOf(list[list.length - 1]) === count - 1 &&
            maxScroll() > 0 && window.scrollY >= maxScroll() - 0.5;
        if (atBookEnd) i = list.length - 1;
        return { index: indexOf(list[i]), fraction: atBookEnd ? 1 :
            Math.max(0, Math.min(1, (window.scrollY - top(list[i])) / span(list, i, 2))) };
    }
    function report() {
        if (!ready || busy || pendingTarget) return;
        const current = position();
        if (!current) return;
        focused = current.index;
        previous.disabled = focused === 0;
        next.disabled = focused === count - 1;
        if (window.ComicLabBridge) window.ComicLabBridge.reportProgress(current.index, current.fraction, window.comicLabNavigationToken || 0);
    }
    function error() {
        if (window.ComicLabBridge && window.ComicLabBridge.documentError) window.ComicLabBridge.documentError(window.comicLabNavigationToken || 0);
    }
    function applyTarget(target) {
        const list = sections();
        const i = list.findIndex(section => indexOf(section) === target.index);
        if (i < 0) return false;
        const anchor = target.anchor ? document.getElementById(target.anchor) : null;
        if (target.anchor && !anchor) return false;
        let y = anchor ? top(anchor) : top(list[i]) + target.fraction * span(list, i, target.version);
        if (!anchor && target.version !== 1 && target.index === count - 1 && target.fraction >= 1) y = maxScroll();
        window.scrollTo(0, y);
        pinnedTarget = y > maxScroll() + 0.5 ? { index: target.index, fraction: target.fraction, y: window.scrollY } : null;
        return true;
    }
    function finishRestore() {
        if (!pendingTarget) return;
        applyTarget(pendingTarget);
        pendingTarget = null;
        clearTimeout(restoreTimer);
        report();
    }
    async function loadAround(index, target) {
        const request = ++generation;
        if (controller) controller.abort();
        controller = new AbortController();
        const signal = controller.signal;
        busy = true;
        boundaryBlocked = null;
        clearTimeout(reportTimer);
        clearTimeout(restoreTimer);
        pendingTarget = null;
        const old = sections();
        const desired = [];
        for (let n = Math.max(0, index - 1); n <= Math.min(count - 1, index + 1); n++) desired.push(n);
        try {
            const readSection = async n => {
                const existing = old.find(section => indexOf(section) === n);
                if (existing) return existing;
                const response = await fetch('/chapters/' + n, { signal, cache: 'no-store' });
                if (!response.ok) throw new Error('Chapter is unavailable');
                const template = document.createElement('template');
                const html = await response.text();
                if (request !== generation) throw new Error('Obsolete chapter');
                template.innerHTML = html;
                const section = template.content.querySelector('#chapter-' + n);
                if (!section) throw new Error('Invalid chapter');
                let tags = 0;
                for (let i = 0; i < html.length; i++) if (html.charAt(i) === '<') tags++;
                section.ebookSize = { chars: html.length, tags };
                return section;
            };
            const loaded = await Promise.all(desired.map(readSection));
            if (request !== generation) return false;
            let chars = loaded.reduce((sum, section) => sum + section.ebookSize.chars, 0);
            let tags = loaded.reduce((sum, section) => sum + section.ebookSize.tags, 0);
            if (chars > charBudget || tags > tagBudget) throw new Error('Chapter window exceeds its budget');
            if (target && target.anchor && target.anchor !== 'chapter-' + index &&
                !loaded.find(section => indexOf(section) === index).querySelector('#' + target.anchor)) {
                busy = false;
                return false;
            }
            // Capture just before committing: the user may have scrolled while IO was pending.
            const before = position();
            if (!target && before && !desired.includes(before.index)) {
                return loadAround(before.index, null);
            }
            const reference = before && old.find(section => indexOf(section) === before.index);
            const offset = reference ? top(reference) - window.scrollY : 0;
            old.filter(section => !desired.includes(indexOf(section))).forEach(section => section.remove());
            loaded.forEach(section => article.appendChild(section));
            header.hidden = desired[0] !== 0;
            focused = index;
            ready = true;
            if (target) {
                if (!applyTarget(target)) {
                    busy = false;
                    error();
                    return false;
                }
                pendingTarget = target;
            } else if (reference && desired.includes(indexOf(reference))) {
                window.scrollTo(0, top(reference) - offset);
            }
            // A short trailing chapter need not reach the top of the viewport before more content is needed.
            // Fill only a screen ahead/behind, with both aggregate complexity and chapter-count ceilings.
            while (loaded.length < chapterLimit) {
                const first = indexOf(loaded[0]);
                const last = indexOf(loaded[loaded.length - 1]);
                const forward = last < count - 1 && maxScroll() - window.scrollY < window.innerHeight;
                const backward = first > 0 && window.scrollY < window.innerHeight;
                if (!forward && !backward) break;
                const section = await readSection(forward ? last + 1 : first - 1);
                if (request !== generation) return false;
                if (chars + section.ebookSize.chars > charBudget || tags + section.ebookSize.tags > tagBudget) {
                    boundaryBlocked = index;
                    break;
                }
                const keepTarget = target && !target.cancelled;
                const current = position();
                const anchor = sections().find(node => indexOf(node) === (keepTarget ? target.index : current && current.index));
                const oldOffset = anchor ? top(anchor) - window.scrollY : 0;
                if (forward) { loaded.push(section); article.appendChild(section); }
                else { loaded.unshift(section); article.insertBefore(section, loaded[1]); }
                header.hidden = indexOf(loaded[0]) !== 0;
                chars += section.ebookSize.chars;
                tags += section.ebookSize.tags;
                if (keepTarget) applyTarget(target);
                else if (anchor) window.scrollTo(0, top(anchor) - oldOffset);
            }
            if (loaded.length >= chapterLimit) boundaryBlocked = index;
            busy = false;
            if (target && !target.cancelled) {
                // Reapply the requested coordinate as images change layout; user input ends restoration.
                const images = sections().flatMap(section => Array.from(section.querySelectorAll('img')))
                    .filter(image => !image.complete);
                if (images.length) {
                    let remaining = images.length;
                    images.forEach(image => {
                        const settled = () => {
                            if (request !== generation || !pendingTarget) return;
                            applyTarget(pendingTarget);
                            if (--remaining === 0) finishRestore();
                        };
                        image.addEventListener('load', settled, { once: true });
                        image.addEventListener('error', settled, { once: true });
                    });
                    restoreTimer = setTimeout(() => { if (request === generation) finishRestore(); }, 3000);
                } else finishRestore();
            } else report();
            return true;
        } catch (failure) {
            if (request !== generation) return false;
            controller.abort();
            busy = false;
            error();
            return false;
        }
    }
    window.restoreComicLabProgress = function (chapter, fraction, version) {
        if (!Number.isInteger(chapter) || chapter < 0 || chapter >= count || !Number.isFinite(fraction)) return Promise.resolve(false);
        return loadAround(chapter, { index: chapter, fraction: Math.max(0, Math.min(1, fraction)), version: version || 2 });
    };
    window.jumpToComicLabAnchor = function (anchor) {
        const match = /^chapter-(\d+)(?:-a-[0-9a-f]+)?$/.exec(anchor);
        const index = match ? Number(match[1]) : -1;
        if (index < 0 || index >= count) return Promise.resolve(false);
        return loadAround(index, { index, fraction: 0, version: 2, anchor });
    };
    window.jumpToComicLabChapter = index => window.restoreComicLabProgress(index, 0, 2);
    window.addEventListener('scroll', function () {
        clearTimeout(reportTimer);
        reportTimer = setTimeout(function () {
            if (!ready || busy || pendingTarget) return;
            const current = position();
            const list = sections();
            const nearBoundary = list.length &&
                ((indexOf(list[list.length - 1]) < count - 1 && maxScroll() - window.scrollY < window.innerHeight) ||
                 (indexOf(list[0]) > 0 && window.scrollY < window.innerHeight));
            if (current && (current.index !== focused || (nearBoundary && boundaryBlocked !== current.index))) loadAround(current.index, null);
            else report();
        }, 80);
    });
    ['touchstart', 'wheel', 'keydown'].forEach(name => window.addEventListener(name, function () {
        if (pendingTarget) {
            pendingTarget.cancelled = true;
            pendingTarget = null;
            clearTimeout(restoreTimer);
            report();
        }
    }, { passive: true }));
    document.addEventListener('click', function (event) {
        if (event.defaultPrevented) return;
        const link = event.target.closest ? event.target.closest('a[href]') : null;
        const href = link ? link.getAttribute('href') : null;
        if (href && /^#chapter-\d+(?:-a-[0-9a-f]+)?$/.test(href)) {
            event.preventDefault();
            window.jumpToComicLabAnchor(href.substring(1));
        }
    });
    previous.addEventListener('click', () => window.jumpToComicLabChapter(Math.max(0, focused - 1)));
    next.addEventListener('click', () => window.jumpToComicLabChapter(Math.min(count - 1, focused + 1)));
})();
