"""检验统计口径：截断与未经历机动的配对不能混入误差均值。"""

import unittest

import report_guidance_maneuvers as report


def row(pip, ppn, effective=True):
    """极小统计夹具，null 表示未完成首次接近。"""
    return {"pipCompleted": pip is not None, "ppnCompleted": ppn is not None,
            "pipMiss": pip, "ppnMiss": ppn, "effectiveManeuver": effective}


class GuidanceReportTest(unittest.TestCase):
    """输出较小数字之前先验证集合选择和分位数定义。"""

    def test_censored_and_pre_maneuver_rows_are_reported_but_not_averaged(self):
        result = report.aggregate([row(5, 3), row(2, None), row(None, 10), row(None, None), row(0, 0, False)])
        self.assertEqual(5, result["total"])
        self.assertEqual(1, result["effectivePairs"])
        self.assertEqual(1, result["endedBeforeManeuver"])
        self.assertEqual(1, result["pipOnlyCompleted"])
        self.assertEqual(1, result["ppnOnlyCompleted"])
        self.assertEqual(1, result["neitherCompleted"])
        self.assertEqual(5, result["pip"]["mean"])
        self.assertEqual(3, result["ppn"]["mean"])
        self.assertEqual(1, result["ppnSmaller"])

    def test_empty_collection_has_no_fake_zero_error(self):
        result = report.aggregate([row(None, None)])
        self.assertIsNone(result["pip"]["median"])
        self.assertIsNone(result["ppn"]["p90"])
        self.assertEqual(0, result["ties"])

    def test_percentile_and_paired_winner_are_independent_of_input_order(self):
        rows = [row(10, 5), row(1, 2), row(0, 0.0005), row(9, 8)]
        before = report.aggregate(rows)
        self.assertEqual(before, report.aggregate(list(reversed(rows))))
        self.assertAlmostEqual(9.7, before["pip"]["p90"])
        self.assertEqual(2, before["ppnSmaller"])
        self.assertEqual(1, before["pipSmaller"])
        self.assertEqual(1, before["ties"])


if __name__ == "__main__":
    unittest.main()
