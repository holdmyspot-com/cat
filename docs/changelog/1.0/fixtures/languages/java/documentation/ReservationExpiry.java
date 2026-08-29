import java.time.Instant;
import java.util.Iterator;
import java.util.List;

public final class ReservationExpiry {
    public int moveExpired(List<Reservation> activeReservations, List<Reservation> expiredReservations, Instant cutoff, int limit) {
        if (limit < 0) throw new IllegalArgumentException("limit must not be negative");
        int moved = 0;
        for (Iterator<Reservation> iterator = activeReservations.iterator(); iterator.hasNext() && moved < limit;) {
            Reservation reservation = iterator.next();
            if (!reservation.expiresAt().isAfter(cutoff)) { iterator.remove(); expiredReservations.add(reservation); moved++; }
        }
        return moved;
    }
    public record Reservation(String id, Instant expiresAt) {}
}
