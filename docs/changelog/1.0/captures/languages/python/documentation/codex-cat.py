class ReservationExpiry:
    """Move reservations whose expiry time has passed into an expired collection."""

    def move_expired(self, active_reservations, expired_reservations, cutoff, limit):
        """Move up to ``limit`` reservations expiring at or before ``cutoff``.

        Scans ``active_reservations`` in its current order. Each moved reservation
        is removed from that collection and appended to ``expired_reservations``;
        non-expired reservations retain their relative order. The returned count is
        the number of reservations moved, which may be less than ``limit`` when
        fewer reservations meet the cutoff.

        Args:
            active_reservations: Mutable, ordered reservations to inspect. Every
                reservation must expose an ``expires_at`` value comparable with
                ``cutoff``. This collection is changed in place.
            expired_reservations: Mutable collection that receives moved
                reservations by append. Its existing contents are retained.
            cutoff: Expiry threshold; reservations with ``expires_at <= cutoff``
                are eligible to move.
            limit: Maximum number of eligible reservations to move. Must be zero
                or greater; zero leaves both collections unchanged.

        Returns:
            The number of reservations moved.

        Raises:
            ValueError: If ``limit`` is negative. Neither collection is changed
                in this case.
        """
        if limit < 0:
            raise ValueError("limit must not be negative")
        moved = 0
        index = 0
        while index < len(active_reservations) and moved < limit:
            reservation = active_reservations[index]
            if reservation.expires_at <= cutoff:
                active_reservations.pop(index)
                expired_reservations.append(reservation)
                moved += 1
            else:
                index += 1
        return moved
