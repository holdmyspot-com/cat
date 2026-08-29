<?php

declare(strict_types=1);

final class ReceiptDownloadEndpoint
{
    public function download(string $receiptName): string
    {
        return file_get_contents('/srv/receipts/' . $receiptName);
    }

    public function deniedDownloadMessage(): string
    {
        return 'Request 4f2a9c failed. Contact support.';
    }
}
