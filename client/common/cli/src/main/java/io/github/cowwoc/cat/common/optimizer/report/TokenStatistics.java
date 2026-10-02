package io.github.cowwoc.cat.common.optimizer.report;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.port.SessionRole;
import io.github.cowwoc.cat.common.optimizer.port.TokenUsage;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;

/**
 * The observed token usage per nested session of each role.
 * <p>
 * The statistics describe sessions that already ran. They are not a total, a forecast or a bound on future trials.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param roles the usage of each role that has at least one session that reported usage
 * @param unavailable why no usage is shown, or {@code null} if at least one role has usage
 */
public record TokenStatistics(Map<SessionRole, RoleUsage> roles, String unavailable)
{
	/**
	 * The median and the 90th percentile of one token count per session, using the nearest-rank method.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param median the 50th percentile
	 * @param p90 the 90th percentile
	 */
	public record Percentiles(long median, long p90)
	{
		/**
		 * Creates percentiles.
		 *
		 * @param median the 50th percentile
		 * @param p90 the 90th percentile
		 * @throws IllegalArgumentException if a value is negative or {@code median} exceeds {@code p90}
		 */
		public Percentiles
		{
			requireThat(median, "median").isNotNegative();
			requireThat(p90, "p90").isGreaterThanOrEqualTo(median, "median");
		}
	}

	/**
	 * The usage of the sessions of one role.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param sessions the number of sessions that reported usage
	 * @param input the input tokens per session, including cached ones
	 * @param cachedInput the cached input tokens per session
	 * @param output the output tokens per session, including reasoning ones
	 * @param reasoningOutput the reasoning output tokens per session
	 */
	public record RoleUsage(int sessions, Percentiles input, Percentiles cachedInput, Percentiles output,
		Percentiles reasoningOutput)
	{
		/**
		 * Creates the usage of a role.
		 *
		 * @param sessions the number of sessions that reported usage
		 * @param input the input tokens per session, including cached ones
		 * @param cachedInput the cached input tokens per session
		 * @param output the output tokens per session, including reasoning ones
		 * @param reasoningOutput the reasoning output tokens per session
		 * @throws NullPointerException if a percentile is null
		 * @throws IllegalArgumentException if {@code sessions} is not positive
		 */
		public RoleUsage
		{
			requireThat(sessions, "sessions").isPositive();
			requireThat(input, "input").isNotNull();
			requireThat(cachedInput, "cachedInput").isNotNull();
			requireThat(output, "output").isNotNull();
			requireThat(reasoningOutput, "reasoningOutput").isNotNull();
		}
	}

	/**
	 * Creates statistics.
	 *
	 * @param roles the usage of each role that has at least one session that reported usage
	 * @param unavailable why no usage is shown, or {@code null} if at least one role has usage
	 * @throws NullPointerException if {@code roles} is null
	 * @throws IllegalArgumentException if {@code unavailable} is null exactly when {@code roles} is not empty
	 */
	public TokenStatistics
	{
		requireThat(roles, "roles").isNotNull();
		requireThat(unavailable == null, "hasUsage").isEqualTo(!roles.isEmpty(), "rolesNotEmpty");
		roles = Map.copyOf(roles);
	}

	/**
	 * Computes the statistics of the sessions that reported usage.
	 *
	 * @param usage the usage of each finished session, by role
	 * @return the statistics
	 * @throws NullPointerException if {@code usage} is null
	 */
	public static TokenStatistics of(Map<SessionRole, List<TokenUsage>> usage)
	{
		requireThat(usage, "usage").isNotNull();
		Map<SessionRole, RoleUsage> roles = new EnumMap<>(SessionRole.class);
		for (Map.Entry<SessionRole, List<TokenUsage>> entry : usage.entrySet())
		{
			List<TokenUsage> sessions = entry.getValue();
			if (sessions.isEmpty())
				continue;
			roles.put(entry.getKey(), new RoleUsage(sessions.size(),
				percentiles(sessions, TokenUsage::input), percentiles(sessions, TokenUsage::cachedInput),
				percentiles(sessions, TokenUsage::output), percentiles(sessions, TokenUsage::reasoningOutput)));
		}
		if (roles.isEmpty())
			return new TokenStatistics(roles, "The harness reported no token usage.");
		return new TokenStatistics(roles, null);
	}

	/**
	 * Returns the median and 90th percentile of one count.
	 *
	 * @param sessions the sessions
	 * @param count the count of a session
	 * @return the percentiles, by the nearest-rank method
	 */
	private static Percentiles percentiles(List<TokenUsage> sessions, ToLongFunction<TokenUsage> count)
	{
		long[] sorted = sessions.stream().mapToLong(count).sorted().toArray();
		return new Percentiles(nearestRank(sorted, 50), nearestRank(sorted, 90));
	}

	/**
	 * Returns a percentile by the nearest-rank method.
	 *
	 * @param sorted the values in ascending order
	 * @param percent the percentile, in (0, 100]
	 * @return the value at rank {@code ceil(percent * n / 100)}
	 */
	private static long nearestRank(long[] sorted, int percent)
	{
		int rank = (percent * sorted.length + 99) / 100;
		return sorted[rank - 1];
	}
}
