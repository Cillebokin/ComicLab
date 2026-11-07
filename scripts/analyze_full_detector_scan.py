"""Analyze locally recorded detector scans; DBNet is a reference, not ground truth."""

import argparse
from collections import Counter, defaultdict
import hashlib
import json
import math
from pathlib import Path, PureWindowsPath
import sqlite3
import time


def completion_state(pending, errors):
    return pending == 0, pending == 0 and errors == 0


def classify_layout(width, height):
    if width <= 0 or height <= 0:
        raise ValueError('invalid source dimensions')
    if height > 3 * width:
        return 'long_portrait'
    return 'landscape' if width > height else 'portrait_or_square'


def rectangle(quad):
    if len(quad) != 4 or any(len(p) != 2 for p in quad):
        raise ValueError('invalid quadrilateral')
    coords = [float(v) for point in quad for v in point]
    if not all(math.isfinite(v) for v in coords):
        raise ValueError('non-finite quadrilateral')
    xs, ys = coords[::2], coords[1::2]
    return min(xs), min(ys), max(xs), max(ys)


def filter_by_aspect(boxes, min_ratio):
    selected = []
    for q in boxes:
        l, t, r, b = rectangle(q)
        width, height = r - l, b - t
        if width > 0 and height > 0 and max(width / height, height / width) >= min_ratio:
            selected.append(q)
    return selected


def overlap_pairs(reference, candidates, threshold):
    refs = [rectangle(q) for q in reference]
    dets = [rectangle(q) for q in candidates]
    matches = []
    for i, a in enumerate(refs):
        aa = (a[2] - a[0]) * (a[3] - a[1])
        for j, b in enumerate(dets):
            iw = min(a[2], b[2]) - max(a[0], b[0])
            ih = min(a[3], b[3]) - max(a[1], b[1])
            if iw <= 0 or ih <= 0:
                continue
            intersection = iw * ih
            union = aa + (b[2] - b[0]) * (b[3] - b[1]) - intersection
            overlap = intersection / union if union > 0 else 0
            if overlap >= threshold:
                matches.append((overlap, i, j))
    return sorted(matches, reverse=True)


def reference_indexes(matches, allowed=None):
    matched_refs, matched_dets = set(), set()
    for _, i, j in matches:
        if allowed is not None and j not in allowed:
            continue
        if i not in matched_refs and j not in matched_dets:
            matched_refs.add(i); matched_dets.add(j)
    return matched_refs


def count_pairs(matches, allowed=None):
    return len(reference_indexes(matches, allowed))


def greedy_matches(reference, candidates, threshold):
    return count_pairs(overlap_pairs(reference, candidates, threshold))


def aspect_variant_counts(reference, candidates, threshold):
    pairs = overlap_pairs(reference, candidates, threshold)
    results = {'base': (len(candidates), count_pairs(pairs))}
    for label, ratio in (('150', 1.5), ('175', 1.75)):
        allowed = set()
        for i, quad in enumerate(candidates):
            l, t, r, b = rectangle(quad)
            width, height = r - l, b - t
            if width > 0 and height > 0 and max(width / height, height / width) >= ratio:
                allowed.add(i)
        results[label] = (len(allowed), count_pairs(pairs, allowed))
    return results


def reference_strata(row):
    refs = row['dbnet']['boxes']
    matched = {key: reference_indexes(overlap_pairs(refs, row[key]['boxes'], .3)) for key in ('light', 'midtones')}
    result = defaultdict(Counter)
    for i, quad in enumerate(refs):
        l, t, r, b = rectangle(quad)
        width, height = r - l, b - t
        orientation = 'vertical' if height > width * 1.5 else 'horizontal' if width > height * 1.5 else 'other'
        short = min(width, height)
        size = '<8' if short < 8 else '8-15' if short < 16 else '16-31' if short < 32 else '32+'
        bucket = result[f'{orientation}/{size}']
        bucket['dbnet'] += 1
        for key in ('light', 'midtones'):
            bucket[f'{key}_matched'] += int(i in matched[key])
    return result


def summarize_row(row):
    reference = row['dbnet']
    if reference['lineCount'] != len(reference['boxes']):
        raise ValueError('reference count does not match boxes')
    result = {'pages': 1, 'dbnet_lines': reference['lineCount'], 'dbnet_mask_white': reference['maskWhite']}
    for key in ('light', 'midtones'):
        candidate = row[key]
        if candidate['lineCount'] != len(candidate['boxes']):
            raise ValueError(f'{key} count does not match boxes')
        variants = aspect_variant_counts(reference['boxes'], candidate['boxes'], .3)
        result[f'{key}_candidates'], result[f'{key}_matched'] = variants['base']
        result[f'{key}_capped'] = int(candidate['capReached'])
        result[f'{key}_mask_white'] = candidate['maskWhite']
        result[f'{key}_mask_intersection'] = candidate['maskIntersection']
        result[f'{key}_mask_union'] = candidate['maskUnion']
        # Offline simulation only: actual detector mask and post-cap behavior are unchanged.
        for suffix in ('150', '175'):
            result[f'{key}_aspect{suffix}_candidates'], result[f'{key}_aspect{suffix}_matched'] = variants[suffix]
    return result


