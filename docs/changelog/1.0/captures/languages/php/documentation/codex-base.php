<?php

declare(strict_types=1);

/**
 * A reservation that remains active until its expiry timestamp.
 */
final class Reservation
{
    /**
     * @param string $id        Unique identifier for the reservation.
     * @param DateTimeImmutable $expiresAt Timestamp at which the reservation expires.
     */
    public function __construct(public string $id, public DateTimeImmutable $expiresAt)
    {
    }
}

/**
 * Moves expired reservations from an active collection to an expired collection.
 */
final class ReservationExpiry
{
    /**
     * Moves at most the supplied limit of reservations whose expiry is at or before
     * the cutoff.
     *
     * Both collections are modified in place. Expired reservations are appended to
     * `$expiredReservations` in their original active-reservation order. Remaining
     * active reservations retain their order and are re-indexed with consecutive
     * numeric keys.
     *
     * @param array<int, Reservation> $activeReservations Active reservations to inspect.
     * @param array<int, Reservation> $expiredReservations Destination for moved reservations.
     * @param DateTimeImmutable $cutoff Reservations expiring at or before this time are expired.
     * @param int $limit Maximum number of reservations to move; zero moves none.
     *
     * @return int The number of reservations moved.
     *
     * @throws InvalidArgumentException If `$limit` is negative.
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
