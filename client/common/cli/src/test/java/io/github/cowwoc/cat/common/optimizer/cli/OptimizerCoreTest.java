package io.github.cowwoc.cat.common.optimizer.cli;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.fakes.ManualClock;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.ConcurrencyPolicy;
import io.github.cowwoc.cat.common.optimizer.campaign.SlowStart;
import io.github.cowwoc.cat.common.optimizer.campaign.Throttle;
import io.github.cowwoc.cat.common.optimizer.campaign.PendingDecision;
import io.github.cowwoc.cat.common.optimizer.execution.AuthorConflict;
import io.github.cowwoc.cat.common.optimizer.campaign.Builder;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignEvent;
import io.github.cowwoc.cat.common.optimizer.campaign.Header;
import io.github.cowwoc.cat.common.optimizer.campaign.Transitions;
import io.github.cowwoc.cat.common.optimizer.campaign.Choice;
import io.github.cowwoc.cat.common.optimizer.campaign.Applicability;
import io.github.cowwoc.cat.common.optimizer.campaign.Harness;
import io.github.cowwoc.cat.common.optimizer.campaign.Lifecycle;
import io.github.cowwoc.cat.common.optimizer.campaign.CertificationParameters;
import io.github.cowwoc.cat.common.optimizer.campaign.CertificationRecord;
import io.github.cowwoc.cat.common.optimizer.campaign.MeasurementCondition;
import io.github.cowwoc.cat.common.optimizer.campaign.MeasurementIdentity;
import io.github.cowwoc.cat.common.optimizer.campaign.Outcome;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;
import io.github.cowwoc.cat.common.optimizer.store.CertificationRecordJson;
import tools.jackson.databind.JsonNode;
import io.github.cowwoc.cat.common.optimizer.contract.CertificationScope;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfile;
import io.github.cowwoc.cat.common.optimizer.inventory.AuthorityWarning;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.Limit;
import io.github.cowwoc.cat.common.optimizer.inventory.Overlay;
import io.github.cowwoc.cat.common.optimizer.port.FakeProfileInventory;
import io.github.cowwoc.cat.common.optimizer.port.FakeSessionPort;
import io.github.cowwoc.cat.common.optimizer.port.HostLogin;
import io.github.cowwoc.cat.common.optimizer.port.SessionHandle;
import io.github.cowwoc.cat.common.optimizer.port.SessionPort;
import io.github.cowwoc.cat.common.optimizer.port.SessionRequest;
import io.github.cowwoc.cat.common.optimizer.port.SessionResult;
import io.github.cowwoc.cat.common.optimizer.port.SessionRole;
import io.github.cowwoc.cat.common.optimizer.port.TokenUsage;
import io.github.cowwoc.cat.common.optimizer.report.TokenStatistics;
import io.github.cowwoc.cat.common.optimizer.store.FileCampaignStore;
import io.github.cowwoc.cat.common.optimizer.store.FileCandidateOverlays;
import io.github.cowwoc.cat.common.optimizer.workspace.ForkView;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Tests {@link OptimizerCore} against a real project directory and campaign store, without a harness.
 */
public final class OptimizerCoreTest
{
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Creates the test.
	 */
	public OptimizerCoreTest()
	{
	}

	/**
	 * A project that holds one campaign.
	 *
	 * @param root the project root
	 * @param target the campaign's target
	 * @param campaign the campaign's identifier
	 */
	private record Project(Path root, Path target, String campaign)
	{
		/**
		 * Returns the scope of a command that a harness runs in this project.
		 *
		 * @param harness the harness that runs the launcher
		 * @return the scope
		 */
		TestOptimizerCommandScope<FakeSessionPort> scope(Harness harness)
		{
			return TestOptimizerCommandScope.of(harness, root);
		}
	}

	/**
	 * Creates a project with one campaign that a harness started.
	 *
	 * @param scope removes the project
	 * @param owner the harness that started the campaign
	 * @return the project
	 * @throws IOException if the project cannot be written
	 * @throws CommandRefusedException if the project is not a Git repository
	 */
	private static Project project(TemporaryDirectories scope, Harness owner)
		throws IOException, CommandRefusedException
	{
		Path root = scope.add(Files.createTempDirectory("optimizer-core")).toRealPath();
		Files.createDirectory(root.resolve(".git"));
		Path target = root.resolve("CLAUDE.md");
		Files.writeString(target, "Be brief.\n");
		Header header = new Header("campaign-1", root, target, owner, Lifecycle.ACTIVE);
		Campaign campaign = new Builder().header(header).confirmed().build();
		try (TestOptimizerCommandScope<FakeSessionPort> command = TestOptimizerCommandScope.of(owner, root))
		{
			command.store().replaceSnapshot(campaign);
		}
		return new Project(root, target, "campaign-1");
	}

	/**
	 * Returns the content of every file below a directory.
	 *
	 * @param root the directory
	 * @return each file's relative path and content
	 * @throws IOException if a file cannot be read
	 */
	private static Map<String, String> contents(Path root) throws IOException
	{
		Map<String, String> result = new TreeMap<>();
		try (Stream<Path> files = Files.walk(root))
		{
			for (Path path : files.toList())
			{
				String value = "<directory>";
				if (Files.isRegularFile(path))
					value = Files.readString(path, StandardCharsets.ISO_8859_1);
				result.put(root.relativize(path).toString(), value);
			}
		}
		return result;
	}

