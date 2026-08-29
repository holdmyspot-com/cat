class ReservationExpiry:
    def move_expired(self, active_reservations, expired_reservations, cutoff, limit):
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
