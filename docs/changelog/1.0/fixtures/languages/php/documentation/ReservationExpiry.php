<?php

declare(strict_types=1);

final class Reservation
{
    public function __construct(public string $id, public DateTimeImmutable $expiresAt)
    {
    }
}

final class ReservationExpiry
{
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
