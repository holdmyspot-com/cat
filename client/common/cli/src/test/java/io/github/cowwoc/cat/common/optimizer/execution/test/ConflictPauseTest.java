package io.github.cowwoc.cat.common.optimizer.execution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.Round;
import io.github.cowwoc.cat.common.optimizer.campaign.test.Builder;
import io.github.cowwoc.cat.common.optimizer.execution.PromptAuthorConflict;
import io.github.cowwoc.cat.common.optimizer.execution.Driver;
import io.github.cowwoc.cat.common.optimizer.port.SessionRole;
import io.github.cowwoc.cat.common.optimizer.port.test.FakeSessionPort.FakeSession;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Verifies prompt author conflict handling and the campaign edits that the driver supplies to prompt authors.
 */
public final class ConflictPauseTest
{
	/**
	 * Creates the test.
	 */
	public ConflictPauseTest()
	{
	}

	/**
	 * Verifies that confirmed conflicts discard even valid prompt author hunks, pause new admissions, and let trials
	 * finish.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void pausesAndDiscardsPromptAuthorEdits() throws IOException
	{
		// Requirement: R-D13
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver-conflict")),
				authoring(1, true));
			harness.promptAuthor().conflicts(brief -> List.of(new PromptAuthorConflict(List.copyOf(brief.failureIds()),
				List.of("r1", "r2"), List.of(new PromptAuthorConflict.Quotation(DriverHarness.PROMPT, "fork")),
				List.of("Exempt r1 from r2", "Drop r2"), 1, "Keep both requirements")));
			try (Driver driver = harness.open())
			{
				driver.step();
				int running = harness.runningTrials().size();
				requireThat(running, "inFlightTrials").isPositive();
				for (FakeSession session : harness.running())
				{
					if (session.request().role() == SessionRole.PROMPT_AUTHOR)
						session.end(harness.edit(session.request(), DriverHarness.PROMPT));
				}
				driver.step();
				requireThat(harness.campaign().pending(), "pending").isNotNull();
				requireThat(harness.campaign().promptAuthorAttempts(), "promptAuthorAttempts").isEqualTo(0);
				requireThat(harness.campaign().development().promptAuthor(), "candidate").isNull();
				int trials = harness.sessions().sessions().size();
				driver.step();
				requireThat(harness.sessions().sessions().size(), "sessionsWhilePending").isEqualTo(trials);
				requireThat(harness.runningTrials().size(), "inFlightTrials").isEqualTo(running);
				harness.endSessions();
				harness.settle(driver);
				requireThat(harness.runningTrials(), "finishedTrials").isEmpty();
				requireThat(harness.campaign().pending(), "stillPending").isNotNull();
			}
		}
	}

	/**
	 * Returns an incumbent and an edited working candidate for the brief comparison.
	 *
	 * @return candidate numbers
	 */
	@DataProvider
	public static Object[][] workingCandidates()
	{
		return new Object[][] {{0}, {1}};
	}

	/**
	 * Verifies the driver supplies an empty diff for the incumbent and a unified diff for a working edit.
	 *
	 * @param working the working candidate's number
	 * @throws IOException if the fixture cannot be used
	 */
	@Test(dataProvider = "workingCandidates")
	public void suppliesCampaignEdits(int working) throws IOException
	{
		// Requirement: R-D13
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver-edits")),
				authoring(working, false));
			try (Driver driver = harness.open())
			{
				driver.step();
				String edits = harness.promptAuthor().briefs().getFirst().campaignEdits();
				if (working == 0)
					requireThat(edits, "incumbentEdits").isEmpty();
				else
				{
					requireThat(edits, "workingEdits").contains("--- a/CLAUDE.md\n").contains("+++ b/CLAUDE.md\n").
						contains("@@ -1,1 +1,1 @@\n").contains("-fork\n").contains("+candidate-1\n");
				}
			}
		}
	}

	/**
	 * Returns a campaign ready to launch a safety prompt author with one supplied failure.
	 *
	 * @param working the working candidate's number
	 * @param certification whether a certification attempt also runs
	 * @return the campaign
	 */
	private static Campaign authoring(int working, boolean certification)
	{
		Builder builder = new Builder().confirmed().readyBaseline().development("formatting", 50, 0).
			development("safety", 50, 1).failure(Builder.testCase("safety", "logs")).
			round(new Round(Map.of("safety", 0)));
		if (working > 0)
			builder.working(working);
		if (certification)
			builder.certificationAttempt(1);
		return builder.build();
	}
}
