import java.time.Instant;
import java.util.Iterator;
import java.util.List;

/**
 * Transfers expired reservations from an active collection to an expired collection.
 */
public final class ReservationExpiry {
    /**
     * Moves up to a requested number of reservations whose expiry is at or before a cutoff.
     *
     * <p>
     * Reservations are inspected in the iteration order of {@code activeReservations}. Each
     * moved reservation is removed from {@code activeReservations} and appended to
     * {@code expiredReservations}. A zero limit performs no transfer.
     *
     * @param activeReservations reservations currently active; this list is read and each moved
     *     reservation is removed from it
     * @param expiredReservations destination for moved reservations; each moved reservation is
     *     appended to this list
     * @param cutoff inclusive expiry boundary; a reservation is moved when its expiry is at or
     *     before this instant
     * @param limit maximum number of reservations to move; must not be negative
     * @return the number of reservations moved
     * @throws IllegalArgumentException if {@code limit} is negative
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
     * @param id reservation identifier
     * @param expiresAt instant at which the reservation is no longer active
     */
    public record Reservation(String id, Instant expiresAt) {}
}
