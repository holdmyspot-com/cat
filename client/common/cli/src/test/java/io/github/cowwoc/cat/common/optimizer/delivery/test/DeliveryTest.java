package io.github.cowwoc.cat.common.optimizer.delivery.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.PortableFiles;
import io.github.cowwoc.cat.common.PortablePaths;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.git.Git;
import io.github.cowwoc.cat.common.optimizer.campaign.Applicability;
import io.github.cowwoc.cat.common.optimizer.campaign.Candidate;
import io.github.cowwoc.cat.common.optimizer.campaign.CertificationParameters;
import io.github.cowwoc.cat.common.optimizer.campaign.CertificationRecord.CellResult;
import io.github.cowwoc.cat.common.optimizer.campaign.CertificationRecord;
import io.github.cowwoc.cat.common.optimizer.campaign.DeliveryRequest;
import io.github.cowwoc.cat.common.optimizer.campaign.MeasurementCondition;
import io.github.cowwoc.cat.common.optimizer.campaign.MeasurementIdentity;
import io.github.cowwoc.cat.common.optimizer.campaign.Outcome;
import io.github.cowwoc.cat.common.optimizer.contract.Assertion;
import io.github.cowwoc.cat.common.optimizer.contract.AssertionKind;
import io.github.cowwoc.cat.common.optimizer.contract.CertificationScope;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.FixtureFile;
import io.github.cowwoc.cat.common.optimizer.contract.PromptTestFiles;
import io.github.cowwoc.cat.common.optimizer.contract.PromptTestLocations.Location;
import io.github.cowwoc.cat.common.optimizer.contract.Scenario;
import io.github.cowwoc.cat.common.optimizer.contract.ScenarioFormatException;
import io.github.cowwoc.cat.common.optimizer.contract.ValuesFile;
import io.github.cowwoc.cat.common.optimizer.contract.VariableDeclaration;
import io.github.cowwoc.cat.common.optimizer.contract.test.ContractFixtures;
import io.github.cowwoc.cat.common.optimizer.delivery.DeliveredTest;
import io.github.cowwoc.cat.common.optimizer.delivery.Delivery;
import io.github.cowwoc.cat.common.optimizer.delivery.DeliveryResult.ConcurrentEdit;
import io.github.cowwoc.cat.common.optimizer.delivery.DeliveryResult.Conflicted;
import io.github.cowwoc.cat.common.optimizer.delivery.DeliveryResult.Delivered;
import io.github.cowwoc.cat.common.optimizer.delivery.DeliveryResult.UnownedChange;
import io.github.cowwoc.cat.common.optimizer.delivery.DeliveryResult;
import io.github.cowwoc.cat.common.optimizer.inventory.DeliveryChange.Create;
import io.github.cowwoc.cat.common.optimizer.inventory.DeliveryChange.Edit;
import io.github.cowwoc.cat.common.optimizer.inventory.DeliveryChange;
import io.github.cowwoc.cat.common.optimizer.inventory.DeliveryConflict;
import io.github.cowwoc.cat.common.optimizer.inventory.DeliveryPlan;
import io.github.cowwoc.cat.common.optimizer.inventory.FileContent;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.RegularFile;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.InventoryScanner;
import io.github.cowwoc.cat.common.optimizer.inventory.Overlay;
import io.github.cowwoc.cat.common.optimizer.inventory.PathChange;
import io.github.cowwoc.cat.common.optimizer.port.test.FakeWorkspacePort;
import io.github.cowwoc.cat.common.optimizer.store.CampaignStore;
import io.github.cowwoc.cat.common.optimizer.store.CertificationRecordJson;
import io.github.cowwoc.cat.common.optimizer.store.DeliveryJournal;
import io.github.cowwoc.cat.common.optimizer.store.FileCampaignStore;
import io.github.cowwoc.cat.common.optimizer.store.test.InMemoryCampaignStore;
import io.github.cowwoc.cat.common.test.FileTrees;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests {@link Delivery} against a test-owned base worktree, which is a real Git repository, and a test-owned
 * external directory that holds a read-only external participant. The optimization worktree is the in-memory
 * {@link FakeWorkspacePort}. Git runs only to set up the base, to report the final diff and for the no-commit check.
 * <p>
 * Every test starts from the same fork: the base holds the prompt sources {@code rules/main.md},
 * {@code rules/style.md} and {@code rules/old.md} and the non-prompt file {@code README.md}, and the external directory
 * holds the read-only prompt source {@code shared.md}. The candidate edits {@code rules/main.md}, creates
 * {@code rules/new.md} and deletes {@code rules/old.md}. A certified delivery also writes the certification record of
 * the target {@code rules/main.md} to {@code .cat/certifications/rules/main.json}.
 */
public final class DeliveryTest
{
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String CAMPAIGN = "campaign-1";
	private static final Set<PosixFilePermission> READ_WRITE = PosixFilePermissions.fromString("rw-r--r--");
	private static final Consumer<DeliveryChange> NO_HOOK = _ ->
	{
	};
	private static final CertificationRecord RECORD = record(1);
	private static final Path RECORD_FILE = Path.of(".cat/certifications/rules/main.json");
	/**
	 * The directory that holds the regression scenarios of the target {@code rules/main.md}.
	 */
	private static final Path TESTS = Path.of(".cat/tests/rules/main");
	private static final String DESIGN_GOALS = "The agent summarizes the release notes.";
	/**
	 * The regression scenario that the edited prompt test {@code release_notes} was derived from.
	 */
	private static final Path EDITED_SCENARIO = TESTS.resolve("release_notes").resolve(PromptTestFiles.SCENARIO);
	/**
	 * The content of {@link #EDITED_SCENARIO} at the fork.
	 */
	private static final String RECORDED_SCENARIO = """
		# Release notes

		Written for the 2.0 release.

		## Design Goals

		The agent summarizes the release notes.

		## Variables

		- **file**: The notes file

		## Scenario

		Read `{{file}}`.

		## Assertions

		### read

		- **kind**: effect
		- **description**: The reply reads the file.
		""";

	/**
	 * Creates the test.
	 */
	public DeliveryTest()
	{
	}

	/**
	 * A certified candidate is delivered, labelled {@code certified}, while an edit that the user made after the fork to
	 * a file the candidate does not change survives. The result is
	 * current because the surviving edit is not a prompt source.
	 *
	 * @throws IOException if the fixture or delivery fails
	 */
	@Test
	public void disjointBaseEditSurvives() throws IOException
	{
		// Requirement: R-W8, R-F3
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup);
			Overlay candidate = fixture.candidate();
			Files.writeString(fixture.base().resolve("README.md"), "readme edited by the user\n", UTF_8);

			DeliveryResult result = fixture.delivery(new InMemoryCampaignStore(), candidate, NO_HOOK).
				deliver(CAMPAIGN, request(candidate, Outcome.CERTIFIED), candidate, candidate, fixture.promptSources(),
					RECORD, List.of());

