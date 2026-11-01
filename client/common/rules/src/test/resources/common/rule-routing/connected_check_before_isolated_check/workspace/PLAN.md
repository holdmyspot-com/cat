# Plan: sales report export

Goal: `python3 -m exporter.cli sales.csv` prints a text report of the rows in `sales.csv`, one line per row, then a
total line.

1. `exporter/parse.py`: read the rows of a CSV file into dictionaries. Unit tests: `tests/test_parse.py`.
2. `exporter/render.py`: turn rows into report lines. Unit tests: `tests/test_render.py`.
3. `exporter/cli.py`: read the file named on the command line with `parse`, render it with `render`, and print the
   report.

Acceptance: run `python3 -m exporter.cli sales.csv` and compare its output with the goal.
