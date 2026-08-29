package receipts

import "os"

type ReceiptDownloadEndpoint struct{}

func (ReceiptDownloadEndpoint) Download(receiptName string) ([]byte, error) {
    return os.ReadFile("/srv/receipts/" + receiptName)
}

func (ReceiptDownloadEndpoint) DeniedDownloadMessage() string {
    return "Request 4f2a9c failed. Contact support."
}
