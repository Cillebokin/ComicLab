import unittest

from scripts.analyze_full_detector_scan import aspect_variant_counts, classify_layout, completion_state, filter_by_aspect, greedy_matches, reference_strata, summarize_row


def box(x0, y0, x1, y1):
    return [[x0, y0], [x1, y0], [x1, y1], [x0, y1]]


class AnalyzeFullDetectorScanTest(unittest.TestCase):
    def test_matching_is_one_to_one_and_honors_overlap_threshold(self):
        refs = [box(0, 0, 10, 10), box(0, 0, 10, 10)]
        self.assertEqual(1, greedy_matches(refs, [box(0, 0, 10, 10)], .3))
        self.assertEqual(0, greedy_matches(refs, [box(100, 100, 110, 110)], .3))
        self.assertEqual(0, greedy_matches([], [], .3))

    def test_simulated_aspect_filter_excludes_short_square_boxes(self):
        short = box(0, 0, 10, 10)
        medium = box(0, 0, 10, 16)
        long = box(0, 0, 10, 30)
        self.assertEqual([medium, long], filter_by_aspect([short, medium, long], 1.5))
        self.assertEqual([long], filter_by_aspect([short, medium, long], 1.75))

    def test_optimized_variant_counts_match_independent_greedy_runs(self):
        refs = [box(0, 0, 10, 10), box(30, 0, 40, 30)]
        candidates = [box(0, 0, 10, 10), box(30, 0, 40, 30), box(60, 0, 70, 16)]
        result = aspect_variant_counts(refs, candidates, .3)
        self.assertEqual(greedy_matches(refs, candidates, .3), result['base'][1])
        for label, ratio in [('150', 1.5), ('175', 1.75)]:
            filtered = filter_by_aspect(candidates, ratio)
            self.assertEqual((len(filtered), greedy_matches(refs, filtered, .3)), result[label])

    def test_accounted_errors_are_not_reported_as_success(self):
        self.assertEqual((False, False), completion_state(1, 0))
        self.assertEqual((True, False), completion_state(0, 1))
        self.assertEqual((True, True), completion_state(0, 0))

    def test_layout_marks_long_portrait_separately(self):
        self.assertEqual('long_portrait', classify_layout(400, 1400))
        self.assertEqual('landscape', classify_layout(1200, 800))
        self.assertEqual('portrait_or_square', classify_layout(800, 1200))
        with self.assertRaises(ValueError):
            classify_layout(0, 100)

    def test_summary_counts_cap_and_mask_separately_from_matched_boxes(self):
        row = {
            'dbnet': {'boxes': [box(0, 0, 10, 10)], 'lineCount': 1, 'maskWhite': 20},
            'light': {'boxes': [box(0, 0, 10, 10)], 'lineCount': 1, 'capReached': False, 'maskWhite': 40, 'maskIntersection': 10, 'maskUnion': 50},
            'midtones': {'boxes': [box(100, 100, 110, 110)], 'lineCount': 1, 'capReached': True, 'maskWhite': 50, 'maskIntersection': 5, 'maskUnion': 65},
        }
        result = summarize_row(row)
        self.assertEqual(1, result['light_matched'])
        self.assertEqual(0, result['midtones_matched'])
        self.assertEqual(1, result['midtones_capped'])
        self.assertEqual(10, result['light_mask_intersection'])

    def test_reference_breakdown_preserves_orientation_and_size(self):
        row = {
            'dbnet': {'boxes': [box(0, 0, 10, 30), box(50, 0, 90, 10)]},
            'light': {'boxes': [box(0, 0, 10, 30)]},
            'midtones': {'boxes': [box(0, 0, 10, 30), box(50, 0, 90, 10)]},
        }
        result = reference_strata(row)
        self.assertEqual({'dbnet': 1, 'light_matched': 1, 'midtones_matched': 1}, result['vertical/8-15'])
        self.assertEqual({'dbnet': 1, 'light_matched': 0, 'midtones_matched': 1}, result['horizontal/8-15'])


if __name__ == '__main__':
    unittest.main()
