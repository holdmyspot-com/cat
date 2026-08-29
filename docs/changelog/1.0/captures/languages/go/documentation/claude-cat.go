// Package checkout provides reservation expiration operations.
package checkout

import "time"

// Reservation identifies a checkout reservation and when it expires.
type Reservation struct {
	// ID identifies the reservation.
	ID string
	// ExpiresAt is the time at which the reservation becomes eligible to expire.
	ExpiresAt time.Time
}

// ReservationExpiry moves expired reservations out of the active set.
type ReservationExpiry struct{}

// MoveExpired moves up to limit reservations whose expiration time is at or
// before cutoff. It examines activeReservations in order, removes each moved
// reservation, and appends it to expiredReservations without changing the
// relative order of either moved or retained reservations. It returns the
// number of reservations moved. A limit of zero leaves both slices unchanged.
// MoveExpired panics if limit is negative.
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
