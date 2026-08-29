#include <chrono>
#include <string>
#include <vector>

struct Reservation
{
    std::string id;
    std::chrono::system_clock::time_point expiresAt;
};

class ReservationExpiry
{
public:
    int moveExpired(std::vector<Reservation>& activeReservations, std::vector<Reservation>& expiredReservations,
        std::chrono::system_clock::time_point cutoff, int limit)
    {
        if (limit < 0)
            throw std::invalid_argument("limit must not be negative");
        int moved = 0;
        for (auto iterator = activeReservations.begin(); iterator != activeReservations.end() && moved < limit;)
        {
            if (iterator->expiresAt <= cutoff)
            {
                expiredReservations.push_back(*iterator);
                iterator = activeReservations.erase(iterator);
                ++moved;
            }
            else
                ++iterator;
        }
        return moved;
    }
};
