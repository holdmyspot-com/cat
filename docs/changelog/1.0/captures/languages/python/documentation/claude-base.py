"""Utilities for moving expired reservations between collections."""


class ReservationExpiry:
    """Moves expired reservations out of an active reservation collection."""

    def move_expired(self, active_reservations, expired_reservations, cutoff, limit):
        """Move up to ``limit`` expired reservations into another collection.

        A reservation is considered expired when its ``expires_at`` value is
        less than or equal to ``cutoff``. Reservations are examined in their
        current order. Moved entries are removed from ``active_reservations``
        and appended to ``expired_reservations``; unexpired entries retain
        their relative order.

        Args:
            active_reservations: Mutable sequence of active reservations. Each
                reservation must expose a comparable ``expires_at`` attribute.
            expired_reservations: Mutable collection supporting ``append``.
                Expired reservations are added to the end of this collection.
            cutoff: Expiration threshold comparable with ``expires_at``.
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
