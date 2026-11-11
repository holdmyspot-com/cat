import datetime

from src.dates import parse_iso_week, parse_leap_day


def test_parse_iso_week():
    assert parse_iso_week("2024-W09") == datetime.date(2024, 2, 26)


def test_parse_leap_day():
    assert parse_leap_day("2000") == datetime.date(2000, 2, 29)
