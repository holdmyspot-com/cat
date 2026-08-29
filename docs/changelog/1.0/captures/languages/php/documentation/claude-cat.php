<?php

declare(strict_types=1);

/**
 * A reservation identified by an ID and the time at which it expires.
 */
final class Reservation
{
    /**
     * @param string $id The reservation's identifier.
     * @param DateTimeImmutable $expiresAt The reservation's expiration date and time.
     */
    public function __construct(public string $id, public DateTimeImmutable $expiresAt)
    {
    }
}

/**
 * Moves expired reservations out of the active collection.
 */
final class ReservationExpiry
{
    /**
     * Moves up to the requested number of expired reservations from the active collection to the expired collection.
     *
     * A reservation is expired when its expiration time is earlier than or equal to the cutoff. Reservations are
     * considered in active collection order. Moved reservations are appended to the expired collection in that order,
     * and the remaining active reservations are reindexed from zero. A limit of zero moves no reservations but still
     * reindexes the active collection.
     *
     * @param Reservation[] $activeReservations The reservations to inspect. On return, this contains only the
     *     reservations that were not moved, with consecutive integer keys starting at zero.
     * @param Reservation[] $expiredReservations The previously expired reservations. Moved reservations are appended
     *     to this array; its existing entries and keys are unchanged.
     * @param DateTimeImmutable $cutoff The latest expiration time that qualifies a reservation to be moved.
     * @param int $limit The maximum number of reservations to move; must be zero or greater.
     *
     * @return int The number of reservations moved.
     *
     * @throws InvalidArgumentException If the limit is negative. Neither collection is changed in this case.
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