def manifest_digest(db):
    digest = hashlib.sha256()
    for row in db.execute('SELECT id,relpath,size,mtime_ns FROM images ORDER BY id'):
        digest.update(json.dumps(row, ensure_ascii=False, separators=(',', ':')).encode('utf-8'))
        digest.update(b'\n')
    return digest.hexdigest()


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument('--databases', nargs='+', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args(argv)
    if len({p.resolve() for p in args.databases}) != len(args.databases):
        raise ValueError('database paths must be distinct')
    dbs = [sqlite3.connect('file:' + p.resolve(strict=True).as_posix() + '?mode=ro', uri=True) for p in args.databases]
    try:
        configs = [db.execute("SELECT value FROM metadata WHERE key='config'").fetchone()[0] for db in dbs]
        if len(set(configs)) != 1:
            raise ValueError('shard experiment configurations differ')
        shard_count = json.loads(configs[0])['shardCount']
        indexes = [int(db.execute("SELECT value FROM metadata WHERE key='shard_index'").fetchone()[0]) for db in dbs]
        if sorted(indexes) != list(range(shard_count)):
            raise ValueError('shards are missing, duplicated or inconsistent')
        manifests = [manifest_digest(db) for db in dbs]
        if len(set(manifests)) != 1:
            raise ValueError('shard image snapshots differ')
        totals = Counter(); by_book = defaultdict(Counter); by_split = defaultdict(Counter); by_layout = defaultdict(Counter); by_reference_shape = defaultdict(Counter); errors = []
        args.output.mkdir(parents=True, exist_ok=True)
        pending_path = args.output / 'per-page.pending.jsonl'
        final_path = args.output / 'per-page.jsonl'
        with pending_path.open('w', encoding='utf-8', newline='\n') as out:
            for db, shard in zip(dbs, indexes):
                stray = db.execute('SELECT count(*) FROM images WHERE status IS NOT NULL AND id % ? != ?', (shard_count, shard)).fetchone()[0]
                if stray:
                    raise ValueError(f'shard {shard} contains results assigned to another shard')
                for image_id, relpath, status, raw in db.execute('SELECT id,relpath,status,result FROM images WHERE id % ? = ? ORDER BY id', (shard_count, shard)):
                    totals['snapshot_pages'] += 1
                    if status is None:
                        totals['pending'] += 1
                        continue
                    if status != 'ok':
                        totals['errors'] += 1
                        errors.append({'id': image_id, 'path': relpath, 'detail': json.loads(raw) if raw else None})
                        continue
                    row = json.loads(raw)
                    counts = summarize_row(row)
                    book = PureWindowsPath(relpath).parts[0] if len(PureWindowsPath(relpath).parts) > 1 else '(root)'
                    split = 'tune' if int(hashlib.sha256(relpath.encode('utf-8')).hexdigest()[:8], 16) % 5 < 3 else 'holdout'
                    layout = classify_layout(row['sourceWidth'], row['sourceHeight'])
                    totals.update(counts); by_book[book].update(counts); by_split[split].update(counts); by_layout[layout].update(counts)
                    for label, values in reference_strata(row).items():
                        by_reference_shape[label].update(values)
                    out.write(json.dumps({'id': image_id, 'path': relpath, 'split': split, 'layout': layout, **counts}, ensure_ascii=False, separators=(',', ':')) + '\n')
        pending_path.replace(final_path)
        all_accounted, all_succeeded = completion_state(totals['pending'], totals['errors'])
        report = {'generated_at': time.strftime('%Y-%m-%d %H:%M:%S%z'), 'all_accounted': all_accounted, 'all_succeeded': all_succeeded,
                  'manifest_sha256': manifests[0], 'shards': indexes, 'summary': totals,
                  'by_book': by_book, 'by_split': by_split, 'by_layout': by_layout, 'by_reference_shape': by_reference_shape, 'errors': errors,
                  'limitations': ['DBNet is an imperfect reference, not ground truth.', 'IoU and reference shape use axis-aligned rectangles; shape size is in preview pixels.', 'Mask counts are raw preview masks, not final inpainting quality.', 'Aspect filtering is a post-detection simulation: masks and previously capped proposals are not regenerated.', 'Split is within works; related pages may be correlated and prior samples informed tuning.']}
        temporary = args.output / 'summary.pending.json'
        temporary.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
        temporary.replace(args.output / 'summary.json')
        print(json.dumps({'all_accounted': all_accounted, 'all_succeeded': all_succeeded, 'summary': totals}, ensure_ascii=False, indent=2))
    finally:
        for db in dbs:
            db.close()


if __name__ == '__main__':
    main()
