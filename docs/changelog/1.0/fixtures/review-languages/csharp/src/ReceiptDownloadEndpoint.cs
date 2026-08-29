using System.IO;

public sealed class ReceiptDownloadEndpoint
{
    public byte[] Download(string receiptName)
    {
        return File.ReadAllBytes(Path.Combine("/srv/receipts", receiptName));
    }

    public string DeniedDownloadMessage()
    {
        return "Request 4f2a9c failed. Contact support.";
    }
}
