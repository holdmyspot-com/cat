package checkout

import "time"

// Reservation represents a checkout reservation and the time at which it expires.
type Reservation struct {
    ID        string
    ExpiresAt time.Time
}

// ReservationExpiry moves expired reservations from an active collection to an
// expired collection.
type ReservationExpiry struct{}

// MoveExpired moves up to limit reservations whose ExpiresAt time is at or
// before cutoff from activeReservations to expiredReservations. It preserves
// the order of reservations retained in activeReservations and appended to
// expiredReservations, and returns the number moved.
//
// The supplied slices are updated in place. MoveExpired panics if limit is
// negative.
func (ReservationExpiry) MoveExpired(activeReservations *[]Reservation, expiredReservations *[]Reservation,
    cutoff time.Time, limit int) int {
    if limit < 0 {
        panic("limit must not be negative")
    }

    moved := 0
    active := *activeReservations
    retained := make([]Reservation, 0, len(active))
    for _, reservation := range active {
        if moved < limit && !reservation.ExpiresAt.After(cutoff) {
            *expiredReservations = append(*expiredReservations, reservation)
            moved++
        } else {
            retained = append(retained, reservation)
        }
    }
    *activeReservations = retained
    return moved
}
