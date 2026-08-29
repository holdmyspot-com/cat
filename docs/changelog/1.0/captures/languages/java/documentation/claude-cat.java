import java.time.Instant;
import java.util.Iterator;
import java.util.List;

/**
 * Moves expired reservations from an active collection to an expired collection.
 */
public final class ReservationExpiry {
    /**
     * Moves, in iteration order, up to {@code limit} reservations that expire at or
     * before {@code cutoff}. Each moved reservation is removed from
     * {@code activeReservations} and appended to {@code expiredReservations}.
     *
     * <p>
     * This operation is not atomic. If adding a reservation to
     * {@code expiredReservations} fails, that reservation has already been removed
     * from {@code activeReservations}.
     *
     * @param activeReservations the reservations to inspect and remove from; its iterator must support removal
     * @param expiredReservations the list to which expired reservations are appended
     * @param cutoff the latest expiration instant that qualifies a reservation for moving
     * @param limit the maximum number of reservations to move; must not be negative
     * @return the number of reservations moved
     * @throws IllegalArgumentException if {@code limit} is negative
     * @throws NullPointerException if {@code activeReservations}, {@code expiredReservations}, or {@code cutoff} is
     *         {@code null}, or if an inspected reservation or its expiration instant is {@code null}
     * @throws UnsupportedOperationException if a qualifying reservation is found and either list does not support the
     *         required modification
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
     * Identifies a reservation and the instant at which it expires.
     *
     * @param id the reservation identifier
     * @param expiresAt the instant at which the reservation expires
     */
    public record Reservation(String id, Instant expiresAt) {}
}
