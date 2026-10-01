package io.github.cowwoc.cat.common.optimizer.cli;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignBuilder;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignHeader;
import io.github.cowwoc.cat.common.optimizer.campaign.Choice;
import io.github.cowwoc.cat.common.optimizer.campaign.Harness;
import io.github.cowwoc.cat.common.optimizer.campaign.Lifecycle;
import io.github.cowwoc.cat.common.optimizer.execution.FixedCalibrator;
import io.github.cowwoc.cat.common.optimizer.execution.ManualClock;
import io.github.cowwoc.cat.common.optimizer.execution.SliceSettings;
import io.github.cowwoc.cat.common.optimizer.inventory.Overlay;
import io.github.cowwoc.cat.common.optimizer.port.FakeHostInterruptSignal;
import io.github.cowwoc.cat.common.optimizer.port.FakeSessionPort;
import io.github.cowwoc.cat.common.optimizer.port.HostLogin;
import io.github.cowwoc.cat.common.optimizer.port.SessionHandle;
import io.github.cowwoc.cat.common.optimizer.port.SessionPort;
import io.github.cowwoc.cat.common.optimizer.port.SessionRequest;
import io.github.cowwoc.cat.common.optimizer.port.SessionResult;
import io.github.cowwoc.cat.common.optimizer.port.SessionRole;
import io.github.cowwoc.cat.common.optimizer.store.FileCampaignStore;
import io.github.cowwoc.cat.common.optimizer.store.FileCandidateOverlays;
import io.github.cowwoc.cat.common.optimizer.workspace.ForkView;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.random.RandomGenerator;
import java.util.stream.Stream;

/**
 * Tests {@link OptimizerCore} against a real project directory and campaign store, without a harness.
 */
public final class OptimizerCoreTest
{
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final FixedCalibrator CALIBRATOR = new FixedCalibrator(FixedCalibrator.STANDARD_CAP);

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
	 * @param sessions the sessions that the harness started
	 * @param versionReads counts how often the harness version was read
	 * @param campaign the campaign's identifier
	 */
	private record Project(Path root, Path target, FakeSessionPort sessions, AtomicInteger versionReads,
		String campaign)
	{
		/**
		 * Returns the core of a harness.
		 *
		 * @param harness the harness that runs the launcher
		 * @return the core
		 */
		OptimizerCore core(Harness harness)
		{
			Host host = new Host(harness, sessions, new FakeHostInterruptSignal(),
				new ManualClock(Instant.parse("2026-10-01T00:00:00Z")), RandomGenerator.getDefault(), JSON, root,
				() ->
				{
					versionReads.incrementAndGet();
					return "1.0";
				}, "harness-default", List.of(Path.of(".claude/settings.json")), SliceSettings.DEFAULT, 8);
			return new OptimizerCore(host, new InMemoryForks(root, Set.of()), CALIBRATOR);
		}
	}

