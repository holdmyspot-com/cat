from pathlib import Path


class ReceiptDownloadEndpoint:
    def download(self, receipt_name: str) -> bytes:
        return (Path("/srv/receipts") / receipt_name).read_bytes()

    def denied_download_message(self) -> str:
        return "Request 4f2a9c failed. Contact support."
