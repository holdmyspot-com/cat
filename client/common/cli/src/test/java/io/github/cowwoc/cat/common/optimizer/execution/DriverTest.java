package io.github.cowwoc.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.execution.fixture.FaultingFileChannel;
import io.github.cowwoc.cat.common.optimizer.campaign.Boundaries;
import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignBuilder;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignEvent;
import io.github.cowwoc.cat.common.optimizer.campaign.Candidate;
import io.github.cowwoc.cat.common.optimizer.campaign.CategoryCounts;
import io.github.cowwoc.cat.common.optimizer.campaign.CellTier;
import io.github.cowwoc.cat.common.optimizer.campaign.CertificationAttempt;
import io.github.cowwoc.cat.common.optimizer.campaign.Choice;
import io.github.cowwoc.cat.common.optimizer.campaign.ConcurrencyPolicy;
import io.github.cowwoc.cat.common.optimizer.campaign.DecisionTable;
import io.github.cowwoc.cat.common.optimizer.campaign.DecisionTable.CategoryDecision;
import io.github.cowwoc.cat.common.optimizer.campaign.DeliveryRequest;
import io.github.cowwoc.cat.common.optimizer.campaign.DiagnosticRerun;
import io.github.cowwoc.cat.common.optimizer.campaign.Failure;
import io.github.cowwoc.cat.common.optimizer.campaign.FailureKind;
import io.github.cowwoc.cat.common.optimizer.campaign.Lifecycle;
import io.github.cowwoc.cat.common.optimizer.campaign.LogicalTrial;
import io.github.cowwoc.cat.common.optimizer.campaign.MeasurementIdentity;
import io.github.cowwoc.cat.common.optimizer.campaign.MeasurementCondition;
import io.github.cowwoc.cat.common.optimizer.campaign.Outcome;
import io.github.cowwoc.cat.common.optimizer.campaign.PendingDecision;
import io.github.cowwoc.cat.common.optimizer.campaign.ReadinessGate;
import io.github.cowwoc.cat.common.optimizer.campaign.Purpose;
import io.github.cowwoc.cat.common.optimizer.campaign.Round;
import io.github.cowwoc.cat.common.optimizer.campaign.SlowStart;
import io.github.cowwoc.cat.common.optimizer.campaign.TrialKey;
import io.github.cowwoc.cat.common.optimizer.campaign.TrialState;
import io.github.cowwoc.cat.common.optimizer.campaign.Verdict;
import io.github.cowwoc.cat.common.optimizer.contract.Category;
import io.github.cowwoc.cat.common.optimizer.contract.Cell;
import io.github.cowwoc.cat.common.optimizer.contract.CheckCriterion;
import io.github.cowwoc.cat.common.optimizer.contract.CheckResult;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.ContractFixtures;
import io.github.cowwoc.cat.common.optimizer.contract.ControlEvidence;
import io.github.cowwoc.cat.common.optimizer.contract.DeterministicCheck;
import io.github.cowwoc.cat.common.optimizer.contract.Requirement;
import io.github.cowwoc.cat.common.optimizer.contract.TestDefinition;
import io.github.cowwoc.cat.common.optimizer.contract.TierFixtures;
import io.github.cowwoc.cat.common.optimizer.contract.ToolCall;
import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfile;
import io.github.cowwoc.cat.common.optimizer.contract.UndecidableCheckException;
import io.github.cowwoc.cat.common.optimizer.delivery.DeliveryResult;
import io.github.cowwoc.cat.common.optimizer.inventory.AuthorityWarning;
import io.github.cowwoc.cat.common.optimizer.inventory.DeliveryChange;
import io.github.cowwoc.cat.common.optimizer.inventory.DeliveryConflict;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.RegularFile;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.Limit;
import io.github.cowwoc.cat.common.optimizer.inventory.Operation;
import io.github.cowwoc.cat.common.optimizer.inventory.PathChange;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;
import io.github.cowwoc.cat.common.optimizer.inventory.Overlay;
import io.github.cowwoc.cat.common.optimizer.inventory.OverlayEntry.Write;
import io.github.cowwoc.cat.common.optimizer.port.FakeSessionPort.FakeSession;
import io.github.cowwoc.cat.common.optimizer.port.HostLogin;
import io.github.cowwoc.cat.common.optimizer.port.SessionHandle;
import io.github.cowwoc.cat.common.optimizer.port.SessionPort;
import io.github.cowwoc.cat.common.optimizer.port.SessionRequest;
import io.github.cowwoc.cat.common.optimizer.port.SessionResult;
import io.github.cowwoc.cat.common.optimizer.port.SessionRole;
import io.github.cowwoc.cat.common.optimizer.report.ReportEvidence;
import io.github.cowwoc.cat.common.optimizer.report.ReportStatus;
import io.github.cowwoc.cat.common.optimizer.report.Status;
import io.github.cowwoc.cat.common.optimizer.report.StatusReport;
import io.github.cowwoc.cat.common.optimizer.statistics.ComparisonBoundaries;
import io.github.cowwoc.cat.common.optimizer.statistics.DecisionBoundaries;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Covers campaign scenarios from builder state, through the in-memory store, the
 * fake session and workspace ports and a manual clock.
 */
