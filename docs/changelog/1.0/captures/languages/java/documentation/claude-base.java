import java.time.Instant;
import java.util.Iterator;
import java.util.List;

/**
 * Moves expired reservations from an active collection to an expiration archive.
 */
public final class ReservationExpiry {
    /**
     * Creates a reservation expiration processor.
     */
    public ReservationExpiry() {}

    /**
     * Moves up to {@code limit} reservations whose expiration time is at or before
     * {@code cutoff}. Reservations are examined in the iteration order of
     * {@code activeReservations} and appended to {@code expiredReservations} in
     * that same order.
     *
     * <p>Both lists must be mutable. The active list's iterator must support
     * removal, and the expired list must support insertion.</p>
     *
     * @param activeReservations the reservations to examine and remove from
     * @param expiredReservations the destination for expired reservations
     * @param cutoff the inclusive expiration cutoff
     * @param limit the maximum number of reservations to move; zero moves none
     * @return the number of reservations moved
     * @throws IllegalArgumentException if {@code limit} is negative
     * @throws NullPointerException if either list, {@code cutoff}, a reservation,
     *         or a reservation's expiration time is {@code null}
     */
    public int moveExpired(List<Reservation> activeReservations, List<Reservation> expiredReservations, Instant cutoff, int limit) {
        if (limit < 0) throw new IllegalArgumentException("limit must not be negative");
        int moved = 0;
        for (Iterator<Reservation> iterator = activeReservations.iterator(); iterator.hasNext() && moved < limit;) {
            Reservation reservation = iterator.next();
            if (!reservation.expiresAt().isAfter(cutoff)) { iterator.remove(); expiredReservations.add(reservation); moved++; }
        }
        return moved;
    }
    /**
     * A reservation identified by {@code id} and valid until {@code expiresAt}.
     *
     * @param id the reservation identifier
     * @param expiresAt the instant at which the reservation expires
     */
    public record Reservation(String id, Instant expiresAt) {}
}
