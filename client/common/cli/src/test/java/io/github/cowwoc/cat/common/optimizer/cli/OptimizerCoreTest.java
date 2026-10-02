package io.github.cowwoc.cat.common.optimizer.cli;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignBuilder;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignEvent;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignHeader;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignTransitions;
import io.github.cowwoc.cat.common.optimizer.campaign.Choice;
import io.github.cowwoc.cat.common.optimizer.campaign.Harness;
import io.github.cowwoc.cat.common.optimizer.campaign.Lifecycle;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfile;
import io.github.cowwoc.cat.common.optimizer.execution.ManualClock;
import io.github.cowwoc.cat.common.optimizer.inventory.AuthorityWarning;
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
import java.util.List;
import java.util.Map;
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
		CampaignHeader header = new CampaignHeader("campaign-1", root, target, owner, Lifecycle.ACTIVE);
		Campaign campaign = new CampaignBuilder().header(header).confirmed().build();
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
					() -> core.confirm(id),
					() -> core.revise(id, "more"),
					() -> core.choose(id, Choice.STOP, 0, 0, null),
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
					case DERIVER -> new SessionResult.Completed(transcript, List.of("working", deriver.apply(
						derivers.getAndIncrement())), effects, "model", null, null, List.of(), "hook: none", List.of());
					case TRIAL -> new SessionResult.Completed(transcript, request.turns().stream().
						map(_ -> "answer").toList(), effects, "model", null, null, List.of(), "CLAUDE.md", List.of());
					case JUDGE -> new SessionResult.Completed(transcript, List.of(
						"{\"verdict\": \"pass\", \"rationale\": \"The answer follows the rubric.\"}"), effects,
						"model", null, null, List.of(), "CLAUDE.md", List.of());
					case AUTHOR -> new SessionResult.Completed(transcript, List.of("{\"needed_edits\": []}"), effects,
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
			Path file = repository.directory(id).resolve("proposal.json");
			JsonMapper json = JsonMapper.builder().build();
			ObjectNode stored = (ObjectNode) json.readTree(Files.readString(file, StandardCharsets.UTF_8));
			stored.put("format_version", 2);
			Files.writeString(file, json.writeValueAsString(stored), StandardCharsets.UTF_8);
			IOException refusal = null;
			try
			{
				repository.core().confirm(id);
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
				contains("category safety: safety (bound-model/low)").
				contains("category formatting: formatting (test-model/high)").
				doesNotContain("unused-model").doesNotContain("not-a-binding-file").doesNotContain("empty.agent");

			String id = result.status().campaignId();
			repository.core().confirm(id);
			Campaign campaign = repository.load(id);
			requireThat(campaign.contract().findCell("safety").orElseThrow().profile(), "safety").isEqualTo(bound);
			requireThat(campaign.contract().judge().profile(), "judge").isEqualTo(FakeProfileInventory.ROLE);
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
			requireThat(repository.scope().store().listHeaders(), "headers").isEmpty();
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
			repository.core().confirm(id);
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
				repository.core().choose(id, Choice.STOP, 0, 0, null);
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
	 * Verifies that {@code choose authorize-target} records the target that a pending authority warning names, and
	 * that a target no warning names is refused without a change.
	 */
	@Test
	public void authorizeTargetRecordsTheWarnedTarget() throws Exception
	{
		// Requirement: R-W4
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(_ ->
			{
			}));
			String id = repository.core().start(Path.of("CLAUDE.md")).status().campaignId();
			repository.core().confirm(id);
			Path target = Path.of("/external/shared.md");
			AuthorityWarning warning = new AuthorityWarning("R1", Path.of("shared.md"), target,
				Limit.EXTERNAL_TARGET_READ_ONLY, List.of(AuthorityWarning.Choice.AUTHORIZE_TARGET,
				AuthorityWarning.Choice.USE_AUTHORIZED_SOURCE, AuthorityWarning.Choice.STOP_CAMPAIGN,
				AuthorityWarning.Choice.REVISE_CAMPAIGN));
			// The scripted deriver yields no supported output, which leaves a pending decision about it; clear the
			// pending decisions so that the authority warning becomes the decision asked now
			Campaign c = repository.load(id);
			Campaign confirmed = new Campaign(c.header(), c.ceiling(), c.lineages(), c.identity(), c.incumbent(),
				c.candidates(), c.authorAttempts(), c.development(), c.failedCells(), c.baseline(), c.attempts(),
				c.trials(), c.sequences(), c.evidenceRevision(), c.slowStart(), c.throttle(), c.authorizedTargets(),
				List.of(), c.revisionRequested(), c.outcome(), c.delivery());
			Campaign warned = CampaignTransitions.apply(confirmed, new CampaignEvent.WarnAuthority(List.of(warning)));
			repository.scope().store().replaceSnapshot(warned);
			String before = Files.readString(repository.directory(id).resolve("campaign.json"));

			CommandRefusedException refusal = null;
			try
			{
				repository.core().choose(id, Choice.AUTHORIZE_TARGET, 0, 0, Path.of("/external/other.md"));
			}
			catch (CommandRefusedException e)
			{
				refusal = e;
			}
			requireThat(refusal, "refusal").isNotNull();
			requireThat(Files.readString(repository.directory(id).resolve("campaign.json")), "unchanged").
				isEqualTo(before);

			CommandResult result = repository.core().choose(id, Choice.AUTHORIZE_TARGET, 0, 0, target);
			requireThat(result.status().authorityWarnings(), "warnings").isEmpty();
			Campaign stored = repository.load(id);
			requireThat(stored.authorizedTargets(), "authorizedTargets").isEqualTo(Set.of(target));
			requireThat(stored.pending(), "pending").isNull();
		}
	}
}