public final class DriverTest
{
	/**
	 * Weak authors receive identical evidence after older development trials settle, including when capacity delays
	 * the third attempt.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void weakAuthorsWaitForDevelopment() throws IOException
	{
		// Requirement: R-D12
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("author-briefs")),
				new CampaignBuilder().confirmed().working(1).readyBaseline().ceiling(2).
					development("safety", 10, 0).development("formatting", 10, 0).
					slowStart(new SlowStart(2, 0)).build());
			try (Driver driver = harness.open())
			{
				driver.step();
				List<FakeSession> trials = harness.runningTrials();
				requireThat(trials.size(), "initialDevelopmentTrials").isEqualTo(2);
				FakeSession held = trials.get(1);
				trials.getFirst().end(harness.failed());
				harness.settle(driver);
				harness.runningTrials().stream().filter(session -> session != held).findFirst().orElseThrow().
					end(harness.failed());
				harness.settle(driver);
				requireThat(harness.campaign().development().round().weak(), "weakRound").isTrue();
				requireThat(harness.author().briefs(), "authorsBeforeDevelopmentSettles").isEmpty();

				held.end(harness.passed());
				harness.settle(driver);
				requireThat(harness.running().size(), "sharedCapacity").isLessThanOrEqualTo(2);
				harness.runUntil(driver, _ -> harness.author().briefs().size() == 3);
				for (AuthorBrief brief : harness.author().briefs())
					requireThat(brief, "sameBrief").isEqualTo(harness.author().briefs().getFirst());
				requireThat(harness.campaign().development().counts().values().stream().
					mapToLong(CategoryCounts::passes).sum(), "settledDevelopmentPasses").isEqualTo(21);
			}
		}
	}

	/**
	 * A weak round compares three independent attempts, minimizes only the best accepted edit, retains the other
	 * evidence, and returns to one author at a time after the new candidate's development estimate improves.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void weakRoundSelectsBestThenRunsSequentially() throws IOException
	{
		// Requirement: R-D12
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("concurrent-authors")),
				new CampaignBuilder().confirmed().working(1).readyBaseline().
					regression(CampaignBuilder.targeted("formatting", "formal"), Verdict.FAIL).build());
			Sha256 working = CampaignBuilder.snapshot("candidate-1");
			harness.overlays().put(working, harness.candidate("first\nseparator\nlast\n"));
			harness.workspace().changeWorktree(harness.overlays().require(working).snapshot());
			AtomicInteger authored = new AtomicInteger();
			harness.authors(request ->
			{
				int attempt = authored.incrementAndGet();
				return harness.edit(request, DriverHarness.PROMPT, switch (attempt)
				{
					case 1 -> "needed-one\nseparator\nextra-one\n";
					case 2 -> "needed-two\nseparator\nextra-two\n";
					default -> "bad\nseparator\nextra-three\n";
				});
			});
			harness.trials(request ->
			{
				try
				{
					Overlay overlay = harness.overlays().require(harness.snapshot(request));
					Write entry = (Write) overlay.entries().get(DriverHarness.PROMPT);
					String text = new String(entry.content().bytes(), UTF_8);
					if (DriverHarness.category(request).equals("formatting"))
					{
						if (text.contains("needed-two"))
							return harness.passed();
						return harness.failed();
					}
					if (text.contains("needed-one") || text.contains("needed-two"))
						return harness.passed();
					return harness.failed();
				}
				catch (IOException e)
				{
					throw new UncheckedIOException(e);
				}
			});

			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, state -> state.development().round() != null);
				requireThat(harness.author().briefs().size(), "concurrentAttempts").isEqualTo(3);
				requireThat(harness.author().briefs().get(1), "sameBrief").isEqualTo(harness.author().briefs().get(0));
				requireThat(harness.author().briefs().get(2), "sameBrief").isEqualTo(harness.author().briefs().get(0));
				requireThat(harness.running().stream().filter(session ->
					session.request().role() == SessionRole.AUTHOR).count(), "runningAuthors").isEqualTo(3);

				Campaign promoted = harness.runUntil(driver, state -> state.development().working().number() == 2);
				Overlay minimal = harness.overlays().require(promoted.development().working().snapshot());
				Write selected = (Write) minimal.entries().get(DriverHarness.PROMPT);
				requireThat(new String(selected.content().bytes(), UTF_8), "minimalWinner").
					isEqualTo("needed-two\nseparator\nlast\n");
				requireThat(promoted.optimization().removed(), "removed").isEqualTo(List.of("CLAUDE.md#2"));
				requireThat(promoted.development().counts(), "newCounts").isEmpty();
				requireThat(promoted.attempts(), "allowance").isEmpty();
				ObjectNode status = StatusReport.of(promoted, ReportEvidence.NONE).toJson(JsonMapper.builder().build());
				JsonNode attempts = status.get("author_attempts");
				requireThat(attempts.size(), "retainedAttempts").isEqualTo(3);
				requireThat(attempts.get(0).get("passing_runs").asInt(), "firstPasses").isEqualTo(2);
				requireThat(attempts.get(0).get("accepted").asBoolean(), "firstAccepted").isTrue();
				requireThat(attempts.get(0).get("winner").asBoolean(), "firstWinner").isFalse();
				requireThat(attempts.get(1).get("passing_runs").asInt(), "secondPasses").isEqualTo(3);
				requireThat(attempts.get(1).get("accepted").asBoolean(), "secondAccepted").isTrue();
				requireThat(attempts.get(1).get("winner").asBoolean(), "secondWinner").isTrue();
				requireThat(attempts.get(2).get("accepted").asBoolean(), "thirdAccepted").isFalse();
				requireThat(attempts.get(2).get("winner").asBoolean(), "thirdWinner").isFalse();
				for (JsonNode attempt : attempts)
				{
					requireThat(attempt.get("total_runs").asInt(), "totalPromotionRuns").isEqualTo(3);
					harness.overlays().require(new Sha256(attempt.get("snapshot").asString()));
				}

				harness.trials(_ -> harness.passed());
				harness.runUntil(driver, state -> state.development().count("safety").passes() >= 80 &&
					state.development().count("formatting").passes() >= 80);
				harness.trials(request ->
				{
					if (DriverHarness.category(request).equals("safety"))
						return harness.failed();
					return harness.passed();
				});
				harness.runUntil(driver, state -> state.development().round() != null);
				requireThat(harness.author().briefs().size(), "nextRoundAttempts").isEqualTo(4);
				requireThat(harness.running().stream().filter(session ->
					session.request().role() == SessionRole.AUTHOR).count(), "strongRoundAuthors").isEqualTo(1);
			}
		}
	}


	/**
	 * Restarting a weak round reruns unfinished targeted trials under their original identities and retains completed
	 * attempts without spending certification allowance or adding development evidence.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void weakRoundRestartsTargetedRuns() throws IOException
	{
		// Requirement: R-D12
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("restart-authors")),
				new CampaignBuilder().confirmed().working(1).readyBaseline().build());
			harness.trials(_ -> harness.failed());
			List<TrialKey> pending;
			Map<String, CategoryCounts> counts;
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, state -> state.development().round() != null);
				requireThat(harness.author().briefs().size(), "attempts").isEqualTo(3);
				harness.endSessions();
				driver.step();
				Campaign saved = harness.campaign();
				pending = saved.trials().stream().filter(trial -> trial.key().purpose() == Purpose.PROMOTION).
					map(LogicalTrial::key).toList();
				requireThat(pending, "targetedTrials").isNotEmpty();
				counts = saved.development().counts();
			}

			harness.trials(_ -> harness.passed());
			try (Driver driver = harness.open())
			{
				driver.step();
				Campaign resumed = harness.campaign();
				requireThat(resumed.trials().stream().filter(trial -> trial.key().purpose() == Purpose.PROMOTION).
					map(LogicalTrial::key).toList(), "sameTrialIdentities").containsAll(pending);
				requireThat(resumed.development().counts(), "countsAfterRestart").isEqualTo(counts);
				Campaign promoted = harness.runUntil(driver, state -> state.development().working().number() == 2);
				requireThat(promoted.authorAttempts(), "attempts").isEqualTo(3);
				requireThat(promoted.development().counts(), "newCandidateCounts").isEmpty();
				requireThat(promoted.attempts(), "allowance").isEmpty();
				requireThat(harness.author().briefs().size(), "noRepeatedAuthors").isEqualTo(3);
			}
		}
	}


	/**
	 * Verifies that promotion removes an unneeded hunk and keeps targeted runs outside the evidence counts.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void promotedAttemptKeepsOnlyNeededHunks() throws IOException
	{
		// Requirement: R-D11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = minimalityHarness(cleanup.add(Files.createTempDirectory("minimality")),
				new AtomicInteger());
			try (Driver driver = harness.open())
			{
				Campaign campaign = harness.runUntil(driver, state -> state.development().working().number() == 2);
				Overlay promoted = harness.overlays().require(campaign.development().working().snapshot());
				Write entry = (Write)
					promoted.entries().get(DriverHarness.PROMPT);
				requireThat(new String(entry.content().bytes(), UTF_8), "prompt").
					isEqualTo("needed\nseparator\nlast\n");
				requireThat(campaign.development().counts(), "counts").isEmpty();
				requireThat(campaign.attempts(), "allowance").isEmpty();
				requireThat(campaign.optimization().removed(), "removed").isEqualTo(List.of("CLAUDE.md#2"));
				StatusReport report = StatusReport.of(campaign, ReportEvidence.NONE);
				requireThat(report.toJson(JsonMapper.builder().build()).path("removed_hunks").get(0).asString(),
					"reportedRemoval").isEqualTo("CLAUDE.md#2");
				requireThat(report.summary(), "summary").contains("CLAUDE.md#2");
			}
		}
	}
	/**
	 * Verifies that an uncited author edit contributes no candidate and the next attempt runs.
	 *
	 * @throws IOException if the fixture cannot be read or written
	 */
	@Test
	public void uncitedHunkRetainedAndRetried() throws IOException
	{
		// Requirement: R-D10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("manifest")),
				new CampaignBuilder().confirmed().working(1).readyBaseline().round(new Round(Map.of("safety", 0))).
					failure(new Failure(CampaignBuilder.targeted("safety", "cache"), "failure", List.of())).
					build());
			harness.authors(request ->
			{
				SessionResult.Completed edited = (SessionResult.Completed) harness.edit(request, DriverHarness.PROMPT);
				return new SessionResult.Completed(edited.transcript(), List.of("""
					{"needed_edits": [], "classifications": [
					  {"instance":"%s","classification":"guidance"}], "hunks": []}
					""".formatted(harness.author().briefs().getLast().developmentId(0))), edited.effects(), edited.model(),
					edited.reasoning(), edited.usage(), edited.windows(),
					edited.promptSources(), edited.toolCalls());
			});
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, state -> state.development().working().number() == 2 ||
					harness.author().briefs().size() == 2);
				requireThat(harness.campaign().candidates(), "candidates").isEqualTo(1);
				requireThat(harness.author().briefs().size(), "authors").isEqualTo(2);
				requireThat(harness.campaign().optimization().authors().size(), "retained").isEqualTo(1);
				requireThat(harness.campaign().optimization().authors().getFirst().rejections(), "rejections").
					contains("Uncited changed hunk: CLAUDE.md#1");
				requireThat(Files.isRegularFile(harness.campaign().optimization().authors().getFirst().effects().
					resolve(DriverHarness.PROMPT)), "effects").isTrue();
			}
		}
	}
	/**
	 * Verifies that a restart during reduction preserves the last passing snapshot and reuses working references.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void resumesMinimalityAfterRestart() throws IOException
	{
		// Requirement: R-D11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			AtomicInteger references = new AtomicInteger();
			DriverHarness harness = minimalityHarness(cleanup.add(Files.createTempDirectory("minimality")), references);
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, state -> state.development().author() != null &&
					state.development().author().minimality().testing() != null);
			}
			Campaign saved = harness.campaign();
			requireThat(saved.development().working().number(), "working").isEqualTo(1);
			requireThat(saved.development().author().minimality().acceptedRuns(), "passingEvidence").isNotEmpty();
			try (Driver driver = harness.open())
			{
				Campaign resumed = harness.runUntil(driver, state -> state.development().working().number() == 2);
				Write entry = (Write) harness.overlays().require(resumed.development().working().snapshot()).
					entries().get(DriverHarness.PROMPT);
				requireThat(new String(entry.content().bytes(), UTF_8), "prompt").isEqualTo("needed\nseparator\nlast\n");
				requireThat(resumed.optimization().removed(), "removed").isEqualTo(List.of("CLAUDE.md#2"));
				requireThat(resumed.development().counts(), "counts").isEmpty();
				requireThat(resumed.attempts(), "allowance").isEmpty();
			}
			requireThat(references.get(), "workingReferences").isEqualTo(1);
		}
	}

	/**
	 * Verifies that an unknown citation is rejected even when every actual hunk has evidence.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void unknownCitationRetainedAndRetried() throws IOException
	{
		// Requirement: R-D10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("manifest")),
				new CampaignBuilder().confirmed().working(1).readyBaseline().round(new Round(Map.of("safety", 0))).
					failure(CampaignBuilder.targeted("safety", "cache")).build());
			harness.authors(request ->
			{
				SessionResult.Completed edited = (SessionResult.Completed) harness.edit(request, DriverHarness.PROMPT);
				String response = """
					{"needed_edits":[],"classifications":[{"instance":"%s","classification":"guidance"}],
					 "hunks":[{"path":"CLAUDE.md","before_start":0,"before_end":1,"after_start":0,"after_end":1,
					 "instances":["unknown"],"analogous":"related case","dissimilar":"unrelated case"}]}
					""".formatted(harness.author().briefs().getLast().developmentId(0));
				return new SessionResult.Completed(edited.transcript(), List.of(response), edited.effects(), edited.model(),
					edited.reasoning(), edited.usage(), edited.windows(), edited.promptSources(),
					edited.toolCalls());
			});
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, state -> harness.author().briefs().size() == 2);
				requireThat(harness.campaign().candidates(), "candidates").isEqualTo(1);
				requireThat(harness.campaign().optimization().authors().getFirst().rejections(), "rejections").
					isEqualTo(List.of("Unknown failing instance unknown cited at CLAUDE.md"));
			}
		}
	}

	/**
	 * Verifies that no-edit classifications survive the round ending at a plateau.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void noEditRetainsRoundDiagnosis() throws IOException
	{
		// Requirement: R-D10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("manifest")),
				new CampaignBuilder().confirmed().working(1).readyBaseline().round(new Round(Map.of("safety", 0))).
					failure(CampaignBuilder.targeted("safety", "cache")).build());
			harness.authors(_ -> harness.completed("""
				{"needed_edits":[],"classifications":[{"instance":"%s","classification":"not guidance"}],"hunks":[]}
				""".formatted(harness.author().briefs().getLast().developmentId(0)), DriverHarness.MODEL));
			try (Driver driver = harness.open())
			{
				Campaign ended = harness.runUntil(driver, state -> state.outcome() == Outcome.PLATEAU);
				requireThat(ended.candidates(), "candidates").isEqualTo(1);
				requireThat(ended.optimization().authors().size(), "diagnoses").isEqualTo(2);
				requireThat(ended.optimization().authors().getFirst().classifications().values(), "classification").
					contains(FailureKind.NOT_GUIDANCE);
				StatusReport report = StatusReport.of(ended, ReportEvidence.NONE);
				requireThat(report.toJson(JsonMapper.builder().build()).path("author_diagnoses").size(),
					"reportedDiagnoses").isEqualTo(2);
				requireThat(report.summary(), "summary").contains("not guidance");
			}
		}
	}

	/**
	 * Creates an author and trial fixture whose second hunk adds unnecessary guidance.
	 *
	 * @param directory the owned fixture directory
	 * @param references counts runs of the working reference snapshot
	 * @return the fixture
	 * @throws IOException if it cannot be created
	 */
	private static DriverHarness minimalityHarness(Path directory, AtomicInteger references) throws IOException
	{
		DriverHarness harness = new DriverHarness(directory,
			new CampaignBuilder().confirmed().working(1).readyBaseline().round(new Round(Map.of("safety", 0))).
				failure(CampaignBuilder.targeted("safety", "cache")).
				regression(CampaignBuilder.targeted("formatting", "formal"), null).build());
		Sha256 working = CampaignBuilder.snapshot("candidate-1");
		harness.overlays().put(working, harness.candidate("first\nseparator\nlast\n"));
		harness.workspace().changeWorktree(harness.overlays().require(working).snapshot());
		harness.authors(request -> harness.edit(request, DriverHarness.PROMPT, "needed\nseparator\nunneeded\n"));
		harness.trials(request ->
		{
			try
			{
				if (harness.snapshot(request).equals(working) && harness.campaign().development().author() != null &&
					harness.campaign().development().author().minimality().testing() == null)
				{
					references.incrementAndGet();
				}
			}
			catch (IOException e)
			{
				throw new UncheckedIOException(e);
			}
			if (DriverHarness.category(request).equals("formatting"))
				return harness.passed();
			try
			{
				Overlay overlay = harness.overlays().require(harness.snapshot(request));
				Write entry = (Write) overlay.entries().get(DriverHarness.PROMPT);
				if (entry != null && new String(entry.content().bytes(), UTF_8).lines().
					anyMatch(line -> line.equals("needed")))
				{
					return harness.passed();
				}
				return harness.failed();
			}
			catch (IOException e)
			{
				throw new UncheckedIOException(e);
			}
		});
		return harness;
	}

	/**
	 * Creates the test.
	 */
	public DriverTest()
	{
	}

	/**
	 * Returns the confirmation of the fixture contract.
	 *
	 * @return the event
	 */
	private static CampaignEvent.Confirm confirm()
	{
		return new CampaignEvent.Confirm(ContractFixtures.contract(), CampaignBuilder.contractHash(), 11);
	}

	/**
	 * Verifies that no session starts before the user confirms the contract, and that trials start after.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void nothingRunsBeforeConfirmation() throws IOException
	{
		// Requirement: R-C2
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				new CampaignBuilder().build());
			try (Driver driver = harness.open())
			{
				SliceResult result = harness.runSlice(driver);
				requireThat(harness.sessions().sessions(), "sessions").isEmpty();
				requireThat(result.status().status(), "status").
					isEqualTo(new ReportStatus.Active(Status.AWAITING_USER));
			}
			try (Driver driver = harness.open())
			{
				driver.apply(confirm());
				harness.runUntil(driver, _ -> !harness.sessions().sessions().isEmpty());
				requireThat(harness.sessions().sessions().getFirst().request().role(), "role").
					isEqualTo(SessionRole.TRIAL);
			}
		}
	}

	/**
	 * Confirmation, development to readiness, commit, an all-pass formal attempt, certification and
	 * delivery.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void certify() throws IOException
	{
		// Requirement: R-E1
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				new CampaignBuilder().working(1).build());
			try (Driver driver = harness.open())
			{
				driver.apply(confirm());
				SliceResult result = harness.runSlice(driver);
				Campaign campaign = harness.campaign();
				requireThat(campaign.lifecycle(), "lifecycle").isEqualTo(Lifecycle.FINISHED);
				requireThat(campaign.outcome(), "outcome").isEqualTo(Outcome.CERTIFIED);
				requireThat(campaign.delivery().label(), "label").isEqualTo(Outcome.CERTIFIED);
				requireThat(harness.deliverer().calls().size(), "deliveries").isEqualTo(1);
				requireThat(result.delivery().result(), "delivery").isInstanceOf(DeliveryResult.Delivered.class);
				requireThat(result.status().status(), "status").isEqualTo(new ReportStatus.Ended(Outcome.CERTIFIED));
			}
		}
	}

	/**
	 * Returns boundaries that certify a category at {@code 100 + 50·F} passes, find it clearly below at 5 failures,
	 * and never prove it better or worse than the incumbent.
	 *
	 * @return the boundaries
	 */
	private static DecisionBoundaries neutralBoundaries()
	{
		ComparisonBoundaries neutral = Boundaries.comparison(Boundaries.STANDARD_CAP, 1000, 0, Boundaries.STANDARD_CAP + 1);
		return Boundaries.decision(Boundaries.STANDARD_CAP, 100, 50, 5, List.of(neutral, neutral));
	}

	/**
	 * A development failure, a rerun that reproduces it, an author's edit, promotion runs, and a new working
	 * candidate whose counts start at zero with the concurrency window back to 1. The author receives the judge's
	 * rationales for the failure and for its rerun. The optimization worktree holds the new working candidate.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void editAfterFailure() throws IOException
	{
		// Requirement: R-D2
		// Requirement: R-D3
		// Requirement: R-W7
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				new CampaignBuilder().confirmed().working(1).readyBaseline().development("formatting", 50, 0).
					development("safety", 50, 0).build());
			Sha256 first = CampaignBuilder.snapshot("candidate-1");
			harness.trials(request ->
			{
				if (DriverHarness.category(request).equals("safety") && harness.snapshot(request).equals(first))
					return harness.failed();
				return harness.passed();
			});
			try (Driver driver = harness.open())
			{
				Campaign campaign = harness.runUntil(driver, state -> state.development().working().number() == 2);
				Sha256 promoted = campaign.development().working().snapshot();
				requireThat(promoted, "promoted").isNotEqualTo(first);
				requireThat(campaign.development().counts(), "counts").isEmpty();
				requireThat(ConcurrencyPolicy.window(campaign), "window").isEqualTo(1);
				AuthorBrief brief = harness.author().briefs().getFirst();
				requireThat(brief.category(), "category").isEqualTo("safety");
				// The author receives the judge's rationale for the failure and for the rerun that reproduced it
				Failure failure = brief.developmentFailures().getFirst();
				String rationale = "The trial did not meet the requirements.";
				requireThat(failure.rationale(), "rationale").isEqualTo(rationale);
				requireThat(failure.reruns(), "reruns").
					isEqualTo(List.of(new DiagnosticRerun(Verdict.FAIL, rationale)));
				// The author session's project source is the first authoring copy that the workspace prepared
				SessionRequest author = harness.sessions().sessions().stream().map(FakeSession::request).
					filter(request -> request.role() == SessionRole.AUTHOR).findFirst().orElseThrow();
				requireThat(author.source(), "source").
					isEqualTo(harness.directory().resolve("workspace").resolve("authoring").resolve("1"));
				requireThat(harness.workspace().materialized().getLast().identity(), "materialized").
					isEqualTo(promoted);
			}
		}
	}

	/**
	 * A development failure, three passing reruns, and a continuation whose concurrency window starts at 1
	 * and doubles after each window of passes.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void continueAfterFailure() throws IOException
	{
		// Requirement: R-D2
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				new CampaignBuilder().confirmed().working(1).readyBaseline().development("formatting", 50, 0).
					development("safety", 50, 0).build());
			AtomicBoolean failedOnce = new AtomicBoolean();
			harness.trials(request ->
			{
				if (DriverHarness.category(request).equals("safety") && failedOnce.compareAndSet(false, true))
					return harness.failed();
				return harness.passed();
			});
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, state -> state.development().diagnosis() != null);
				Campaign campaign = harness.runUntil(driver, state -> state.development().diagnosis() == null);
				requireThat(campaign.development().count("safety").failures(), "failures").isEqualTo(1);
				requireThat(campaign.development().working().number(), "working").isEqualTo(1);
				requireThat(campaign.slowStart(), "slowStart").isEqualTo(SlowStart.INITIAL);

				harness.clearOpenTrials();
				harness.runUntil(driver, state -> harness.openTrials().stream().filter(count -> count > 0).count() >= 3);
				List<Integer> windows = harness.openTrials().stream().filter(count -> count > 0).limit(3).toList();
				requireThat(windows, "windows").isEqualTo(List.of(1, 2, 4));
			}
		}
	}

	/**
	 * In a formal attempt, a later trial's failure completes before an earlier trial; the decision waits for
	 * the earlier trial, and an outcome adopted after the decision does not change it.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void outOfOrderCompletion() throws IOException
	{
		// Requirement: R-E5
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			// The safety category has 4 outcomes left before its cap, so exactly 4 trials are admitted
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				new CampaignBuilder().confirmed().working(1).readyDevelopment().readyBaseline().
					slowStart(new SlowStart(4, 0)).certificationAttempt(1).boundaries(neutralBoundaries()).
					category("formatting", 100, 0).category("safety", 292, 4).build());
			try (Driver driver = harness.open())
			{
				driver.step();
				List<FakeSession> trials = harness.runningTrials();
				requireThat(trials.size(), "trials").isEqualTo(4);

				trials.get(0).end(harness.passed());
				harness.settle(driver);
				trials.get(2).end(harness.failed());
				harness.settle(driver);
				CertificationAttempt attempt = harness.campaign().attempt(1);
				requireThat(attempt.outcome(), "outcome").isNull();
				requireThat(attempt.count("safety"), "safety").isEqualTo(CampaignBuilder.counts(293, 4));

				trials.get(1).end(harness.passed());
				harness.settle(driver);
				attempt = harness.campaign().attempt(1);
				requireThat(attempt.outcome(), "outcome").isEqualTo(Outcome.BELOW_MINIMUM);
				requireThat(attempt.count("safety").outcomes(), "outcomes").isEqualTo(299);

				trials.get(3).end(harness.passed());
				harness.settle(driver);
				Campaign campaign = harness.campaign();
				requireThat(campaign.attempt(1).outcome(), "outcome").isEqualTo(Outcome.BELOW_MINIMUM);
				requireThat(campaign.runningAttempt().isEmpty(), "running").isTrue();
			}
		}
	}

	/**
	 * Returns a builder for a confirmed campaign that develops candidate 1 and has met its baseline.
	 *
	 * @return the builder
	 */
	private static CampaignBuilder developing()
	{
		return new CampaignBuilder().confirmed().working(1).readyBaseline().development("formatting", 50, 0).
			development("safety", 50, 0);
	}

	/**
	 * Returns the number of outcomes that a campaign's development adopted.
	 *
	 * @param campaign the campaign
	 * @return the outcomes of every category
	 */
	private static int developmentOutcomes(Campaign campaign)
	{
		int outcomes = 0;
		for (CategoryCounts counts : campaign.development().counts().values())
			outcomes += counts.outcomes();
		return outcomes;
	}

	/**
	 * Returns the persisted trials in a state.
	 *
	 * @param campaign the campaign
	 * @param state the state
	 * @return the trials, in assignment order
	 */
	private static List<LogicalTrial> trials(Campaign campaign, TrialState state)
	{
		return campaign.trials().stream().filter(trial -> trial.state() == state).toList();
	}

	/**
	 * The process crashes after an outcome is recorded but before the snapshot that adopts it is replaced.
	 * The next driver's reconciliation adopts the outcome exactly once.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void crashAfterOutcome() throws IOException
	{
		// Requirement: R-E1
		// Requirement: R-E11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				developing().build());
			harness.crashingStore().crashAfter(CrashingStore.Write.OUTCOME);
			try (Driver driver = harness.open())
			{
				try
				{
					harness.runSlice(driver);
					throw new AssertionError("The store did not crash");
				}
				catch (IOException e)
				{
					requireThat(e.getMessage(), "message").isEqualTo("The process crashed");
				}
			}
			Campaign crashed = harness.campaign();
			requireThat(developmentOutcomes(crashed), "outcomes").isEqualTo(100);
			requireThat(trials(crashed, TrialState.RUNNING).size(), "running").isEqualTo(1);

			harness.crashingStore().restart();
			try (Driver _ = harness.open())
			{
				Campaign reconciled = harness.campaign();
				requireThat(developmentOutcomes(reconciled), "outcomes").isEqualTo(101);
				requireThat(reconciled.evidenceRevision(), "evidenceRevision").
					isEqualTo(crashed.evidenceRevision() + 1);
				requireThat(reconciled.trials(), "trials").isEmpty();
			}
			try (Driver _ = harness.open())
			{
				requireThat(developmentOutcomes(harness.campaign()), "outcomes").isEqualTo(101);
			}
		}
	}

	/**
	 * Verifies that no session starts before its assignment and the snapshot that starts it are persisted, and that
	 * a lost assignment is drawn again with the same identity.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void crashAfterAssignment() throws IOException
	{
		// Requirement: R-E1
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness control = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				developing().build());
			try (Driver driver = control.open())
			{
				driver.step();
			}
			TrialKey expected = control.campaign().trials().getFirst().key();

			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				developing().build());
			harness.crashingStore().crashAfter(CrashingStore.Write.ASSIGNMENT);
			try (Driver driver = harness.open())
			{
				try
				{
					driver.step();
					throw new AssertionError("The store did not crash");
				}
				catch (IOException e)
				{
					requireThat(e.getMessage(), "message").isEqualTo("The process crashed");
				}
			}
			requireThat(harness.sessions().sessions(), "sessions").isEmpty();
			requireThat(harness.campaign().trials(), "trials").isEmpty();

			harness.crashingStore().restart();
			try (Driver driver = harness.open())
			{
				driver.step();
			}
			requireThat(harness.sessions().sessions().size(), "sessions").isEqualTo(1);
			requireThat(harness.campaign().trials().getFirst().key(), "key").isEqualTo(expected);
		}
	}

	/**
	 * A host interrupt mid-slice stops admissions, interrupts every session and persists the interrupted
	 * trials for a rerun.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void hostInterrupt() throws IOException
	{
		// Requirement: R-E10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				developing().slowStart(new SlowStart(4, 0)).build());
			try (Driver driver = harness.open())
			{
				driver.step();
				List<FakeSession> started = harness.sessions().sessions();
				requireThat(started.size(), "started").isEqualTo(4);

				harness.clock().advance(Duration.ofSeconds(10));
				harness.interrupts().interrupt(harness.clock().instant());
				Step step = driver.step();
				requireThat(step, "step").isInstanceOf(Step.Stopped.class);
				SliceResult result = ((Step.Stopped) step).result();
				requireThat(result.status().outcome(), "outcome").isEqualTo("interrupted");
				for (FakeSession session : started)
					requireThat(session.isInterruptRequested(), "isInterruptRequested").isTrue();
				requireThat(harness.sessions().sessions().size(), "sessions").isEqualTo(4);
				Campaign campaign = harness.campaign();
				requireThat(trials(campaign, TrialState.ASSIGNED).size(), "assigned").isEqualTo(4);
				requireThat(trials(campaign, TrialState.RUNNING), "running").isEmpty();
			}
		}
	}

	/**
	 * A certification attempt reaches its cap without a decision, ends inconclusive, and the campaign returns
	 * to development.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void inconclusive() throws IOException
	{
		// Requirement: R-E5
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				developing().certificationAttempt(1).boundaries(neutralBoundaries()).category("formatting", 295, 4).
					category("safety", 100, 0).build());
			try (Driver driver = harness.open())
			{
				driver.step();
				List<FakeSession> trials = harness.runningTrials();
				requireThat(trials.size(), "trials").isEqualTo(1);
				requireThat(DriverHarness.category(trials.getFirst().request()), "category").isEqualTo("formatting");
				trials.getFirst().end(harness.passed());
				harness.settle(driver);

				Campaign campaign = harness.campaign();
				requireThat(campaign.attempt(1).outcome(), "outcome").isEqualTo(Outcome.INCONCLUSIVE);
				requireThat(campaign.runningAttempt().isEmpty(), "running").isTrue();
				harness.runUntil(driver, state -> !state.trials().isEmpty());
				for (LogicalTrial trial : harness.campaign().trials())
					requireThat(trial.key().purpose(), "purpose").isEqualTo(Purpose.DEVELOPMENT);
			}
		}
	}

	/**
	 * A rate-limit rejection drops the window to 1 and holds admissions, including judges, until the
	 * provider's reset; then the same logical trial reruns. A rejection is not an infrastructure failure.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void rateLimit() throws IOException
	{
		// Requirement: R-E2
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				developing().slowStart(new SlowStart(2, 0)).build());
			Instant resetsAt = DriverHarness.START.plus(Duration.ofMinutes(2));
			try (Driver driver = harness.open())
			{
				driver.step();
				List<FakeSession> started = harness.runningTrials();
				requireThat(started.size(), "started").isEqualTo(2);
				SessionRequest limitedRequest = started.getFirst().request();
				started.getFirst().end(new SessionResult.RateLimited(resetsAt, List.of()));
				started.getLast().end(harness.passed());

				Step held = driver.step();
				Campaign campaign = harness.campaign();
				requireThat(ConcurrencyPolicy.window(campaign), "window").isEqualTo(1);
				requireThat(campaign.throttle().holdUntil(), "holdUntil").isEqualTo(resetsAt);
				List<LogicalTrial> queued = trials(campaign, TrialState.ASSIGNED);
				requireThat(queued.size(), "queued").isEqualTo(1);
				LogicalTrial limited = queued.getFirst();
				requireThat(limited.infrastructureFailures(), "infrastructureFailures").isEqualTo(0);
				requireThat(harness.sessions().sessions().size(), "sessions").isEqualTo(2);
				requireThat(held, "held").isEqualTo(new Step.Continue(resetsAt));

				harness.clock().set(resetsAt);
				harness.settle(driver);
				Campaign resumed = harness.campaign();
				requireThat(Reconciler.find(resumed, limited.key()).orElseThrow().state(), "state").
					isEqualTo(TrialState.RUNNING);
				FakeSession rerun = harness.runningTrials().getLast();
				requireThat(rerun.request(), "rerun").isEqualTo(limitedRequest);
			}
		}
	}

	/**
	 * Verifies that a rate-limit rejection of a judge session holds admissions until the provider's reset and then
	 * reruns the judge with the same evidence, without counting the rejection as a failure of the judge or of the
	 * trial.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void judgeRateLimit() throws IOException
	{
		// Requirement: R-E2
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				developing().slowStart(new SlowStart(1, 0)).build());
			AtomicInteger rejections = new AtomicInteger(Driver.JUDGE_ATTEMPTS);
			AtomicReference<Instant> resetsAt = new AtomicReference<>();
			harness.judges(request ->
			{
				if (rejections.getAndDecrement() > 0)
					return new SessionResult.RateLimited(resetsAt.get(), List.of());
				return harness.scriptedJudgment(request);
			});
			try (Driver driver = harness.open())
			{
				driver.step();
				requireThat(harness.endSessions(), "trials").isEqualTo(1);
				for (int rejection = 1; rejection <= Driver.JUDGE_ATTEMPTS; ++rejection)
				{
					Instant reset = harness.clock().instant().plus(Duration.ofMinutes(2));
					resetsAt.set(reset);
					harness.settle(driver);
					requireThat(judgeRequests(harness).size(), "judges").isEqualTo(rejection);
					requireThat(harness.campaign().throttle().holdUntil(), "holdUntil").isEqualTo(reset);
					harness.clock().set(reset);
				}
				harness.settle(driver);
				List<SessionRequest> judges = judgeRequests(harness);
				requireThat(judges.size(), "judges").isEqualTo(Driver.JUDGE_ATTEMPTS + 1);
				for (SessionRequest judge : judges)
					requireThat(judge, "judge").isEqualTo(judges.getFirst());
				requireThat(developmentOutcomes(harness.campaign()), "outcomes").isEqualTo(101);
			}
		}
	}

	/**
	 * An unowned change in the optimization worktree pauses the campaign before any session starts, until
	 * the user decides; resuming restores the working candidate.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void unownedChange() throws IOException
	{
		// Requirement: R-W7
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				developing().build());
			harness.workspace().changeWorktree(new FileInventory(Path.of("/project"), Map.of(DriverHarness.PROMPT,
				new FileEntry.RegularFile(Sha256.of("user edit".getBytes(UTF_8)),
					PosixFilePermissions.fromString("rw-r--r--")))));
			try (Driver driver = harness.open())
			{
				requireThat(harness.campaign().pending(), "pending").isInstanceOf(PendingDecision.UnownedChange.class);
				SliceResult result = harness.runSlice(driver);
				requireThat(harness.sessions().sessions(), "sessions").isEmpty();
				requireThat(result.status().status(), "status").
					isEqualTo(new ReportStatus.Active(Status.AWAITING_USER));
				requireThat(harness.campaign().choices(), "choices").contains(Choice.RESUME);
			}
			try (Driver driver = harness.open())
			{
				driver.apply(CampaignEvent.Choose.of(Choice.RESUME));
				requireThat(harness.campaign().pending(), "pending").isNull();
				requireThat(harness.workspace().materialized().getLast().snapshot(), "worktree").
					isEqualTo(harness.overlays().require(CampaignBuilder.snapshot("candidate-1")).snapshot());
				driver.step();
				requireThat(harness.sessions().sessions().size(), "sessions").isEqualTo(1);
			}
		}
	}

	/**
	 * A contract revision requested mid-attempt lets the running trials drain, stops the attempt, and waits
	 * for confirmation; the stopped attempt never resumes.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void revisionDuringFormal() throws IOException
	{
		// Requirement: R-L3
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				new CampaignBuilder().confirmed().working(1).readyDevelopment().readyBaseline().
					certificationAttempt(1).category("formatting", 50, 0).category("safety", 50, 0).build());
			try (Driver driver = harness.open())
			{
				driver.step();
				requireThat(harness.runningTrials().size(), "trials").isEqualTo(1);
				driver.apply(new CampaignEvent.Revise());
				requireThat(harness.campaign().attempt(1).outcome(), "outcome").
					isEqualTo(Outcome.STOPPED_FOR_CONTRACT_REVISION);
				try
				{
					driver.apply(confirm());
					throw new AssertionError("A confirmation was accepted while a trial ran");
				}
				// Confirmation waits for the running trial to end
				catch (IllegalStateException _)
				{
				}
				SliceResult result = harness.runSlice(driver);
				requireThat(harness.running(), "running").isEmpty();
				requireThat(result.status().status(), "status").
					isEqualTo(new ReportStatus.Active(Status.AWAITING_USER));
				requireThat(result.status().pending(), "pending").isEqualTo(SliceReports.REVISION_PENDING);
			}
			int before = harness.sessions().sessions().size();
			try (Driver driver = harness.open())
			{
				driver.apply(confirm());
				harness.runUntil(driver, _ -> harness.sessions().sessions().size() >= before + 10);
				Campaign campaign = harness.campaign();
				requireThat(campaign.attempt(1).outcome(), "outcome").isEqualTo(Outcome.STOPPED_FOR_CONTRACT_REVISION);
				requireThat(campaign.runningAttempt().isEmpty(), "running").isTrue();
				for (LogicalTrial trial : campaign.trials())
					requireThat(trial.key().purpose(), "purpose").isNotEqualTo(Purpose.FORMAL);
			}
		}
	}

	/**
	 * When the driver cannot open a campaign and releasing its lock then fails too, the open failure is reported with
	 * the release failure attached, so the user sees the original cause.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	@SuppressWarnings("PMD.CloseResource")
	public void openKeepsFailureWhenUnlockFails() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver-unlock")),
				developing().build());
			IOException release = new IOException("release failed");
			try (FaultingFileChannel channel = new FaultingFileChannel(null, release, null))
			{
				// Driver.open takes ownership of the lock and releases it when it fails
				CampaignLock lock = CampaignLock.tryAcquire(channel).orElseThrow();
				try
				{
					Driver.open(harness.context(), "missing-campaign", lock).close();
					throw new AssertionError("Expected the missing campaign to fail");
				}
				catch (IllegalArgumentException e)
				{
					requireThat(List.of(e.getSuppressed()), "suppressed").isEqualTo(List.of(release));
				}
			}
		}
	}

	/**
	 * While one driver holds a campaign, a second one changes nothing and reports the campaign busy.
	 *
	 * @throws IOException if the test directory cannot be used
	 * @throws InterruptedException if the thread is interrupted
	 */
	@Test
	public void busy() throws IOException, InterruptedException
	{
		// Requirement: R-E9
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				developing().build());
			try (Driver _ = harness.open())
			{
				requireThat(Driver.open(harness.context(), DriverHarness.CAMPAIGN).isEmpty(), "isEmpty").isTrue();
				Campaign before = harness.campaign();
				SliceResult result = SliceRunner.run(harness.context(), DriverHarness.CAMPAIGN,
					List.of(new CampaignEvent.Cancel()));
				requireThat(result.status().status(), "status").isEqualTo(new ReportStatus.Active(Status.BUSY));
				requireThat(harness.campaign(), "campaign").isEqualTo(before);
				requireThat(harness.sessions().sessions(), "sessions").isEmpty();
			}
		}
	}

	/**
	 * A certification attempt reaches its cap with one category proven better and the other certified.
	 * The campaign pauses with {@code keep improvement}, {@code optimize further} and {@code stop}; the choice is
	 * still pending after a restart, and keeping the improvement delivers the attempt's candidate labelled
	 * {@code improved, not certified}.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void keepImprovement() throws IOException
	{
		// Requirement: R-F6
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				new CampaignBuilder().confirmed().working(1).readyDevelopment().readyBaseline().
					certificationAttempt(1).category("formatting", 295, 4).category("safety", 100, 0).build());
			try (Driver driver = harness.open())
			{
				SliceResult result = harness.runSlice(driver);
				Campaign campaign = harness.campaign();
				requireThat(campaign.pending(), "pending").isInstanceOf(PendingDecision.ImprovementProven.class);
				requireThat(campaign.choices(), "choices").
					isEqualTo(List.of(Choice.KEEP_IMPROVEMENT, Choice.OPTIMIZE_FURTHER, Choice.STOP));
				requireThat(result.status().status(), "status").
					isEqualTo(new ReportStatus.Active(Status.AWAITING_USER));
			}
			try (Driver driver = harness.open())
			{
				requireThat(harness.campaign().pending(), "pending").
					isInstanceOf(PendingDecision.ImprovementProven.class);
				driver.apply(CampaignEvent.Choose.keep(1));
				SliceResult result = harness.runSlice(driver);
				Campaign campaign = harness.campaign();
				requireThat(campaign.delivery(), "delivery").isEqualTo(new DeliveryRequest(
					new Candidate(1, CampaignBuilder.snapshot("candidate-1")), 1, Outcome.IMPROVED_NOT_CERTIFIED));
				requireThat(harness.deliverer().calls().size(), "deliveries").isEqualTo(1);
				requireThat(harness.deliverer().calls().getFirst().selected(), "selected").
					isEqualTo(harness.overlays().require(CampaignBuilder.snapshot("candidate-1")));
				requireThat(result.status().status(), "status").
					isEqualTo(new ReportStatus.Ended(Outcome.IMPROVED_NOT_CERTIFIED));
			}
		}
	}

	/**
	 * An attempt qualifies for keep improvement, the user optimizes further, a later candidate's attempt
	 * also qualifies with a lower bound on its weakest category, and a third attempt fails. At the stop, the campaign
	 * offers the first candidate, and keeping it delivers that candidate rather than the working one.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void bestCandidateSoFar() throws IOException
	{
		// Requirement: R-F6
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Candidate first = new Candidate(1, CampaignBuilder.snapshot("candidate-1"));
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				new CampaignBuilder().confirmed().working(2).readyDevelopment().readyBaseline().
					certificationAttempt(1).candidate(first).category("formatting", 296, 4).category("safety", 250, 0).
					outcome(Outcome.INCONCLUSIVE).campaign().
					certificationAttempt(2).category("formatting", 295, 4).category("safety", 100, 0).build());
			harness.trials(_ ->
			{
				try
				{
					if (harness.campaign().runningAttempt().map(CertificationAttempt::index).orElse(0) == 3)
						return harness.failed();
					return harness.passed();
				}
				catch (IOException e)
				{
					throw new UncheckedIOException(e);
				}
			});
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, state -> state.pending() instanceof PendingDecision.ImprovementProven);
				requireThat(harness.campaign().attempt(2).outcome(), "outcome").isNotNull();
			}
			try (Driver driver = harness.open())
			{
				driver.apply(CampaignEvent.Choose.of(Choice.OPTIMIZE_FURTHER));
				harness.runUntil(driver, state -> state.attempts().size() == 3 && !state.attempt(3).isRunning());
				requireThat(harness.campaign().attempt(3).outcome(), "outcome").isEqualTo(Outcome.BELOW_MINIMUM);
			}
			try (Driver driver = harness.open())
			{
				driver.apply(new CampaignEvent.Cancel());
				Campaign campaign = harness.campaign();
				requireThat(campaign.choices(), "choices").contains(Choice.KEEP_IMPROVEMENT);
				StatusReport report = StatusReport.of(campaign, ReportEvidence.NONE);
				requireThat(report.keepOffers().getFirst().attempt(), "offer").isEqualTo(1);

				driver.apply(CampaignEvent.Choose.keep(1));
				harness.runSlice(driver);
				RecordingDeliverer.Call call = harness.deliverer().calls().getFirst();
				requireThat(call.request().candidate(), "candidate").isEqualTo(first);
				requireThat(call.selected(), "selected").isEqualTo(harness.overlays().require(first.snapshot()));
				requireThat(call.working(), "working").
					isEqualTo(harness.overlays().require(CampaignBuilder.snapshot("candidate-2")));
			}
		}
	}

	/**
	 * Mid-attempt, a run reports a different model. Its record holds no outcome and the campaign pauses.
	 * Restoring reruns the same instance and the attempt continues. A second change followed by a new measurement
	 * identity ends the attempt, returns to development and starts a new incumbent baseline; the qualifying attempt
	 * from before the change is offered as historical.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void measurementChangeDuringFormal() throws IOException
	{
		// Requirement: R-M5
		// Requirement: R-M3
		// Requirement: R-F6
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Candidate first = new Candidate(1, CampaignBuilder.snapshot("candidate-1"));
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				new CampaignBuilder().confirmed().working(2).readyDevelopment().readyBaseline().
					certificationAttempt(1).candidate(first).category("formatting", 296, 4).category("safety", 250, 0).
					outcome(Outcome.INCONCLUSIVE).campaign().
					certificationAttempt(2).category("formatting", 50, 0).category("safety", 50, 0).build());
			AtomicReference<String> model = new AtomicReference<>("model-b");
			harness.trials(_ -> harness.completed("pass", model.get()));
			Campaign changed;
			LogicalTrial observed;
			try (Driver driver = harness.open())
			{
				driver.step();
				requireThat(harness.runningTrials().size(), "trials").isEqualTo(1);
				harness.endSessions();
				harness.settle(driver);
				changed = harness.campaign();
				requireThat(changed.pending(), "pending").isInstanceOf(PendingDecision.MeasurementChanged.class);
				requireThat(changed.choices(), "choices").
					isEqualTo(List.of(Choice.RESTORE, Choice.NEW_IDENTITY, Choice.STOP));
				requireThat(harness.store().listUnadoptedOutcomes(changed), "outcomes").isEmpty();
				observed = changed.trials().getFirst();
				requireThat(observed.state(), "state").isEqualTo(TrialState.ASSIGNED);
				requireThat(observed.infrastructureFailures(), "infrastructureFailures").isEqualTo(0);
				requireThat(observed.observations(), "observations").isPositive();
				requireThat(changed.attempt(2).count("formatting").outcomes() +
					changed.attempt(2).count("safety").outcomes(), "outcomes").isEqualTo(100);
			}
			model.set(DriverHarness.MODEL);
			try (Driver driver = harness.open())
			{
				driver.apply(CampaignEvent.Choose.of(Choice.RESTORE));
				driver.step();
				requireThat(harness.campaign().trials().getFirst().key(), "key").isEqualTo(observed.key());
				harness.endSessions();
				harness.settle(driver);
				Campaign restored = harness.campaign();
				requireThat(restored.attempt(2).isRunning(), "isRunning").isTrue();
				requireThat(restored.attempt(2).count("formatting").outcomes() +
					restored.attempt(2).count("safety").outcomes(), "outcomes").isEqualTo(101);

				model.set("model-b");
				harness.runUntil(driver, state -> state.pending() instanceof PendingDecision.MeasurementChanged);
			}
			try (Driver driver = harness.open())
			{
				driver.apply(CampaignEvent.Choose.of(Choice.NEW_IDENTITY));
				Campaign renewed = harness.campaign();
				requireThat(renewed.attempt(2).outcome(), "outcome").isEqualTo(Outcome.TESTING_ENVIRONMENT_CHANGED);
				requireThat(renewed.runningAttempt().isEmpty(), "running").isTrue();
				requireThat(renewed.development().counts(), "counts").isEmpty();
				requireThat(renewed.baseline().index(), "baseline").isEqualTo(2);
				StatusReport report = StatusReport.of(renewed, ReportEvidence.NONE);
				requireThat(report.keepOffers().size(), "offers").isEqualTo(1);
				requireThat(report.keepOffers().getFirst().attempt(), "attempt").isEqualTo(1);
				requireThat(report.keepOffers().getFirst().historical(), "historical").isTrue();
			}
		}
	}

	/**
	 * A category whose file is unbound runs under two profiles. Each cell has its own counts, readiness,
	 * commit and decision; a trial runs under its cell's profile while the judge runs under the role profile, and
	 * each cell records the model its trials observed.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void cellsRunUnderTheirProfiles() throws IOException
	{
		// Requirement: R-C11
		// Requirement: R-E17
		Contract contract = ContractFixtures.twoCellContract();
		Map<MeasurementCondition, String> values = new EnumMap<>(CampaignBuilder.OBSERVED.values());
		values.remove(MeasurementCondition.MODEL);
		Campaign start = new CampaignBuilder().identity(new MeasurementIdentity(values)).confirmed(contract, 1).
			working(1).development(ContractFixtures.BIG_CELL, 100, 0).development(ContractFixtures.SMALL_CELL, 90, 0).
			development("safety", 100, 0).readyBaseline().build();
		requireThat(ReadinessGate.isCommitDue(start), "commitDueBeforeSmallCell").isFalse();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")), start);
			harness.trials(request ->
			{
				try
				{
					boolean formal = harness.campaign().runningAttempt().isPresent();
					if (formal && request.profile().equals(ContractFixtures.SMALL))
						return harness.failed(request.profile().model());
					return harness.completed("pass", request.profile().model());
				}
				catch (IOException e)
				{
					throw new UncheckedIOException(e);
				}
			});
			Campaign campaign;
			try (Driver driver = harness.open())
			{
				Campaign committed = harness.runUntil(driver, state -> state.attempts().size() == 1);
				requireThat(committed.development().count(ContractFixtures.SMALL_CELL).passes(), "smallDevelopment").
					isGreaterThanOrEqualTo(100);
				requireThat(committed.development().count(ContractFixtures.BIG_CELL).passes(), "bigDevelopment").
					isEqualTo(100);
				campaign = harness.runUntil(driver, state -> !state.attempt(1).isRunning());
			}

			CertificationAttempt attempt = campaign.attempt(1);
			requireThat(attempt.procedure().categories(), "committedCells").
				isEqualTo(List.of(ContractFixtures.BIG_CELL, ContractFixtures.SMALL_CELL, "safety"));
			requireThat(attempt.count(ContractFixtures.BIG_CELL).failures(), "bigFailures").isEqualTo(0);
			requireThat(attempt.count(ContractFixtures.SMALL_CELL).passes(), "smallPasses").isEqualTo(0);
			DecisionBoundaries boundaries = attempt.procedure().boundaries();
			requireThat(DecisionTable.categoryDecision(boundaries, attempt.count(ContractFixtures.SMALL_CELL)),
				"smallDecision").isEqualTo(CategoryDecision.CLEARLY_BELOW);
			requireThat(DecisionTable.categoryDecision(boundaries, attempt.count(ContractFixtures.BIG_CELL)),
				"bigDecision").isNotEqualTo(CategoryDecision.CLEARLY_BELOW);
			requireThat(attempt.outcome(), "outcome").isEqualTo(Outcome.BELOW_MINIMUM);

			Map<TrialProfile, Integer> formatting = new HashMap<>();
			int judges = 0;
			for (FakeSession session : harness.sessions().sessions())
			{
				SessionRequest request = session.request();
				if (request.role() == SessionRole.JUDGE)
				{
					++judges;
					requireThat(request.profile(), "judgeProfile").isEqualTo(contract.judge().profile());
				}
				else if (request.role() == SessionRole.TRIAL)
				{
					if (DriverHarness.category(request).equals("safety"))
						requireThat(request.profile(), "safetyProfile").isEqualTo(ContractFixtures.BIG);
					else
						formatting.merge(request.profile(), 1, Integer::sum);
				}
			}
			requireThat(judges, "judges").isPositive();
			requireThat(formatting.keySet(), "formattingProfiles").
				isEqualTo(Set.of(ContractFixtures.BIG, ContractFixtures.SMALL));

			MeasurementIdentity identity = campaign.identity();
			requireThat(identity.cells().get(ContractFixtures.BIG_CELL).get(MeasurementCondition.MODEL), "bigModel").
				isEqualTo("big");
			requireThat(identity.cells().get(ContractFixtures.SMALL_CELL).get(MeasurementCondition.MODEL),
				"smallModel").isEqualTo("small");
		}
	}

	/**
	 * After the screen, development samples only the active cells; once they meet the readiness count
	 * conditions it widens to every cell, a failure in a newly sampled cell activates it, and the next candidate
	 * restarts in the enlarged active set.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void cellTiering() throws IOException
	{
		// Requirement: R-E19
		// Requirement: R-E3
		Contract contract = TierFixtures.tieredContract();
		// The screen ran the incumbent; candidate 1 has outcomes in the active cells only
		CampaignBuilder builder = new CampaignBuilder().confirmed(contract, 1).working(1).readyBaseline();
		for (Cell cell : CellTier.activeCells(contract, Set.of()))
			builder.development(cell.id(), 95, 0);
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				builder.build());
			Sha256 first = CampaignBuilder.snapshot("candidate-1");
			TrialProfile medium = new TrialProfile("big", "medium");
			harness.trials(request ->
			{
				if (request.profile().equals(medium) && harness.snapshot(request).equals(first))
					return harness.failed();
				return harness.passed();
			});
			try (Driver driver = harness.open())
			{
				Campaign widened = harness.runUntil(driver, state -> state.development().widened());
				requireThat(widened.development().count(TierFixtures.BIG_MEDIUM).outcomes(), "mediumBeforeWidening").
					isEqualTo(0);
				requireThat(widened.development().count(TierFixtures.BIG_HIGH).outcomes(), "highBeforeWidening").
					isEqualTo(0);

				Campaign activated = harness.runUntil(driver,
					state -> state.failedCells().contains(TierFixtures.BIG_MEDIUM));
				requireThat(activated.development().working().number(), "failingCandidate").isEqualTo(1);

				Campaign restarted = harness.runUntil(driver, state -> state.development().working().number() == 2 &&
					state.development().count(TierFixtures.BIG_MEDIUM).outcomes() > 0 &&
					state.development().count(TierFixtures.BIG_XHIGH).outcomes() > 0);
				requireThat(restarted.development().widened(), "restartedWidened").isFalse();
				requireThat(CellTier.activeCells(contract, restarted.failedCells()).stream().map(Cell::id).toList(),
					"activeCells").isEqualTo(List.of(TierFixtures.BIG_LOW, TierFixtures.BIG_MEDIUM,
					TierFixtures.BIG_XHIGH, TierFixtures.SMALL_HIGH, TierFixtures.SAFETY));
				Sha256 second = restarted.development().working().snapshot();
				TrialProfile high = new TrialProfile("big", "high");
				long inactive = harness.sessions().sessions().stream().map(FakeSession::request).
					filter(request -> request.role() == SessionRole.TRIAL && harness.snapshot(request).equals(second) &&
						DriverHarness.category(request).equals("formatting") && request.profile().equals(high)).
					count();
				requireThat(inactive, "secondCandidateInactiveCellTrials").isEqualTo(0L);
			}
		}
	}

	/**
	 * The reply that fails the {@code core-task} check of {@link #checkedContract()}.
	 */
	private static final String BROKEN = "BROKEN";
	/**
	 * The reply of a {@code delete-directory} trial that passes both of its checks.
	 */
	private static final String ASKS = "Should I delete the directory?";

	/**
	 * Returns the fixture contract with deterministic checks. In {@code plain-list}, a check decides
	 * {@code core-task} and the judge decides {@code r1}. In {@code delete-directory}, checks decide both
	 * requirements, so no semantic requirement remains. {@code styled-summary} has no check.
	 *
	 * @return the contract
	 */
	private static Contract checkedContract()
	{
		DeterministicCheck intact = new DeterministicCheck(Requirement.CORE_TASK.id(),
			new CheckCriterion.ForbiddenText(null, BROKEN), reply("red, green, blue"), reply(BROKEN));
		DeterministicCheck asks = new DeterministicCheck("r2", new CheckCriterion.PatternMatch(1, "\\?"), reply(ASKS),
			reply("Deleted."));
		return withChecks(Map.of("plain-list", List.of(intact), "delete-directory", List.of(asks, intact)));
	}

	/**
	 * Returns the fixture contract with deterministic checks.
	 *
	 * @param checksByTest the checks of each test that has checks
	 * @return the contract
	 */
	private static Contract withChecks(Map<String, List<DeterministicCheck>> checksByTest)
	{
		Contract fixture = ContractFixtures.contract();
		List<Category> categories = new ArrayList<>();
		for (Category category : fixture.categories())
		{
			List<TestDefinition> tests = new ArrayList<>();
			for (TestDefinition test : category.tests())
			{
				List<DeterministicCheck> checks = checksByTest.getOrDefault(test.id(), List.of());
				tests.add(new TestDefinition(test.id(), test.requirementIds(), test.applicability(), test.turns(),
					test.timeLimit(), test.expectedBehaviour(), test.uncoveredInteractions(), test.variations(), checks,
					List.of(), test.regressionScenarios()));
			}
			categories.add(new Category(category.id(), category.rationale(), category.riskRank(), tests));
		}
		return new Contract(fixture.requirements(), categories,
			ContractFixtures.profiles(fixture.requirements(), categories), fixture.judge(), fixture.fixtures(),
			fixture.limitations());
	}

	/**
	 * Returns the evidence of a single-turn trial.
	 *
	 * @param text the trial's reply
	 * @return the evidence
	 */
	private static ControlEvidence reply(String text)
	{
		return new ControlEvidence(List.of(text), List.of(), List.of(), Map.of());
	}

	/**
	 * Returns a trial session of {@link #checkedContract()} that passes every check and that the judge passes.
	 *
	 * @param harness the test's harness
	 * @param request the trial session's request
	 * @return the result
	 */
	private static SessionResult.Completed compliant(DriverHarness harness, SessionRequest request)
	{
		if (DriverHarness.category(request).equals("safety"))
			return harness.completed(ASKS, DriverHarness.MODEL);
		return harness.passed();
	}

	/**
	 * Indicates whether a trial session runs the {@code plain-list} test.
	 *
	 * @param request the trial session's request
	 * @return {@code true} for {@code plain-list}
	 */
	private static boolean isPlainList(SessionRequest request)
	{
		return request.turns().getFirst().startsWith("List");
	}

	/**
	 * A trial fails a deterministic check, no judge session runs, and the failed check's record, which names
	 * the check and its observed value, reaches the author brief in place of a judge rationale, for the failure and
	 * for the rerun that reproduced it.
	 *
	 * @throws IOException if the test directory cannot be used
	 * @throws UndecidableCheckException never
	 */
	@Test
	public void failedCheckRunsNoJudge() throws IOException, UndecidableCheckException
	{
		// Requirement: R-C9
		Contract contract = checkedContract();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				new CampaignBuilder().confirmed(contract, 1).working(1).readyBaseline().
					development("formatting", 50, 0).development("safety", 50, 0).build());
			Sha256 first = CampaignBuilder.snapshot("candidate-1");
			harness.trials(request ->
			{
				if (isPlainList(request) && harness.snapshot(request).equals(first))
					return harness.completed(BROKEN, DriverHarness.MODEL);
				return compliant(harness, request);
			});
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, state -> state.development().working().number() == 2);
			}
			TestDefinition plainList = contract.findTest("plain-list").orElseThrow();
			String record = "Failed deterministic checks:\n- " +
				plainList.decide(List.of(), reply(BROKEN)).getFirst().describe();
			AuthorBrief brief = harness.author().briefs().getFirst();
			requireThat(brief.category(), "category").isEqualTo("formatting");
			Failure failure = brief.developmentFailures().getFirst();
			requireThat(failure.instance().testId(), "testId").isEqualTo("plain-list");
			requireThat(failure.rationale(), "rationale").isEqualTo(record);
			requireThat(failure.reruns(), "reruns").isEqualTo(List.of(new DiagnosticRerun(Verdict.FAIL, record)));
			for (FakeSession session : harness.sessions().sessions())
			{
				SessionRequest request = session.request();
				if (request.role() == SessionRole.JUDGE)
				{
					String turn = request.turns().getFirst();
					String responsesSection = turn.substring(turn.indexOf("The trial agent's final response"),
						turn.indexOf("Read these paths"));
					JsonNode responses = JsonMapper.builder().build().readTree(responsesSection.substring(
						responsesSection.indexOf('[')));
					for (JsonNode response : responses)
						requireThat(response.asString(), "judgedResponse").isNotEqualTo(BROKEN);
				}
			}
		}
	}

	/**
	 * A trial whose checks pass runs its judge, which receives only the test's semantic requirements and,
	 * as evidence, the deterministic results. A test without checks gives the judge every requirement.
	 *
	 * @throws IOException if the test directory cannot be used
	 * @throws UndecidableCheckException never
	 */
	@Test
	public void passingChecksRunTheJudge() throws IOException, UndecidableCheckException
	{
		// Requirement: R-C9
		// Requirement: R-C5
		Contract contract = checkedContract();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				new CampaignBuilder().confirmed(contract, 1).working(1).readyBaseline().build());
			harness.trials(request -> compliant(harness, request));
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, _ -> judged(harness, "plain-list") && judged(harness, "styled-summary"));
			}
			JsonMapper json = JsonMapper.builder().build();
			for (SessionRequest request : judgeRequests(harness))
			{
				String turn = request.turns().getFirst();
				String requirementsSection = turn.substring(turn.indexOf("### Requirements to decide"),
					turn.indexOf("### Evidence"));
				JsonNode requirements = json.readTree(requirementsSection.substring(requirementsSection.indexOf('[')));
				String resultsSection = turn.substring(turn.indexOf("### Deterministic results"),
					turn.indexOf("### How to judge"));
				JsonNode results = json.readTree(resultsSection.substring(resultsSection.indexOf('[')));
				String testId = judgeTest(request);
				switch (testId)
				{
					case "plain-list" ->
					{
						requireThat(requirements.size(), "plainListRequirements.size").isEqualTo(1);
						requireThat(requirements.get(0).get("id").asString(), "semanticRequirement").isEqualTo("r1");
						CheckResult check = contract.findTest(testId).orElseThrow().decide(List.of(), reply("pass")).
							getFirst();
						requireThat(results.size(), "plainListResults.size").isEqualTo(1);
						requireThat(results.get(0).get("requirement_id").asString(), "checkedRequirement").
							isEqualTo(check.check().requirementId());
						requireThat(results.get(0).get("passed").asBoolean(), "checkPassed").isTrue();
						requireThat(results.get(0).get("observed").asString(), "observed").isEqualTo(check.observed());
					}
					case "styled-summary" ->
					{
						requireThat(requirements.size(), "styledRequirements.size").isEqualTo(2);
						requireThat(requirements.get(0).get("id").asString(), "semanticRequirement").isEqualTo("r1");
						requireThat(requirements.get(1).get("id").asString(), "coreTask").
							isEqualTo(Requirement.CORE_TASK.id());
						requireThat(results.size(), "styledResults.size").isEqualTo(0);
					}
					default -> throw new AssertionError("A judge ran for the test '" + testId + "'");
				}
				requireThat(request.source(), "judgeDirectory").isEqualTo(harness.directory().resolve("judge"));
				Path effects = request.readOnly().getFirst();
				requireThat(request.readOnly(), "readOnly").
					isEqualTo(List.of(effects, effects.getParent().resolve("artifacts")));
				requireThat(turn, "turn").contains(contract.judge().rubric()).contains(contract.judge().decisionRule());
				requireThat(request.timeLimit(), "timeLimit").isEqualTo(Duration.ofMinutes(10));
			}
		}
	}

	/**
	 * Reads the trial test identifier from the real judge request's instance evidence.
	 *
	 * @param request the judge session request
	 * @return the trial's test identifier
	 */
	private static String judgeTest(SessionRequest request)
	{
		String turn = request.turns().getFirst();
		String instanceSection = turn.substring(turn.indexOf("### Evidence"),
			turn.indexOf("The trial agent's final response"));
		String instance = instanceSection.substring(instanceSection.indexOf('{'));
		return JsonMapper.builder().build().readTree(instance).get("test").asString();
	}

	/**
	 * Indicates whether a judge was asked to decide a trial of a test.
	 *
	 * @param harness the test's harness
	 * @param testId the test
	 * @return {@code true} if a judge request named a trial of the test
	 */
	private static boolean judged(DriverHarness harness, String testId)
	{
		return judgeRequests(harness).stream().anyMatch(request -> judgeTest(request).equals(testId));
	}

	/**
	 * A trial of a test whose checks decide every requirement completes from its checks without a judge
	 * session.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void mechanicalTestRunsNoJudge() throws IOException
	{
		// Requirement: R-C9
		Contract contract = checkedContract();
		requireThat(contract.findTest("delete-directory").orElseThrow().semanticRequirementIds(), "semantic").
			isEmpty();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				new CampaignBuilder().confirmed(contract, 1).working(1).readyBaseline().build());
			harness.trials(request -> compliant(harness, request));
			Campaign campaign;
			try (Driver driver = harness.open())
			{
				campaign = harness.runUntil(driver, state -> state.development().count("safety").passes() >= 10);
			}
			requireThat(campaign.development().count("safety").failures(), "safetyFailures").isEqualTo(0);
			requireThat(judged(harness, "delete-directory"), "judgedSafety").isFalse();
		}
	}

	/**
	 * Verifies that tool-use and exit-status checks adopt a passing trial without an infrastructure retry or judge.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void recordedToolsDecideTrial() throws IOException
	{
		// Requirement: R-C9
		ToolCall call = new ToolCall("Bash", "true", 0);
		ControlEvidence good = new ControlEvidence(List.of("Done."), List.of(), List.of(call), Map.of());
		ControlEvidence bad = new ControlEvidence(List.of("Done."), List.of(),
			List.of(new ToolCall("Read", null, null), new ToolCall("Bash", "true", 1)), Map.of());
		DeterministicCheck used = new DeterministicCheck("r1", new CheckCriterion.ToolUsed("Bash", true), good,
			reply("Done."));
		DeterministicCheck exited = new DeterministicCheck(Requirement.CORE_TASK.id(),
			new CheckCriterion.ExitStatus("true", 0), good, bad);
		Contract contract = withChecks(Map.of("plain-list", List.of(used, exited)));
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				new CampaignBuilder().confirmed(contract, 1).working(1).readyBaseline().build());
			harness.trials(_ ->
			{
				SessionResult.Completed trial = harness.passed();
				return new SessionResult.Completed(trial.transcript(), trial.responses(), trial.effects(), trial.model(),
					trial.reasoning(), trial.usage(), trial.windows(), trial.promptSources(), List.of(call));
			});
			Campaign campaign;
			try (Driver driver = harness.open())
			{
				campaign = harness.runUntil(driver, state -> state.pending() != null || state.trials().stream().
					anyMatch(trial -> trial.instance().testId().equals("plain-list") && trial.verdict() != null));
			}
			LogicalTrial decided = campaign.trials().stream().
				filter(trial -> trial.instance().testId().equals("plain-list")).findFirst().orElseThrow();
			requireThat(decided.verdict(), "verdict").isEqualTo(Verdict.PASS);
			requireThat(decided.infrastructureFailures(), "infrastructureFailures").isEqualTo(0);
			requireThat(judged(harness, "plain-list"), "judgedPlainList").isFalse();
		}
	}

	/**
	 * Verifies that a run whose session observed different prompt sources loading is a measurement change: its record
	 * holds no outcome and the campaign pauses, naming the changed condition.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void promptSourcesChangeDuringFormal() throws IOException
	{
		// Requirement: R-M5
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				new CampaignBuilder().confirmed().working(1).readyDevelopment().readyBaseline().
					certificationAttempt(1).category("formatting", 50, 0).category("safety", 50, 0).build());
			harness.trials(_ -> harness.completed("pass", DriverHarness.MODEL, "CLAUDE.md, .claude/rules/extra.md"));
			try (Driver driver = harness.open())
			{
				driver.step();
				harness.endSessions();
				harness.settle(driver);
				Campaign campaign = harness.campaign();
				requireThat(campaign.pending(), "pending").isInstanceOf(PendingDecision.MeasurementChanged.class);
				requireThat(((PendingDecision.MeasurementChanged) campaign.pending()).changed(), "changed").
					isEqualTo(List.of(MeasurementCondition.PROMPT_SOURCES));
				requireThat(harness.store().listUnadoptedOutcomes(campaign), "outcomes").isEmpty();
				requireThat(campaign.attempt(1).count("formatting").outcomes() +
					campaign.attempt(1).count("safety").outcomes(), "outcomes").isEqualTo(100);
			}
		}
	}

	/**
	 * Verifies that a change of the loader configuration pauses the campaign before any session starts and offers
	 * only restoring it or starting a new campaign.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void loaderChangeOffersRestoreOrNewCampaign() throws IOException
	{
		// Requirement: R-M3
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				developing().build());
			harness.observe(MeasurementCondition.LOADER_CONFIGURATION, "loader-2");
			try (Driver driver = harness.open())
			{
				SliceResult result = harness.runSlice(driver);
				Campaign campaign = harness.campaign();
				requireThat(campaign.pending(), "pending").isInstanceOf(PendingDecision.MeasurementChanged.class);
				requireThat(campaign.choices(), "choices").
					isEqualTo(List.of(Choice.RESTORE, Choice.NEW_CAMPAIGN, Choice.STOP));
				requireThat(harness.sessions().sessions(), "sessions").isEmpty();
				requireThat(result.status().status(), "status").
					isEqualTo(new ReportStatus.Active(Status.AWAITING_USER));
			}
		}
	}

	/**
	 * Verifies that an author's brief during a certification attempt holds the development failures and the
	 * failures of ended attempts, never an instance of the running attempt.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void authorBriefDuringFormal() throws IOException
	{
		// Requirement: R-D9
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			TrialInstance developmentFailure = CampaignBuilder.instance("safety", "logs");
			TrialInstance endedFailure = CampaignBuilder.instance("safety", "cache");
			TrialInstance runningFailure = CampaignBuilder.instance("safety", "build");
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				developing().development("safety", 50, 1).failure(developmentFailure).
					round(new Round(Map.of("safety", 0))).
					certificationAttempt(1).category("safety", 100, 5).failure(endedFailure).
					outcome(Outcome.BELOW_MINIMUM).campaign().
					certificationAttempt(2).category("safety", 10, 1).failure(runningFailure).build());
			try (Driver driver = harness.open())
			{
				driver.step();
				requireThat(harness.author().briefs().size(), "briefs").isEqualTo(1);
				AuthorBrief brief = harness.author().briefs().getFirst();
				requireThat(brief.developmentFailures(), "developmentFailures").
					isEqualTo(List.of(new Failure(developmentFailure, "", List.of())));
				requireThat(brief.endedAttempts().size(), "endedAttempts").isEqualTo(1);
				requireThat(brief.endedAttempts().getFirst().index(), "index").isEqualTo(1);
				requireThat(brief.endedAttempts().getFirst().failures(), "failures").
					isEqualTo(List.of(new Failure(endedFailure, "", List.of())));
			}
		}
	}

	/**
	 * Verifies that an author attempt that changes a file outside the prompt sources is never submitted, and that
	 * repeated author failures stop the slice with an infrastructure failure.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void forbiddenAuthorAttempt() throws IOException
	{
		// Requirement: R-W7
		// Requirement: R-D3
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				developing().development("safety", 50, 1).failure(CampaignBuilder.instance("safety", "logs")).
					round(new Round(Map.of("safety", 0))).build());
			harness.authors(request -> harness.edit(request, Path.of("README.md")));
			try (Driver driver = harness.open())
			{
				SliceResult result = harness.runSlice(driver);
				Campaign campaign = harness.campaign();
				requireThat(harness.author().briefs().size(), "briefs").isEqualTo(Driver.AUTHOR_FAILURE_LIMIT);
				requireThat(campaign.development().author(), "author").isNull();
				requireThat(campaign.authorAttempts(), "authorAttempts").isEqualTo(0);
				requireThat(campaign.development().working().number(), "working").isEqualTo(1);
				requireThat(result.status().outcome(), "outcome").isEqualTo("infrastructure failure");
				requireThat(result.status().outcomeDetail(), "outcomeDetail").contains("forbidden");
				requireThat(harness.workspace().authoringCopies(), "authoringCopies").isEmpty();
			}
		}
	}

	/**
	 * Returns a builder for a confirmed campaign whose working candidate 1 failed a {@code safety} instance and whose
	 * round offers a {@code safety} author attempt.
	 *
	 * @return the builder
	 */
	private static CampaignBuilder authoring()
	{
		return developing().development("safety", 50, 1).failure(CampaignBuilder.instance("safety", "logs")).
			round(new Round(Map.of("safety", 0)));
	}

	/**
	 * Returns whether trial interrupts also fail, alongside the author interrupt that always fails.
	 *
	 * @return the failure cases
	 */
	@DataProvider
	public static Object[][] interruptFailures()
	{
		return new Object[][] {{false}, {true}};
	}

	/**
	 * Verifies that a failed author interrupt still deletes its copy, attempts every remaining interrupt, preserves
	 * each interrupt failure, and queues the interrupted trials for another run.
	 *
	 * @param rejectTrials whether trial interrupts fail as well
	 * @throws IOException the simulated interrupt failure
	 */
	@Test(dataProvider = "interruptFailures", expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "The interrupt request failed")
	public void cleansCopyAfterInterruptFailure(boolean rejectTrials) throws IOException
	{
		// Requirement: R-E9
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				authoring().baseline("formatting", 49, 0).build());
			List<SessionRole> interrupts = new ArrayList<>();
			DriverContext original = harness.context();
			DriverContext failing = new DriverContext(original.store(),
				rejectInterrupts(original.sessions(), rejectTrials, interrupts), original.workspace(), original.interrupts(),
				original.clock(), original.overlays(), original.judge(), original.author(), original.deliverer(),
				original.calibrator(), original.terminator(), original.leftoverRuns(), original.authority(),
				original.promptSources(), original.environment(), original.lockFile(), original.random(), original.settings());
			try (Driver driver = Driver.open(failing, DriverHarness.CAMPAIGN).orElseThrow())
			{
				driver.step();
				requireThat(harness.workspace().authoringCopies().size(), "copies").isEqualTo(1);
			}
			catch (IOException e)
			{
				int additionalFailures = 0;
				if (rejectTrials)
					additionalFailures = interrupts.size() - 1;
				requireThat(e.getSuppressed().length, "additionalFailures").isEqualTo(additionalFailures);
				throw e;
			}
			finally
			{
				requireThat(interrupts, "interrupts").contains(SessionRole.AUTHOR).contains(SessionRole.TRIAL);
				requireThat(interrupts.size(), "interrupts").isEqualTo(harness.sessions().sessions().size());
				requireThat(harness.workspace().authoringCopies(), "authoringCopies").isEmpty();
				requireThat(trials(harness.campaign(), TrialState.RUNNING), "running").isEmpty();
				requireThat(trials(harness.campaign(), TrialState.ASSIGNED), "queued").isNotEmpty();
			}
		}
	}

	/**
	 * Wraps session handles to simulate interrupt requests that cannot be delivered.
	 *
	 * @param delegate starts the test's sessions
	 * @param rejectTrials whether trial interrupts fail as well as author interrupts
	 * @param interrupts receives the role of every session whose interrupt is attempted
	 * @return the session port that reports the simulated failures
	 */
	private static SessionPort rejectInterrupts(SessionPort delegate, boolean rejectTrials,
		List<SessionRole> interrupts)
	{
		return new SessionPort()
		{
			@Override
			public SessionHandle start(SessionRequest request) throws IOException
			{
				SessionHandle handle = delegate.start(request);
				return new SessionHandle()
				{
					@Override
					public CompletionStage<SessionResult> completion()
					{
						return handle.completion();
					}

					@Override
					public void interrupt() throws IOException
					{
						interrupts.add(request.role());
						if (request.role() == SessionRole.AUTHOR || rejectTrials)
							throw new IOException("The interrupt request failed");
						handle.interrupt();
					}
				};
			}

			@Override
			public HostLogin hostLogin() throws IOException
			{
				return delegate.hostLogin();
			}
		};
	}

	/**
	 * Trace: an author reports that requirement {@code r2} needs a content edit through a link to an external target.
	 * The warning becomes an {@code awaiting-user} decision that survives a restart; the user authorizes the target; a
	 * later author attempt receives the target and edits it; the attempt is promoted, certified and delivered with the
	 * external edit.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void authorityWarningTrace() throws IOException
	{
		// Requirement: R-W4
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("driver")).toRealPath();
			Path target = Files.createDirectories(directory.resolve("external")).resolve("shared.md");
			Files.writeString(target, "external", UTF_8);
			Path link = Path.of("shared.md");
			Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("rw-r--r--");
			FileInventory fork = new FileInventory(Path.of("/project"), Map.of(
				DriverHarness.PROMPT, new RegularFile(Sha256.of("fork".getBytes(UTF_8)), permissions),
				link, new FileEntry.SymbolicLink(target),
				target, new RegularFile(Sha256.of("external".getBytes(UTF_8)), permissions)));
			DriverHarness harness = new DriverHarness(directory, authorityWarningCampaign(), fork);
			// Safety requires the external owning file to change, so reverting that change must fail.
			Map<Path, Boolean> failsSafety = new HashMap<>();
			SessionResult.Completed passed = harness.passed();
			SessionResult.Completed failed = harness.failed();
			harness.trials(request ->
			{
				if (!DriverHarness.category(request).equals("safety"))
					return passed;
				Boolean fails = failsSafety.get(request.source());
				try
				{
					if (fails == null)
					{
						FileEntry external = harness.overlays().require(harness.snapshot(request)).snapshot().
							entry(target).orElseThrow();
						fails = ((RegularFile) external).contentHash().equals(Sha256.of("external".getBytes(UTF_8)));
						failsSafety.put(request.source(), fails);
					}
				}
				catch (IOException e)
				{
					throw new UncheckedIOException(e);
				}
				if (fails)
					return failed;
				return passed;
			});
			harness.authors(request ->
			{
				if (harness.author().briefs().getLast().authorizedTargets().contains(target))
				{
					try
					{
						Files.createDirectories(request.source());
						Files.writeString(request.source().resolve(link), "external", UTF_8);
					}
					catch (IOException e)
					{
						throw new UncheckedIOException(e);
					}
					return harness.edit(request, target);
				}
				return harness.edit(request, DriverHarness.PROMPT);
			});
			harness.author().reports(_ ->
			{
				if (harness.author().briefs().getLast().authorizedTargets().isEmpty())
					return Optional.of(List.of(new NeededEdit("r2", link, Operation.EDIT_CONTENT, target)));
				return Optional.of(List.of());
			});
			PendingDecision.AuthorityWarnings warnings = new PendingDecision.AuthorityWarnings(List.of(
				new AuthorityWarning("r2", link, target, Limit.EXTERNAL_TARGET_READ_ONLY, List.of(
					AuthorityWarning.Choice.AUTHORIZE_TARGET, AuthorityWarning.Choice.USE_AUTHORIZED_SOURCE,
					AuthorityWarning.Choice.STOP_CAMPAIGN, AuthorityWarning.Choice.REVISE_CAMPAIGN))));
			try (Driver driver = harness.open())
			{
				SliceResult result = harness.runSlice(driver);
				requireThat(result.status().status(), "status").
					isEqualTo(new ReportStatus.Active(Status.AWAITING_USER));
				requireThat(harness.campaign().pending(), "pending").isEqualTo(warnings);
				requireThat(result.status().authorityWarnings(), "reported").isEqualTo(warnings.warnings());
			}
			// A restart between the warning and the choice keeps the decision and starts nothing
			int sessions = harness.sessions().sessions().size();
			try (Driver driver = harness.open())
			{
				requireThat(harness.campaign().pending(), "pendingAfterRestart").isEqualTo(warnings);
				SliceResult result = harness.runSlice(driver);
				requireThat(result.status().status(), "statusAfterRestart").
					isEqualTo(new ReportStatus.Active(Status.AWAITING_USER));
				requireThat(harness.sessions().sessions().size(), "sessions").isEqualTo(sessions);
			}
			try (Driver driver = harness.open())
			{
				driver.apply(CampaignEvent.Choose.authorize(target));
				requireThat(harness.campaign().authorizedTargets(), "authorizedTargets").isEqualTo(Set.of(target));
				requireThat(harness.campaign().pending(), "answered").isNull();
				harness.runSlice(driver);
			}
			Campaign campaign = harness.campaign();
			requireThat(campaign.outcome(), "outcome").isEqualTo(Outcome.CERTIFIED);
			requireThat(harness.author().briefs().getLast().authorizedTargets(), "briefTargets").
				isEqualTo(Set.of(target));
			RecordingDeliverer.Call delivery = harness.deliverer().calls().getLast();
			requireThat(delivery.selected().snapshot().entry(target), "deliveredTarget").isEqualTo(Optional.of(
				new RegularFile(Sha256.of("author-2".getBytes(UTF_8)), permissions)));
			requireThat(delivery.promptSources(), "promptSources").contains(target);
		}
	}

	/**
	 * Returns the campaign whose safety requirement needs an edit outside the author's authority.
	 *
	 * @return the confirmed campaign with a safety author attempt
	 */
	private static Campaign authorityWarningCampaign()
	{
		Contract fixture = ContractFixtures.contract();
		List<Requirement> requirements = fixture.requirements().stream().
			filter(requirement -> requirement.id().equals("r2") || requirement.equals(Requirement.CORE_TASK)).toList();
		// Only safety exercises the authority warning; separate certification and cell tests cover formatting
		List<Category> categories = List.of(fixture.findCategory("safety").orElseThrow());
		Contract contract = new Contract(requirements, categories, ContractFixtures.profiles(requirements, categories),
			fixture.judge(), fixture.fixtures(), fixture.limitations());
		return new CampaignBuilder().confirmed(contract, 1).working(1).readyBaseline().development("safety", 50, 1).
			failure(CampaignBuilder.instance("safety", "logs")).round(new Round(Map.of("safety", 0))).build();
	}

	/**
	 * Verifies that a malformed report of needed edits counts as an author infrastructure failure: the attempt is not
	 * submitted, and repeated failures stop the slice.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void malformedAuthorReport() throws IOException
	{
		// Requirement: R-W4
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				authoring().build());
			harness.author().reports(_ -> Optional.empty());
			try (Driver driver = harness.open())
			{
				SliceResult result = harness.runSlice(driver);
				Campaign campaign = harness.campaign();
				requireThat(harness.author().briefs().size(), "briefs").isEqualTo(Driver.AUTHOR_FAILURE_LIMIT);
				requireThat(campaign.authorAttempts(), "authorAttempts").isEqualTo(0);
				requireThat(campaign.pending(), "pending").isNull();
				requireThat(result.status().outcome(), "outcome").isEqualTo("infrastructure failure");
				requireThat(result.status().outcomeDetail(), "outcomeDetail").contains("malformed");
			}
		}
	}

	/**
	 * Verifies that a reported edit that the authority in fact permits raises no warning: the attempt is submitted and
	 * nothing waits for the user.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void permittedNeededEditChangesNothing() throws IOException
	{
		// Requirement: R-W4
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				authoring().build());
			harness.author().reports(_ -> Optional.of(List.of(
				new NeededEdit("r2", DriverHarness.PROMPT, Operation.EDIT_CONTENT, null))));
			try (Driver driver = harness.open())
			{
				Campaign campaign = harness.runUntil(driver, state -> state.authorAttempts() == 1);
				requireThat(campaign.pendingDecisions(), "pendingDecisions").isEmpty();
				requireThat(campaign.authorizedTargets(), "authorizedTargets").isEmpty();
				requireThat(campaign.development().author(), "author").isNotNull();
			}
		}
	}

	/**
	 * Verifies that sessions that fail without an outcome rerun the same logical trial, with its assignment record
	 * rewritten before each run, until the third failure pauses the campaign. A completed session, including one
	 * that the time limit cut off, is judged as behaviour and never rerun.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void infrastructureFailuresPause() throws IOException
	{
		// Requirement: R-E2
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				developing().build());
			harness.trials(_ -> new SessionResult.Failed("The harness crashed", List.of()));
			try (Driver driver = harness.open())
			{
				SliceResult result = harness.runSlice(driver);
				Campaign campaign = harness.campaign();
				requireThat(campaign.pending(), "pending").isInstanceOf(PendingDecision.TrialFailures.class);
				requireThat(campaign.choices(), "choices").contains(Choice.RETRY);
				requireThat(result.status().status(), "status").
					isEqualTo(new ReportStatus.Active(Status.AWAITING_USER));
				List<FakeSession> runs = harness.sessions().sessions();
				requireThat(runs.size(), "runs").isEqualTo(Campaign.INFRASTRUCTURE_FAILURE_LIMIT);
				for (FakeSession run : runs)
					requireThat(run.request(), "request").isEqualTo(runs.getFirst().request());
				List<LogicalTrial> records = harness.crashingStore().assignments();
				requireThat(records.size(), "records").isEqualTo(Campaign.INFRASTRUCTURE_FAILURE_LIMIT);
				for (LogicalTrial trial : records)
					requireThat(trial.key(), "key").isEqualTo(records.getFirst().key());
				LogicalTrial failed = campaign.trials().getFirst();
				requireThat(failed.infrastructureFailures(), "infrastructureFailures").
					isEqualTo(Campaign.INFRASTRUCTURE_FAILURE_LIMIT);
			}
			// The next run outlives its time limit and completes; the judge fails its behaviour
			harness.trials(_ -> harness.failed());
			try (Driver driver = harness.open())
			{
				driver.apply(CampaignEvent.Choose.of(Choice.RETRY));
				driver.step();
				harness.clock().advance(harness.runningTrials().getFirst().request().timeLimit().plusSeconds(1));
				harness.endSessions();
				harness.settle(driver);
				Campaign campaign = harness.campaign();
				requireThat(Reconciler.find(campaign, harness.crashingStore().assignments().getFirst().key()).
					isEmpty(), "isAdopted").isTrue();
				requireThat(campaign.development().count("formatting").failures() +
					campaign.development().count("safety").failures(), "failures").isEqualTo(1);
				TrialKey key = harness.crashingStore().assignments().getFirst().key();
				requireThat(harness.crashingStore().assignments().stream().filter(trial -> trial.key().equals(key)).
					count(), "runs").isEqualTo((long) Campaign.INFRASTRUCTURE_FAILURE_LIMIT + 1);
			}
		}
	}

	/**
	 * Supplies both schema-valid judgment decisions.
	 *
	 * @return the verdicts to adopt
	 */
	@DataProvider
	public static Object[][] judgeVerdicts()
	{
		return new Object[][] {{Verdict.PASS}, {Verdict.FAIL}};
	}

	/**
	 * Verifies that judgment JSON determines development counts independently of the trial's reply, and a failing
	 * judgment preserves its exact rationale for diagnosis.
	 *
	 * @param verdict the decision returned by the judge session
	 * @throws IOException if the test directory cannot be used
	 */
	@Test(dataProvider = "judgeVerdicts")
	public void adoptsJudgmentJson(Verdict verdict) throws IOException
	{
		// Requirement: R-E13
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				developing().slowStart(new SlowStart(1, 0)).build());
			harness.trials(_ -> harness.completed("An unrelated trial reply.", DriverHarness.MODEL));
			String rationale = "Evidence: \"quoted reply\".\nKeep this line.";
			harness.judges(_ -> harness.judged(new Judgment(verdict, rationale)));
			try (Driver driver = harness.open())
			{
				driver.step();
				harness.endSessions();
				harness.settle(driver);
				Campaign campaign = harness.campaign();
				requireThat(developmentOutcomes(campaign), "outcomes").isEqualTo(101);
				int failures = campaign.development().counts().values().stream().mapToInt(CategoryCounts::failures).
					sum();
				switch (verdict)
				{
					case PASS ->
					{
						requireThat(failures, "failures").isEqualTo(0);
						requireThat(campaign.development().failures(), "diagnosisFailures").isEmpty();
					}
					case FAIL ->
					{
						requireThat(failures, "failures").isEqualTo(1);
						requireThat(campaign.development().failures().getFirst().rationale(), "rationale").
							isEqualTo(rationale);
					}
				}
			}
		}
	}

	/**
	 * Verifies that a bare verdict reruns the judge with the same evidence, and that a judge that fails
	 * {@value Driver#JUDGE_ATTEMPTS} times returns the trial to the queue as an infrastructure failure.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void invalidVerdictRerunsJudge() throws IOException
	{
		// Requirement: R-E2
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				developing().build());
			AtomicInteger invalid = new AtomicInteger(Driver.JUDGE_ATTEMPTS);
			harness.judges(request ->
			{
				if (invalid.getAndDecrement() > 0)
					return harness.judged("pass");
				return harness.scriptedJudgment(request);
			});
			try (Driver driver = harness.open())
			{
				driver.step();
				harness.endSessions();
				harness.settle(driver);
				List<SessionRequest> judges = judgeRequests(harness);
				requireThat(judges.size(), "judges").isEqualTo(Driver.JUDGE_ATTEMPTS);
				for (SessionRequest judge : judges)
					requireThat(judge, "judge").isEqualTo(judges.getFirst());
				LogicalTrial trial = harness.campaign().trials().getFirst();
				requireThat(trial.infrastructureFailures(), "infrastructureFailures").isEqualTo(1);
				requireThat(developmentOutcomes(harness.campaign()), "outcomes").isEqualTo(100);

				invalid.set(1);
				harness.endSessions();
				harness.settle(driver);
				judges = judgeRequests(harness);
				requireThat(judges.size(), "judges").isEqualTo(Driver.JUDGE_ATTEMPTS + 2);
				requireThat(judges.getLast(), "rerun").isEqualTo(judges.get(Driver.JUDGE_ATTEMPTS));
				requireThat(Reconciler.find(harness.campaign(), trial.key()).isEmpty(), "isAdopted").isTrue();
				requireThat(developmentOutcomes(harness.campaign()), "outcomes").isEqualTo(101);
			}
		}
	}

	/**
	 * Returns the requests of the judge sessions that started.
	 *
	 * @param harness the harness
	 * @return the requests, in start order
	 */
	private static List<SessionRequest> judgeRequests(DriverHarness harness)
	{
		return harness.sessions().sessions().stream().map(FakeSession::request).
			filter(request -> request.role() == SessionRole.JUDGE).toList();
	}

	/**
	 * Returns the delivery results that stop for the user's direction.
	 *
	 * @return the results
	 */
	@DataProvider
	public static Object[][] stoppedDeliveries()
	{
		RegularFile file = new RegularFile(Sha256.of("base".getBytes(UTF_8)),
			PosixFilePermissions.fromString("rw-r--r--"));
		Path target = Path.of("/project/CLAUDE.md");
		return new Object[][]
		{
			{new DeliveryResult.UnownedChange(List.of(new PathChange.Added(Path.of("notes.md"), file)))},
			{new DeliveryResult.Conflicted(List.of(new DeliveryConflict(DeliveryConflict.Kind.BOTH_CHANGED, target,
				DriverHarness.PROMPT)))},
			{new DeliveryResult.ConcurrentEdit(new DeliveryChange.Delete(1, target, file), 0)},
			{new DeliveryResult.VerificationFailed(List.of(target))}
		};
	}

	/**
	 * Verifies that a delivery that stops reports the campaign as waiting for the user, never as delivered.
	 *
	 * @param stopped the delivery's result
	 * @throws IOException if the test directory cannot be used
	 */
	@Test(dataProvider = "stoppedDeliveries")
	public void deliveryStopsForUserDirection(DeliveryResult stopped) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Candidate working = new Candidate(1, CampaignBuilder.snapshot("candidate-1"));
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				new CampaignBuilder().confirmed().working(1).readyDevelopment().readyBaseline().
					certificationAttempt(1).category("formatting", 100, 0).category("safety", 100, 0).
					outcome(Outcome.CERTIFIED).campaign().lifecycle(Lifecycle.FINISHED).outcome(Outcome.CERTIFIED).
					delivery(new DeliveryRequest(working, 1, Outcome.CERTIFIED)).build());
			harness.deliverer().respond(stopped);
			try (Driver driver = harness.open())
			{
				SliceResult result = harness.runSlice(driver);
				requireThat(result.delivery().result(), "delivery").isEqualTo(stopped);
				requireThat(result.status().status(), "status").
					isEqualTo(new ReportStatus.Active(Status.AWAITING_USER));
				requireThat(result.status().pending(), "pending").isEqualTo(SliceReports.describe(stopped));
			}
		}
	}
}
