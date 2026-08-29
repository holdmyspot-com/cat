import {readFile} from "node:fs/promises";

export class ReceiptDownloadEndpoint {
    async download(receiptName: string): Promise<Uint8Array> {
        return readFile(`/srv/receipts/${receiptName}`);
    }

    deniedDownloadMessage(): string {
        return "Request 4f2a9c failed. Contact support.";
    }
}
