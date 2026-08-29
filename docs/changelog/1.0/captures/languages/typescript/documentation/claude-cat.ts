/** A reservation that remains active until its expiration time. */
export type Reservation = {
    /** The reservation's unique identifier. */
    id: string;
    /** The time at which the reservation becomes eligible to expire. */
    expiresAt: Date;
};

/** Moves reservations from an active collection to an expired collection. */
export class ReservationExpiry {
    /**
     * Moves up to `limit` reservations whose expiration time is at or before `cutoff`.
     *
     * Eligible reservations are removed from `activeReservations` and appended to
     * `expiredReservations` in their original order. Reservations after the move limit
     * is reached are not examined.
     *
     * @param activeReservations Active reservations to inspect and modify.
     * @param expiredReservations Previously expired reservations; moved reservations are appended here.
     * @param cutoff The latest expiration time eligible to be moved.
     * @param limit The maximum number of reservations to move; must not be negative.
     * @returns The number of reservations moved between the collections.
     * @throws {RangeError} If `limit` is negative. Neither collection is changed in this case.
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
