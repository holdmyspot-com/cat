"""Reads the rows of a CSV file."""
import csv


def read_rows(path):
    """Return the rows of the CSV file at `path` as dictionaries keyed by column name."""
    with open(path, newline="") as handle:
        return list(csv.DictReader(handle))
