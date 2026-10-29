from pricing import {{old_name}}


def order_summary(order):
    return {
        "id": order["id"],
        "total": {{old_name}}(order["items"], order["tax_rate"]),
    }
