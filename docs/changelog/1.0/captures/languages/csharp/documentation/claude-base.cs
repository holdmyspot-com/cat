public sealed class ReservationExpiry
{
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

public record Reservation(string Id, DateTimeOffset ExpiresAt);
