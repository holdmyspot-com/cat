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
    /// <remarks>
    /// Both supplied lists are modified in place. Eligible reservations retain their order in
    /// <paramref name="expiredReservations"/>, and reservations that remain active retain their
    /// relative order. A limit of zero performs no moves.
    /// </remarks>
    /// <param name="activeReservations">The list from which expired reservations are removed.</param>
    /// <param name="expiredReservations">The list to which expired reservations are appended.</param>
    /// <param name="cutoff">The inclusive expiry boundary used to identify expired reservations.</param>
    /// <param name="limit">The maximum number of reservations to move.</param>
    /// <returns>The number of reservations moved.</returns>
    /// <exception cref="ArgumentOutOfRangeException">
    /// Thrown when <paramref name="limit"/> is negative.
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
/// Represents a reservation and the time at which it expires.
/// </summary>
/// <param name="Id">The reservation identifier.</param>
/// <param name="ExpiresAt">The time at which the reservation is no longer active.</param>
public record Reservation(string Id, DateTimeOffset ExpiresAt);
