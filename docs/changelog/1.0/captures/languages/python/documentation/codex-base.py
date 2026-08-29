"""Utilities for moving expired reservations between mutable collections."""


class ReservationExpiry:
    """Moves reservations whose expiry time has passed into an expired collection."""

    def move_expired(self, active_reservations, expired_reservations, cutoff, limit):
        """Move up to ``limit`` expired reservations from active to expired.

        A reservation is expired when its ``expires_at`` attribute is less than
        or equal to ``cutoff``. Matching reservations are considered in their
        current order in ``active_reservations``; the first ``limit`` matches
        are removed from that list and appended to ``expired_reservations``.

        Both collections are modified in place. Reservations that are not
        moved retain their relative order in ``active_reservations``.

        Args:
            active_reservations: A mutable, indexable collection of objects
                with an ``expires_at`` attribute. It must support ``pop``.
            expired_reservations: A mutable collection supporting ``append``
                that receives each moved reservation.
            cutoff: The latest expiry value that counts as expired. It must be
                comparable with every reservation's ``expires_at`` value.
            limit: Maximum number of expired reservations to move. Zero moves
                none; a negative value is invalid.

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
