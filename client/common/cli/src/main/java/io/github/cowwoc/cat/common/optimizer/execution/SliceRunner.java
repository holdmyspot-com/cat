package io.github.cowwoc.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.CampaignEvent;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Runs a campaign for one bounded interval, called a slice. This is the part of the driver that waits on real time.
 * <p>
 * Between {@link Driver#step() steps}, the runner waits until a session ends, the step's wake-up time arrives, or the
 * poll interval elapses, whichever comes first. The poll interval bounds how late a host interrupt is noticed.
 */
public final class SliceRunner
{
	/**
	 * Prevents instantiation.
	 */
	private SliceRunner()
	{
	}

	/**
	 * Applies the user's commands to a campaign, then advances it until the slice stops. If a driver of another host
	 * session holds the campaign's lock, nothing is applied and the result reports the campaign as busy. If a driver of
	 * the same host session holds it, the slice starts once that driver releases it.
	 *
	 * @param context the driver's context
	 * @param campaignId the campaign's identifier
	 * @param commands the user's commands, applied in order before the first step
	 * @return the slice's result
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code campaignId} is blank, the store holds no campaign with the identifier,
	 *   or a command is not a user's command or names something that the campaign does not offer
	 * @throws IllegalStateException if the campaign's state does not permit a command, or a driver of the same host
	 *   session holds the campaign's lock in the current process or in a process that started it
	 * @throws IOException if the store, the workspace or a session cannot be read or written
	 * @throws InterruptedException if the thread is interrupted while waiting
	 */
	public static SliceResult run(DriverContext context, String campaignId, List<CampaignEvent> commands)
		throws IOException, InterruptedException
	{
		requireThat(context, "context").isNotNull();
		requireThat(campaignId, "campaignId").isNotBlank();
		requireThat(commands, "commands").isNotNull();
		Optional<Driver> opened = Driver.open(context, campaignId);
		if (opened.isEmpty())
			return Driver.busy(context, campaignId);
		try (Driver driver = opened.get())
		{
			for (CampaignEvent command : commands)
				driver.apply(command);
			while (true)
			{
				switch (driver.step())
				{
					case Step.Stopped stopped ->
					{
						return stopped.result();
					}
					case Step.Continue next -> driver.awaitEvent(timeout(context, next.wakeBy()));
				}
			}
		}
	}

	/**
	 * Returns how long to wait for the next session to end.
	 *
	 * @param context the driver's context
	 * @param wakeBy when the driver must step again
	 * @return the time until {@code wakeBy}, at most the poll interval and never negative
	 */
	private static Duration timeout(DriverContext context, Instant wakeBy)
	{
		Duration untilWake = Duration.between(context.clock().instant(), wakeBy);
		if (untilWake.isNegative())
			return Duration.ZERO;
		Duration pollInterval = context.settings().pollInterval();
		if (untilWake.compareTo(pollInterval) > 0)
			return pollInterval;
		return untilWake;
	}
}
