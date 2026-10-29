from pricing import {{old_name}}


def daily_revenue(orders):
    return sum({{old_name}}(order["items"], order["tax_rate"]) for order in orders)
