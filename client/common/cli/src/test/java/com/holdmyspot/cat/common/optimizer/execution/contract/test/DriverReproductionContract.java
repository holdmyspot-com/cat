package com.holdmyspot.cat.common.optimizer.execution.contract.test;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.campaign.CellTier;
import com.holdmyspot.cat.common.optimizer.campaign.FailedAssertion;
import com.holdmyspot.cat.common.optimizer.campaign.Failure;
import com.holdmyspot.cat.common.optimizer.campaign.FailureGroup;
import com.holdmyspot.cat.common.optimizer.campaign.Harness;
import com.holdmyspot.cat.common.optimizer.campaign.Header;
import com.holdmyspot.cat.common.optimizer.campaign.Lifecycle;
import com.holdmyspot.cat.common.optimizer.campaign.MeasurementCondition;
import com.holdmyspot.cat.common.optimizer.campaign.Outcome;
import com.holdmyspot.cat.common.optimizer.campaign.ReproductionRound;
import com.holdmyspot.cat.common.optimizer.campaign.TriedTestCase;
import com.holdmyspot.cat.common.optimizer.campaign.test.Builder;
import com.holdmyspot.cat.common.optimizer.contract.AddedTestCase;
import com.holdmyspot.cat.common.optimizer.contract.Contract;
import com.holdmyspot.cat.common.optimizer.contract.DrawStream;
import com.holdmyspot.cat.common.optimizer.contract.DrawnTestCase;
import com.holdmyspot.cat.common.optimizer.contract.Generator;
import com.holdmyspot.cat.common.optimizer.contract.PromptTest;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;
import com.holdmyspot.cat.common.optimizer.contract.test.ContractFixtures;
import com.holdmyspot.cat.common.optimizer.contract.test.TierFixtures;
import com.holdmyspot.cat.common.optimizer.execution.Driver;
import com.holdmyspot.cat.common.optimizer.execution.test.DriverHarness;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Applies the common campaign policy to supplied session results for the output-limit reproduction scenario.
 * <p>
 * The scenario does not run a native harness or prove native enforcement of an output limit.
 */
public final class DriverReproductionContract
{
	/**
	 * Prevents instantiation.
	 */
	private DriverReproductionContract()
	{
	}

	/**
	 * Verifies that the campaign reproduces the same failure in the lowest effort cell under the recorded output
	 * limit and carries that limit into development trials.
	 * <p>
	 * The supplied session results exercise campaign policy; they do not prove native harness execution or output-limit
	 * enforcement.
	 *
	 * @param harness the harness identity recorded in the campaign header
	 * @throws NullPointerException if {@code harness} is null
	 * @throws IOException if the test directory cannot be used
	 */
	public static void reproducesWithOutputLimit(Harness harness) throws IOException
	{
		Objects.requireNonNull(harness, "harness");
		Contract contract = TierFixtures.tieredContract();
		DrawnTestCase failed = new Generator(contract).draw(TierFixtures.BIG_LOW,
			DrawStream.create(DrawStream.Kind.DEVELOPMENT, 1));
		PromptTest test = contract.findTest(failed.testId()).orElseThrow();
		List<TriedTestCase> tried = new ArrayList<>();
		List<AddedTestCase> added = ContractFixtures.addedTestCases(test, 1, "a");
		for (int i = 0; i < added.size(); ++i)
		{
			int trials = 25;
			if (i == added.size() - 1)
				trials = 24;
			tried.add(new TriedTestCase(added.get(i), trials, 0));
		}
		FailedAssertion wrongTone = new FailedAssertion(test.assertions().getFirst().assertion().id(), "wrong_tone");
		Failure failure = new Failure(failed, "", List.of(wrongTone), null, List.of(), List.of(), Builder.FILES);
		String limited = TierFixtures.BIG_LOW + "/tool-output-5000";
		FailureGroup group = new FailureGroup(TierFixtures.BIG_LOW, test.id(), "", wrongTone, 2, FailureGroup.ROUNDS,
			List.of(new ReproductionRound(TierFixtures.BIG_LOW, tried)), FailureGroup.Status.REPRODUCING);
		Path project = Path.of("/project").toAbsolutePath();
		Campaign start = new Builder().header(new Header(DriverHarness.CAMPAIGN, project,
			project.resolve(DriverHarness.PROMPT), harness, Lifecycle.ACTIVE)).confirmed(contract, 1).working(1).
			readyBaseline().readyDevelopment().certificationAttempt(1).failure(failure).failure(failure).
			outcome(Outcome.BELOW_MINIMUM).groups(List.of(group)).campaign().build();
		List<TrialProfile> limitedProfiles = new CopyOnWriteArrayList<>();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness driverHarness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")), start);
			driverHarness.trials(request ->
			{
				try
				{
					if (request.profile().toolOutputTokenLimit() == 0)
						return driverHarness.passed();
					limitedProfiles.add(request.profile());
					if (driverHarness.campaign().reproducing().isPresent())
						return driverHarness.failed();
					return driverHarness.passed();
				}
				catch (IOException e)
				{
					throw new UncheckedIOException(e);
				}
			});
			try (Driver driver = driverHarness.open())
			{
				Campaign developed = driverHarness.runUntil(driver, state ->
					state.development().count(limited).outcomes() > 0);

				FailureGroup reproduced = developed.attempt(1).group(1);
				requireThat(reproduced.status(), "status").isEqualTo(FailureGroup.Status.REPRODUCED);
				requireThat(reproduced.round().cellId(), "round().cellId()").isEqualTo(limited);
				requireThat(CellTier.developmentCellIds(developed), "developmentCells").contains(limited);
				requireThat(Set.copyOf(limitedProfiles), "limitedProfiles").
					isEqualTo(Set.of(new TrialProfile("big", "low", 5000)));
				requireThat(developed.identity().cells().getOrDefault(limited, Map.of()).
					get(MeasurementCondition.TOOL_OUTPUT_LIMIT), "toolOutputLimit").isEqualTo("5000");
			}
		}
	}
}
