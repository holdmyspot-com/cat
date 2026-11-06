package com.holdmyspot.cat.common.optimizer.execution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.campaign.Round;
import com.holdmyspot.cat.common.optimizer.campaign.test.Builder;
import com.holdmyspot.cat.common.optimizer.execution.CampaignEdits;
import com.holdmyspot.cat.common.optimizer.execution.Driver;
import com.holdmyspot.cat.common.optimizer.execution.PromptAuthorConflict;
import com.holdmyspot.cat.common.optimizer.inventory.FileContent;
import com.holdmyspot.cat.common.optimizer.inventory.FileEntry.RegularFile;
import com.holdmyspot.cat.common.optimizer.inventory.FileInventory;
import com.holdmyspot.cat.common.optimizer.inventory.Overlay;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import com.holdmyspot.cat.common.optimizer.port.SessionRole;
import com.holdmyspot.cat.common.optimizer.port.test.FakeSessionPort.FakeSession;
import com.holdmyspot.cat.common.optimizer.port.test.FakeWorkspacePort;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
	 * finish. The one trial in flight, with the prompt author, is all that the claim needs: the campaign's ceiling
	 * leaves one free slot, which an admission during the pause would take. The prompt author's brief carries the
	 * working candidate's edits.
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
				authoring());
			harness.conflicts(brief -> List.of(new PromptAuthorConflict(List.copyOf(brief.failureIds()),
				List.of("r1", "r2"), List.of(new PromptAuthorConflict.Quotation(DriverHarness.PROMPT, "fork")),
				List.of("Exempt r1 from r2", "Drop r2"), 1, "Keep both requirements")));
			try (Driver driver = harness.open())
			{
				harness.settle(driver);
				requireThat(harness.promptAuthor().briefs().getFirst().campaignEdits(), "campaignEdits").
					contains("+candidate-1\n");
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
	 * Verifies the edits that a prompt author brief carries are empty for the incumbent and a unified diff for a
	 * working edit.
	 *
	 * @param working the working candidate's number
	 * @throws IOException if the candidates cannot be read
	 */
	@Test(dataProvider = "workingCandidates")
	public void campaignEdits(int working) throws IOException
	{
		// Requirement: R-D13
		FileInventory fork = new FileInventory(Path.of("/project").toAbsolutePath(), Map.of(DriverHarness.PROMPT,
			new RegularFile(Sha256.of("fork".getBytes(UTF_8)), PosixFilePermissions.fromString("rw-r--r--"))));
		FakeWorkspacePort workspace = new FakeWorkspacePort(Path.of("workspace"), fork);
		workspace.supplyFile(DriverHarness.PROMPT, new FileContent("fork".getBytes(UTF_8)));
		Overlay incumbent = Overlay.of(fork);
		Overlay candidate = incumbent;
		if (working > 0)
		{
			candidate = incumbent.write(DriverHarness.PROMPT, new FileContent("candidate-1".getBytes(UTF_8)),
				PosixFilePermissions.fromString("rw-r--r--"));
		}
		String edits = CampaignEdits.of(workspace, incumbent, candidate, Set.of(DriverHarness.PROMPT));
		if (working == 0)
			requireThat(edits, "incumbentEdits").isEmpty();
		else
		{
			requireThat(edits, "workingEdits").contains("--- a/CLAUDE.md\n").contains("+++ b/CLAUDE.md\n").
				contains("@@ -1,1 +1,1 @@\n").contains("-fork\n").contains("+candidate-1\n");
		}
	}

	/**
	 * Returns a campaign ready to launch a safety prompt author with one supplied failure. Its working candidate is
	 * the first edit, and a certification attempt runs. The ceiling allows the prompt author and one trial.
	 *
	 * @return the campaign
	 */
	private static Campaign authoring()
	{
		return new Builder().confirmed().ceiling(2).readyBaseline().development("formatting", 50, 0).
			development("safety", 50, 1).failure(Builder.testCase("safety", "logs")).
			round(new Round(Map.of("safety", 0))).working(1).certificationAttempt(1).build();
	}
}
