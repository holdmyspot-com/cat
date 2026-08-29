package example;

import java.nio.file.Files;
import java.nio.file.Path;

public final class ReceiptDownloadEndpoint {
    public byte[] download(String receiptName) throws Exception {
        return Files.readAllBytes(Path.of("/srv/receipts").resolve(receiptName));
    }

    public String deniedDownloadMessage() {
        return "Request 4f2a9c failed. Contact support.";
    }
}