			Delivered delivered = requireDelivered(result);
			requireThat(delivered.label(), "label").isEqualTo(Outcome.CERTIFIED);
			requireThat(delivered.applicability().status(), "status").isEqualTo(Applicability.Status.CURRENT);
			requireThat(delivered.baseOnlyChanges(), "baseOnlyChanges").isEqualTo(List.of(Path.of("README.md")));
			requireThat(delivered.changedPromptSources(), "changedPromptSources").isEmpty();
			requireThat(read(fixture.base().resolve("README.md")), "README.md").
				isEqualTo("readme edited by the user\n");
			requireCandidateDelivered(fixture);
			requireThat(delivered.diff(), "diff").contains("+main v2").contains("new file mode").
				contains("+new rule").contains("deleted file mode").doesNotContain("README.md");
		}
	}

	/**
	 * When the user edited a file after the fork that the candidate also changes, delivery stops before writing any
	 * file or journal and reports the conflict.
	 *
	 * @throws IOException if the fixture or delivery fails
	 */
	@Test
	public void sameFileConflictStopsBeforeAnyWrite() throws IOException
	{
		// Requirement: R-W8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup);
			Overlay candidate = fixture.candidate();
			Files.writeString(fixture.base().resolve("rules/main.md"), "main edited by the user\n", UTF_8);
			CampaignStore store = new InMemoryCampaignStore();
			List<DeliveryChange> writes = new ArrayList<>();

			DeliveryResult result = fixture.delivery(store, candidate, writes::add).
				deliver(CAMPAIGN, request(candidate, Outcome.CERTIFIED), candidate, candidate, fixture.promptSources(),
					RECORD, List.of());

			requireThat(result, "result").isInstanceOf(Conflicted.class);
			requireThat(((Conflicted) result).conflicts(), "conflicts").isEqualTo(List.of(
				new DeliveryConflict(DeliveryConflict.Kind.BOTH_CHANGED, Path.of("rules/main.md"),
					Path.of("rules/main.md"))));
			requireThat(writes, "writes").isEmpty();
			requireThat(store.loadJournal(CAMPAIGN), "journal").isEmpty();
			requireThat(read(fixture.base().resolve("rules/main.md")), "main.md").isEqualTo("main edited by the user\n");
			requireThat(read(fixture.shared()), "shared.md").isEqualTo("shared v1\n");
			requireThat(Files.notExists(fixture.base().resolve("rules/new.md")), "newIsAbsent").isTrue();
			requireThat(fixture.base().resolve("rules/old.md"), "old.md").isRegularFile();
		}
	}

	/**
	 * A crash after any change's file is written, but before the journal records it, is followed by a restart that
	 * resumes the same delivery: every change, including the certification record's, is written exactly once across
	 * both runs, and the delivery completes. Each crash point runs its two deliveries in its own copy of one fixture.
	 *
	 * @throws IOException if the fixture or delivery fails
	 */
	@Test
	public void faultBetweenWritesResumesOnce() throws IOException
	{
		// Requirement: R-W8
		int changeCount = 4;
		try (TemporaryDirectories templateCleanup = new TemporaryDirectories())
		{
			Fixture template = Fixture.create(templateCleanup);
			for (int crashAfter = 1; crashAfter <= changeCount; ++crashAfter)
			{
				try (TemporaryDirectories cleanup = new TemporaryDirectories())
				{
					Fixture fixture = template.copy(cleanup);
					Overlay candidate = fixture.candidate();
					DeliveryRequest request = request(candidate, Outcome.CERTIFIED);
					List<Integer> written = new ArrayList<>();
					int limit = crashAfter;
					Consumer<DeliveryChange> crashing = change ->
					{
						written.add(change.sequence());
						if (written.size() == limit)
							throw new SimulatedCrash(change);
					};
					boolean crashed = false;
					try
					{
						fixture.delivery(new FileCampaignStore(fixture.base(), JSON), candidate, crashing).
							deliver(CAMPAIGN, request, candidate, candidate, fixture.promptSources(), RECORD, List.of());
					}
					catch (SimulatedCrash _)
					{
						crashed = true;
					}
					requireThat(crashed, "crashed").withContext(crashAfter, "crashAfter").isTrue();

					// The restarted process opens a new store and executor over the same base worktree
					DeliveryResult result = fixture.delivery(new FileCampaignStore(fixture.base(), JSON), candidate,
						change -> written.add(change.sequence())).
						deliver(CAMPAIGN, request, candidate, candidate, fixture.promptSources(), RECORD, List.of());

					requireDelivered(result);
					requireThat(written, "written").withContext(crashAfter, "crashAfter").
						isEqualTo(List.of(0, 1, 2, 3));
					requireCandidateDelivered(fixture);
					requireThat(new CertificationRecordJson(JSON).read(fixture.resolve(RECORD_FILE)), "record").
						isEqualTo(RECORD);
				}
			}
		}
	}

	/**
	 * When the user edits a target after the delivery started but before CAT writes it, delivery stops before that
	 * write and keeps the user's edit; running the delivery again still does not overwrite it.
	 *
	 * @throws IOException if the fixture or delivery fails
	 */
	@Test
	public void concurrentEditDetectedBeforeWrite() throws IOException
	{
		// Requirement: R-W8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup);
			Overlay candidate = fixture.candidate();
			List<DeliveryChange> planned = DeliveryPlan.of(candidate, fixture.scan()).changes();
			Path editedByUser = fixture.resolve(planned.get(1).target());
			// The user edits the second change's target while CAT is between its first and second writes
			Consumer<DeliveryChange> userEdit = change ->
			{
				if (change.sequence() == 0)
					write(editedByUser, "edited by the user during delivery\n");
			};
			CampaignStore store = new InMemoryCampaignStore();
			DeliveryRequest request = request(candidate, Outcome.CERTIFIED);

			DeliveryResult result = fixture.delivery(store, candidate, userEdit).
				deliver(CAMPAIGN, request, candidate, candidate, fixture.promptSources(), RECORD, List.of());

			requireThat(result, "result").isEqualTo(new ConcurrentEdit(planned.get(1), 1));
			requireThat(read(editedByUser), "editedByUser").isEqualTo("edited by the user during delivery\n");
			requireThat(store.loadJournal(CAMPAIGN).map(DeliveryJournal::applied), "applied").
				isEqualTo(Optional.of(1));

			DeliveryResult resumed = fixture.delivery(store, candidate, NO_HOOK).
				deliver(CAMPAIGN, request, candidate, candidate, fixture.promptSources(), RECORD, List.of());
			requireThat(resumed, "resumed").isEqualTo(new ConcurrentEdit(planned.get(1), 1));
			requireThat(read(editedByUser), "editedByUser").isEqualTo("edited by the user during delivery\n");
		}
	}

	/**
	 * When the user edited, after the fork, a prompt source that the candidate does not change, delivery merges the
	 * candidate and keeps the edit, but reports the result as historical and names the changed prompt source.
	 *
	 * @throws IOException if the fixture or delivery fails
	 */
	@Test
	public void baseOnlyPromptEditIsHistorical() throws IOException
	{
		// Requirement: R-W8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup);
			Overlay candidate = fixture.candidate();
			Files.writeString(fixture.base().resolve("rules/style.md"), "style edited by the user\n", UTF_8);

			DeliveryResult result = fixture.delivery(new InMemoryCampaignStore(), candidate, NO_HOOK).
				deliver(CAMPAIGN, request(candidate, Outcome.CERTIFIED), candidate, candidate, fixture.promptSources(),
					RECORD, List.of());

			Delivered delivered = requireDelivered(result);
			requireThat(delivered.applicability(), "applicability").
				isEqualTo(new Applicability(Applicability.Status.HISTORICAL, true, List.of()));
			requireThat(delivered.changedPromptSources(), "changedPromptSources").
				isEqualTo(List.of(Path.of("rules/style.md")));
			requireThat(delivered.label(), "label").isEqualTo(Outcome.CERTIFIED);
			requireThat(read(fixture.base().resolve("rules/style.md")), "style.md").
				isEqualTo("style edited by the user\n");
			requireCandidateDelivered(fixture);
		}
	}

	/**
	 * When the user changed a read-only external participant after the fork, delivery leaves it unchanged, delivers the
	 * candidate, and reports the result as historical, naming the external participant as a changed prompt source.
	 *
	 * @throws IOException if the fixture or delivery fails
	 */
	@Test
	public void externalParticipantChangeIsHistorical() throws IOException
	{
		// Requirement: R-W8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup);
			Overlay candidate = fixture.candidate();
			Files.writeString(fixture.shared(), "shared edited by the user\n", UTF_8);

			DeliveryResult result = fixture.delivery(new InMemoryCampaignStore(), candidate, NO_HOOK).
				deliver(CAMPAIGN, request(candidate, Outcome.NOT_CERTIFIED), candidate, candidate,
					fixture.promptSources(), null, List.of());

			Delivered delivered = requireDelivered(result);
			requireThat(delivered.applicability(), "applicability").
				isEqualTo(new Applicability(Applicability.Status.HISTORICAL, true, List.of()));
			requireThat(delivered.changedPromptSources(), "changedPromptSources").
				isEqualTo(List.of(fixture.shared()));
			requireThat(delivered.baseOnlyChanges(), "baseOnlyChanges").isEqualTo(List.of(fixture.shared()));
			requireThat(read(fixture.shared()), "shared.md").isEqualTo("shared edited by the user\n");
			requireThat(read(fixture.base().resolve("rules/main.md")), "main.md").isEqualTo("main v2\n");
		}
	}

	/**
	 * A candidate that changes an external file is refused before delivery writes any file or journal, because
	 * external files are read-only.
	 *
	 * @throws IOException if the fixture or delivery fails
	 */
	@Test
	public void externalChangeRefusedBeforeAnyWrite() throws IOException
	{
		// Requirement: R-W4, R-W8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup);
			Overlay candidate = fixture.candidate().write(fixture.shared(), content("shared v2\n"), READ_WRITE);
			CampaignStore store = new InMemoryCampaignStore();
			List<DeliveryChange> writes = new ArrayList<>();

			try
			{
				fixture.delivery(store, candidate, writes::add).
					deliver(CAMPAIGN, request(candidate, Outcome.NOT_CERTIFIED), candidate, candidate,
						fixture.promptSources(), null, List.of());
				throw new AssertionError("A candidate that changes an external file was delivered");
			}
			catch (IllegalArgumentException e)
			{
				requireThat(e.getMessage(), "message").contains(fixture.shared().toString()).contains("read-only");
			}
			requireThat(writes, "writes").isEmpty();
			requireThat(store.loadJournal(CAMPAIGN), "journal").isEmpty();
			requireThat(read(fixture.shared()), "shared.md").isEqualTo("shared v1\n");
			requireThat(read(fixture.base().resolve("rules/main.md")), "main.md").isEqualTo("main v1\n");
			requireThat(fixture.base().resolve("rules/old.md"), "old.md").isRegularFile();
		}
	}

	/**
	 * Keeping an improvement whose candidate is older than the working candidate first materializes the kept candidate
	 * in the optimization worktree, then delivers that candidate, labelled {@code improved, not certified}.
	 *
	 * @throws IOException if the fixture or delivery fails
	 */
	@Test
	public void keptOlderImprovementIsMaterialized() throws IOException
	{
		// Requirement: R-F6, R-W8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup);
			Overlay kept = fixture.candidate();
			Overlay working = kept.write(Path.of("rules/main.md"), content("main v3\n"), READ_WRITE);
			FakeWorkspacePort workspace = new FakeWorkspacePort(fixture.root().resolve("workspace"),
				working.snapshot());
			Delivery delivery = new Delivery(new InMemoryCampaignStore(), workspace, JSON, NO_HOOK);

			DeliveryResult result = delivery.deliver(CAMPAIGN,
				new DeliveryRequest(new Candidate(1, kept.identity()), 1, Outcome.IMPROVED_NOT_CERTIFIED),
				kept, working, fixture.promptSources(), null, List.of());

			Delivered delivered = requireDelivered(result);
			requireThat(workspace.materialized(), "materialized").isEqualTo(List.of(kept));
			requireThat(delivered.label(), "label").isEqualTo(Outcome.IMPROVED_NOT_CERTIFIED);
			requireThat(delivered.label().label(), "labelText").isEqualTo("improved, not certified");
			requireCandidateDelivered(fixture);
		}
	}

	/**
	 * Delivery leaves the delivered changes for the user to review: it creates no commit, moves no branch and stages
	 * nothing in the base worktree.
	 *
	 * @throws IOException if the fixture or delivery fails
	 */
	@Test
	public void noCommitCreatedInBase() throws IOException
	{
		// Requirement: R-W8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup);
			Overlay candidate = fixture.candidate();
			Git git = new Git(fixture.base());
			List<String> commitsBefore = git.lines("rev-list", "--all");
			String headBefore = git.run("rev-parse", "HEAD");

			requireDelivered(fixture.delivery(new InMemoryCampaignStore(), candidate, NO_HOOK).
				deliver(CAMPAIGN, request(candidate, Outcome.CERTIFIED), candidate, candidate, fixture.promptSources(),
					RECORD, List.of()));

			requireThat(git.lines("rev-list", "--all"), "commits").isEqualTo(commitsBefore);
			requireThat(git.run("rev-parse", "HEAD"), "head").isEqualTo(headBefore);
			requireThat(git.lines("diff", "--cached", "--name-only"), "staged").isEmpty();
			requireThat(git.lines("status", "--porcelain", "--untracked-files=all"), "status").
				containsAll(List.of(" M rules/main.md", " D rules/old.md", "?? rules/new.md"));
		}
	}

	/**
	 * When the optimization worktree holds a change that CAT did not record, delivery stops before changing the base
	 * worktree and reports the change.
	 *
	 * @throws IOException if the fixture or delivery fails
	 */
	@Test
	public void unownedWorktreeChangeStops() throws IOException
	{
		// Requirement: R-W8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup);
			Overlay candidate = fixture.candidate();
			Map<Path, io.github.cowwoc.cat.common.optimizer.inventory.FileEntry> files =
				new HashMap<>(candidate.snapshot().entries());
			RegularFile stray = new RegularFile(content("stray\n").hash(), READ_WRITE);
			files.put(Path.of("rules/stray.md"), stray);
			FakeWorkspacePort workspace = new FakeWorkspacePort(fixture.root().resolve("workspace"),
				new FileInventory(fixture.base(), files));
			CampaignStore store = new InMemoryCampaignStore();

			DeliveryResult result = new Delivery(store, workspace, JSON, NO_HOOK).deliver(CAMPAIGN,
				request(candidate, Outcome.CERTIFIED), candidate, candidate, fixture.promptSources(), RECORD,
				List.of());

			requireThat(result, "result").isEqualTo(new UnownedChange(List.of(
				new PathChange.Added(Path.of("rules/stray.md"), stray))));
			requireThat(store.loadJournal(CAMPAIGN), "journal").isEmpty();
			requireThat(read(fixture.base().resolve("rules/main.md")), "main.md").isEqualTo("main v1\n");
			requireThat(workspace.materialized(), "materialized").isEmpty();
		}
	}

	/**
	 * A candidate that the user explicitly accepted without certification is delivered labelled
	 * {@code not certified}.
	 *
	 * @throws IOException if the fixture or delivery fails
	 */
	@Test
	public void uncertifiedAcceptanceIsLabelled() throws IOException
	{
		// Requirement: R-F3
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup);
			Overlay candidate = fixture.candidate();

			DeliveryResult result = fixture.delivery(new InMemoryCampaignStore(), candidate, NO_HOOK).
				deliver(CAMPAIGN,
					new DeliveryRequest(new Candidate(2, candidate.identity()), 0, Outcome.NOT_CERTIFIED),
					candidate, candidate, fixture.promptSources(), null, List.of());

			requireThat(requireDelivered(result).label().label(), "label").isEqualTo("not certified");
		}
	}

	/**
	 * A request labelled with an outcome that never delivers a candidate, such as {@code not certified: below
	 * minimum}, is rejected before anything changes.
	 *
	 * @throws IOException if the fixture or delivery fails
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = "(?s).*not certified: below minimum.*")
	public void rejectsUndeliverableLabel() throws IOException
	{
		// Requirement: R-F3
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup);
			Overlay candidate = fixture.candidate();

			fixture.delivery(new InMemoryCampaignStore(), candidate, NO_HOOK).
				deliver(CAMPAIGN, request(candidate, Outcome.BELOW_MINIMUM), candidate, candidate,
					fixture.promptSources(), null, List.of());
		}
	}

	/**
	 * Running a completed delivery again writes nothing and reports the same delivered result.
	 *
	 * @throws IOException if the fixture or delivery fails
	 */
	@Test
	public void completedDeliveryIsNotRepeated() throws IOException
	{
		// Requirement: R-W8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup);
			Overlay candidate = fixture.candidate();
			CampaignStore store = new InMemoryCampaignStore();
			DeliveryRequest request = request(candidate, Outcome.CERTIFIED);
			DeliveryResult first = fixture.delivery(store, candidate, NO_HOOK).
				deliver(CAMPAIGN, request, candidate, candidate, fixture.promptSources(), RECORD, List.of());
			List<DeliveryChange> writes = new ArrayList<>();

			DeliveryResult second = fixture.delivery(store, candidate, writes::add).
				deliver(CAMPAIGN, request, candidate, candidate, fixture.promptSources(), RECORD, List.of());

			requireThat(writes, "writes").isEmpty();
			requireThat(second, "second").isEqualTo(first);
		}
	}

	/**
	 * A campaign whose journal belongs to another candidate's delivery refuses to start a second delivery.
	 *
	 * @throws IOException if the fixture or delivery fails
	 */
	@Test(expectedExceptions = IllegalStateException.class,
		expectedExceptionsMessageRegExp = "(?s).*already journals the delivery of candidate.*")
	public void rejectsSecondDeliveryOfCampaign() throws IOException
	{
		// Requirement: R-W8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup);
			Overlay candidate = fixture.candidate();
			Overlay other = Overlay.of(fixture.fork()).write(Path.of("rules/style.md"), content("style v2\n"),
				READ_WRITE);
			CampaignStore store = new InMemoryCampaignStore();
			store.writeJournal(CAMPAIGN, new DeliveryJournal(other.identity(), List.of(), 0));

			fixture.delivery(store, candidate, NO_HOOK).
				deliver(CAMPAIGN, request(candidate, Outcome.CERTIFIED), candidate, candidate, fixture.promptSources(),
					RECORD, List.of());
		}
	}

	/**
	 * A certified delivery creates the target's certification record as the last change of the same change set: the
	 * record is in the delivered diff, is not reported as a base-only change, and reads back as the delivered record.
	 *
	 * @throws IOException if the fixture or delivery fails
	 */
	@Test
	public void certifiedDeliveryCreatesRecord() throws IOException
	{
		// Requirement: R-M6
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup);
			Overlay candidate = fixture.candidate();

			Delivered delivered = requireDelivered(fixture.delivery(new InMemoryCampaignStore(), candidate, NO_HOOK).
				deliver(CAMPAIGN, request(candidate, Outcome.CERTIFIED), candidate, candidate, fixture.promptSources(),
					RECORD, List.of()));

			DeliveryChange last = delivered.changes().getLast();
			requireThat(last, "last").isInstanceOf(Create.class);
			requireThat(last.target(), "target").isEqualTo(RECORD_FILE);
			requireThat(new CertificationRecordJson(JSON).read(fixture.resolve(RECORD_FILE)), "record").
				isEqualTo(RECORD);
			requireThat(delivered.diff(), "diff").contains("+++ b/" + PortablePaths.text(RECORD_FILE)).
				contains("\"certification_index\"");
			requireThat(delivered.baseOnlyChanges(), "baseOnlyChanges").isEmpty();
			requireThat(delivered.applicability().status(), "status").isEqualTo(Applicability.Status.CURRENT);
		}
	}

	/**
	 * A certified delivery replaces the record that an earlier certification of the same target left, and the diff
	 * shows the replacement.
	 *
	 * @throws IOException if the fixture or delivery fails
	 */
	@Test
	public void certifiedDeliveryReplacesEarlierRecord() throws IOException
	{
		// Requirement: R-M6
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CertificationRecordJson codec = new CertificationRecordJson(JSON);
			Fixture fixture = Fixture.create(cleanup).commit(RECORD_FILE, codec.write(record(3)));
			Overlay candidate = fixture.candidate();

			Delivered delivered = requireDelivered(fixture.delivery(new InMemoryCampaignStore(), candidate, NO_HOOK).
				deliver(CAMPAIGN, request(candidate, Outcome.CERTIFIED), candidate, candidate, fixture.promptSources(),
					RECORD, List.of()));

			DeliveryChange last = delivered.changes().getLast();
			requireThat(last, "last").isInstanceOf(Edit.class);
			requireThat(last.target(), "target").isEqualTo(RECORD_FILE);
			requireThat(codec.read(fixture.resolve(RECORD_FILE)), "record").isEqualTo(RECORD);
			requireThat(delivered.diff(), "diff").contains("+++ b/" + PortablePaths.text(RECORD_FILE)).
				contains("-  \"certification_index\" : 3").contains("+  \"certification_index\" : 1");
			requireThat(delivered.baseOnlyChanges(), "baseOnlyChanges").isEmpty();
		}
	}

	/**
	 * Delivery checks the record's target like any other: a directory at the record's path stops delivery before any
	 * write, and a file that appears there after planning stops delivery before the record is written and survives.
	 *
	 * @throws IOException if the fixture or delivery fails
	 */
	@Test
	public void recordTargetIsChecked() throws IOException
	{
		// Requirement: R-M6
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup);
			Overlay candidate = fixture.candidate();
			Path recordFile = fixture.resolve(RECORD_FILE);
			Files.createDirectories(recordFile.resolve("occupied"));
			Files.writeString(recordFile.resolve("occupied/file.txt"), "user data\n", UTF_8);
			List<DeliveryChange> writes = new ArrayList<>();

			DeliveryResult conflicted = fixture.delivery(new InMemoryCampaignStore(), candidate, writes::add).
				deliver(CAMPAIGN, request(candidate, Outcome.CERTIFIED), candidate, candidate, fixture.promptSources(),
					RECORD, List.of());

			requireThat(conflicted, "conflicted").isEqualTo(new Conflicted(List.of(new DeliveryConflict(
				DeliveryConflict.Kind.STRUCTURAL_COLLISION, RECORD_FILE, RECORD_FILE.resolve("occupied/file.txt")))));
			requireThat(writes, "writes").isEmpty();
		}

		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup);
			Overlay candidate = fixture.candidate();
			Path recordFile = fixture.resolve(RECORD_FILE);
			// The user writes the record's path while CAT is between its first and second writes
			Consumer<DeliveryChange> userEdit = change ->
			{
				if (change.sequence() == 0)
				{
					try
					{
						Files.createDirectories(recordFile.getParent());
					}
					catch (IOException e)
					{
						throw new AssertionError("Could not create " + recordFile.getParent(), e);
					}
					write(recordFile, "written by the user\n");
				}
			};

			DeliveryResult result = fixture.delivery(new InMemoryCampaignStore(), candidate, userEdit).
				deliver(CAMPAIGN, request(candidate, Outcome.CERTIFIED), candidate, candidate, fixture.promptSources(),
					RECORD, List.of());

			requireThat(result, "result").isInstanceOf(ConcurrentEdit.class);
			requireThat(((ConcurrentEdit) result).change().target(), "target").isEqualTo(RECORD_FILE);
			requireThat(read(recordFile), "record").isEqualTo("written by the user\n");
		}
	}

	/**
	 * Only a certified delivery carries a certification record: a certified request without one, or another label
	 * with one, is rejected before anything changes.
	 *
	 * @throws IOException if the fixture or delivery fails
	 */
	@Test
	public void recordAccompaniesOnlyCertifiedDelivery() throws IOException
	{
		// Requirement: R-M6
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup);
			Overlay candidate = fixture.candidate();
			Delivery delivery = fixture.delivery(new InMemoryCampaignStore(), candidate, NO_HOOK);

			try
			{
				delivery.deliver(CAMPAIGN, request(candidate, Outcome.CERTIFIED), candidate, candidate,
					fixture.promptSources(), null, List.of());
				throw new AssertionError("A certified delivery without a record was accepted");
			}
			catch (IllegalArgumentException e)
			{
				requireThat(e.getMessage(), "message").contains("record");
			}
			try
			{
				delivery.deliver(CAMPAIGN, request(candidate, Outcome.IMPROVED_NOT_CERTIFIED), candidate, candidate,
					fixture.promptSources(), RECORD, List.of());
				throw new AssertionError("An uncertified delivery with a record was accepted");
			}
			catch (IllegalArgumentException e)
			{
				requireThat(e.getMessage(), "message").contains("record");
			}
			requireThat(read(fixture.resolve(Path.of("rules/main.md"))), "main.md").isEqualTo("main v1\n");
		}
	}

	/**
	 * A certified delivery writes each prompt test's values file, holding every test case of the campaign, and its
	 * regression scenario. A created prompt test gets a new scenario titled with its identifier; an edited prompt test
	 * keeps the title, preamble and design goals of the scenario that the fork recorded. Each written scenario reads back
	 * as the prompt test's scenario.
	 *
	 * @throws IOException             if the fixture or delivery fails
	 * @throws ScenarioFormatException if a written file cannot be read back
	 */
	@Test
	public void certifiedDeliveryWritesPromptTests() throws IOException, ScenarioFormatException
	{
		// Requirement: R-W11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup).commit(EDITED_SCENARIO, RECORDED_SCENARIO.getBytes(UTF_8));
			Overlay candidate = fixture.candidate();
			DeliveredTest created = promptTest("created_summary", true);
			DeliveredTest edited = promptTest("release_notes", false);

			DeliveryResult result = fixture.delivery(new InMemoryCampaignStore(), candidate, NO_HOOK,
				recordedScenario()).
				deliver(CAMPAIGN, request(candidate, Outcome.CERTIFIED), candidate, candidate, fixture.promptSources(),
					RECORD, List.of(created, edited));

			Delivered delivered = requireDelivered(result);
			requireThat(delivered.label(), "label").isEqualTo(Outcome.CERTIFIED);
			requireThat(delivered.baseOnlyChanges(), "baseOnlyChanges").isEmpty();
			requireCandidateDelivered(fixture);
			requirePromptTestDelivered(fixture, created);
			requirePromptTestDelivered(fixture, edited);
			requireThat(read(fixture.resolve(created.location().directory().resolve(PromptTestFiles.SCENARIO))),
				"createdScenario").startsWith("# created_summary\n\n## Design Goals\n\n" + DESIGN_GOALS + "\n");
			requireThat(read(fixture.resolve(EDITED_SCENARIO)), "editedScenario").
				startsWith("# Release notes\n\nWritten for the 2.0 release.\n\n## Design Goals\n\n" +
					"The agent summarizes the release notes.\n").
				doesNotContain("Read `{{file}}`.");
		}
	}

	/**
	 * An edited prompt test's fixture that a shared {@code fixtures/} directory supplies unchanged stays shared:
	 * delivery does not copy it into the prompt test's own {@code fixtures/}. A fixture whose content differs is
	 * written there, and delivery appends a fixture pattern so that the copy replaces the shared file:
	 * {@code fixtures/**} when no pattern names the prompt test's own {@code fixtures/}, or a pattern naming the copy
	 * alone when a later pattern would still replace it.
	 *
	 * @throws IOException             if the fixture or delivery fails
	 * @throws ScenarioFormatException if a written file cannot be read back
	 */
	@Test
	public void sharedFixtureStaysShared() throws IOException, ScenarioFormatException
	{
		// Requirement: R-W11, R-C21
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path sharedNotes = TESTS.resolve("fixtures/docs/{{notes}}");
			Path sharedAgents = TESTS.resolve("fixtures/AGENTS.md");
			String notes = "Release {{notes}} fixes the installer.\n";
			Path ownPatternScenario = TESTS.resolve("release_notes").resolve(PromptTestFiles.SCENARIO);
			String ownPatternRecorded = RECORDED_SCENARIO + "\n## Fixtures\n\n- **patterns**: `fixtures/**`, " +
				"`../fixtures/{AGENTS.md,docs/**}`\n";
			Path sharedOnlyScenario = TESTS.resolve("release_dates").resolve(PromptTestFiles.SCENARIO);
			String sharedOnlyRecorded = RECORDED_SCENARIO + "\n## Fixtures\n\n- **patterns**: `../fixtures/**`\n";
			Fixture fixture = Fixture.create(cleanup).
				commit(sharedNotes, notes.getBytes(UTF_8)).
				commit(sharedAgents, "Reply briefly.\n".getBytes(UTF_8)).
				commit(ownPatternScenario, ownPatternRecorded.getBytes(UTF_8)).
				commit(sharedOnlyScenario, sharedOnlyRecorded.getBytes(UTF_8));
			Overlay candidate = fixture.candidate();
			List<FixtureFile> fixtures = List.of(new FixtureFile("AGENTS.md", "Reply in one sentence.\n"),
				new FixtureFile("docs/{{notes}}", notes));
			DeliveredTest ownPattern = withFixtures(promptTest("release_notes", false), fixtures);
			DeliveredTest sharedOnly = withFixtures(promptTest("release_dates", false), fixtures);

			DeliveryResult result = fixture.delivery(new InMemoryCampaignStore(), candidate, NO_HOOK,
				Map.of(sharedNotes, content(notes), sharedAgents, content("Reply briefly.\n"), ownPatternScenario,
					content(ownPatternRecorded), sharedOnlyScenario, content(sharedOnlyRecorded))).
				deliver(CAMPAIGN, request(candidate, Outcome.CERTIFIED), candidate, candidate, fixture.promptSources(),
					RECORD, List.of(ownPattern, sharedOnly));

			requireDelivered(result);
			for (DeliveredTest test : List.of(ownPattern, sharedOnly))
			{
				Path own = fixture.resolve(test.location().directory().resolve(PromptTestFiles.FIXTURES));
				requireThat(read(own.resolve("AGENTS.md")), "agents").withContext(test.id(), "id").
					isEqualTo("Reply in one sentence.\n");
				requireThat(Files.notExists(own.resolve("docs")), "notesAreShared").withContext(test.id(), "id").
					isTrue();
			}
			requireThat(read(fixture.resolve(sharedNotes)), "sharedNotes").isEqualTo(notes);
			requireThat(read(fixture.resolve(sharedAgents)), "sharedAgents").isEqualTo("Reply briefly.\n");
			requireThat(Scenario.fixturePatterns(read(fixture.resolve(ownPatternScenario))), "ownPattern").
				isEqualTo(List.of("fixtures/**", "../fixtures/{AGENTS.md,docs/**}", "fixtures/AGENTS.md"));
			requireThat(Scenario.fixturePatterns(read(fixture.resolve(sharedOnlyScenario))), "sharedOnly").
				isEqualTo(List.of("../fixtures/**", "fixtures/**"));
		}
	}

	/**
	 * Returns a prompt test with other fixtures.
	 *
	 * @param test     the prompt test
	 * @param fixtures the fixtures
	 * @return the prompt test with {@code fixtures}
	 */
	private static DeliveredTest withFixtures(DeliveredTest test, List<FixtureFile> fixtures)
	{
		return new DeliveredTest(test.id(), test.location(), test.scenario(), test.designGoals(), test.testCases(),
			fixtures);
	}

	/**
	 * When the user keeps the incumbent, delivery writes the prompt tests alone: the result carries no label, no
	 * prompt source or certification record changes, and the files that the rejected candidate changed keep their
	 * forked content.
	 *
	 * @throws IOException             if the fixture or delivery fails
	 * @throws ScenarioFormatException if a written file cannot be read back
	 */
	@Test
	public void keptIncumbentDeliversPromptTestsAlone() throws IOException, ScenarioFormatException
	{
		// Requirement: R-W11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup);
			Overlay candidate = fixture.candidate();
			DeliveredTest created = promptTest("created_summary", true);

			DeliveryResult result = fixture.delivery(new InMemoryCampaignStore(), candidate, NO_HOOK).
				deliver(CAMPAIGN, null, Overlay.of(fixture.fork()), candidate, fixture.promptSources(),
					null, List.of(created));

			Delivered delivered = requireDelivered(result);
			requireThat(delivered.label(), "label").isNull();
			requireThat(delivered.baseOnlyChanges(), "baseOnlyChanges").isEmpty();
			requireThat(delivered.changedPromptSources(), "changedPromptSources").isEmpty();
			requirePromptTestDelivered(fixture, created);
			requireThat(read(fixture.resolve(Path.of("rules/main.md"))), "main.md").isEqualTo("main v1\n");
			requireThat(fixture.resolve(Path.of("rules/old.md")), "old.md").isRegularFile();
			requireThat(Files.notExists(fixture.resolve(Path.of("rules/new.md"))), "newIsAbsent").isTrue();
			requireThat(Files.notExists(fixture.resolve(RECORD_FILE)), "recordIsAbsent").isTrue();
		}
	}

	/**
	 * A prompt test conflicts when the user created a file inside the directory that delivery creates, or edited the
	 * recorded scenario that delivery rewrites; delivery then stops before writing any file or journal.
	 *
	 * @throws IOException if the fixture or delivery fails
	 */
	@Test
	public void promptTestConflictStopsBeforeAnyWrite() throws IOException
	{
		// Requirement: R-W11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup).commit(EDITED_SCENARIO, RECORDED_SCENARIO.getBytes(UTF_8));
			Overlay candidate = fixture.candidate();
			Path createdDirectory = TESTS.resolve("created_summary");
			Path occupant = createdDirectory.resolve("notes.md");
			Files.createDirectories(fixture.resolve(createdDirectory));
			Files.writeString(fixture.resolve(occupant), "written by the user\n", UTF_8);
			Files.writeString(fixture.resolve(EDITED_SCENARIO), "edited by the user\n", UTF_8);
			CampaignStore store = new InMemoryCampaignStore();
			List<DeliveryChange> writes = new ArrayList<>();

			DeliveryResult result = fixture.delivery(store, candidate, writes::add, recordedScenario()).
				deliver(CAMPAIGN, request(candidate, Outcome.CERTIFIED), candidate, candidate, fixture.promptSources(),
					RECORD, List.of(promptTest("created_summary", true), promptTest("release_notes", false)));

			requireThat(result, "result").isInstanceOf(Conflicted.class);
			requireThat(((Conflicted) result).conflicts(), "conflicts").containsExactly(List.of(
				new DeliveryConflict(DeliveryConflict.Kind.STRUCTURAL_COLLISION, createdDirectory, occupant),
				new DeliveryConflict(DeliveryConflict.Kind.BOTH_CHANGED, EDITED_SCENARIO, EDITED_SCENARIO)));
			requireThat(writes, "writes").isEmpty();
			requireThat(store.loadJournal(CAMPAIGN), "journal").isEmpty();
			requireThat(read(fixture.resolve(Path.of("rules/main.md"))), "main.md").isEqualTo("main v1\n");
			requireThat(read(fixture.resolve(EDITED_SCENARIO)), "scenario").isEqualTo("edited by the user\n");
		}
	}

	/**
	 * A crash after any file of a delivery that writes a prompt test, before the journal records it, is followed by a
	 * restart that resumes the same delivery: every change, including the prompt test's files, is written exactly once
	 * across both runs, and the delivery completes.
	 * <p>
	 * Each of the 7 crash points needs an untouched project, so each runs its two deliveries in its own copy of one
	 * fixture.
	 *
	 * @throws IOException             if the fixture or delivery fails
	 * @throws ScenarioFormatException if a written file cannot be read back
	 */
	@Test
	public void promptTestFaultResumesOnce() throws IOException, ScenarioFormatException
	{
		// Requirement: R-W11
		int changeCount = 7;
		DeliveredTest created = promptTest("created_summary", true);
		try (TemporaryDirectories templateCleanup = new TemporaryDirectories())
		{
			Fixture template = Fixture.create(templateCleanup);
			for (int crashAfter = 1; crashAfter <= changeCount; ++crashAfter)
			{
				try (TemporaryDirectories cleanup = new TemporaryDirectories())
				{
					Fixture fixture = template.copy(cleanup);
					Overlay candidate = fixture.candidate();
					DeliveryRequest request = request(candidate, Outcome.CERTIFIED);
					List<Integer> written = new ArrayList<>();
					int limit = crashAfter;
					Consumer<DeliveryChange> crashing = change ->
					{
						written.add(change.sequence());
						if (written.size() == limit)
							throw new SimulatedCrash(change);
					};
					boolean crashed = false;
					try
					{
						fixture.delivery(new FileCampaignStore(fixture.base(), JSON), candidate, crashing).
							deliver(CAMPAIGN, request, candidate, candidate, fixture.promptSources(), RECORD,
								List.of(created));
					}
					catch (SimulatedCrash _)
					{
						crashed = true;
					}
					requireThat(crashed, "crashed").withContext(crashAfter, "crashAfter").isTrue();

					// The restarted process opens a new store and executor over the same base worktree
					DeliveryResult result = fixture.delivery(new FileCampaignStore(fixture.base(), JSON), candidate,
						change -> written.add(change.sequence())).
						deliver(CAMPAIGN, request, candidate, candidate, fixture.promptSources(), RECORD,
							List.of(created));

					requireDelivered(result);
					requireThat(written, "written").withContext(crashAfter, "crashAfter").
						isEqualTo(List.of(0, 1, 2, 3, 4, 5, 6));
					requireCandidateDelivered(fixture);
					requirePromptTestDelivered(fixture, created);
				}
			}
		}
	}

	/**
	 * Only a certified delivery or a kept incumbent writes prompt tests: a delivery under another label that carries
	 * prompt tests is rejected before anything changes, as is a kept incumbent that changes a file.
	 *
	 * @throws IOException if the fixture or delivery fails
	 */
	@Test
	public void promptTestsAccompanyOnlyCertifiedOrKept() throws IOException
	{
		// Requirement: R-W11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup);
			Overlay candidate = fixture.candidate();
			Delivery delivery = fixture.delivery(new InMemoryCampaignStore(), candidate, NO_HOOK);
			List<DeliveredTest> tests = List.of(promptTest("created_summary", true));

			try
			{
				delivery.deliver(CAMPAIGN, request(candidate, Outcome.IMPROVED_NOT_CERTIFIED), candidate, candidate,
					fixture.promptSources(), null, tests);
				throw new AssertionError("An uncertified delivery with prompt tests was accepted");
			}
			catch (IllegalArgumentException e)
			{
				requireThat(e.getMessage(), "message").contains("prompt tests");
			}
			try
			{
				delivery.deliver(CAMPAIGN, null, candidate, candidate, fixture.promptSources(),
					null, tests);
				throw new AssertionError("A kept incumbent that changes files was accepted");
			}
			catch (IllegalArgumentException e)
			{
				requireThat(e.getMessage(), "message").contains("kept incumbent");
			}
			requireThat(read(fixture.resolve(Path.of("rules/main.md"))), "main.md").isEqualTo("main v1\n");
			requireThat(Files.notExists(fixture.resolve(TESTS)), "testsAreAbsent").isTrue();
		}
	}

	/**
	 * Returns a prompt test of the target {@code rules/main.md} that has two test cases.
	 *
	 * @param id      the prompt test's identifier, which names its directory
	 * @param created {@code true} if delivery creates the prompt test's directory, or {@code false} if it edits the
	 *                regression scenario recorded there
	 * @return the prompt test
	 */
	private static DeliveredTest promptTest(String id, boolean created)
	{
		Scenario scenario = new Scenario(List.of(new VariableDeclaration("notes", "The release-notes file name")),
			List.of("Summarize `docs/{{notes}}`."),
			List.of(new Assertion("summary_given", AssertionKind.EFFECT, null, "The reply summarizes the notes.")));
		return new DeliveredTest(id, new Location(TESTS.resolve(id), created), scenario, DESIGN_GOALS, List.of(
			ContractFixtures.testCase("summary_given", Map.of("notes", "2.0.md")),
			ContractFixtures.testCase("summary_given", Map.of("notes", "2.1.md"))),
			List.of(new FixtureFile("docs/{{notes}}", "Release {{notes}} fixes the installer.\n")));
	}

	/**
	 * Returns the forked content of {@link #EDITED_SCENARIO}, which the optimization worktree supplies.
	 *
	 * @return the content keyed by its path
	 */
	private static Map<Path, FileContent> recordedScenario()
	{
		return Map.of(EDITED_SCENARIO, content(RECORDED_SCENARIO));
	}

	/**
	 * Requires a prompt test's files to be in place: a scenario that reads back as the prompt test's scenario, a
	 * values file that holds its test cases, and each fixture file with its placeholders.
	 *
	 * @param fixture the fixture
	 * @param test    the prompt test
	 * @throws IOException             if a file cannot be read
	 * @throws ScenarioFormatException if a file cannot be read back
	 */
	private static void requirePromptTestDelivered(Fixture fixture, DeliveredTest test)
		throws IOException, ScenarioFormatException
	{
		Path directory = fixture.resolve(test.location().directory());
		requireThat(Scenario.parse(read(directory.resolve(PromptTestFiles.SCENARIO))), "scenario").
			withContext(test.id(), "id").isEqualTo(test.scenario());
		try (Reader in = Files.newBufferedReader(directory.resolve(ValuesFile.NAME), UTF_8))
		{
			requireThat(ValuesFile.read(JSON, in, ValuesFile.NAME), "testCases").withContext(test.id(), "id").
				isEqualTo(test.testCases());
		}
		for (FixtureFile file : test.fixtures())
		{
			requireThat(read(directory.resolve(PromptTestFiles.FIXTURES).resolve(file.path())), "fixture").
				withContext(test.id(), "id").isEqualTo(file.content());
		}
	}

	/**
	 * Returns a request to deliver a candidate.
	 *
	 * @param candidate the candidate
	 * @param label the label that the delivered result carries
	 * @return the request
	 */
	private static DeliveryRequest request(Overlay candidate, Outcome label)
	{
		return new DeliveryRequest(new Candidate(2, candidate.identity()), 1, label);
	}

	/**
	 * Returns the certification record of the target {@code rules/main.md}.
	 *
	 * @param certificationIndex the certification index
	 * @return the record
	 */
	private static CertificationRecord record(int certificationIndex)
	{
		return new CertificationRecord(Path.of("rules/main.md"), certificationIndex, CertificationScope.WEAKEST_CELL,
			CertificationParameters.current(certificationIndex, Contract.DEFAULT_MINIMUM_PASS_RATE),
			List.of(new CellResult("formatting", "formatting",
			120, 0, Map.of(), Map.of())), Map.of(Path.of("rules/main.md"), content("main v2\n").hash()),
			new MeasurementIdentity(Map.of(MeasurementCondition.PROVIDER, "claude"), Map.of()));
	}

	/**
	 * Requires a result to be a complete delivery.
	 *
	 * @param result the result
	 * @return the delivered result
	 */
	private static Delivered requireDelivered(DeliveryResult result)
	{
		requireThat(result, "result").isInstanceOf(Delivered.class);
		return (Delivered) result;
	}

	/**
	 * Requires every change of {@link Fixture#candidate()} to be in place.
	 *
	 * @param fixture the fixture
	 * @throws IOException if a file cannot be read
	 */
	private static void requireCandidateDelivered(Fixture fixture) throws IOException
	{
		Path base = fixture.base();
		requireThat(read(base.resolve("rules/new.md")), "new.md").isEqualTo("new rule\n");
		requireThat(Files.notExists(base.resolve("rules/old.md")), "oldIsAbsent").isTrue();
		requireThat(read(fixture.shared()), "shared.md").isEqualTo("shared v1\n");
		requireThat(PortableFiles.mode(base.resolve("rules/new.md")), "newPermissions").
			isEqualTo(READ_WRITE);
		requireThat(read(base.resolve("rules/main.md")), "main.md").isEqualTo("main v2\n");
	}

	/**
	 * Returns file content holding UTF-8 text.
	 *
	 * @param text the text
	 * @return the content
	 */
	private static FileContent content(String text)
	{
		return new FileContent(text.getBytes(UTF_8));
	}

	/**
	 * Reads a UTF-8 text file.
	 *
	 * @param file the file
	 * @return the text
	 * @throws IOException if the file cannot be read
	 */
	private static String read(Path file) throws IOException
	{
		return Files.readString(file, UTF_8);
	}

	/**
	 * Writes a UTF-8 text file from a callback that cannot throw a checked exception.
	 *
	 * @param file the file
	 * @param text the text
	 */
	private static void write(Path file, String text)
	{
		try
		{
			Files.writeString(file, text, UTF_8);
		}
		catch (IOException e)
		{
			throw new AssertionError("Could not write " + file, e);
		}
	}

	/**
	 * A base worktree and an external directory, as recorded at the fork.
	 *
	 * @param root the test-owned directory that holds both
	 * @param base the base worktree, a Git repository whose {@code HEAD} holds the fork's files
	 * @param external the directory outside the base that holds the read-only external participant
	 * @param fork the files recorded at the fork
	 */
	private record Fixture(Path root, Path base, Path external, FileInventory fork)
	{
		/**
		 * Creates the base worktree with one commit and the external directory, and records the fork.
		 *
		 * @param cleanup the scope that deletes the fixture
		 * @return the fixture
		 * @throws IOException if the fixture cannot be created
		 */
		static Fixture create(TemporaryDirectories cleanup) throws IOException
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-delivery-"));
			Path base = Files.createDirectory(root.resolve("base"));
			Path external = Files.createDirectory(root.resolve("external"));
			Files.createDirectory(base.resolve("rules"));
			Files.writeString(base.resolve("rules/main.md"), "main v1\n", UTF_8);
			Files.writeString(base.resolve("rules/style.md"), "style v1\n", UTF_8);
			Files.writeString(base.resolve("rules/old.md"), "old rule\n", UTF_8);
			Files.writeString(base.resolve("README.md"), "readme v1\n", UTF_8);
			Files.writeString(external.resolve("shared.md"), "shared v1\n", UTF_8);

			Git git = new Git(base);
			git.run("init", "--quiet");
			git.run("add", "--all");
			git.run("-c", "user.name=CAT Test", "-c", "user.email=cat-test@example.com", "-c", "commit.gpgsign=false",
				"commit", "--quiet", "--no-verify", "--message", "Fork point");
			return new Fixture(root, base, external, InventoryScanner.scan(base, Set.of(external)));
		}

		/**
		 * Copies the fixture, with its Git repository, to a new directory.
		 *
		 * @param cleanup the scope that deletes the copy
		 * @return the copy
		 * @throws IOException if the fixture cannot be copied
		 */
		Fixture copy(TemporaryDirectories cleanup) throws IOException
		{
			Path copy = cleanup.add(Files.createTempDirectory("cat-delivery-"));
			FileTrees.copy(root, copy);
			Path copiedBase = copy.resolve(root.relativize(base).toString());
			Path copiedExternal = copy.resolve(root.relativize(external).toString());
			return new Fixture(copy, copiedBase, copiedExternal, InventoryScanner.scan(copiedBase, Set.of(copiedExternal)));
		}

		/**
		 * Commits a file to the base worktree and records the fork again.
		 *
		 * @param path the file's path, relative to the base worktree
		 * @param bytes the file's content
		 * @return the fixture whose fork holds the file
		 * @throws IOException if the file cannot be written or committed
		 */
		Fixture commit(Path path, byte[] bytes) throws IOException
		{
			Path file = base.resolve(path);
			Files.createDirectories(file.getParent());
			Files.write(file, bytes);
			Git git = new Git(base);
			git.run("add", "--all");
			git.run("-c", "user.name=CAT Test", "-c", "user.email=cat-test@example.com", "-c", "commit.gpgsign=false",
				"commit", "--quiet", "--no-verify", "--message", "Earlier certification");
			return new Fixture(root, base, external, InventoryScanner.scan(base, Set.of(external)));
		}

		/**
		 * Returns the read-only external participant.
		 *
		 * @return the absolute path of {@code shared.md}
		 */
		Path shared()
		{
			return external.resolve("shared.md");
		}

		/**
		 * Returns the prompt sources: the {@code rules} directory and the read-only external participant.
		 *
		 * @return the paths
		 */
		Set<Path> promptSources()
		{
			return Set.of(Path.of("rules"), shared());
		}

		/**
		 * Returns the candidate that edits {@code rules/main.md}, creates {@code rules/new.md} and deletes
		 * {@code rules/old.md}.
		 *
		 * @return the candidate
		 */
		Overlay candidate()
		{
			return Overlay.of(fork).
				write(Path.of("rules/main.md"), content("main v2\n"), recordedPermissions(Path.of("rules/main.md"))).
				write(Path.of("rules/new.md"), content("new rule\n"), READ_WRITE).
				delete(Path.of("rules/old.md"));
		}

		/**
		 * Returns the mode that the fork recorded for a file.
		 *
		 * @param path the file's inventory path
		 * @return the permissions
		 */
		private Set<PosixFilePermission> recordedPermissions(Path path)
		{
			return ((RegularFile) fork.entry(path).orElseThrow()).permissions();
		}

		/**
		 * Returns a delivery executor whose optimization worktree holds the working candidate.
		 *
		 * @param store the store that holds the delivery journal
		 * @param working the working candidate
		 * @param afterWrite receives each change after its file is written
		 * @return the executor
		 */
		Delivery delivery(CampaignStore store, Overlay working, Consumer<DeliveryChange> afterWrite)
		{
			return delivery(store, working, afterWrite, Map.of());
		}

		/**
		 * Returns a delivery executor whose optimization worktree holds the working candidate and can read forked files.
		 *
		 * @param store      the store that holds the delivery journal
		 * @param working    the working candidate
		 * @param afterWrite receives each change after its file is written
		 * @param recorded   the forked content of each file that delivery reads, keyed by its path
		 * @return the executor
		 */
		Delivery delivery(CampaignStore store, Overlay working, Consumer<DeliveryChange> afterWrite,
			Map<Path, FileContent> recorded)
		{
			FakeWorkspacePort workspace = new FakeWorkspacePort(root.resolve("workspace"), working.snapshot());
			for (Map.Entry<Path, FileContent> file : recorded.entrySet())
				workspace.supplyFile(file.getKey(), file.getValue());
			return new Delivery(store, workspace, JSON, afterWrite);
		}

		/**
		 * Scans the base worktree and the external participant as delivery's preflight does.
		 *
		 * @return the current files
		 * @throws IOException if a file cannot be read
		 */
		FileInventory scan() throws IOException
		{
			return InventoryScanner.scan(base, Set.of(shared()));
		}

		/**
		 * Returns the file that an inventory path names.
		 *
		 * @param path a path relative to the base worktree, or an absolute external path
		 * @return the absolute file
		 */
		Path resolve(Path path)
		{
			return base.resolve(path);
		}
	}

	/**
	 * Simulates a process that stops after writing a change's file.
	 */
	private static final class SimulatedCrash extends RuntimeException
	{
		private static final long serialVersionUID = 0L;

		/**
		 * Creates the exception.
		 *
		 * @param change the change whose file was written last
		 */
		SimulatedCrash(DeliveryChange change)
		{
			super("Crashed after writing " + change.target());
		}
	}
}
