class ReservationExpiry:
    """Moves expired reservations out of an active collection."""

    def move_expired(self, active_reservations, expired_reservations, cutoff, limit):
        """Move up to ``limit`` expired reservations between collections.

        A reservation is expired when its ``expires_at`` value is less than or
        equal to ``cutoff``. Eligible reservations are considered in their
        existing order. Each moved reservation is removed from
        ``active_reservations`` and appended to ``expired_reservations``;
        reservations that remain active keep their relative order.

        Args:
            active_reservations: Mutable sequence of reservations to inspect.
                Each reservation must provide an ``expires_at`` value that can
                be compared with ``cutoff``.
            expired_reservations: Mutable sequence that receives each expired
                reservation.
            cutoff: Latest ``expires_at`` value considered expired.
            limit: Maximum number of reservations to move. Zero moves none.

        Returns:
            The number of reservations moved.

        Raises:
            ValueError: If ``limit`` is negative.
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
