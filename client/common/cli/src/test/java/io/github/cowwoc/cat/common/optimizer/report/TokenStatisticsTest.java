package io.github.cowwoc.cat.common.optimizer.report;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.port.SessionRole;
import io.github.cowwoc.cat.common.optimizer.port.TokenUsage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.testng.annotations.Test;

/**
 * Tests {@link TokenStatistics}.
 */
public final class TokenStatisticsTest
{
	/**
	 * Creates the test.
	 */
	public TokenStatisticsTest()
	{
	}

	/**
	 * Verifies that token statistics use nearest-rank percentiles per role and are omitted without usage.
	 */
	@Test
	public void percentilesByRole()
	{
		// Requirement: R-E12
		// TRIAL: ten sessions with input 10, 20, ..., 100: sorted rank ceil(0.5*10) = 5 -> 50; ceil(0.9*10) = 9 -> 90
		//        cached 1..10 -> 5 and 9; output 2..20 -> 10 and 18; reasoning 1..10 -> 5 and 9
		// JUDGE: three sessions with input 5, 1, 3: sorted [1, 3, 5]; ceil(0.5*3) = 2 -> 3; ceil(0.9*3) = 3 -> 5
		List<TokenUsage> trial = new ArrayList<>();
		for (int i = 10; i >= 1; --i)
			trial.add(new TokenUsage(i * 10L, i, i * 2L, i));
		List<TokenUsage> judge = List.of(new TokenUsage(5, 0, 0, 0), new TokenUsage(1, 0, 0, 0),
			new TokenUsage(3, 0, 0, 0));
		TokenStatistics statistics = TokenStatistics.of(Map.of(SessionRole.TRIAL, trial, SessionRole.JUDGE, judge,
			SessionRole.AUTHOR, List.of()));
		requireThat(statistics.roles().keySet(), "roles").isEqualTo(Set.of(SessionRole.TRIAL,
			SessionRole.JUDGE));
		TokenStatistics.RoleUsage trialUsage = statistics.roles().get(SessionRole.TRIAL);
		requireThat(trialUsage.sessions(), "trial.sessions").isEqualTo(10);
		requireThat(trialUsage.input(), "trial.input").isEqualTo(new TokenStatistics.Percentiles(50, 90));
		requireThat(trialUsage.cachedInput(), "trial.cached").isEqualTo(new TokenStatistics.Percentiles(5, 9));
		requireThat(trialUsage.output(), "trial.output").isEqualTo(new TokenStatistics.Percentiles(10, 18));
		requireThat(trialUsage.reasoningOutput(), "trial.reasoning").
			isEqualTo(new TokenStatistics.Percentiles(5, 9));
		requireThat(statistics.roles().get(SessionRole.JUDGE).input(), "judge.input").
			isEqualTo(new TokenStatistics.Percentiles(3, 5));
		requireThat(statistics.unavailable(), "unavailable").isNull();

		TokenStatistics none = TokenStatistics.of(Map.of());
		requireThat(none.roles(), "noneRoles").isEmpty();
		requireThat(none.unavailable(), "noneUnavailable").isNotNull();
	}
}
