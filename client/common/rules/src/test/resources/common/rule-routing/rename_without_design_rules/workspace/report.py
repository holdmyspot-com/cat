def calc(rows):
    return sum(row["amount"] for row in rows)


def print_report(rows):
    print("Total:", calc(rows))
