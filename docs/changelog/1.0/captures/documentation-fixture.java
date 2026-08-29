package example;

import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

public final class ReservationExpiry {
    public int moveExpired(List<Reservation> activeReservations, List<Reservation> expiredReservations,
            Instant cutoff, int limit) {
        Objects.requireNonNull(activeReservations, "activeReservations");
        Objects.requireNonNull(expiredReservations, "expiredReservations");
        Objects.requireNonNull(cutoff, "cutoff");
        if (limit < 0) {
            throw new IllegalArgumentException("limit must not be negative");
        }

        int moved = 0;
        Iterator<Reservation> iterator = activeReservations.iterator();
        while (iterator.hasNext() && moved < limit) {
            Reservation reservation = iterator.next();
            if (!reservation.expiresAt().isAfter(cutoff)) {
                iterator.remove();
                expiredReservations.add(reservation);
                moved++;
            }
        }
        return moved;
    }

    record Reservation(String id, Instant expiresAt) {
    }
}
