import java.time.Instant;
import java.util.Iterator;
import java.util.List;

/**
 * Moves reservations whose expiry time has passed from an active collection to an expired
 * collection.
 *
 * <p>The supplied lists are updated in place. Reservations are considered expired when their
 * expiry time is equal to or earlier than the cutoff instant.
 */
public final class ReservationExpiry {
    /**
     * Creates a reservation expiry processor.
     */
    public ReservationExpiry() {}

    /**
     * Moves up to {@code limit} expired reservations from {@code activeReservations} to
     * {@code expiredReservations}.
     *
     * <p>Reservations are examined in the iteration order of {@code activeReservations}. Each
     * eligible reservation is removed from that list and appended to {@code expiredReservations},
     * preserving the order in which reservations are moved. A reservation expires at the cutoff
     * when {@code reservation.expiresAt().equals(cutoff)}.
     *
     * @param activeReservations the mutable list from which expired reservations are removed
     * @param expiredReservations the list to which expired reservations are appended
     * @param cutoff the instant at or before which a reservation is expired
     * @param limit the maximum number of reservations to move; zero moves none
     * @return the number of reservations moved
     * @throws IllegalArgumentException if {@code limit} is negative
     * @throws NullPointerException if any argument, a reservation, or its expiry time is
     *     {@code null}
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
     * An identified reservation and the instant at which it expires.
     *
     * @param id the reservation identifier
     * @param expiresAt the instant at or after which the reservation is expired
     */
    public record Reservation(String id, Instant expiresAt) {}
}
