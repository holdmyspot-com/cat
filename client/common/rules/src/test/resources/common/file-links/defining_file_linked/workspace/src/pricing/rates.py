VAT = 0.2


def gross(net):
    return round(net * (1 + VAT), 2)
