<?php

declare(strict_types=1);

/**
 * A reservation that remains valid until a fixed expiration time.
 */
final class Reservation
{
    /**
     * @param string            $id        Unique reservation identifier.
     * @param DateTimeImmutable $expiresAt Time at which the reservation expires.
     */
    public function __construct(public string $id, public DateTimeImmutable $expiresAt)
    {
    }
}

/**
 * Moves expired reservations out of an active collection.
 */
final class ReservationExpiry
{
    /**
     * Moves up to $limit reservations expiring at or before $cutoff.
     *
     * Reservations are considered in their existing iteration order. Both
     * collections are modified in place: moved reservations are appended to
     * $expiredReservations, while the remaining active reservations are
     * reindexed with consecutive integer keys.
     *
     * @param list<Reservation> $activeReservations  Active reservations to inspect.
     * @param list<Reservation> $expiredReservations Destination for expired reservations.
     * @param DateTimeImmutable $cutoff              Inclusive expiration cutoff.
     * @param int               $limit               Maximum number to move; zero moves none.
     *
     * @return int Number of reservations moved.
     *
     * @throws InvalidArgumentException If $limit is negative.
     */
    public function moveExpired(array &$activeReservations, array &$expiredReservations, DateTimeImmutable $cutoff,
        int $limit): int
    {
        if ($limit < 0) {
            throw new InvalidArgumentException('limit must not be negative');
        }

        $moved = 0;
        foreach ($activeReservations as $index => $reservation) {
            if ($moved >= $limit) {
                break;
            }
            if ($reservation->expiresAt <= $cutoff) {
                unset($activeReservations[$index]);
                $expiredReservations[] = $reservation;
                ++$moved;
            }
        }

        $activeReservations = array_values($activeReservations);
        return $moved;
    }
}
