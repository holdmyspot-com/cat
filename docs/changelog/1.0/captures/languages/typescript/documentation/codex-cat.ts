/**
 * A reservation identified by `id` that is valid until `expiresAt`.
 */
export type Reservation = { id: string; expiresAt: Date };

/**
 * Moves expired reservations from an active collection to an expired one.
 */
export class ReservationExpiry {
    /**
     * Moves up to `limit` reservations whose expiry time is on or before `cutoff`.
     *
     * Both supplied arrays are mutated in place. Moved reservations are appended to
     * `expiredReservations` in their original order, while non-expired active
     * reservations retain their order.
     *
     * @param activeReservations - The source collection to remove expired reservations from.
     * @param expiredReservations - The destination collection for moved reservations.
     * @param cutoff - Reservations expiring at or before this time are considered expired.
     * @param limit - Maximum number of reservations to move; must be non-negative.
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
