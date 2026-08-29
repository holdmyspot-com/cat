/// <summary>
/// Moves expired reservations from an active collection to an expired collection.
/// </summary>
public sealed class ReservationExpiry
{
    /// <summary>
    /// Moves up to <paramref name="limit"/> reservations whose expiry time is at or before
    /// <paramref name="cutoff"/> from <paramref name="activeReservations"/> to
    /// <paramref name="expiredReservations"/>.
    /// </summary>
    /// <param name="activeReservations">
    /// The active reservations to inspect in their current order. Each moved reservation is removed;
    /// reservations that are not moved retain their relative order.
    /// </param>
    /// <param name="expiredReservations">
    /// The collection that receives moved reservations. Reservations are appended in the order in
    /// which they appear in <paramref name="activeReservations"/>.
    /// </param>
    /// <param name="cutoff">
    /// The inclusive expiry boundary. A reservation is expired when its <see cref="Reservation.ExpiresAt"/>
    /// value is less than or equal to this value.
    /// </param>
    /// <param name="limit">
    /// The maximum number of expired reservations to move. A value of zero moves none.
    /// </param>
    /// <returns>The number of reservations moved to <paramref name="expiredReservations"/>.</returns>
    /// <exception cref="ArgumentOutOfRangeException">
    /// Thrown when <paramref name="limit"/> is negative. Neither collection is changed.
    /// </exception>
    public int MoveExpired(List<Reservation> activeReservations, List<Reservation> expiredReservations, DateTimeOffset cutoff, int limit)
    {
        if (limit < 0) throw new ArgumentOutOfRangeException(nameof(limit));
        var moved = 0;
        for (var index = 0; index < activeReservations.Count && moved < limit;)
        {
            var reservation = activeReservations[index];
            if (reservation.ExpiresAt <= cutoff) { activeReservations.RemoveAt(index); expiredReservations.Add(reservation); moved++; }
            else index++;
        }
        return moved;
    }
}

/// <summary>
/// Identifies a reservation and the time at which it expires.
/// </summary>
/// <param name="Id">The reservation's identifier.</param>
/// <param name="ExpiresAt">The instant at which the reservation is considered expired.</param>
public record Reservation(string Id, DateTimeOffset ExpiresAt);
