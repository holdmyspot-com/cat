#include <chrono>
#include <string>
#include <vector>

/// A reservation that remains valid until a specific point in time.
struct Reservation
{
    /// The reservation's unique identifier.
    std::string id;

    /// The time at which the reservation expires.
    std::chrono::system_clock::time_point expiresAt;
};

/// Moves expired reservations out of an active collection.
class ReservationExpiry
{
public:
    /// Moves up to `limit` reservations expiring on or before `cutoff` from
    /// `activeReservations` to the end of `expiredReservations`.
    ///
    /// Reservations are examined in their current order. Reservations that
    /// have not expired remain in `activeReservations`, with their relative
    /// order preserved. A limit of zero leaves both collections unchanged.
    ///
    /// @param activeReservations Reservations to inspect and remove from.
    /// @param expiredReservations Destination for expired reservations.
    /// @param cutoff Latest expiration time considered expired.
    /// @param limit Maximum number of reservations to move; must be nonnegative.
    /// @return The number of reservations moved.
    /// @throws std::invalid_argument If `limit` is negative.
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
