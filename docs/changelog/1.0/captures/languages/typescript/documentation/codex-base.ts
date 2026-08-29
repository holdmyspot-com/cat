/**
 * A reservation that remains active until its expiration time.
 */
export type Reservation = {
    /** Unique identifier for the reservation. */
    id: string;
    /** Time at which the reservation is considered expired. */
    expiresAt: Date;
};

/**
 * Moves expired reservations from an active collection to an expired collection.
 */
export class ReservationExpiry {
    /**
     * Moves up to {@link limit} reservations whose expiration time is at or before
     * {@link cutoff} from `activeReservations` to `expiredReservations`.
     *
     * Both arrays are mutated in place. Moved reservations retain their relative
     * order in `expiredReservations`; reservations that remain active retain their
     * relative order in `activeReservations`.
     *
     * @param activeReservations - The mutable collection to remove expired reservations from.
     * @param expiredReservations - The mutable collection to append expired reservations to.
     * @param cutoff - Reservations expiring at or before this time are eligible to move.
     * @param limit - Maximum number of eligible reservations to move. Must be non-negative.
     * @returns The number of reservations moved.
     * @throws {RangeError} If `limit` is negative.
     */
    moveExpired(activeReservations: Reservation[], expiredReservations: Reservation[], cutoff: Date, limit: number): number {
        if (limit < 0) throw new RangeError("limit must not be negative");
        let moved = 0;
        for (let index = 0; index < activeReservations.length && moved < limit;) {
            const reservation = activeReservations[index];
            if (reservation.expiresAt <= cutoff) {
                activeReservations.splice(index, 1);
                expiredReservations.push(reservation);
                moved++;
            } else index++;
        }
        return moved;
    }
}
