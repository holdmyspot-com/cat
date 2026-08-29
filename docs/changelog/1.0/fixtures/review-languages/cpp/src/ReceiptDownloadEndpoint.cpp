#include <fstream>
#include <string>
#include <vector>

class ReceiptDownloadEndpoint
{
public:
    std::vector<char> download(const std::string& receiptName)
    {
        std::ifstream receipt("/srv/receipts/" + receiptName, std::ios::binary);
        return {std::istreambuf_iterator<char>(receipt), std::istreambuf_iterator<char>()};
    }

    std::string deniedDownloadMessage()
    {
        return "Request 4f2a9c failed. Contact support.";
    }
};