	/**
	 * Verifies that either harness may report a campaign, and that reporting runs no session and reads no version.
	 */
	@Test
	public void statusIsOpenToTheOtherHarness() throws Exception
	{
		// Requirement: R-L1
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Project project = project(scope, Harness.CLAUDE);
			Map<String, String> before = contents(project.root());
			try (TestOptimizerCommandScope<FakeSessionPort> command = project.scope(Harness.CODEX))
			{
				CommandResult result = new OptimizerCore(command).status(project.campaign());
				requireThat(result.status().campaignId(), "campaignId").isEqualTo("campaign-1");
				requireThat(result.harness(), "harness").isEqualTo(Harness.CLAUDE);
				requireThat(command.sessions().sessions(), "sessions").isEmpty();
				requireThat(command.versionReads(), "versionReads").isEqualTo(0);
			}
			requireThat(contents(project.root()), "contents").isEqualTo(before);
		}
	}

	/**
	 * Verifies that every command that changes or runs a campaign refuses the other harness's campaign, before any
	 * state changes.
	 */
	@Test
	public void refusesOtherHarnessBeforeAnyChange() throws Exception
	{
		// Requirement: R-L1
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Project project = project(scope, Harness.CLAUDE);
			Map<String, String> before = contents(project.root());
			try (TestOptimizerCommandScope<FakeSessionPort> command = project.scope(Harness.CODEX))
			{
				OptimizerCore core = new OptimizerCore(command);
				String id = project.campaign();
				List<ThrowingCall> calls = List.of(
					() -> core.run(id),
					() -> core.confirm(id, CertificationScope.LATEST_VERSIONS),
					() -> core.revise(id, "more"),
					() -> core.choose(id, Choice.STOP, 0, 0),
					() -> core.cancel(id),
					() -> core.start(project.target()));
				for (ThrowingCall call : calls)
				{
					CommandRefusedException refusal = null;
					try
					{
						call.run();
					}
					catch (CommandRefusedException e)
					{
						refusal = e;
					}
					requireThat(refusal, "refusal").isNotNull();
					requireThat(refusal.getMessage(), "message").contains("claude").contains(id);
				}
				requireThat(command.sessions().sessions(), "sessions").isEmpty();
				requireThat(command.versionReads(), "versionReads").isEqualTo(0);
			}
			requireThat(contents(project.root()), "contents").isEqualTo(before);
		}
	}

	/**
	 * A call to the core.
	 */
	@FunctionalInterface
	private interface ThrowingCall
	{
		/**
		 * Calls the core.
		 *
		 * @throws Exception if the core refuses or fails
		 */
		void run() throws Exception;
	}

	/**
	 * Verifies that the harness that started a campaign gets past the harness check, and is refused only for what the
	 * campaign lacks.
	 */
	@Test
	public void sameHarnessReachesTheCampaign() throws Exception
	{
		// Requirement: R-L1
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Project project = project(scope, Harness.CLAUDE);
			OptimizerCore core = new OptimizerCore(project.scope(Harness.CLAUDE));
			CommandRefusedException refusal = null;
			try
			{
				core.confirm(project.campaign(), CertificationScope.LATEST_VERSIONS);
			}
			catch (CommandRefusedException e)
			{
				refusal = e;
			}
			requireThat(refusal, "refusal").isNotNull();
			requireThat(refusal.getMessage(), "message").doesNotContain("was started by");
		}
	}

	/**
	 * Verifies that an unknown campaign is refused.
	 */
	@Test
	public void refusesUnknownCampaign() throws Exception
	{
		// Requirement: R-I2
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Project project = project(scope, Harness.CLAUDE);
			CommandRefusedException refusal = null;
			try
			{
				new OptimizerCore(project.scope(Harness.CLAUDE)).status("missing");
			}
			catch (CommandRefusedException e)
			{
				refusal = e;
			}
			requireThat(refusal, "refusal").isNotNull();
			requireThat(refusal.getMessage(), "message").contains("missing");
		}
	}

	/**
	 * Verifies that a target outside the project is refused before anything is created.
	 */
	@Test
	public void refusesTargetOutsideProject() throws Exception
	{
		// Requirement: R-I2
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Project project = project(scope, Harness.CLAUDE);
			Path outside = scope.add(Files.createTempDirectory("optimizer-outside")).resolve("prompt.md");
			Files.writeString(outside, "x");
			Map<String, String> before = contents(project.root());
			CommandRefusedException refusal = null;
			try
			{
				new OptimizerCore(project.scope(Harness.CLAUDE)).start(outside);
			}
			catch (CommandRefusedException e)
			{
				refusal = e;
			}
			requireThat(refusal, "refusal").isNotNull();
			requireThat(refusal.getMessage(), "message").contains("inside the project");
			requireThat(contents(project.root()), "contents").isEqualTo(before);
		}
	}

	/**
	 * Verifies that a host setting that keeps sessions from being denied reads of held-out content is refused before a
	 * campaign starts, with the message that names it: no campaign is created and no session starts.
	 *
	 * @throws Exception if the fixture fails
	 */
	@Test(timeOut = 30_000)
	public void refusesExposingHostSetting() throws Exception
	{
		// Requirement: R-E24
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Path root = scope.add(Files.createTempDirectory("optimizer-core")).toRealPath();
			Files.createDirectory(root.resolve(".git"));
			Files.writeString(root.resolve("CLAUDE.md"), "Be brief.\n");
			Map<String, String> before = contents(root);
			String exposure = "The sandbox is off. Remove the setting and run `start` again.";
			FakeSessionPort sessions = new FakeSessionPort(HostLogin.UNKNOWN, Optional.of(exposure));
			CommandRefusedException refusal = null;
			try (TestOptimizerCommandScope<FakeSessionPort> command = new TestOptimizerCommandScope<>(Harness.CLAUDE,
				root, Set.of(), _ -> sessions))
			{
				new OptimizerCore(command).start(Path.of("CLAUDE.md"));
			}
			catch (CommandRefusedException e)
			{
				refusal = e;
			}
			requireThat(refusal, "refusal").isNotNull();
			requireThat(refusal.getMessage(), "message").isEqualTo(exposure);
			requireThat(sessions.sessions(), "sessions").isEmpty();
			requireThat(contents(root), "contents").isEqualTo(before);
		}
	}

	/**
	 * A session port that answers each session at once from a script and advances the clock by one minute per
	 * session, so that a slice's budget runs out without waiting.
	 */
	private static final class ScriptedSessions implements SessionPort
	{
		private final List<SessionRequest> requests = new ArrayList<>();
		private final ManualClock clock;
		private final Function<SessionRequest, SessionResult> script;
		private final Map<SessionRole, List<TokenUsage>> usage = new java.util.EnumMap<>(SessionRole.class);

		/**
		 * Creates the port.
		 *
		 * @param clock the clock to advance
		 * @param script decides each session's result
		 */
		ScriptedSessions(ManualClock clock, Function<SessionRequest, SessionResult> script)
		{
			this.clock = clock;
			this.script = script;
		}

		@Override
		public int sessionEnvironmentVersion()
		{
			return FakeSessionPort.SESSION_ENVIRONMENT_VERSION;
		}

		@Override
		public int judgeVersion()
		{
			return FakeSessionPort.JUDGE_VERSION;
		}

		@Override
		public String renderEvidence(SessionResult.Completed evidence, Path source)
		{
			return "No tool or hook activity.\n\nFile changes\nNo file changes.\n";
		}

		@Override
		public List<Path> runRoots()
		{
			return List.of();
		}

		@Override
		public HostLogin hostLogin()
		{
			return HostLogin.UNKNOWN;
		}

		@Override
		public Optional<String> findHeldOutExposure(Path project)
		{
			return Optional.empty();
		}

		@Override
		public SessionHandle start(SessionRequest request)
		{
			requests.add(request);
			clock.advance(Duration.ofMinutes(1));
			SessionResult response = script.apply(request);
			List<TokenUsage> tokens = usage.getOrDefault(request.role(), List.of());
			if (!tokens.isEmpty() && response instanceof SessionResult.Completed completed)
			{
				int index = (int) requests.stream().filter(started -> started.role() == request.role()).count() - 1;
				response = new SessionResult.Completed(completed.transcript(), completed.responses(),
					completed.effects(),
					completed.model(), completed.reasoning(), tokens.get(index % tokens.size()), completed.windows(),
					completed.promptSources(), completed.toolCalls());
			}
			CompletableFuture<SessionResult> result = CompletableFuture.completedFuture(response);
			return new SessionHandle()
			{
				@Override
				public CompletionStage<SessionResult> completion()
				{
					return result;
				}

				@Override
				public void interrupt()
				{
				}
			};
		}

		/**
		 * Returns the roles of the sessions started so far.
		 *
		 * @return the roles, in start order
		 */
		List<SessionRole> roles()
		{
			return requests.stream().map(SessionRequest::role).toList();
		}
	}

	/**
	 * A project directory that holds a tracked prompt and ignored files, with in-memory forks and the core that
	 * runs on it.
	 *
	 * @param root the project directory
	 * @param scope the command's scope
	 * @param core the core
	 */
	private record Repository(Path root, TestOptimizerCommandScope<ScriptedSessions> scope, OptimizerCore core)
	{
		/**
		 * Returns the scripted sessions.
		 *
		 * @return the sessions
		 */
		ScriptedSessions sessions()
		{
			return scope.sessions();
		}

		/**
		 * Returns the in-memory forks.
		 *
		 * @return the forks
		 */
		InMemoryForks forks()
		{
			return scope.forks();
		}

		/**
		 * Returns the campaign directory.
		 *
		 * @param id the campaign's identifier
		 * @return the directory
		 */
		Path directory(String id)
		{
			return root.resolve(".cat/work/optimizer").resolve(id);
		}

		/**
		 * Loads a campaign.
		 *
		 * @param id the campaign's identifier
		 * @return the campaign
		 * @throws IOException if the store cannot be read
		 * @throws CommandRefusedException if the project is not a Git repository
		 */
		Campaign load(String id) throws IOException, CommandRefusedException
		{
			return scope.store().load(id).orElseThrow();
		}
	}

	/**
	 * Returns the text of the deriver output fixture.
	 *
	 * @param change edits the output
	 * @return the output as JSON text
	 * @throws java.io.UncheckedIOException if the fixture cannot be read
	 */
	private static String derivation(java.util.function.Consumer<ObjectNode> change)
	{
		try (InputStream in = OptimizerCoreTest.class.getResourceAsStream("/optimizer/proposal/derivation.json"))
		{
			ObjectNode output = (ObjectNode) JSON.readTree(in.readAllBytes());
			change.accept(output);
			return JSON.writeValueAsString(output);
		}
		catch (IOException e)
		{
			throw new java.io.UncheckedIOException(e);
		}
	}

	/**
	 * Creates a repository and a core whose sessions follow a script.
	 *
	 * @param scope removes the repository
	 * @param deriver returns the deriver's final response for the number of deriver sessions started so far
	 * @return the repository
	 * @throws IOException if the repository cannot be written
	 */
	private static Repository repository(TemporaryDirectories scope, Function<Integer, String> deriver)
		throws IOException
	{
		return repository(scope, deriver, Set.of(Path.of("CLAUDE.md")));
	}

	/**
	 * Creates a repository and a core whose sessions follow a script.
	 *
	 * @param scope removes the repository
	 * @param deriver returns the deriver's final response for the number of deriver sessions started so far
	 * @param tracked the files, relative to the project, that Git tracks
	 * @return the repository
	 * @throws IOException if the repository cannot be written
	 */
	private static Repository repository(TemporaryDirectories scope, Function<Integer, String> deriver,
		Set<Path> tracked) throws IOException
	{
		return repository(scope, deriver, tracked, Optional.empty());
	}

	/**
	 * Creates a repository and a core whose sessions follow a script.
	 *
	 * @param scope removes the repository
	 * @param deriver returns the deriver's final response for the number of deriver sessions started so far
	 * @param tracked the files, relative to the project, that Git tracks
	 * @param deriverEnd how every deriver session ends instead of completing with {@code deriver}'s response, or
	 *   empty to let the deriver complete
	 * @return the repository
	 * @throws IOException if the repository cannot be written
	 */
	private static Repository repository(TemporaryDirectories scope, Function<Integer, String> deriver,
		Set<Path> tracked, Optional<SessionResult> deriverEnd) throws IOException
	{
		Path root = scope.add(Files.createTempDirectory("optimizer-flow")).toRealPath();
		Path scratch = Files.createDirectory(root.resolve("scratch-outside-git"));
		Files.createDirectory(root.resolve(".git"));
		Files.writeString(root.resolve("CLAUDE.md"), "Be brief.\n");
		Files.writeString(root.resolve(".env.example"), "TOKEN=fixture\n");
		Files.writeString(root.resolve("extra.local.md"), "Local rules.\n");
		AtomicInteger derivers = new AtomicInteger();
		Function<SessionRequest, SessionResult> script = request ->
		{
			try
			{
				Path transcript = Files.createTempFile(scratch, "transcript", ".jsonl");
				Path effects = Files.createTempDirectory(scratch, "effects");
				return switch (request.role())
				{
					case DERIVER -> deriverEnd.orElseGet(() -> new SessionResult.Completed(transcript,
						List.of(List.of("working", deriver.apply(derivers.getAndIncrement()))), effects, "model", null,
						null, List.of(), "hook: none", List.of()));
					case TRIAL -> new SessionResult.Completed(transcript, request.turns().stream().
						map(_ -> List.of("answer")).toList(), effects, "model", null, null, List.of(), "CLAUDE.md",
							List.of());
					case JUDGE -> new SessionResult.Completed(transcript, List.of(List.of(
						"{\"verdict\": \"pass\", \"rationale\": \"The answer follows the rubric.\"}")), effects,
						"model", null, null, List.of(), "CLAUDE.md", List.of());
					case TRIAGE -> new SessionResult.Completed(transcript, List.of(List.of(
						"{\"impact\": \"blocking\", \"rationale\": \"The answer is incomplete.\"}")), effects,
						"model", null, null, List.of(), "CLAUDE.md", List.of());
					case AUTHOR -> new SessionResult.Completed(transcript, List.of(List.of("{\"needed_edits\": []}")),
						effects,
						"model", null, null, List.of(), "CLAUDE.md", List.of());
				};
			}
			catch (IOException e)
			{
				throw new java.io.UncheckedIOException(e);
			}
		};
		TestOptimizerCommandScope<ScriptedSessions> command = new TestOptimizerCommandScope<>(Harness.CLAUDE, root,
			tracked, clock -> new ScriptedSessions(clock, script));
		return new Repository(root, command, new OptimizerCore(command));
	}

	/**
	 * Returns each way that a deriver session can end without completing, with the message that {@code start} and
	 * {@code revise} report.
	 *
	 * @return the session result and the text that the message contains
	 */
	@DataProvider
	public Object[][] deriverEndsWithoutCompleting()
	{
		Instant resetsAt = Instant.parse("2026-10-05T23:00:00Z");
		return new Object[][]
			{
				{new SessionResult.Failed("The deriver session reached its 30-minute time limit", List.of()),
					"The deriver session did not complete: The deriver session reached its 30-minute time limit. " +
					"Run `start` again to retry."},
				{new SessionResult.Interrupted(List.of()),
					"The deriver session was interrupted. Run `start` again to retry."},
				{new SessionResult.RateLimited(resetsAt, List.of()),
					"The provider rejected the deriver session because of a rate limit until " + resetsAt +
					". Run `start` again after that time."},
				{new SessionResult.RateLimited(null, List.of()),
					"The provider rejected the deriver session because of a rate limit. " +
					"Run `start` again later."}
			};
	}

	/**
	 * Verifies that {@code start} reports a deriver session that did not complete by its kind and reason, not by the
	 * result's record text.
	 *
	 * @param end how the deriver session ends
	 * @param message the message that {@code start} reports
	 * @throws Exception if the repository cannot be written or the command fails
	 */
	@Test(dataProvider = "deriverEndsWithoutCompleting")
	public void startReportsDeriverThatDidNotComplete(SessionResult end, String message) throws Exception
	{
		// Requirement: R-E13
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(_ ->
			{
			}), Set.of(Path.of("CLAUDE.md")), Optional.of(end));

			CommandResult result = repository.core().start(Path.of("CLAUDE.md"));

			requireThat(result.message(), "message").contains(message).
				doesNotContain(end.getClass().getSimpleName() + "[");
		}
	}

	/**
	 * Verifies that {@code start} derives a proposal, persists it, and runs nothing else.
	 */
	@Test
	public void startDerivesAndPersistsProposal() throws Exception
	{
		// Requirement: R-I2
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(_ ->
			{
			}));
			CommandResult result = repository.core().start(Path.of("CLAUDE.md"));
			String id = result.status().campaignId();
			requireThat(id, "id").matches("20261001T000000Z-[0-9a-f]{6}");
			requireThat(repository.sessions().roles(), "roles").isEqualTo(List.of(SessionRole.DERIVER));
			requireThat(result.proposal(), "proposal").isNotNull().contains(".env.example");
			requireThat(repository.directory(id).resolve("held_out/proposal.json"), "proposalFile").isRegularFile();
			requireThat(repository.load(id).isConfirmed(), "confirmed").isFalse();
		}
	}

	/**
	 * Verifies that start persists the selected ceiling and supplies it to the concurrency policy.
	 */
	@Test
	public void startPersistsCampaignCeiling() throws IOException, CommandRefusedException
	{
		// Requirement: R-E4
		for (int ceiling : List.of(16, 8))
		{
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Repository repository = repository(cleanup, _ -> derivation(_ ->
				{
				}));
				List<String> args = new ArrayList<>(List.of("start", "CLAUDE.md"));
				if (ceiling == 16)
					args.addAll(List.of("--max-concurrency", "16"));
				ByteArrayOutputStream bytes = new ByteArrayOutputStream();
				try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8))
				{
					requireThat(new Commands(repository.core(), JSON, ZoneOffset.UTC).execute(args, out), "exit").isEqualTo(0);
				}
				String id = JSON.readTree(bytes.toString(StandardCharsets.UTF_8)).get("campaign").asString();
				Campaign campaign = repository.load(id);
				requireThat(campaign.ceiling(), "ceiling").isEqualTo(ceiling);
				requireThat(ConcurrencyPolicy.window(100, 0, new SlowStart(16, 0), campaign.ceiling(), Throttle.NONE),
					"window").isEqualTo(ceiling);
				try (TestOptimizerCommandScope<FakeSessionPort> next = TestOptimizerCommandScope.of(Harness.CLAUDE,
					repository.root()))
				{
					requireThat(next.store().load(id).orElseThrow().ceiling(), "reloadedCeiling").isEqualTo(ceiling);
				}
			}
		}
	}

	/**
	 * Verifies that a started campaign records the session port's session environment version.
	 */
	@Test
	public void startRecordsSessionEnvironmentVersion()
		throws IOException, CommandRefusedException, InterruptedException
	{
		// Requirement: R-M1
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Repository repository = repository(cleanup, _ -> derivation(_ ->
			{
			}));
			String id = repository.core().start(Path.of("CLAUDE.md")).status().campaignId();
			Map<MeasurementCondition, String> values = repository.load(id).identity().values();
			requireThat(values.get(MeasurementCondition.SESSION_ENVIRONMENT_VERSION), "sessionEnvironmentVersion").
				isEqualTo(String.valueOf(FakeSessionPort.SESSION_ENVIRONMENT_VERSION));
		}
	}

	/**
	 * Verifies that a started campaign records the session port's judge version.
	 */
	@Test
	public void startRecordsJudgeVersion() throws IOException, CommandRefusedException, InterruptedException
	{
		// Requirement: R-M1
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Repository repository = repository(cleanup, _ -> derivation(_ ->
			{
			}));
			String id = repository.core().start(Path.of("CLAUDE.md")).status().campaignId();
			requireThat(repository.load(id).identity().values().get(MeasurementCondition.JUDGE), "judgeVersion").
				isEqualTo(String.valueOf(FakeSessionPort.JUDGE_VERSION));
		}
	}

	/**
	 * Verifies that a stored campaign without a ceiling loads the default.
	 */
	@Test
	public void storedCampaignWithoutCeilingLoadsDefault()
		throws IOException, CommandRefusedException, InterruptedException
	{
		// Requirement: R-E4
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Repository repository = repository(cleanup, _ -> derivation(_ ->
			{
			}));
			String id = repository.core().start(Path.of("CLAUDE.md")).status().campaignId();
			Path snapshot = repository.directory(id).resolve("held_out/campaign.json");
			ObjectNode stored = (ObjectNode) JSON.readTree(Files.readString(snapshot));
			stored.remove("ceiling");
			Files.writeString(snapshot, JSON.writeValueAsString(stored));
			requireThat(repository.load(id).ceiling(), "ceiling").isEqualTo(8);
		}
	}

	/**
	 * Verifies that the deriver receives a recorded scenario's path and complete content from the project's test tree.
	 *
	 * @throws IOException if the project cannot be written
	 * @throws CommandRefusedException if the project cannot start a campaign
	 * @throws InterruptedException if the session wait is interrupted
	 */
	@Test
	public void startIncludesRecordedScenario() throws IOException, CommandRefusedException, InterruptedException
	{
		// Requirement: R-I3
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(_ ->
			{
			}));
			Path scenarios = Files.createDirectories(repository.root().resolve(".cat/tests/CLAUDE"));
			Files.writeString(scenarios.resolve("reported.md"), "Turn: Summarize the notes.\nExpected: Be brief.\n");
			repository.core().start(Path.of("CLAUDE.md"));
			requireThat(repository.sessions().requests.getFirst().turns().getFirst(), "deriverTurn").
				contains(".cat/tests/CLAUDE/reported.md").
				contains("Turn: Summarize the notes.\nExpected: Be brief.\n");
		}
	}

	/**
	 * Verifies that a proposal omitting a scenario recorded beside a user rule is refused before it is persisted.
	 *
	 * @throws IOException if the project cannot be written
	 * @throws CommandRefusedException if the project cannot start a campaign
	 * @throws InterruptedException if the session wait is interrupted
	 */
	@Test
	public void startRefusesUncitedUserScenario() throws IOException, CommandRefusedException, InterruptedException
	{
		// Requirement: R-I3
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(output ->
			{
				((ObjectNode) output.get("requirements").get(0)).put("source",
					"home/.cat/rules/common/wording.md");
			}));
			Path rules = Files.createDirectories(repository.root().resolve("home/.cat/rules/common"));
			Files.writeString(rules.resolve("wording.md"), "Be brief.\n");
			Path scenarios = Files.createDirectories(repository.root().resolve("home/.cat/tests/common/wording"));
			Files.writeString(scenarios.resolve("reported.md"), "Turn: Summarize.\nExpected: Be brief.\n");
			CommandResult result = repository.core().start(Path.of("CLAUDE.md"));
			requireThat(result.message(), "message").contains("The deriver's proposal is invalid").
				contains("reported.md");
			requireThat(Files.exists(repository.directory(result.status().campaignId()).resolve("held_out/proposal.json")),
				"proposalFileExists").isFalse();
		}
	}

	/**
	 * Verifies that confirmation discovers scenarios recorded after derivation and rejects the now-incomplete proposal.
	 *
	 * @throws IOException if the project cannot be written or the stored proposal omits the new scenario
	 * @throws CommandRefusedException if the project cannot start a campaign
	 * @throws InterruptedException if the session wait is interrupted
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*reported\\.md.*")
	public void confirmChecksNewRecordedScenario() throws IOException, CommandRefusedException, InterruptedException
	{
		// Requirement: R-I3
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(_ ->
			{
			}));
			String id = repository.core().start(Path.of("CLAUDE.md")).status().campaignId();
			Path scenarios = Files.createDirectories(repository.root().resolve(".cat/tests/CLAUDE"));
			Files.writeString(scenarios.resolve("reported.md"), "Turn: Summarize.\nExpected: Be brief.\n");
			repository.core().confirm(id, CertificationScope.LATEST_VERSIONS);
		}
	}

	/**
	 * Verifies that confirmation refuses the proposal format that predates explicit regression scenario citations.
	 *
	 * @throws IOException if the proposal file cannot be written or its obsolete format is refused
	 * @throws CommandRefusedException if the project cannot start a campaign
	 * @throws InterruptedException if the session wait is interrupted
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*version 3 proposal file.*")
	public void confirmRefusesUncitedProposalFormat() throws IOException, CommandRefusedException, InterruptedException
	{
		// Requirement: R-I3
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(_ ->
			{
			}));
			String id = repository.core().start(Path.of("CLAUDE.md")).status().campaignId();
			Path file = repository.directory(id).resolve("held_out/proposal.json");
			ObjectNode stored = (ObjectNode) JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
			stored.put("format_version", 3);
			Files.writeString(file, JSON.writeValueAsString(stored), StandardCharsets.UTF_8);
			repository.core().confirm(id, CertificationScope.LATEST_VERSIONS);
		}
	}

	/**
	 * Verifies that confirmation refuses the proposal format that predates the stored capability ranking.
	 *
	 * @throws IOException if the proposal file cannot be written or its obsolete format is refused
	 * @throws CommandRefusedException if the project cannot start a campaign
	 * @throws InterruptedException if the session wait is interrupted
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*version 5 proposal file.*")
	public void confirmRefusesUnrankedProposalFormat() throws IOException, CommandRefusedException,
		InterruptedException
	{
		// Requirement: R-E22
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(_ ->
			{
			}));
			String id = repository.core().start(Path.of("CLAUDE.md")).status().campaignId();
			Path file = repository.directory(id).resolve("held_out/proposal.json");
			ObjectNode stored = (ObjectNode) JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
			stored.put("format_version", 5);
			stored.remove("ranking");
			stored.remove("ranking_source");
			Files.writeString(file, JSON.writeValueAsString(stored), StandardCharsets.UTF_8);
			repository.core().confirm(id, CertificationScope.LATEST_VERSIONS);
		}
	}

	/**
	 * Verifies that confirmation refuses the proposal format that predates failure behaviors.
	 *
	 * @throws IOException if the proposal file cannot be written or its obsolete format is refused
	 * @throws CommandRefusedException if the project cannot start a campaign
	 * @throws InterruptedException if the session wait is interrupted
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*version 7 proposal file.*")
	public void confirmRefusesProposalWithoutBehaviors() throws IOException, CommandRefusedException,
		InterruptedException
	{
		// Requirement: R-C18
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(_ ->
			{
			}));
			String id = repository.core().start(Path.of("CLAUDE.md")).status().campaignId();
			Path file = repository.directory(id).resolve("held_out/proposal.json");
			ObjectNode stored = (ObjectNode) JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
			stored.put("format_version", 7);
			Files.writeString(file, JSON.writeValueAsString(stored), StandardCharsets.UTF_8);
			repository.core().confirm(id, CertificationScope.LATEST_VERSIONS);
		}
	}

	/**
	 * Executes a command and reads the JSON that the user sees.
	 *
	 * @param repository the test project and core
	 * @param arguments the command arguments
	 * @return the command output
	 */
	private static ObjectNode output(Repository repository, List<String> arguments)
	{
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8))
		{
			requireThat(new Commands(repository.core(), JSON, ZoneOffset.UTC).execute(arguments, out), "exitCode").
				isEqualTo(0);
		}
		return (ObjectNode) JSON.readTree(bytes.toString(StandardCharsets.UTF_8));
	}

	/**
	 * Verifies that the start command reports a local exclude addition supplied by the fork boundary, and that
	 * confirmation and resumption do not repeat the notice. Real Git's creation decision is covered by fork tests.
	 */
	@Test
	public void startReportsAddedExclude() throws Exception
	{
		// Requirement: R-W1
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(_ ->
			{
			}));
			repository.forks().addExcludeOnCreate();
			ObjectNode started = output(repository, List.of("start", "CLAUDE.md"));
			requireThat(started.get("message").asString(), "message").
				contains("Added /.cat/work/").contains("info/exclude");
			requireThat(started.get("proposal").isNull(), "missingProposal").isFalse();
			String id = started.get("campaign").asString();
			requireThat(output(repository, List.of("confirm", id, "latest_versions")).get("message").isNull(),
				"confirmNotice").isTrue();
			requireThat(output(repository, List.of("start", "CLAUDE.md")).get("message").isNull(), "resumeNotice").
				isTrue();
		}
	}

	/**
	 * Verifies that the start command emits no notice when the fork boundary reports that Git already ignores the
	 * work directory. Real Git's ignore check is covered by fork tests.
	 */
	@Test
	public void startDoesNotReportExistingExclude() throws Exception
	{
		// Requirement: R-W1
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(_ ->
			{
			}));
			requireThat(output(repository, List.of("start", "CLAUDE.md")).get("message").isNull(), "notice").isTrue();
		}
	}

	/**
	 * Verifies that reporting an added exclude preserves the deriver's invalid-proposal diagnostic.
	 */
	@Test
	public void startReportsExcludeAndDeriverFailure() throws Exception
	{
		// Requirement: R-W1
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> "invalid proposal");
			repository.forks().addExcludeOnCreate();
			ObjectNode started = output(repository, List.of("start", "CLAUDE.md"));
			requireThat(started.get("message").asString(), "message").
				contains("Added /.cat/work/").contains("info/exclude").contains("The deriver's proposal is invalid");
		}
	}

	/**
	 * Verifies that {@code confirm} refuses a proposal file of format version 2, which predates deterministic checks
	 * and the core-task requirement, and asks for a new derivation instead of misreading it.
	 */
	@Test
	public void confirmRefusesVersionTwoProposalFile() throws Exception
	{
		// Requirement: R-C9
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(_ ->
			{
			}));
			String id = repository.core().start(Path.of("CLAUDE.md")).status().campaignId();
			Path file = repository.directory(id).resolve("held_out/proposal.json");
			JsonMapper json = JsonMapper.builder().build();
			ObjectNode stored = (ObjectNode) json.readTree(Files.readString(file, StandardCharsets.UTF_8));
			stored.put("format_version", 2);
			Files.writeString(file, json.writeValueAsString(stored), StandardCharsets.UTF_8);
			IOException refusal = null;
			try
			{
				repository.core().confirm(id, CertificationScope.LATEST_VERSIONS);
			}
			catch (IOException e)
			{
				refusal = e;
			}
			requireThat(refusal, "refusal").isNotNull();
			requireThat(refusal.getMessage(), "message").contains("is a version 2 proposal file").
				contains("Run `start` again");
		}
	}

	/**
	 * Verifies that {@code start} reads the bindings from the staged prompt system: a requirement whose source binds a
	 * profile gives its category one cell under that profile, while an unbound category has a cell for each inventory
	 * profile. Only files that the harness can bind are read, and a binding file that binds nothing is ignored. The
	 * deriver runs under the role profile, and the proposal shows the inventory and the cells.
	 */
	@Test
	public void startComputesCellsFromStagedBindings() throws Exception
	{
		// Requirement: R-C10
		// Requirement: R-E17
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(output ->
			{
				ObjectNode safety = (ObjectNode) output.get("requirements").get(1);
				safety.put("source", "agents/safety.agent");
			}), Set.of(Path.of("CLAUDE.md"), Path.of("agents/safety.agent"), Path.of("agents/unused.agent"),
				Path.of("agents/empty.agent")));
			TrialProfile bound = new TrialProfile("bound-model", "low");
			Files.writeString(repository.root().resolve("CLAUDE.md"), "Be brief.\nmodel=not-a-binding-file\n");
			Files.createDirectory(repository.root().resolve("agents"));
			Files.writeString(repository.root().resolve("agents/empty.agent"), "Binds nothing.\n");
			Files.writeString(repository.root().resolve("agents/safety.agent"), FakeProfileInventory.content(bound));
			Files.writeString(repository.root().resolve("agents/unused.agent"),
				FakeProfileInventory.content(new TrialProfile("unused-model", "")));

			CommandResult result = repository.core().start(Path.of("CLAUDE.md"));
			requireThat(repository.sessions().requests.getFirst().profile(), "deriverProfile").
				isEqualTo(FakeProfileInventory.ROLE);
			requireThat(result.proposal(), "proposal").
				contains("Trial profile inventory (" + FakeProfileInventory.INVENTORY.source() + ")").
				contains("agents/safety.agent binds bound-model/low (outside the inventory, used as written)").
				contains("category safety (1 cell, named by the category): bound-model/low").
				contains("category formatting (1 cell, named by the category): test-model/high").
				doesNotContain("unused-model").doesNotContain("not-a-binding-file").doesNotContain("empty.agent");

			String id = result.status().campaignId();
			repository.core().confirm(id, CertificationScope.LATEST_VERSIONS);
			Campaign campaign = repository.load(id);
			requireThat(campaign.contract().findCell("safety").orElseThrow().profile(), "safety").isEqualTo(bound);
			requireThat(campaign.contract().judge().profile(), "judge").isEqualTo(FakeProfileInventory.ROLE);
		}
	}

	/**
	 * Starts a campaign whose requirement's source is an agent file that binds a profile.
	 *
	 * @param rejected the model that the harness rejects
	 * @param bound the profile that the agent file binds
	 * @param observe receives the repository and the result of {@code start}
	 * @throws Exception if the campaign cannot be started
	 */
	private static void startWithBoundAgent(String rejected, TrialProfile bound,
		java.util.function.BiConsumer<Repository, CommandResult> observe) throws Exception
	{
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(output ->
			{
				ObjectNode safety = (ObjectNode) output.get("requirements").get(1);
				safety.put("source", "agents/safety.agent");
			}), Set.of(Path.of("CLAUDE.md"), Path.of("agents/safety.agent")));
			repository.scope().profiles().rejectModel(rejected);
			Files.createDirectory(repository.root().resolve("agents"));
			Files.writeString(repository.root().resolve("agents/safety.agent"), FakeProfileInventory.content(bound));

			observe.accept(repository, repository.core().start(Path.of("CLAUDE.md")));
		}
	}

	/**
	 * Verifies that {@code start} starts no deriver session when a staged binding file binds a model that the harness
	 * does not support, and reports the file and the model with the instruction to change the binding and run
	 * {@code start} again.
	 */
	@Test
	public void startReportsUnsupportedModelBinding() throws Exception
	{
		// Requirement: R-D18
		startWithBoundAgent("gpt-5.6-luna", new TrialProfile("gpt-5.6-luna", "low"), (repository, result) ->
		{
			requireThat(repository.sessions().requests, "requests").isEmpty();
			requireThat(result.proposal(), "proposal").isNull();
			requireThat(result.message(), "message").contains("agents/safety.agent").contains("gpt-5.6-luna").
				contains("Change the binding").contains("`start` again");
		});
	}

	/**
	 * Verifies that {@code start} derives when a staged binding file binds a model that the harness supports.
	 */
	@Test
	public void startDerivesWithSupportedModelBinding() throws Exception
	{
		// Requirement: R-D18
		startWithBoundAgent("gpt-5.6-luna", new TrialProfile("gpt-6-luna", "low"), (repository, result) ->
		{
			requireThat(repository.sessions().requests.size(), "requests").isEqualTo(1);
			requireThat(result.proposal(), "proposal").isNotNull().contains("agents/safety.agent binds gpt-6-luna/low");
		});
	}

	/**
	 * Verifies that {@code start} derives when a staged binding file binds only an effort, even though the harness
	 * rejects a model.
	 */
	@Test
	public void startDerivesWithEffortOnlyBinding() throws Exception
	{
		// Requirement: R-D18
		startWithBoundAgent("gpt-5.6-luna", new TrialProfile("", "low"), (repository, result) ->
		{
			requireThat(repository.sessions().requests.size(), "requests").isEqualTo(1);
			requireThat(result.proposal(), "proposal").isNotNull();
		});
	}

	/**
	 * Verifies that {@code start} refuses a base worktree that the fork seam reports as dirty, before it stores a
	 * campaign or starts a session.
	 */
	@Test
	public void startRefusesDirtyBase() throws Exception
	{
		// Requirement: R-W2
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(_ ->
			{
			}));
			repository.forks().makeDirty();
			CommandRefusedException refusal = null;
			try
			{
				repository.core().start(Path.of("CLAUDE.md"));
			}
			catch (CommandRefusedException e)
			{
				refusal = e;
			}
			requireThat(refusal, "refusal").isNotNull();
			requireThat(refusal.getMessage(), "message").contains("changes");
			requireThat(repository.sessions().roles(), "roles").isEmpty();
			requireThat(repository.scope().store().listHeaders(), "headers").isEmpty();
		}
	}

	/**
	 * Returns the conflicts used to exercise sequential resolution choices.
	 *
	 * @return the conflicts
	 */
	private static List<AuthorConflict> reportedConflicts()
	{
		return List.of(new AuthorConflict(List.of("f1"), List.of("r1", "r2"),
			List.of(new AuthorConflict.Quotation(Path.of("CLAUDE.md"), "Be brief.")),
			List.of("Exempt explanations.", "Drop brevity."), 1, "Keeps brevity elsewhere."),
			new AuthorConflict(List.of("f2"), List.of("r3", "r4"),
				List.of(new AuthorConflict.Quotation(Path.of("rules.md"), "Always explain.")),
				List.of("Exempt greetings.", "Drop explanations."), 2, "Keeps the core task."));
	}

	/**
	 * Installs an author-conflict decision as the current persisted pause.
	 *
	 * @param repository the owned campaign repository
	 * @param id the campaign identifier
	 * @throws IOException if the store fails
	 * @throws CommandRefusedException if the owned project cannot be opened
	 */
	private static void pauseConflicts(Repository repository, String id)
		throws IOException, CommandRefusedException
	{
		Campaign c = repository.load(id);
		repository.scope().store().replaceSnapshot(new Campaign(c.header(), c.ceiling(), c.lineages(), c.identity(),
			c.incumbent(), c.candidates(), c.authorAttempts(), c.development(), c.baseline(),
			c.attempts(), c.trials(), c.sequences(), c.evidenceRevision(), c.slowStart(), c.throttle(),
			List.of(new PendingDecision.ConflictFound(reportedConflicts(), List.of())),
			c.revisionRequested(), c.outcome(), c.delivery(), c.optimization(), c.answeredWarnings()));
	}

	/**
	 * Verifies that numbered choices advance conflicts and derive one proposal containing all selected feedback.
	 *
	 * @throws IOException if the owned repository or store fails
	 * @throws CommandRefusedException if an authorized command is refused
	 * @throws InterruptedException if derivation is interrupted
	 */
	@Test
	public void choosesConflictsBeforeRevision() throws IOException, CommandRefusedException, InterruptedException
	{
		// Requirement: R-D13
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Repository repository = repository(cleanup, _ -> derivation(_ ->
			{
			}));
			String id = repository.core().start(Path.of("CLAUDE.md")).status().campaignId();
			repository.core().confirm(id, CertificationScope.LATEST_VERSIONS);
			pauseConflicts(repository, id);
			ObjectNode first = output(repository, List.of("choose", id, "resolution_2"));
			requireThat(first.get("proposal").isNull(), "proposal").isTrue();
			requireThat(first.get("choices").get(0).get("label").asString(), "nextResolution").
				isEqualTo("Exempt greetings.");
			requireThat(repository.load(id).revisionRequested(), "revisionRequested").isFalse();
			ObjectNode last = output(repository, List.of("choose", id, "resolution_1"));
			requireThat(last.get("proposal").asString(), "proposal").contains("## Requirements");
			requireThat(repository.load(id).revisionRequested(), "revisionRequested").isTrue();
			requireThat(repository.load(id).pending(), "pending").isNull();
			requireThat(repository.sessions().roles().stream().filter(role -> role == SessionRole.DERIVER).count(),
				"deriverCalls").isEqualTo(2L);
			SessionRequest request = repository.sessions().requests.getLast();
			requireThat(request.turns().getFirst(), "feedback").contains("r1, r2").contains("r3, r4").
				contains("CLAUDE.md: Be brief.").contains("rules.md: Always explain.").contains("Drop brevity.").
				contains("Exempt greetings.");
			repository.core().confirm(id, CertificationScope.LATEST_VERSIONS);
			requireThat(repository.load(id).lineages().size(), "lineages").isEqualTo(2);
		}
	}

	/**
	 * Verifies that feedback supplied during a conflict pause derives a proposal and clears the pause.
	 *
	 * @throws IOException if the owned repository or store fails
	 * @throws CommandRefusedException if an authorized command is refused
	 * @throws InterruptedException if derivation is interrupted
	 */
	@Test
	public void revisesPausedConflicts() throws IOException, CommandRefusedException, InterruptedException
	{
		// Requirement: R-D13
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Repository repository = repository(cleanup, _ -> derivation(_ ->
			{
			}));
			String id = repository.core().start(Path.of("CLAUDE.md")).status().campaignId();
			repository.core().confirm(id, CertificationScope.LATEST_VERSIONS);
			pauseConflicts(repository, id);
			CommandResult revised = repository.core().revise(id, "Exempt detailed safety explanations.");
			requireThat(revised.proposal(), "proposal").contains("## Requirements");
			requireThat(repository.load(id).pending(), "pending").isNull();
			requireThat(repository.load(id).revisionRequested(), "revisionRequested").isTrue();
			requireThat(repository.sessions().requests.getLast().turns().getFirst(), "feedback").
				contains("Exempt detailed safety explanations.");
		}
	}

	/**
	 * Verifies that a resolution outside a conflict pause is refused, and an out-of-range choice preserves the pause.
	 *
	 * @throws IOException if the owned repository or store fails
	 * @throws CommandRefusedException if an authorized setup command is refused
	 * @throws InterruptedException if derivation is interrupted
	 */
	@Test
	public void refusesUnlistedResolution() throws IOException, CommandRefusedException, InterruptedException
	{
		// Requirement: R-D13
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Repository repository = repository(cleanup, _ -> derivation(_ ->
			{
			}));
			String id = repository.core().start(Path.of("CLAUDE.md")).status().campaignId();
			repository.core().confirm(id, CertificationScope.LATEST_VERSIONS);
			try
			{
				repository.core().resolveConflict(id, 1);
				requireThat(false, "refusedOutsidePause").isTrue();
			}
			catch (CommandRefusedException e)
			{
				requireThat(e.getMessage(), "message").contains("does not offer a conflict resolution now");
			}
			pauseConflicts(repository, id);
			String before = Files.readString(repository.directory(id).resolve("held_out/campaign.json"));
			try
			{
				repository.core().resolveConflict(id, 3);
				requireThat(false, "refused").isTrue();
			}
			catch (CommandRefusedException e)
			{
				requireThat(e.getMessage(), "message").contains("does not offer resolution_3");
			}
			requireThat(Files.readString(repository.directory(id).resolve("held_out/campaign.json")), "unchanged").
				isEqualTo(before);
		}
	}

	/**
	 * Verifies that {@code revise} regenerates the complete proposal from the feedback and stores it.
	 */
	@Test
	public void reviseRegeneratesProposal() throws Exception
	{
		// Requirement: R-I2
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, count -> derivation(output ->
			{
				ArrayNode limitations = (ArrayNode) output.get("limitations");
				limitations.add("Revision number " + count + " limit.");
			}));
			String id = repository.core().start(Path.of("CLAUDE.md")).status().campaignId();
			CommandResult revised = repository.core().revise(id, "cover the formal tone");
			requireThat(repository.sessions().roles(), "roles").isEqualTo(List.of(SessionRole.DERIVER,
				SessionRole.DERIVER));
			requireThat(repository.sessions().requests.get(1).turns().getFirst(), "turn").
				contains("cover the formal tone");
			requireThat(revised.proposal(), "proposal").contains("Revision number 1 limit.");
			requireThat(Files.readString(repository.directory(id).resolve("held_out/proposal.json")), "stored").
				contains("Revision number 1 limit.");
		}
	}

	/**
	 * Verifies that {@code confirm} without a certification scope is refused with every choice named and confirms
	 * nothing, that a described scope without described cells is refused, and that the chosen scope is frozen with
	 * the contract.
	 */
	@Test
	public void confirmRequiresCertificationScope() throws Exception
	{
		// Requirement: R-C15
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(_ ->
			{
			}));
			String id = repository.core().start(Path.of("CLAUDE.md")).status().campaignId();
			String missing = refusal(() -> repository.core().confirm(id, null));
			requireThat(missing, "missing").contains("weakest_cell").contains("latest_versions").contains("described");
			requireThat(repository.load(id).isConfirmed(), "confirmedWithoutScope").isFalse();

			String described = refusal(() -> repository.core().confirm(id, CertificationScope.DESCRIBED));
			requireThat(described, "described").contains("revision feedback");
			requireThat(repository.load(id).isConfirmed(), "confirmedWithoutDescribedCells").isFalse();

			repository.core().confirm(id, CertificationScope.WEAKEST_CELL);
			requireThat(repository.load(id).contract().profiles().scope(), "scope").
				isEqualTo(CertificationScope.WEAKEST_CELL);
		}
	}

	/**
	 * Returns the message of a refused call.
	 *
	 * @param call the call
	 * @return the refusal's message
	 * @throws Exception if the call fails other than by a refusal
	 * @throws AssertionError if the call is not refused
	 */
	private static String refusal(ThrowingCall call) throws Exception
	{
		try
		{
			call.run();
		}
		catch (CommandRefusedException e)
		{
			return e.getMessage();
		}
		throw new AssertionError("The call was not refused");
	}

	/**
	 * Verifies that {@code confirm} freezes the contract, records the fixture under an ignored path in the fork, and
	 * that the fixture is staged with the incumbent afterwards.
	 */
	@Test
	public void confirmFreezesContractAndRecordsFixtures() throws Exception
	{
		// Requirement: R-W2
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(_ ->
			{
			}));
			String id = repository.core().start(Path.of("CLAUDE.md")).status().campaignId();
			repository.core().confirm(id, CertificationScope.LATEST_VERSIONS);
			Campaign campaign = repository.load(id);
			requireThat(campaign.isConfirmed(), "confirmed").isTrue();
			requireThat(campaign.contract().fixtures().ignoredPaths(), "fixtures").isEqualTo(List.of(".env.example"));
			ForkView fork = repository.forks().open(repository.root(), repository.directory(id));
			Overlay incumbent = new FileCandidateOverlays(repository.directory(id), fork.inventory(),
				repository.scope().json()).require(campaign.incumbent().snapshot());
			fork.workspace().stage(incumbent);
			requireThat(incumbent.snapshot().entries().keySet(), "stagedFiles").contains(Path.of(".env.example")).
				contains(Path.of("CLAUDE.md"));
		}
	}

	/**
	 * Verifies that {@code confirm} records derived sources that Git ignores or that lie outside the project, so
	 * trials never read them live.
	 */
	@Test
	public void confirmRecordsDerivedSources() throws Exception
	{
		// Requirement: R-W2
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Path outside = scope.add(Files.createTempDirectory("optimizer-shared")).toRealPath().resolve("shared.md");
			Files.writeString(outside, "Shared rules.\n");
			Repository repository = repository(scope, _ -> derivation(output ->
			{
				ArrayNode sources = (ArrayNode) output.get("derived_sources");
				for (String path : List.of("extra.local.md", outside.toString()))
				{
					ObjectNode source = sources.addObject();
					source.put("path", path);
					source.put("rule", "The harness loads the file by convention.");
				}
			}));
			String id = repository.core().start(Path.of("CLAUDE.md")).status().campaignId();
			repository.core().confirm(id, CertificationScope.LATEST_VERSIONS);
			ForkView fork = repository.forks().open(repository.root(), repository.directory(id));
			requireThat(fork.inventory().entries().keySet(), "held").contains(Path.of("extra.local.md")).
				contains(outside).contains(Path.of(".env.example"));
			Campaign campaign = repository.load(id);
			Overlay incumbent = new FileCandidateOverlays(repository.directory(id), fork.inventory(),
				repository.scope().json()).require(campaign.incumbent().snapshot());
			requireThat(incumbent.snapshot().entries().keySet(), "stagedFiles").contains(Path.of("extra.local.md")).
				contains(outside);
		}
	}

	/**
	 * Verifies that {@code confirm} refuses a fixture that does not exist, naming it, and writes nothing.
	 */
	@Test
	public void confirmRefusesMissingFixture() throws Exception
	{
		// Requirement: R-W2
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(output ->
			{
				ArrayNode paths = (ArrayNode) output.get("fixtures").get("ignored_paths");
				paths.add("missing/fixture.txt");
			}));
			String id = repository.core().start(Path.of("CLAUDE.md")).status().campaignId();
			Path snapshot = repository.root().resolve(".cat/work/optimizer").resolve(id).resolve("held_out/campaign.json");
			String before = Files.readString(snapshot);
			CommandRefusedException refusal = null;
			try
			{
				repository.core().confirm(id, CertificationScope.LATEST_VERSIONS);
			}
			catch (CommandRefusedException e)
			{
				refusal = e;
			}
			requireThat(refusal, "refusal").isNotNull();
			requireThat(refusal.getMessage(), "message").contains("missing/fixture.txt");
			requireThat(Files.readString(snapshot), "campaignJson").isEqualTo(before);
		}
	}

	/**
	 * Status in a fresh command scope combines reported trial and judge usage across slices.
	 *
	 * @throws IOException if the fixture cannot be used
	 * @throws CommandRefusedException if a command is refused
	 * @throws InterruptedException if a command is interrupted
	 */
	// Requirement: R-E12
	@Test
	public void statusCombinesSliceUsage() throws IOException, CommandRefusedException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Repository repository = repository(cleanup, _ -> derivation(output -> output.putArray("scope_conditions")));
			TokenUsage trial = new TokenUsage(100, 20, 30, 10);
			TokenUsage judge = new TokenUsage(200, 40, 60, 20);
			repository.sessions().usage.put(SessionRole.TRIAL, List.of(trial));
			repository.sessions().usage.put(SessionRole.JUDGE, List.of(judge));
			String id = repository.core().start(Path.of("CLAUDE.md")).status().campaignId();
			repository.core().confirm(id, CertificationScope.LATEST_VERSIONS);
			CommandResult first = repository.core().run(id);
			try (TestOptimizerCommandScope<FakeSessionPort> fresh = TestOptimizerCommandScope.of(Harness.CLAUDE,
				repository.root()))
			{
				TokenStatistics reported = new OptimizerCore(fresh).status(id).status().tokens();
				requireThat(reported, "reloadedUsage").isEqualTo(first.status().tokens());
				requireThat(reported.roles().get(SessionRole.TRIAL).input().median(), "trialMedian").isEqualTo(100L);
				requireThat(reported.roles().get(SessionRole.JUDGE).cachedInput().p90(),
					"judgeCachedP90").isEqualTo(40L);
				CommandResult second = repository.core().run(id);
				TokenStatistics combined = new OptimizerCore(fresh).status(id).status().tokens();
				requireThat(combined, "combinedUsage").isEqualTo(second.status().tokens());
				for (SessionRole role : List.of(SessionRole.TRIAL, SessionRole.JUDGE))
				{
					int count = (int) repository.sessions().roles().stream().filter(value -> value == role).count();
					requireThat(combined.roles().get(role).sessions(), "sessions").isEqualTo(count);
					requireThat(count,
						"combinedSessions").isGreaterThan(first.status().tokens().roles().get(role).sessions());
				}
				requireThat(combined.roles().containsKey(SessionRole.DERIVER), "missingDeriverUsage").isFalse();
			}
		}
	}

	/**
	 * Every derivation contributes reported usage, including invalid proposals and revisions.
	 *
	 * @throws IOException if the fixture cannot be used
	 * @throws CommandRefusedException if a command is refused
	 * @throws InterruptedException if a command is interrupted
	 */
	// Requirement: R-E12
	@Test
	public void statusRetainsDeriverUsage() throws IOException, CommandRefusedException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Repository repository = repository(cleanup, number ->
			{
				if (number == 0)
					return "invalid";
				return derivation(_ -> {});
			});
			List<TokenUsage> usage = List.of(new TokenUsage(100, 20, 30, 10), new TokenUsage(300, 60, 90, 30));
			repository.sessions().usage.put(SessionRole.DERIVER, usage);
			String id = repository.core().start(Path.of("CLAUDE.md")).status().campaignId();
			repository.core().start(Path.of("CLAUDE.md"));
			repository.core().revise(id, "Cover safety.");
			try (TestOptimizerCommandScope<FakeSessionPort> fresh = TestOptimizerCommandScope.of(Harness.CLAUDE,
				repository.root()))
			{
				TokenStatistics.RoleUsage reported = new OptimizerCore(fresh).status(id).status().tokens().roles().
					get(SessionRole.DERIVER);
				requireThat(reported, "deriverUsage").isEqualTo(TokenStatistics.of(Map.of(SessionRole.DERIVER,
					List.of(usage.get(0), usage.get(1), usage.get(0)))).roles().get(SessionRole.DERIVER));
			}
		}
	}

	/**
	 * Verifies that {@code run} executes one slice with scripted trial and judge sessions and reports the status.
	 */
	@Test
	public void runExecutesOneSlice() throws Exception
	{
		// Requirement: R-I2
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(output -> output.putArray("scope_conditions")));
			String id = repository.core().start(Path.of("CLAUDE.md")).status().campaignId();
			repository.core().confirm(id, CertificationScope.LATEST_VERSIONS);
			CommandResult result = repository.core().run(id);
			requireThat(result.status().status().toString(), "status").contains("RUNNING");
			requireThat(result.status().campaignId(), "campaignId").isEqualTo(id);
			requireThat(repository.sessions().roles(), "roles").contains(SessionRole.TRIAL).
				contains(SessionRole.JUDGE);
			requireThat(repository.load(id).trials().size() + repository.load(id).evidenceRevision(), "progress").
				isGreaterThan(0L);
		}
	}

	/**
	 * Verifies that a command first completes a removal that an earlier command recorded but did not finish: the
	 * campaign's directory and optimization worktree are gone, so the command no longer finds the campaign.
	 */
	@Test
	public void commandCompletesInterruptedRemoval() throws Exception
	{
		// Requirement: R-W9
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(_ ->
			{
			}));
			String id = repository.core().start(Path.of("CLAUDE.md")).status().campaignId();
			Path directory = repository.directory(id);
			requireThat(repository.forks().exists(directory), "forkBefore").isTrue();
			FileCampaignStore.recordRemoval(repository.root(), id);

			CommandRefusedException refusal = null;
			try
			{
				repository.core().status(id);
			}
			catch (CommandRefusedException e)
			{
				refusal = e;
			}

			requireThat(refusal, "refusal").isNotNull();
			requireThat(Files.notExists(directory), "directoryIsAbsent").isTrue();
			requireThat(repository.forks().exists(directory), "forkAfter").isFalse();
		}
	}

	/**
	 * Writes the certification record of {@code CLAUDE.md} as a certified delivery would, for the first certification
	 * under the current parameters and the latest-versions scope. The record names the provider, harness version,
	 * session environment version and judge version that the test scope reports, except for any condition that
	 * {@code change} replaces, and the trial model {@code model-a}, which only a session observes.
	 *
	 * @param repository the test project
	 * @param promptText the text whose hash the record holds for {@code CLAUDE.md}
	 * @param change edits the recorded conditions
	 * @throws IOException if the record cannot be written
	 */
	private static void writeRecord(Repository repository, String promptText,
		java.util.function.Consumer<Map<MeasurementCondition, String>> change) throws IOException
	{
		Map<MeasurementCondition, String> values = new java.util.EnumMap<>(MeasurementCondition.class);
		values.put(MeasurementCondition.PROVIDER, Harness.CLAUDE.commandName());
		values.put(MeasurementCondition.HARNESS_VERSION, "1.0");
		values.put(MeasurementCondition.SESSION_ENVIRONMENT_VERSION,
			String.valueOf(FakeSessionPort.SESSION_ENVIRONMENT_VERSION));
		values.put(MeasurementCondition.JUDGE, String.valueOf(FakeSessionPort.JUDGE_VERSION));
		values.put(MeasurementCondition.MODEL, "model-a");
		change.accept(values);
		CertificationRecord record = new CertificationRecord(Path.of("CLAUDE.md"), 1,
			CertificationScope.LATEST_VERSIONS, CertificationParameters.current(1), List.of(),
			Map.of(Path.of("CLAUDE.md"), Sha256.of(promptText.getBytes(StandardCharsets.UTF_8))),
			new MeasurementIdentity(values));
		Path file = repository.root().resolve(CertificationRecord.location(record.target()));
		Files.createDirectories(file.getParent());
		Files.write(file, new CertificationRecordJson(JSON).write(record));
	}

	/**
	 * Verifies that {@code status} of a target that has no campaign but a certification record reports the record as
	 * one result: current while the prompt file and every condition that CAT knows are unchanged, with the trial
	 * model, which only a session observes, unchecked.
	 */
	@Test
	public void statusOfTargetReportsCurrentRecord() throws Exception
	{
		// Requirement: R-M6, R-W9
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(_ ->
			{
			}));
			writeRecord(repository, "Be brief.\n", _ ->
			{
			});

			ObjectNode status = output(repository, List.of("status", "CLAUDE.md"));

			requireThat(status.get("status").asString(), "status").isEqualTo("certified");
			requireThat(status.get("target").asString(), "target").isEqualTo("CLAUDE.md");
			requireThat(status.get("attempt").asInt(), "attempt").isEqualTo(1);
			requireThat(status.get("scope").asString(), "scope").isEqualTo("latest_versions");
			JsonNode applicability = status.get("applicability");
			requireThat(applicability.get("status").asString(), "applicability").isEqualTo("current");
			requireThat(applicability.get("prompt_content_changed").asBoolean(), "promptContentChanged").isFalse();
			requireThat(applicability.get("changed").isEmpty(), "changedIsEmpty").isTrue();
			requireThat(applicability.get("unchecked").get(0).asString(), "unchecked").isEqualTo("model");
			requireThat(applicability.get("unchecked").size(), "uncheckedSize").isEqualTo(1);
			requireThat(status.get("summary").asString(), "summary").isEqualTo("""
				Certification record of CLAUDE.md, scope latest_versions (every cell of each model family's latest \
				version)
				Attempt 1: certified
				  Not checked: trial model.""");
			requireThat(repository.sessions().roles(), "roles").isEmpty();
		}
	}

	/**
	 * Verifies that {@code status} of a target reports its certification record as historical, naming each change,
	 * when the prompt file's content or a recorded condition that CAT knows changed.
	 */
	@Test
	public void statusOfTargetReportsHistoricalRecord() throws Exception
	{
		// Requirement: R-M6, R-W9
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(_ ->
			{
			}));
			writeRecord(repository, "Be terse.\n", values -> values.put(MeasurementCondition.HARNESS_VERSION,
				"0.9"));

			ObjectNode status = output(repository, List.of("status", "CLAUDE.md"));

			JsonNode applicability = status.get("applicability");
			requireThat(applicability.get("status").asString(), "applicability").isEqualTo("historical");
			requireThat(applicability.get("prompt_content_changed").asBoolean(), "promptContentChanged").isTrue();
			requireThat(applicability.get("changed").get(0).asString(), "changed").isEqualTo("harness_version");
			requireThat(status.get("summary").asString(), "summary").contains(
				"Attempt 1: certified (historical; changed: prompt_content, harness_version)\n" +
				"  Not checked: trial model.");
		}
	}

	/**
	 * Verifies that {@code status} of a target that has a campaign reports the campaign, even when a certification
	 * record exists too.
	 */
	@Test
	public void statusOfTargetReportsItsCampaign() throws Exception
	{
		// Requirement: R-M6, R-W9
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(_ ->
			{
			}));
			writeRecord(repository, "Be brief.\n", _ ->
			{
			});
			String id = repository.core().start(Path.of("CLAUDE.md")).status().campaignId();

			ObjectNode status = output(repository, List.of("status", "CLAUDE.md"));

			requireThat(status.get("campaign").asString(), "campaign").isEqualTo(id);
			requireThat(output(repository, List.of("status", id)).get("campaign").asString(), "byId").
				isEqualTo(id);
		}
	}

	/**
	 * Verifies that a target's finished campaign does not hide a certification record: {@code status} of the target
	 * reports the record, and {@code status} of the campaign's identifier still reports the campaign.
	 */
	@Test
	public void recordOutranksFinishedCampaign() throws Exception
	{
		// Requirement: R-M6
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(_ ->
			{
			}));
			String id = repository.core().start(Path.of("CLAUDE.md")).status().campaignId();
			Campaign finished = new Builder().header(repository.load(id).header().withLifecycle(Lifecycle.FINISHED)).
				confirmed().outcome(Outcome.BELOW_MINIMUM).build();
			repository.scope().store().replaceSnapshot(finished);
			writeRecord(repository, "Be brief.\n", _ ->
			{
			});

			ObjectNode status = output(repository, List.of("status", "CLAUDE.md"));

			requireThat(status.get("status").asString(), "status").isEqualTo("certified");
			requireThat(status.get("campaign").isNull(), "campaignIsNull").isTrue();
			requireThat(output(repository, List.of("status", id)).get("campaign").asString(), "byId").
				isEqualTo(id);
		}
	}

	/**
	 * Verifies that {@code status} from a harness other than the one that certified the target leaves the
	 * harness-specific conditions unchecked instead of changed, so the certification stays current.
	 */
	@Test
	public void otherHarnessLeavesConditionsUnchecked() throws Exception
	{
		// Requirement: R-M6
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(_ ->
			{
			}));
			writeRecord(repository, "Be brief.\n", values -> values.put(MeasurementCondition.HARNESS_VERSION, "0.9"));

			CommandResult result;
			try (TestOptimizerCommandScope<FakeSessionPort> codex = TestOptimizerCommandScope.of(Harness.CODEX,
				repository.root()))
			{
				result = new OptimizerCore(codex).status("CLAUDE.md");
			}

			Applicability applicability = result.certification().applicability();
			requireThat(applicability.status(), "applicability").isEqualTo(Applicability.Status.CURRENT);
			requireThat(applicability.changed(), "changed").isEmpty();
			requireThat(applicability.unchecked(), "unchecked").isEqualTo(List.of(MeasurementCondition.JUDGE,
				MeasurementCondition.MODEL, MeasurementCondition.PROVIDER, MeasurementCondition.HARNESS_VERSION,
				MeasurementCondition.SESSION_ENVIRONMENT_VERSION));
			requireThat(result.summary(ZoneOffset.UTC), "summary").endsWith("""
				Attempt 1: certified
				  Not checked: judge version, trial model, model provider, harness version, session environment \
				version.""");
		}
	}

	/**
	 * Verifies that {@code status} of a target that has neither a campaign nor a certification record is refused.
	 */
	@Test
	public void statusOfUnknownTargetIsRefused() throws Exception
	{
		// Requirement: R-M6, R-W9
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(_ ->
			{
			}));

			String message = refusal(() -> repository.core().status("CLAUDE.md"));

			requireThat(message, "message").isEqualTo("No campaign or certification record exists for CLAUDE.md.");
		}
	}

	/**
	 * Verifies that a new campaign's proposal shows the incumbent's certification from the target's record, compared
	 * with what CAT knows when it presents the proposal.
	 */
	@Test
	public void proposalShowsIncumbentCertification() throws Exception
	{
		// Requirement: R-M6, R-W9
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(_ ->
			{
			}));
			writeRecord(repository, "Be brief.\n", values -> values.put(MeasurementCondition.JUDGE, "0"));

			CommandResult started = repository.core().start(Path.of("CLAUDE.md"));

			requireThat(started.proposal(), "proposal").contains("""

				## Incumbent certification

				Certification record of CLAUDE.md, scope latest_versions (every cell of each model family's latest \
				version)
				Attempt 1: certified (historical; changed: judge)
				  Not checked: trial model.
				""");
		}
	}

	/**
	 * Verifies that a removal that keeps failing does not fail other commands: a command for another target
	 * succeeds and warns with the path and the cause, a refused command carries the same warning, and the removal
	 * stays recorded for the next command.
	 */
	@Test
	public void failingRemovalWarnsOtherCommands() throws Exception
	{
		// Requirement: R-W9
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(_ ->
			{
			}), Set.of(Path.of("CLAUDE.md"), Path.of("AGENTS.md")));
			Files.writeString(repository.root().resolve("AGENTS.md"), "Be kind.\n");
			String id = repository.core().start(Path.of("CLAUDE.md")).status().campaignId();
			FileCampaignStore.recordRemoval(repository.root(), id);
			Path worktree = repository.directory(id).resolve("worktree");
			repository.forks().denyRemoval();
			String warning = "Could not remove the files of campaign " + id + ": " + worktree +
				": Permission denied. The next command retries the removal.";

			ObjectNode started = output(repository, List.of("start", "AGENTS.md"));

			requireThat(started.get("proposal").isNull(), "proposalIsNull").isFalse();
			requireThat(started.get("summary").asString(), "summary").endsWith("\n" + warning);
			// The host shows the proposal instead of the summary, so the warning is in the message too
			requireThat(started.get("message").asString(), "message").endsWith(warning);
			String message = refusal(() -> repository.core().cancel("no-such-campaign"));
			requireThat(message, "message").isEqualTo("No campaign has the identifier no-such-campaign.\n" +
				warning);
			requireThat(FileCampaignStore.removals(repository.root()), "removals").isEqualTo(List.of(id));
		}
	}

	/**
	 * Verifies that a command that removes its campaign's files says so in its summary: retaining the incumbent after
	 * a certified attempt ends the campaign and removes its files.
	 */
	@Test
	public void removalIsReported() throws Exception
	{
		// Requirement: R-W9
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(_ ->
			{
			}));
			String id = repository.core().start(Path.of("CLAUDE.md")).status().campaignId();
			Campaign started = repository.load(id);
			Campaign built = new Builder().header(started.header()).confirmed().working(1).
				certificationAttempt(1).cell("formatting", 100, 0).cell("safety", 100, 0).outcome(Outcome.CERTIFIED).
				campaign().outcome(Outcome.CERTIFIED).build();
			// The optimization worktree holds the started campaign's working candidate, whose overlay is saved
			Campaign tradeOff = new Campaign(built.header(), built.ceiling(), built.lineages(), built.identity(),
				started.incumbent(), built.candidates(), built.authorAttempts(), started.development(), built.baseline(),
				built.attempts(), built.trials(), built.sequences(), built.evidenceRevision(), built.slowStart(),
				built.throttle(), List.of(new PendingDecision.RegressionTradeOff(1)),
				built.revisionRequested(), built.outcome(), built.delivery(), built.optimization(),
				built.answeredWarnings());
			repository.scope().store().replaceSnapshot(tradeOff);

			ObjectNode chosen = output(repository, List.of("choose", id, "retain_incumbent"));

			requireThat(chosen.get("summary").asString(), "summary").
				endsWith("\nRemoved the campaign's files from .cat/work/.");
			requireThat(Files.notExists(repository.directory(id)), "directoryIsAbsent").isTrue();
		}
	}

	/**
	 * Verifies that {@code cancel} and {@code choose} reach the campaign's driver: a cancel is applied, and a choice
	 * the campaign does not wait for is refused by the driver.
	 */
	@Test
	public void chooseAndCancelReachTheDriver() throws Exception
	{
		// Requirement: R-I2
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(_ ->
			{
			}));
			String id = repository.core().start(Path.of("CLAUDE.md")).status().campaignId();
			repository.core().confirm(id, CertificationScope.LATEST_VERSIONS);
			CommandResult cancelled = repository.core().cancel(id);
			requireThat(cancelled.status().lifecycle(), "lifecycle").isEqualTo(Lifecycle.CANCELLED);
			requireThat(repository.load(id).lifecycle(), "stored").isEqualTo(Lifecycle.CANCELLED);
			CommandRefusedException refusal = null;
			try
			{
				repository.core().choose(id, Choice.STOP, 0, 0);
			}
			catch (CommandRefusedException e)
			{
				refusal = e;
			}
			requireThat(refusal, "refusal").isNotNull();
			requireThat(refusal.getMessage(), "message").contains("does not accept this command now");
		}
	}

	/**
	 * Verifies that {@code choose use_authorized_source} answers a pending warning about an edit to an external file,
	 * which is read-only, and records the warning as answered so that a later report of it asks the user nothing.
	 *
	 * @throws Exception if the owned repository or store fails
	 */
	@Test
	public void useAuthorizedSourceAnswersTheWarning() throws Exception
	{
		// Requirement: R-W4
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(_ ->
			{
			}));
			String id = repository.core().start(Path.of("CLAUDE.md")).status().campaignId();
			repository.core().confirm(id, CertificationScope.LATEST_VERSIONS);
			Path target = Path.of("/external/shared.md");
			AuthorityWarning warning = new AuthorityWarning("R1", Path.of("shared.md"), target,
				Limit.EXTERNAL_TARGET_READ_ONLY, List.of(AuthorityWarning.Choice.USE_AUTHORIZED_SOURCE,
				AuthorityWarning.Choice.STOP_CAMPAIGN, AuthorityWarning.Choice.REVISE_CAMPAIGN));
			// The scripted deriver yields no supported output, which leaves a pending decision about it; clear the
			// pending decisions so that the authority warning becomes the decision asked now
			Campaign c = repository.load(id);
			Campaign confirmed = new Campaign(c.header(), c.ceiling(), c.lineages(), c.identity(), c.incumbent(),
				c.candidates(), c.authorAttempts(), c.development(), c.baseline(), c.attempts(),
				c.trials(), c.sequences(), c.evidenceRevision(), c.slowStart(), c.throttle(),
				List.of(), c.revisionRequested(), c.outcome(), c.delivery(), c.optimization(),
				c.answeredWarnings());
			Campaign warned = Transitions.apply(confirmed, new CampaignEvent.WarnAuthority(List.of(warning)));
			repository.scope().store().replaceSnapshot(warned);
			requireThat(warned.choices(), "choices").isEqualTo(List.of(Choice.USE_AUTHORIZED_SOURCE, Choice.STOP));

			CommandResult result = repository.core().choose(id, Choice.USE_AUTHORIZED_SOURCE, 0, 0);
			requireThat(result.status().authorityWarnings(), "warnings").isEmpty();
			Campaign stored = repository.load(id);
			requireThat(stored.pending(), "pending").isNull();
			requireThat(stored.answeredWarnings(), "answeredWarnings").isEqualTo(List.of(warning));
		}
	}

	/**
	 * Verifies that conflicts refuse confirmation without changing the campaign, and feedback can resolve them.
	 *
	 * @throws IOException if the owned repository cannot be read or written
	 * @throws CommandRefusedException if a command other than the expected refusal is refused
	 * @throws InterruptedException if derivation is interrupted
	 */
	@Test
	public void conflictRefusalAndRevision() throws IOException, CommandRefusedException, InterruptedException
	{
		// Requirement: R-C13
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, count -> derivation(output ->
			{
				ArrayNode conflicts = output.putArray("conflicts");
				if (count == 0)
				{
					ObjectNode conflict = conflicts.addObject();
					conflict.putArray("requirement_ids").add("r1").add("r2");
					conflict.put("evidence", "CLAUDE.md: be brief; give a full explanation.");
					conflict.putArray("resolutions").add("Exempt explanations.").add("Drop brevity.");
					conflict.put("recommended", 1);
					conflict.put("reason", "Keeps the most intent.");
				}
			}));
			String id = repository.core().start(Path.of("CLAUDE.md")).status().campaignId();
			Path snapshot = repository.directory(id).resolve("held_out/campaign.json");
			String before = Files.readString(snapshot);
			try
			{
				repository.core().confirm(id, CertificationScope.LATEST_VERSIONS);
				requireThat(false, "refused").isTrue();
			}
			catch (CommandRefusedException e)
			{
				requireThat(e.getMessage(), "message").isEqualTo("Confirmation is refused because the proposal " +
					"lists conflicting requirements: `r1` and `r2`. Run revise with feedback that resolves each conflict.");
			}
			requireThat(Files.readString(snapshot), "campaignJson").isEqualTo(before);
			repository.core().revise(id, "Exempt full explanations from the brevity requirement.");
			repository.core().confirm(id, CertificationScope.LATEST_VERSIONS);
			requireThat(repository.load(id).isConfirmed(), "confirmed").isTrue();
		}
	}

	/**
	 * Supplies conflict-only and combined link/conflict blockers.
	 *
	 * @return whether to add a broken link
	 */
	@DataProvider
	public Object[][] confirmationBlockers()
	{
		return new Object[][] {{false}, {true}};
	}

	/**
	 * Verifies exact formatting of several conflict groups, including three identifiers, with links listed first.
	 *
	 * @param brokenLink whether a broken symbolic link also blocks confirmation
	 * @throws IOException if the owned repository cannot be read or written
	 * @throws CommandRefusedException for the expected confirmation refusal
	 * @throws InterruptedException if derivation is interrupted
	 */
	@Test(dataProvider = "confirmationBlockers", expectedExceptions = CommandRefusedException.class,
		expectedExceptionsMessageRegExp = ".*Confirmation is refused because the proposal lists conflicting " +
			"requirements: `r1`, `r2` and `core_task`; `r2` and `r1`\\. Run revise with feedback that resolves each " +
			"conflict\\.")
	public void confirmListsEveryBlocker(boolean brokenLink)
		throws IOException, CommandRefusedException, InterruptedException
	{
		// Requirement: R-C13
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(output ->
			{
				ArrayNode conflicts = output.putArray("conflicts");
				ObjectNode first = conflicts.addObject();
				first.putArray("requirement_ids").add("r1").add("r2").add("core_task");
				first.put("evidence", "CLAUDE.md: three incompatible instructions.");
				first.putArray("resolutions").add("Exempt explanations.").add("Drop brevity.");
				first.put("recommended", 1);
				first.put("reason", "Keeps the most intent.");
				ObjectNode second = conflicts.addObject();
				second.putArray("requirement_ids").add("r2").add("r1");
				second.put("evidence", "rules/safety.md: another conflict.");
				second.putArray("resolutions").add("Exempt explanations.").add("Drop brevity.");
				second.put("recommended", 1);
				second.put("reason", "Keeps the most intent.");
			}));
			String id = repository.core().start(Path.of("CLAUDE.md")).status().campaignId();
			if (brokenLink)
			{
				ForkView fork = repository.forks().open(repository.root(), repository.directory(id));
				Map<Path, FileEntry> entries = new TreeMap<>(fork.inventory().entries());
				entries.put(Path.of("broken.md"), new FileEntry.SymbolicLink(Path.of("missing.md")));
				repository.forks().setInventory(repository.directory(id), new FileInventory(repository.root(),
					entries));
			}
			Path snapshot = repository.directory(id).resolve("held_out/campaign.json");
			String before = Files.readString(snapshot);
			try
			{
				repository.core().confirm(id, CertificationScope.LATEST_VERSIONS);
			}
			catch (CommandRefusedException e)
			{
				String expected = "Confirmation is refused because the proposal lists conflicting requirements: " +
					"`r1`, `r2` and `core_task`; `r2` and `r1`. Run revise with feedback that resolves each conflict.";
				if (brokenLink)
				{
					expected = "These symbolic links block confirmation until they are resolved: broken.md. " + expected;
				}
				requireThat(e.getMessage(), "message").isEqualTo(expected);
				throw e;
			}
			finally
			{
				requireThat(Files.readString(snapshot), "campaignJson").isEqualTo(before);
			}
		}
	}
}
