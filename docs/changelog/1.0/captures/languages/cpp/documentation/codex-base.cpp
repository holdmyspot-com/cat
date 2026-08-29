#include <chrono>
#include <stdexcept>
#include <string>
#include <vector>

/**
 * A reservation that remains active until its expiry timestamp.
 *
 * `id` identifies the reservation, while `expiresAt` is compared against the
 * cutoff supplied to ReservationExpiry::moveExpired().
 */
struct Reservation
{
    std::string id;
    std::chrono::system_clock::time_point expiresAt;
};

/**
 * Moves expired reservations from the active collection to the expired
 * collection.
 */
class ReservationExpiry
{
public:
    /**
     * Transfers up to `limit` reservations whose expiry is at or before
     * `cutoff`.
     *
     * Eligible reservations are considered in their current order in
     * `activeReservations`. Each transferred reservation is appended to
     * `expiredReservations` and removed from `activeReservations`; reservations
     * that do not qualify keep their relative order. A `limit` of zero performs
     * no transfer, and a negative limit is rejected.
     *
     * @param activeReservations Reservations still awaiting expiry processing.
     *        This collection is modified in place.
     * @param expiredReservations Destination for transferred reservations. New
     *        entries are appended to this collection.
     * @param cutoff Reservations expiring at or before this time are eligible.
     * @param limit Maximum number of eligible reservations to transfer.
     * @return The number of reservations transferred.
     * @throws std::invalid_argument if `limit` is negative.
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
