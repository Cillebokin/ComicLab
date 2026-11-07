"""Resumable, local-only Android detector comparison over a snapshot of image paths.

Only named staging files and an experiment SQLite database are written; source files
are opened read-only. Run with an isolated test APK installed on an Android emulator.
"""

import argparse
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import sys
import time
import uuid

APP_ID = 'com.example.comiclab.detectorprobe'
RUNNER = APP_ID + '.test/androidx.test.runner.AndroidJUnitRunner'
DEVICE_DIR = '/data/local/tmp/comiclab-detector-fullscan'
PRIVATE_DIR = 'files/detector-comparison'
SUPPORTED = {'.jpg', '.jpeg', '.png', '.webp', '.bmp', '.gif', '.tif', '.tiff', '.avif', '.heic', '.heif'}


def batches(items, size):
    if size <= 0:
        raise ValueError('batch size must be positive')
    for start in range(0, len(items), size):
        yield items[start:start + size]


def belongs_to_shard(image_id, index, count):
    if count <= 0 or not 0 <= index < count:
        raise ValueError('invalid shard')
    return image_id % count == index


@contextmanager
def workspace_lock(output):
    # An OS lock is released by the OS if a worker crashes; the lock file itself is harmless.
    handle = (output / '.worker.lock').open('a+b')
    try:
        handle.seek(0, os.SEEK_END)
        if handle.tell() == 0:
            handle.write(b'0'); handle.flush()
        handle.seek(0)
        if os.name == 'nt':
            import msvcrt
            msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        yield
    finally:
        handle.close()


def parse_batch_output(text, batch_id, slots):
    results = {}
    for line in text.splitlines():
        try:
            row = json.loads(line)
        except (ValueError, TypeError):
            continue
        if not isinstance(row, dict) or row.get('batchId') != batch_id or row.get('status') not in ('ok', 'error'):
            continue
        image_id = slots.get(row.get('slot'))
        if image_id is not None and image_id not in results:
            results[image_id] = row
    return results


def adb(serial, *args, timeout=180, check=True):
    cmd = ['adb', '-s', serial, *map(str, args)]
    result = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError(f'adb {args[0]} failed: {result.stderr.decode("utf-8", "replace")[-800:]}')
    return result


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def configuration(root, workspace):
    paths = [
        'scripts/full_detector_scan.py',
        'app/src/main/java/li/joye/yakuyomi/engine/LightTextDetector.kt',
        'app/src/main/java/li/joye/yakuyomi/engine/Detector.kt',
        'app/src/main/java/li/joye/yakuyomi/engine/DetectorConfig.kt',
        'app/src/main/java/li/joye/yakuyomi/engine/ImageOps.kt',
        'app/src/main/java/li/joye/yakuyomi/engine/Geometry.kt',
        'app/src/main/java/li/joye/yakuyomi/engine/NcnnBackend.kt',
        'app/src/main/cpp/translation.cpp',
        'app/src/main/assets/translation/models.json',
        'app/src/androidTest/java/com/example/comiclab/FullDetectorScanDeviceTest.kt',
        'app/build/outputs/apk/debug/app-debug.apk',
        'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk',
        'app/build/translation-validation/models/dbnet_detect.ncnn.param',
        'app/build/translation-validation/models/dbnet_detect.ncnn.bin',
    ]
    return {'root': str(root), 'appId': APP_ID, 'files': {p: digest(workspace / p) for p in paths}, 'format': 1}


def initialize(db, root, config):
    db.execute('CREATE TABLE IF NOT EXISTS metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)')
    db.execute('CREATE TABLE IF NOT EXISTS images (id INTEGER PRIMARY KEY, relpath TEXT NOT NULL UNIQUE, size INTEGER NOT NULL, mtime_ns INTEGER NOT NULL, status TEXT, result TEXT)')
    old = db.execute("SELECT value FROM metadata WHERE key='config'").fetchone()
    encoded = json.dumps(config, ensure_ascii=False, sort_keys=True)
    if old:
        if old[0] != encoded:
            raise RuntimeError('Experiment configuration, source, model or APK changed; use a new database.')
        return
    if db.execute('SELECT COUNT(*) FROM images').fetchone()[0]:
        raise RuntimeError('Incomplete existing database; refusing to overwrite it.')
    errors = []
    records = []
    for folder, dirs, files in os.walk(root, onerror=lambda error: errors.append(str(error)), followlinks=False):
        dirs.sort()
        for name in dirs:
            directory = Path(folder) / name
            if directory.is_symlink() or (hasattr(os.path, 'isjunction') and os.path.isjunction(directory)):
                errors.append(f'Linked directory requires explicit handling: {directory}')
        for name in sorted(files):
            path = Path(folder) / name
            if path.suffix.lower() not in SUPPORTED:
                continue
            try:
                resolved = path.resolve(strict=True)
                if not resolved.is_relative_to(root):
                    errors.append(f'Image outside root via link: {path}')
                    continue
                st = path.stat()
                records.append((str(path.relative_to(root)), st.st_size, st.st_mtime_ns))
            except OSError as exc:
                errors.append(f'{path}: {exc}')
    if errors:
        raise RuntimeError('Image inventory failed; no snapshot saved. First error: ' + errors[0])
    if not records:
        raise RuntimeError('No supported images found.')
    with db:
        db.executemany('INSERT INTO images(relpath,size,mtime_ns) VALUES(?,?,?)', records)
        db.execute("INSERT INTO metadata(key,value) VALUES('config',?)", (encoded,))
        db.execute("INSERT INTO metadata(key,value) VALUES('snapshot_time',?)", (time.strftime('%Y-%m-%d %H:%M:%S%z'),))
    print(f'Image snapshot: {len(records)} files', flush=True)


