package com.holdmyspot.cat.common.optimizer.store;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.Throttle;
import com.holdmyspot.cat.common.optimizer.port.HostLogin;
import com.holdmyspot.cat.common.optimizer.port.RateLimitWindow;
import com.holdmyspot.cat.common.optimizer.port.TokenUsage;

import java.time.Instant;
import java.util.List;

/**
 * The conditions under which one trial session ran, recorded when the session ended.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param startedAt when the session started
 * @param endedAt when the driver received the session's end
 * @param concurrency the number of trial sessions running immediately after this one started
 * @param throttle the campaign's throttle state once the rate-limit windows that the session reported were applied
 * @param windows the rate-limit windows that the session reported, empty if it reported none
 * @param login indicates whether the harness authenticates through a subscription login or an API key
 * @param usage the token counts that the harness reported for the session, or {@code null} if it reported none
 */
public record TrialSession(Instant startedAt, Instant endedAt, int concurrency, Throttle throttle,
	List<RateLimitWindow> windows, HostLogin login, TokenUsage usage)
{
	/**
	 * Creates a session record.
	 *
	 * @param startedAt when the session started
	 * @param endedAt when the driver received the session's end, which precedes {@code startedAt} if the wall clock
	 *   stepped backwards during the session
	 * @param concurrency the number of trial sessions running immediately after this one started
	 * @param throttle the campaign's throttle state once the rate-limit windows that the session reported were applied
	 * @param windows the rate-limit windows that the session reported, empty if it reported none
	 * @param login whether the harness authenticates through a subscription login or an API key
	 * @param usage the token counts that the harness reported for the session, or {@code null} if it reported none
	 * @throws NullPointerException if any argument other than {@code usage} is null, or {@code windows} contains null
	 * @throws IllegalArgumentException if {@code concurrency} is less than 1
	 */
	public TrialSession
	{
		requireThat(startedAt, "startedAt").isNotNull();
		requireThat(endedAt, "endedAt").isNotNull();
		requireThat(concurrency, "concurrency").isGreaterThanOrEqualTo(1);
		requireThat(throttle, "throttle").isNotNull();
		requireThat(windows, "windows").isNotNull();
		requireThat(login, "login").isNotNull();
		windows = List.copyOf(windows);
	}
}
