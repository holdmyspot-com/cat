#include <chrono>
#include <stdexcept>
#include <string>
#include <vector>

/// A reservation that remains valid until a specified time.
struct Reservation
{
    /// The identifier used to distinguish this reservation.
    std::string id;

    /// The first time at which the reservation is considered expired.
    std::chrono::system_clock::time_point expiresAt;
};

/// Moves reservations that have expired out of an active collection.
class ReservationExpiry
{
public:
    /**
     * Moves up to `limit` reservations whose expiration time is at or before `cutoff`.
     *
     * Eligible reservations are considered in their current order. Each moved reservation is
     * removed from `activeReservations` and appended to `expiredReservations`; the relative
     * order of both moved and retained reservations is preserved. A limit of zero makes no
     * changes.
     *
     * @param activeReservations Reservations to examine and remove from when expired.
     * @param expiredReservations Destination to which expired reservations are appended.
     * @param cutoff Latest expiration time that qualifies a reservation to be moved.
     * @param limit Maximum number of reservations to move; must not be negative.
     * @return The number of reservations moved.
     * @throws std::invalid_argument If `limit` is negative. Neither collection is changed.
     */
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
