<?php

declare(strict_types=1);

/**
 * A reservation together with the time at which it is no longer active.
 */
final class Reservation
{
    /**
     * @param string            $id        Identifier of the reservation.
     * @param DateTimeImmutable $expiresAt Time at which the reservation expires.
     */
    public function __construct(public string $id, public DateTimeImmutable $expiresAt)
    {
    }
}

/**
 * Moves expired reservations from the active collection to the expired collection.
 */
final class ReservationExpiry
{
    /**
     * Moves, at most, the requested number of reservations whose expiry time is at or before the cutoff.
     *
     * Active reservations retain their relative order and are reindexed before this method returns. Moved
     * reservations are appended to {@see $expiredReservations} in their original active-reservation order.
     *
     * @param Reservation[]     $activeReservations  Active reservations to inspect and remove expired entries from.
     * @param Reservation[]     $expiredReservations Destination collection to which expired reservations are appended.
     * @param DateTimeImmutable $cutoff              Inclusive expiry cutoff.
     * @param int               $limit               Maximum number of reservations to move; must not be negative.
     *
     * @return int The number of reservations moved.
     *
     * @throws InvalidArgumentException If {@see $limit} is negative.
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
