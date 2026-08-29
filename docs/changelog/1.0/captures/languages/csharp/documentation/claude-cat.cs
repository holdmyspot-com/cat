/// <summary>
/// Moves expired reservations from an active collection to an expired collection.
/// </summary>
public sealed class ReservationExpiry
{
    /// <summary>
    /// Moves up to <paramref name="limit"/> reservations whose expiration time is at or before
    /// <paramref name="cutoff"/>.
    /// </summary>
    /// <param name="activeReservations">
    /// The reservations to examine. Each moved reservation is removed from this list.
    /// </param>
    /// <param name="expiredReservations">
    /// The destination list. Moved reservations are appended in their original active-list order.
    /// </param>
    /// <param name="cutoff">
    /// The latest expiration time that qualifies a reservation to be moved.
    /// </param>
    /// <param name="limit">
    /// The maximum number of reservations to move. A value of zero leaves both lists unchanged.
    /// </param>
    /// <returns>The number of reservations moved.</returns>
    /// <exception cref="ArgumentOutOfRangeException">
    /// <paramref name="limit"/> is negative.
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
/// <param name="Id">The reservation identifier.</param>
/// <param name="ExpiresAt">The instant when the reservation becomes eligible for expiration.</param>
public record Reservation(string Id, DateTimeOffset ExpiresAt);
