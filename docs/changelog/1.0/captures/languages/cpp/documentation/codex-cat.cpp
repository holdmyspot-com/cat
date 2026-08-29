#include <chrono>
#include <stdexcept>
#include <string>
#include <vector>

/**
 * A reservation identified by `id` with a recorded expiration time.
 */
struct Reservation
{
    /** Identifier used to distinguish this reservation from others. */
    std::string id;

    /**
     * Time at which the reservation is considered expired. A reservation is
     * expired when this value is no later than the cutoff supplied to
     * ReservationExpiry::moveExpired().
     */
    std::chrono::system_clock::time_point expiresAt;
};

/** Moves reservations that have reached a caller-selected expiration cutoff. */
class ReservationExpiry
{
public:
    /**
     * Moves up to `limit` reservations whose expiration time is at or before
     * `cutoff` from the active collection to the expired collection.
     *
     * Scans active reservations in their current order. Moved reservations are
     * appended to `expiredReservations` in that scan order; all other active
     * reservations retain their relative order. Existing entries in
     * `expiredReservations` are preserved.
     *
     * @param activeReservations Reservations currently eligible for use. On
     * success, each moved reservation is removed from this collection.
     * @param expiredReservations Destination for moved reservations. On
     * success, this collection gains the moved reservations.
     * @param cutoff Expiration boundary. Reservations expiring exactly at this
     * time are moved.
     * @param limit Maximum number of expired reservations to move. Zero moves
     * none; a negative value is invalid.
     * @return The number of reservations moved, from zero through `limit`.
     * @throws std::invalid_argument if `limit` is negative; neither collection
     * is changed in this case.
     *
     * Exceptions raised while appending a reservation to
     * `expiredReservations` propagate. Reservations moved before such an
     * exception remain moved; the reservation being appended is not removed
     * from `activeReservations` unless its append succeeded.
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
