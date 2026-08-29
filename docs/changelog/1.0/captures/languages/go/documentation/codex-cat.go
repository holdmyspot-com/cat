package checkout

import "time"

// Reservation identifies a reservation and the time at which it expires.
//
// ID is the reservation's identifier. ExpiresAt is the instant after which the
// reservation is no longer active; a reservation is considered expired at that
// instant as well.
type Reservation struct {
	ID        string
	ExpiresAt time.Time
}

// ReservationExpiry moves expired reservations between caller-owned slices.
type ReservationExpiry struct{}

// MoveExpired transfers up to limit reservations whose expiry time is at or
// before cutoff from activeReservations to expiredReservations, then returns
// the number transferred. Eligible reservations are selected in their existing
// activeReservations order.
//
// activeReservations contains currently active reservations and is replaced
// with the reservations that were not transferred, preserving their order.
// expiredReservations contains previously expired reservations and receives
// each transferred reservation appended after its existing contents. A limit of
// zero transfers none. A negative limit panics before either slice is changed.
func (ReservationExpiry) MoveExpired(activeReservations *[]Reservation, expiredReservations *[]Reservation,
\tcutoff time.Time, limit int) int {
\tif limit < 0 {
\t\tpanic("limit must not be negative")
\t}

\tmoved := 0
\tactive := *activeReservations
\tretained := make([]Reservation, 0, len(active))
\tfor _, reservation := range active {
\t\tif moved < limit && !reservation.ExpiresAt.After(cutoff) {
\t\t\t*expiredReservations = append(*expiredReservations, reservation)
\t\t\tmoved++
\t\t} else {
\t\t\tretained = append(retained, reservation)
\t\t}
\t}
\t*activeReservations = retained
\treturn moved
}
