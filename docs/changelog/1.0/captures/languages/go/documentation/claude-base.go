package checkout

import "time"

// Reservation identifies a checkout reservation and records when it expires.
type Reservation struct {
	ID        string
	ExpiresAt time.Time
}

// ReservationExpiry moves expired reservations out of an active collection.
type ReservationExpiry struct{}

// MoveExpired moves up to limit reservations that expire at or before cutoff
// from activeReservations to expiredReservations. It preserves the relative
// order of both the moved and retained reservations and returns the number
// moved. MoveExpired panics if limit is negative.
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
