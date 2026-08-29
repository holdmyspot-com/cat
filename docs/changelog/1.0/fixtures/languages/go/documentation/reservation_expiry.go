package checkout

import "time"

type Reservation struct {
    ID        string
    ExpiresAt time.Time
}

type ReservationExpiry struct{}

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