def put_result(db, image_id, status, result):
    db.execute('UPDATE images SET status=?, result=? WHERE id=?', (status, json.dumps(result, ensure_ascii=False), image_id))


def retry_errors(db, shard_params):
    with db:
        db.execute('CREATE TABLE IF NOT EXISTS error_history (image_id INTEGER NOT NULL, result TEXT, retried_at TEXT NOT NULL)')
        db.execute("INSERT INTO error_history(image_id,result,retried_at) SELECT id,result,datetime('now') FROM images WHERE status='error' AND id % ? = ?", shard_params)
        cursor = db.execute("UPDATE images SET status=NULL,result=NULL WHERE status='error' AND id % ? = ?", shard_params)
        return cursor.rowcount


def run_batch(serial, batch_id, slots):
    samples = ','.join(slots)
    cmd = ['adb', '-s', serial, 'shell', 'am', 'instrument', '-w', '-r',
           '-e', 'batchId', batch_id, '-e', 'samples', samples,
           '-e', 'class', 'com.example.comiclab.FullDetectorScanDeviceTest', RUNNER]
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    started = time.monotonic()
    heartbeat = started + 30
    try:
        while proc.poll() is None:
            time.sleep(2)
            now = time.monotonic()
            if now >= heartbeat:
                count = adb(serial, 'shell', 'run-as', APP_ID, 'wc', '-l', f'{PRIVATE_DIR}/full-scan-result.jsonl', timeout=15, check=False)
                print(f'  Current batch: {count.stdout.decode("utf-8", "replace").strip()} / {len(slots)}', flush=True)
                heartbeat = now + 30
            if now - started > 1200:
                proc.terminate()
                raise TimeoutError('Android batch exceeded 20 minutes')
        output = proc.communicate()[0].decode('utf-8', 'replace')
        report = adb(serial, 'exec-out', 'run-as', APP_ID, 'cat', f'{PRIVATE_DIR}/full-scan-result.jsonl', timeout=120, check=False)
        return proc.returncode, output, report.stdout.decode('utf-8', 'replace') if report.returncode == 0 else ''
    finally:
        if proc.poll() is None:
            proc.terminate()


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--serial', default='emulator-5554')
    parser.add_argument('--batch-size', type=int, default=48, choices=range(1, 97))
    parser.add_argument('--limit', type=int, help='Maximum pending files processed in this invocation (pilot/resume).')
    parser.add_argument('--shard-count', type=int, default=1)
    parser.add_argument('--shard-index', type=int, default=0)
    parser.add_argument('--prepare-only', action='store_true', help='Create a snapshot without processing it.')
    parser.add_argument('--retry-errors', action='store_true', help='Preserve and retry previously recorded image errors in this shard.')
    args = parser.parse_args(argv)
    if args.shard_count <= 0 or not 0 <= args.shard_index < args.shard_count:
        parser.error('invalid shard index or count')
    if args.limit is not None and args.limit <= 0:
        parser.error('--limit must be positive')
    root = args.root.resolve(strict=True)
    workspace = Path(__file__).resolve().parent.parent
    output = args.output.resolve()
    if output == root or output.is_relative_to(root):
        raise RuntimeError('Experiment output must be outside the source-image root.')
    output.mkdir(parents=True, exist_ok=True)
    staging = output / 'staging'
    staging.mkdir(exist_ok=True)
    config = configuration(root, workspace)
    config['shardCount'] = args.shard_count
    if not args.prepare_only:
        # Verify the model actually loaded by the isolated Android app, not only the local copy.
        for name in ('dbnet_detect.ncnn.param', 'dbnet_detect.ncnn.bin'):
            remote = adb(args.serial, 'shell', 'run-as', APP_ID, 'sha256sum', f'{PRIVATE_DIR}/{name}').stdout.decode().split()[0]
            local = config['files'][f'app/build/translation-validation/models/{name}']
            if remote.lower() != local.lower():
                raise RuntimeError(f'Android model checksum differs: {name}')
        adb(args.serial, 'shell', 'mkdir', '-p', DEVICE_DIR)
    with workspace_lock(output), sqlite3.connect(output / 'results.sqlite') as db:
        initialize(db, root, config)
        if args.prepare_only:
            print(f'Snapshot prepared: {output / "results.sqlite"}', flush=True)
            return
        encoded_shard = str(args.shard_index)
        previous_shard = db.execute("SELECT value FROM metadata WHERE key='shard_index'").fetchone()
        if previous_shard and previous_shard[0] != encoded_shard:
            raise RuntimeError('This database belongs to another shard.')
        if not previous_shard:
            with db:
                db.execute("INSERT INTO metadata(key,value) VALUES('shard_index',?)", (encoded_shard,))
        params = (args.shard_count, args.shard_index)
        if args.retry_errors:
            print(f'Previously recorded image errors queued for retry: {retry_errors(db, params)}', flush=True)
        pending = db.execute('SELECT id,relpath,size,mtime_ns FROM images WHERE status IS NULL AND id % ? = ? ORDER BY id', params).fetchall()
        if args.limit is not None:
            pending = pending[:args.limit]
        total = db.execute('SELECT COUNT(*) FROM images WHERE id % ? = ?', params).fetchone()[0]
        for group in batches(pending, args.batch_size):
            slots = {}
            staged = []
            with db:
                for image_id, relpath, size, mtime in group:
                    source = root / relpath
                    slot = f'sample-{len(staged):02d}.jpg'
                    try:
                        before = source.stat()
                        if before.st_size != size or before.st_mtime_ns != mtime:
                            raise OSError('source changed since snapshot')
                        shutil.copyfile(source, staging / slot)
                        after = source.stat()
                        if after.st_size != size or after.st_mtime_ns != mtime:
                            raise OSError('source changed during copy')
                        slots[slot] = image_id
                        staged.append(slot)
                    except OSError as exc:
                        put_result(db, image_id, 'error', {'status': 'error', 'error': f'source: {exc}'})
            if not staged:
                continue
            # Push only explicit, fixed slot files. Old staging files never enter this batch.
            adb(args.serial, 'push', *[staging / slot for slot in staged], DEVICE_DIR + '/', timeout=180)
            adb(args.serial, 'shell', 'run-as', APP_ID, 'cp', *[f'{DEVICE_DIR}/{slot}' for slot in staged], f'{PRIVATE_DIR}/', timeout=180)
            batch_id = uuid.uuid4().hex
            done = db.execute('SELECT COUNT(*) FROM images WHERE status IS NOT NULL AND id % ? = ?', params).fetchone()[0]
            print(f'Batch started: {done}/{total} accounted; {len(staged)} images', flush=True)
            returncode, log, text = run_batch(args.serial, batch_id, staged)
            results = parse_batch_output(text, batch_id, slots)
            stored = 0
            with db:
                for image_id, row in results.items():
                    if row['status'] == 'ok' and not all(isinstance(row.get(key), dict) for key in ('dbnet', 'light', 'midtones')):
                        continue
                    put_result(db, image_id, row['status'], row)
                    stored += 1
            if returncode != 0 or 'OK (1 test)' not in log or stored != len(staged):
                print(log[-2000:], flush=True)
                raise RuntimeError(f'Batch did not finish cleanly; {stored}/{len(staged)} rows saved. Restart to resume.')
            done = db.execute('SELECT COUNT(*) FROM images WHERE status IS NOT NULL AND id % ? = ?', params).fetchone()[0]
            errors = db.execute("SELECT COUNT(*) FROM images WHERE status='error' AND id % ? = ?", params).fetchone()[0]
            print(f'Completed: {done}/{total}; recorded image errors: {errors}', flush=True)
        counts = db.execute("SELECT COALESCE(status,'pending'), COUNT(*) FROM images WHERE id % ? = ? GROUP BY status", params).fetchall()
        print('Snapshot status: ' + ', '.join(f'{k}={v}' for k, v in counts), flush=True)
        print(f'Database: {output / "results.sqlite"}', flush=True)


if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        print('Interrupted; completed batches remain saved. Restart the same command to resume.', file=sys.stderr)
        sys.exit(130)
    except Exception as exc:
        print(f'Scan stopped: {exc}', file=sys.stderr)
        sys.exit(1)
