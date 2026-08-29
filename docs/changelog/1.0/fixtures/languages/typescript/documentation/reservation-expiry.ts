export type Reservation = { id: string; expiresAt: Date };

export class ReservationExpiry {
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
