import datetime


def parse_iso_week(text):
    year, week = text.split("-W")
    return datetime.date.fromisocalendar(int(year), int(week), 1)


def parse_leap_day(text):
    year = int(text)
    if year % 4 == 0 and year % 100 != 0:
        return datetime.date(year, 2, 29)
    return None
