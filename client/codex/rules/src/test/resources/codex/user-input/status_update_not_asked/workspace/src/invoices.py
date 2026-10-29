import pricing


def invoice_lines(invoice):
    total = pricing.{{old_name}}(invoice["items"], invoice["tax_rate"])
    return [f"Invoice {invoice['number']}", f"Total due: {total:.2f}"]