	/**
	 * Creates a project with one campaign that a harness started.
	 *
	 * @param scope removes the project
	 * @param owner the harness that started the campaign
	 * @return the project
	 * @throws IOException if the project cannot be written
	 */
	private static Project project(TemporaryDirectories scope, Harness owner) throws IOException
	{
		Path root = scope.add(Files.createTempDirectory("optimizer-core")).toRealPath();
		Files.createDirectory(root.resolve(".git"));
		Path target = root.resolve("CLAUDE.md");
		Files.writeString(target, "Be brief.\n");
		CampaignHeader header = new CampaignHeader("campaign-1", root, target, owner, Lifecycle.ACTIVE);
		Campaign campaign = new CampaignBuilder().header(header).confirmed().build();
		new FileCampaignStore(root, JSON).replaceSnapshot(campaign);
		return new Project(root, target, new FakeSessionPort(), new AtomicInteger(), "campaign-1");
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
			CommandResult result = project.core(Harness.CODEX).status(project.campaign());
			requireThat(result.status().campaignId(), "campaignId").isEqualTo("campaign-1");
			requireThat(result.harness(), "harness").isEqualTo(Harness.CLAUDE);
			requireThat(project.sessions().sessions(), "sessions").isEmpty();
			requireThat(project.versionReads().get(), "versionReads").isEqualTo(0);
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
			OptimizerCore core = project.core(Harness.CODEX);
			String id = project.campaign();
			List<ThrowingCall> calls = List.of(
				() -> core.run(id),
				() -> core.confirm(id),
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
			requireThat(project.sessions().sessions(), "sessions").isEmpty();
			requireThat(project.versionReads().get(), "versionReads").isEqualTo(0);
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
			OptimizerCore core = project.core(Harness.CLAUDE);
			CommandRefusedException refusal = null;
			try
			{
				core.confirm(project.campaign());
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
				project.core(Harness.CLAUDE).status("missing");
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
				project.core(Harness.CLAUDE).start(outside);
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
	 * A session port that answers each session at once from a script and advances the clock by one minute per
	 * session, so that a slice's budget runs out without waiting.
	 */
	private static final class ScriptedSessions implements SessionPort
	{
		private final List<SessionRequest> requests = new ArrayList<>();
		private final ManualClock clock;
		private final Function<SessionRequest, SessionResult> script;

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
		public HostLogin hostLogin()
		{
			return HostLogin.UNKNOWN;
		}

		@Override
		public SessionHandle start(SessionRequest request)
		{
			requests.add(request);
			clock.advance(Duration.ofMinutes(1));
			CompletableFuture<SessionResult> result = CompletableFuture.completedFuture(script.apply(request));
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
	 * @param sessions the scripted sessions
	 * @param forks the in-memory forks
	 * @param core the core
	 */
	private record Repository(Path root, ScriptedSessions sessions, InMemoryForks forks, OptimizerCore core)
	{
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
		 */
		Campaign load(String id) throws IOException
		{
			return new FileCampaignStore(root, JSON).load(id).orElseThrow();
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
		Path root = scope.add(Files.createTempDirectory("optimizer-flow")).toRealPath();
		Path scratch = Files.createDirectory(root.resolve("scratch-outside-git"));
		Files.createDirectory(root.resolve(".git"));
		Files.writeString(root.resolve("CLAUDE.md"), "Be brief.\n");
		Files.writeString(root.resolve(".env.example"), "TOKEN=fixture\n");
		Files.writeString(root.resolve("extra.local.md"), "Local rules.\n");
		ManualClock clock = new ManualClock(Instant.parse("2026-10-01T00:00:00Z"));
		AtomicInteger derivers = new AtomicInteger();
		ScriptedSessions sessions = new ScriptedSessions(clock, request ->
		{
			try
			{
				Path transcript = Files.createTempFile(scratch, "transcript", ".jsonl");
				Path effects = Files.createTempDirectory(scratch, "effects");
				return switch (request.role())
				{
					case DERIVER -> new SessionResult.Completed(transcript, List.of("working", deriver.apply(
						derivers.getAndIncrement())), effects, "model", null, null, List.of(), "hook: none");
					case TRIAL -> new SessionResult.Completed(transcript, request.turns().stream().
						map(_ -> "answer").toList(), effects, "model", null, null, List.of(), "CLAUDE.md");
					case JUDGE, AUTHOR -> new SessionResult.Completed(transcript, List.of(
						"{\"verdict\": \"pass\", \"rationale\": \"The answer follows the rubric.\"}"), effects,
						"model", null, null, List.of(), "CLAUDE.md");
				};
			}
			catch (IOException e)
			{
				throw new java.io.UncheckedIOException(e);
			}
		});
		Host host = new Host(Harness.CLAUDE, sessions, new FakeHostInterruptSignal(), clock,
			new java.util.Random(7), JSON, root, () -> "1.0", "harness-default",
			List.of(Path.of(".claude/settings.json")), SliceSettings.DEFAULT, 8);
		InMemoryForks forks = new InMemoryForks(root, Set.of(Path.of("CLAUDE.md")));
		return new Repository(root, sessions, forks, new OptimizerCore(host, forks, CALIBRATOR));
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
			requireThat(repository.sessions().roles(), "roles").isEqualTo(List.of(SessionRole.DERIVER));
			requireThat(result.proposal(), "proposal").isNotNull().contains(".env.example");
			requireThat(repository.directory(id).resolve("proposal.json"), "proposalFile").isRegularFile();
			requireThat(repository.load(id).isConfirmed(), "confirmed").isFalse();
		}
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
			requireThat(new FileCampaignStore(repository.root(), JSON).listHeaders(), "headers").isEmpty();
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
			requireThat(Files.readString(repository.directory(id).resolve("proposal.json")), "stored").
				contains("Revision number 1 limit.");
		}
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
			repository.core().confirm(id);
			Campaign campaign = repository.load(id);
			requireThat(campaign.isConfirmed(), "confirmed").isTrue();
			requireThat(campaign.contract().fixtures().ignoredPaths(), "fixtures").isEqualTo(List.of(".env.example"));
			ForkView fork = repository.forks().open(repository.root(), repository.directory(id));
			Overlay incumbent = new FileCandidateOverlays(repository.directory(id), fork.inventory(), JSON).
				require(campaign.incumbent().snapshot());
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
			repository.core().confirm(id);
			ForkView fork = repository.forks().open(repository.root(), repository.directory(id));
			requireThat(fork.inventory().entries().keySet(), "held").contains(Path.of("extra.local.md")).
				contains(outside).contains(Path.of(".env.example"));
			Campaign campaign = repository.load(id);
			Overlay incumbent = new FileCandidateOverlays(repository.directory(id), fork.inventory(), JSON).
				require(campaign.incumbent().snapshot());
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
			Path snapshot = repository.root().resolve(".cat/work/optimizer").resolve(id).resolve("campaign.json");
			String before = Files.readString(snapshot);
			CommandRefusedException refusal = null;
			try
			{
				repository.core().confirm(id);
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
			repository.core().confirm(id);
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
			repository.core().confirm(id);
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
}
