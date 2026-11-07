import unittest
import sqlite3
import tempfile
from pathlib import Path

from scripts.full_detector_scan import batches, belongs_to_shard, initialize, parse_batch_output, retry_errors, workspace_lock


class FullDetectorScanTest(unittest.TestCase):
    def test_batches_keep_all_items_and_order(self):
        self.assertEqual([[1, 2], [3, 4], [5]], list(batches([1, 2, 3, 4, 5], 2)))
        with self.assertRaises(ValueError):
            list(batches([1], 0))

    def test_batch_output_rejects_stale_unknown_and_duplicate_slots(self):
        slots = {'sample-00.jpg': 100, 'sample-01.jpg': 101}
        data = '\n'.join([
            '{"batchId":"old","slot":"sample-00.jpg","status":"ok"}',
            '{"batchId":"new","slot":"other.jpg","status":"ok"}',
            '{"batchId":"new","slot":"sample-00.jpg","status":"ok"}',
            '{"batchId":"new","slot":"sample-00.jpg","status":"error"}',
            '{"batchId":"new","slot":"sample-01.jpg","status":"ok"}',
            '{"batchId":"new"',
        ])
        result = parse_batch_output(data, 'new', slots)
        self.assertEqual({100, 101}, set(result))
        self.assertEqual('ok', result[100]['status'])
        self.assertEqual('ok', result[101]['status'])

    def test_shards_are_disjoint_and_cover_every_snapshot_id(self):
        groups = [{i for i in range(1, 102) if belongs_to_shard(i, s, 3)} for s in range(3)]
        self.assertEqual(set(range(1, 102)), set().union(*groups))
        self.assertTrue(all(groups[i].isdisjoint(groups[j]) for i in range(3) for j in range(i)))
        with self.assertRaises(ValueError):
            belongs_to_shard(1, 3, 3)

    def test_retry_preserves_error_history_and_only_resets_assigned_shard(self):
        with sqlite3.connect(':memory:') as db:
            db.execute('CREATE TABLE images(id INTEGER PRIMARY KEY,status TEXT,result TEXT)')
            db.executemany('INSERT INTO images VALUES(?,?,?)', [(1, 'error', 'e1'), (2, 'error', 'e2'), (4, 'ok', 'ok')])
            db.commit()
            self.assertEqual(1, retry_errors(db, (3, 1)))
            self.assertEqual([(1, None), (2, 'error'), (4, 'ok')], db.execute('SELECT id,status FROM images ORDER BY id').fetchall())
            self.assertEqual([(1, 'e1')], db.execute('SELECT image_id,result FROM error_history').fetchall())

    def test_snapshot_resumes_without_reset_and_rejects_changed_configuration(self):
        workspace_build = (Path(__file__).resolve().parents[2] / 'app' / 'build').resolve()
        workspace_build.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=workspace_build) as temporary:
            root = Path(temporary).resolve()
            self.assertTrue(root.is_relative_to(workspace_build))
            (root / 'a.jpg').write_bytes(b'image')
            (root / 'b.PNG').write_bytes(b'image')
            (root / 'not-an-image.pdf').write_bytes(b'document')
            with sqlite3.connect(':memory:') as db:
                config = {'root': str(root), 'format': 1}
                initialize(db, root, config)
                self.assertEqual(2, db.execute('SELECT count(*) FROM images').fetchone()[0])
                db.execute("UPDATE images SET status='ok' WHERE relpath='a.jpg'")
                db.commit()
                initialize(db, root, config)
                self.assertEqual('ok', db.execute("SELECT status FROM images WHERE relpath='a.jpg'").fetchone()[0])
                with self.assertRaises(RuntimeError):
                    initialize(db, root, {'root': str(root), 'format': 2})

    def test_worker_lock_prevents_duplicate_worker_and_can_be_reacquired(self):
        workspace_build = (Path(__file__).resolve().parents[2] / 'app' / 'build').resolve()
        with tempfile.TemporaryDirectory(dir=workspace_build) as temporary:
            root = Path(temporary).resolve()
            self.assertTrue(root.is_relative_to(workspace_build))
            with workspace_lock(root):
                with self.assertRaises(OSError):
                    with workspace_lock(root):
                        self.fail('second worker unexpectedly acquired the lock')
            with workspace_lock(root):
                pass


if __name__ == '__main__':
    unittest.main()
