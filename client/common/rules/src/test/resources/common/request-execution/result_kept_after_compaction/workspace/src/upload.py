from limits import MAX_UPLOAD_BYTES


def accept(size):
    return size <= MAX_UPLOAD_BYTES
