/** A reservation that remains valid until its expiration time. */
export type Reservation = {
    /** Unique identifier for the reservation. */
    id: string;
    /** Date and time at which the reservation expires. */
    expiresAt: Date;
};

/** Moves expired reservations out of the active collection. */
export class ReservationExpiry {
    /**
     * Moves up to `limit` reservations whose expiration time is at or before
     * `cutoff` from `activeReservations` to `expiredReservations`.
     *
     * Both arrays are mutated in place. Reservations retain their relative
     * order within each collection, and moved reservations are appended to the
     * existing expired reservations.
     *
     * @param activeReservations Reservations to inspect and remove from.
     * @param expiredReservations Destination to which expired reservations are appended.
     * @param cutoff Inclusive expiration cutoff.
     * @param limit Maximum number of reservations to move.
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
