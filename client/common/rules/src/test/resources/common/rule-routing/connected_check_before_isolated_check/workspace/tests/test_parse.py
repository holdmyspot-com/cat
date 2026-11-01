import tempfile
import unittest

from exporter.parse import read_rows


class ReadRowsTest(unittest.TestCase):
    def test_reads_rows_by_column_name(self):
        with tempfile.NamedTemporaryFile("w", suffix=".csv", delete=False) as handle:
            handle.write("region,amount\nnorth,1\n")
        self.assertEqual([{"region": "north", "amount": "1"}], read_rows(handle.name))


if __name__ == "__main__":
    unittest.main()
