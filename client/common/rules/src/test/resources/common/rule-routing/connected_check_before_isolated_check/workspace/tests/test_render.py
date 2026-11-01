import unittest

from exporter.render import render_lines


class RenderLinesTest(unittest.TestCase):
    def test_lines_then_total(self):
        rows = [{"region": "north", "amount": "1"}, {"region": "south", "amount": "2"}]
        self.assertEqual(["north: 1", "south: 2", "total: 3"], render_lines(rows))


if __name__ == "__main__":
    unittest.main()
