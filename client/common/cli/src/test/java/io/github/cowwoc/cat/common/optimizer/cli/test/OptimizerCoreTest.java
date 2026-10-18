package io.github.cowwoc.cat.common.optimizer.cli.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.Platform;
import io.github.cowwoc.cat.common.PortablePaths;
import io.github.cowwoc.cat.common.SessionDirectory;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.fakes.test.ManualClock;
import io.github.cowwoc.cat.common.optimizer.contract.DerivedSource;
import io.github.cowwoc.cat.common.optimizer.campaign.Applicability;
import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignEvent;
import io.github.cowwoc.cat.common.optimizer.campaign.CertificationHistory;
import io.github.cowwoc.cat.common.optimizer.campaign.CertificationParameters;
import io.github.cowwoc.cat.common.optimizer.campaign.CertificationRecord;
import io.github.cowwoc.cat.common.optimizer.campaign.Choice;
import io.github.cowwoc.cat.common.optimizer.campaign.ConcurrencyPolicy;
import io.github.cowwoc.cat.common.optimizer.campaign.FailureKind;
import io.github.cowwoc.cat.common.optimizer.campaign.Harness;
import io.github.cowwoc.cat.common.optimizer.campaign.Header;
import io.github.cowwoc.cat.common.optimizer.campaign.Lifecycle;
import io.github.cowwoc.cat.common.optimizer.campaign.MeasurementCondition;
import io.github.cowwoc.cat.common.optimizer.campaign.MeasurementIdentity;
import io.github.cowwoc.cat.common.optimizer.campaign.Outcome;
import io.github.cowwoc.cat.common.optimizer.campaign.PendingDecision;
import io.github.cowwoc.cat.common.optimizer.campaign.PromptAuthorKind;
import io.github.cowwoc.cat.common.optimizer.campaign.SlowStart;
import io.github.cowwoc.cat.common.optimizer.campaign.TestRevision;
import io.github.cowwoc.cat.common.optimizer.campaign.TestRevisionRequest;
import io.github.cowwoc.cat.common.optimizer.campaign.TestRevisions;
import io.github.cowwoc.cat.common.optimizer.campaign.Throttle;
import io.github.cowwoc.cat.common.optimizer.campaign.Transitions;
import io.github.cowwoc.cat.common.optimizer.campaign.test.Builder;
import io.github.cowwoc.cat.common.optimizer.cli.CommandRefusedException;
import io.github.cowwoc.cat.common.optimizer.cli.CommandResult;
import io.github.cowwoc.cat.common.optimizer.cli.Commands;
import io.github.cowwoc.cat.common.optimizer.cli.OptimizerCore;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.Part;
import io.github.cowwoc.cat.common.optimizer.contract.PromptConfiguration;
import io.github.cowwoc.cat.common.optimizer.contract.PromptTest;
import io.github.cowwoc.cat.common.optimizer.contract.PromptTestFiles;
import io.github.cowwoc.cat.common.optimizer.contract.PromptTestLocations;
import io.github.cowwoc.cat.common.optimizer.contract.TrialPlugin;
import io.github.cowwoc.cat.common.optimizer.contract.ValuesFile;
import io.github.cowwoc.cat.common.optimizer.delivery.DeliveryResult.Delivered;
import io.github.cowwoc.cat.common.optimizer.evidence.SessionEvidence;
import io.github.cowwoc.cat.common.optimizer.execution.GuardPilot;
import io.github.cowwoc.cat.common.optimizer.execution.PromptAuthorConflict;
import io.github.cowwoc.cat.common.optimizer.inventory.InventoryScanner;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;
import io.github.cowwoc.cat.common.optimizer.store.CertificationHistoryJson;
import tools.jackson.databind.JsonNode;
import io.github.cowwoc.cat.common.optimizer.contract.CertificationScope;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfile;
import io.github.cowwoc.cat.common.optimizer.inventory.AuthorityWarning;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.Limit;
import io.github.cowwoc.cat.common.optimizer.inventory.Overlay;
import io.github.cowwoc.cat.common.optimizer.port.HostLogin;
import io.github.cowwoc.cat.common.optimizer.port.SessionHandle;
import io.github.cowwoc.cat.common.optimizer.port.SessionPort;
import io.github.cowwoc.cat.common.optimizer.port.SessionRequest;
import io.github.cowwoc.cat.common.optimizer.port.SessionResult;
import io.github.cowwoc.cat.common.optimizer.port.SessionRole;
import io.github.cowwoc.cat.common.optimizer.port.TokenUsage;
import io.github.cowwoc.cat.common.optimizer.port.HookRegistration;
import io.github.cowwoc.cat.common.optimizer.port.LoadedSources;
import io.github.cowwoc.cat.common.optimizer.port.PluginBase;
import io.github.cowwoc.cat.common.optimizer.port.test.FakeProfileInventory;
import io.github.cowwoc.cat.common.optimizer.port.test.FakeSessionPort;
import io.github.cowwoc.cat.common.optimizer.proposal.ContractContext;
import io.github.cowwoc.cat.common.optimizer.proposal.ProposalCheck;
import io.github.cowwoc.cat.common.optimizer.proposal.OutputFile;
import io.github.cowwoc.cat.common.optimizer.proposal.test.DraftFixtures;
import io.github.cowwoc.cat.common.optimizer.proposal.test.ProposalTest;
import io.github.cowwoc.cat.common.optimizer.report.TokenStatistics;
import io.github.cowwoc.cat.common.optimizer.store.FileCampaignStore;
import io.github.cowwoc.cat.common.optimizer.store.FileCandidateOverlays;
import io.github.cowwoc.cat.common.optimizer.workspace.ForkView;
import io.github.cowwoc.cat.common.runner.HarnessCapacity.Hold;
import io.github.cowwoc.cat.common.secrets.OptimizerCommandScopeSecrets;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
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
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Tests {@link OptimizerCore} against a real project directory and campaign store, without a harness.
 */
public final class OptimizerCoreTest
{
	private static final JsonMapper JSON = JsonMapper.builder().build();
	/**
	 * A prompt test configuration whose Claude trials install no plugin.
	 */
	private static final String NO_PLUGINS = "{\"claude\": {\"plugins\": []}}";
	/**
	 * A host session other than the one that starts the campaigns of the tests.
	 */
	private static final String OTHER_SESSION = "5a7c9e1b-3d2f-4a6b-8c0e-9f1d3b5a7c2e";

	/**
	 * Matches the identifier of each assertion that a judge request lists, which its turn holds as a JSON array of
	 * objects indented by two spaces.
	 */
	private static final Pattern DECIDED_ASSERTION = Pattern.compile("(?m)^  \"id\" : \"([^\"]+)\"");

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
			OptimizerCommandScopeSecrets.access().store(command).replaceSnapshot(campaign);
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
	 * Verifies that another session may report a campaign, by its identifier or its target, and is shown the session
	 * that owns it, without running a session, reading a version or changing anything.
	 */
	@Test
	public void statusShowsOwningSession() throws Exception
	{
		// Requirement: R-L1
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Project project = project(scope, Harness.CLAUDE);
			try (TestOptimizerCommandScope<FakeSessionPort> command = TestOptimizerCommandScope.of(Harness.CLAUDE,
				OTHER_SESSION, project.root()))
			{
				OptimizerCommandScopeSecrets.access().sessionDirectory(command);
				Map<String, String> before = contents(project.root());
				OptimizerCore core = new OptimizerCore(command);
				for (String subject : List.of(project.campaign(), "CLAUDE.md"))
				{
					CommandResult result = core.status(subject);
					requireThat(result.status().campaignId(), subject).isEqualTo(project.campaign());
					requireThat(result.harness(), "harness").isEqualTo(Harness.CLAUDE);
					requireThat(String.join("\n", result.notices()), "notices").
						contains(TestOptimizerCommandScope.SESSION_ID).
						contains("claude --resume " + TestOptimizerCommandScope.SESSION_ID);
				}
				requireThat(command.sessions().sessions(), "sessions").isEmpty();
				requireThat(command.versionReads(), "versionReads").isEqualTo(0);
				requireThat(contents(project.root()), "contents").isEqualTo(before);
			}
		}
	}

	/**
	 * Verifies that every command that runs, changes or delivers a campaign, and {@code start} for its target, refuses
	 * a session other than the one that started it, names that session and the command that resumes it, and changes
	 * nothing.
	 */
	@Test
	public void refusesOtherSessionBeforeAnyChange() throws Exception
	{
		// Requirement: R-L1
		for (Harness harness : Harness.values())
		{
			try (TemporaryDirectories scope = new TemporaryDirectories())
			{
				Project project = project(scope, harness);
				try (TestOptimizerCommandScope<FakeSessionPort> command = TestOptimizerCommandScope.of(harness,
					OTHER_SESSION, project.root()))
				{
					OptimizerCommandScopeSecrets.access().sessionDirectory(command);
					Map<String, String> before = contents(project.root());
					OptimizerCore core = new OptimizerCore(command);
					String id = project.campaign();
					List<ThrowingCall> calls = List.of(
						() -> core.run(id),
						() -> core.confirm(id, CertificationScope.LATEST_VERSIONS),
						() -> core.revise(id, "more"),
						() -> core.choose(id, Choice.STOP, 0, 0),
						() -> core.cancel(id),
						() -> core.start(project.target()));
					String resume = switch (harness)
					{
						case CLAUDE -> "claude --resume ";
						case CODEX -> "codex resume ";
					};
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
						requireThat(refusal.getMessage(), "message").contains(id).
							contains(resume + TestOptimizerCommandScope.SESSION_ID);
					}
					requireThat(command.sessions().sessions(), "sessions").isEmpty();
					requireThat(command.versionReads(), "versionReads").isEqualTo(0);
					requireThat(contents(project.root()), "contents").isEqualTo(before);
				}
			}
		}
	}

	/**
	 * Verifies that a campaign stored where campaigns were kept before they moved into CAT session directories is
	 * neither found nor deleted.
	 */
	@Test
	public void ignoresCampaignsOfOldLocation() throws Exception
	{
		// Requirement: R-W1
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Path root = scope.add(Files.createTempDirectory("optimizer-core")).toRealPath();
			Files.createDirectory(root.resolve(".git"));
			Path target = root.resolve("CLAUDE.md");
			Files.writeString(target, "Be brief.\n");
			Header header = new Header("campaign-1", root, target, Harness.CLAUDE, Lifecycle.ACTIVE);
			new FileCampaignStore(root.resolve(SessionDirectory.ROOT).resolve("optimizer"), JSON).
				replaceSnapshot(new Builder().header(header).confirmed().build());
			try (TestOptimizerCommandScope<FakeSessionPort> command = TestOptimizerCommandScope.of(Harness.CLAUDE, root))
			{
				OptimizerCommandScopeSecrets.access().sessionDirectory(command);
				Map<String, String> before = contents(root);
				OptimizerCore core = new OptimizerCore(command);
				List<ThrowingCall> calls = List.of(
					() -> core.status("campaign-1"),
					() -> core.status("CLAUDE.md"),
					() -> core.cancel("campaign-1"));
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
					requireThat(refusal.getMessage(), "message").startsWith("No campaign");
				}
				requireThat(contents(root), "contents").isEqualTo(before);
			}
		}
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
	 * Verifies that a directory target is refused, naming it, before any session starts.
	 */
	@Test
	public void refusesDirectoryTarget() throws Exception
	{
		// Requirement: R-C25
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(_ ->
			{
			}));
			Path directory = Files.createDirectory(repository.root().resolve("prompts"));
			Files.writeString(directory.resolve("main.md"), "Be brief.\n");
			CommandRefusedException refusal = null;
			try
			{
				repository.core().start(Path.of("prompts"));
			}
			catch (CommandRefusedException e)
			{
				refusal = e;
			}
			requireThat(refusal, "refusal").isNotNull();
			requireThat(refusal.getMessage(), "message").contains("one prompt file").contains(directory.toString());
			requireThat(repository.sessions().roles(), "roles").isEmpty();
		}
	}

	/**
	 * Verifies that the guard pilot's control trials empty only the target prompt file and leave every other prompt
	 * file of the prompt system as the incumbent has it.
	 */
	@Test
	public void controlTrialsEmptyOnlyTarget() throws Exception
	{
		// Requirement: R-W10
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(_ ->
			{
			}), Set.of(Path.of("CLAUDE.md"), Path.of("read-file.md")));
			repository.sessions().addDerivedSources(List.of(new DerivedSource("read-file.md",
				"CLAUDE.md lazy loads the read rule.")));
			Files.writeString(repository.root().resolve("read-file.md"), "Read files whole.\n");
			configure(repository.root(), Path.of("read-file.md"), NO_PLUGINS);
			repository.core().start(Path.of("read-file.md"));
			List<SessionRequest> trials = repository.sessions().requests(SessionRole.TRIAL);
			requireThat(trials, "trials").isNotEmpty();
			for (SessionRequest trial : trials)
			{
				requireThat(Files.readString(trial.source().resolve("read-file.md")), "target").isEmpty();
				requireThat(Files.readString(trial.source().resolve("CLAUDE.md")), "other").isEqualTo("Be brief.\n");
			}
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
			String exposure = "The sandbox is off. Remove the setting and run `start` again.";
			FakeSessionPort sessions = new FakeSessionPort(HostLogin.UNKNOWN, exposure);
			CommandRefusedException refusal = null;
			Map<String, String> before;
			try (TestOptimizerCommandScope<FakeSessionPort> command = new TestOptimizerCommandScope<>(Harness.CLAUDE,
				root, Set.of(), _ -> sessions))
			{
				OptimizerCommandScopeSecrets.access().sessionDirectory(command);
				before = contents(root);
				try
				{
					new OptimizerCore(command).start(Path.of("CLAUDE.md"));
				}
				catch (CommandRefusedException e)
				{
					refusal = e;
				}
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
		/**
		 * The turns that the sessions' follow-ups chose, in the order that they were sent.
		 */
		private final List<String> followUps = new ArrayList<>();
		private final ManualClock clock;
		private final Function<SessionRequest, SessionResult> script;
		private final Map<SessionRole, List<TokenUsage>> usage = new java.util.EnumMap<>(SessionRole.class);
		/**
		 * The hooks that {@link #unapprovedHooks(Path)} returns for each plugin directory, by the directory's name.
		 */
		private final Map<String, List<String>> unapprovedHooks = new HashMap<>();
		/**
		 * The prompt files that {@link #loadedSources(Path, List)} returns as loading both in production and in trials.
		 */
		private volatile List<DerivedSource> derivedSources = List.of(new DerivedSource("CLAUDE.md",
			"The harness reads the project instruction file in the project root at startup."));

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
		public SessionEvidence readEvidence(Path transcript)
		{
			return new SessionEvidence(List.of());
		}

		@Override
		public boolean reportsExitStatus()
		{
			return true;
		}

		@Override
		public List<Path> runRoots()
		{
			return List.of();
		}

		/**
		 * The installed copies that {@link #installedPlugins(String)} returns for each plugin name.
		 */
		private final Map<String, List<Path>> installed = new HashMap<>();

		/**
		 * The files, relative to each trial plugin's base copy, that {@link #loadedSources(Path, List)} also returns as
		 * loading in trials, each named by its absolute path in the base copy.
		 */
		private volatile List<Path> pluginSources = List.of();

		@Override
		public LoadedSources loadedSources(Path project, List<PluginBase> plugins)
		{
			List<DerivedSource> trials = new ArrayList<>(derivedSources);
			for (PluginBase base : plugins)
			{
				Path directory = base.directory().toAbsolutePath().normalize();
				for (Path source : pluginSources)
				{
					trials.add(new DerivedSource(directory.resolve(source).toString(),
						"CAT injects this rule into every session"));
				}
			}
			return new LoadedSources(derivedSources, trials, List.of());
		}

		/**
		 * Sets the files that load in trials from each trial plugin's base copy.
		 *
		 * @param sources the files, relative to the base copy
		 */
		void setPluginSources(List<Path> sources)
		{
			pluginSources = List.copyOf(sources);
		}

		@Override
		public List<HookRegistration> hookRegistrations(Path project, List<PluginBase> plugins)
		{
			return List.of();
		}

		@Override
		public List<Path> installedPlugins(String name)
		{
			synchronized (installed)
			{
				return installed.getOrDefault(name, List.of());
			}
		}

		/**
		 * Sets the user's installed copies of a plugin.
		 *
		 * @param name the plugin's name, {@code <plugin>@<marketplace>}
		 * @param copies the directories of the copies
		 */
		void install(String name, List<Path> copies)
		{
			synchronized (installed)
			{
				installed.put(name, List.copyOf(copies));
			}
		}

		/**
		 * Adds prompt files to those that {@link #loadedSources(Path, List)} returns.
		 *
		 * @param sources the files
		 */
		void addDerivedSources(List<DerivedSource> sources)
		{
			List<DerivedSource> all = new ArrayList<>(derivedSources);
			all.addAll(sources);
			derivedSources = List.copyOf(all);
		}

		@Override
		public HostLogin hostLogin()
		{
			return HostLogin.UNKNOWN;
		}

		@Override
		public List<String> unapprovedHooks(Path plugin)
		{
			return unapprovedHooks.getOrDefault(plugin.getFileName().toString(), List.of());
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
			if (request.followUp().isPresent() && response instanceof SessionResult.Completed first)
			{
				Optional<String> turn = request.followUp().get().next(first);
				if (turn.isPresent() && script.apply(request) instanceof SessionResult.Completed reply)
				{
					followUps.add(turn.get());
					List<List<String>> responses = new ArrayList<>(first.responses());
					responses.addAll(reply.responses());
					// The session's project ends in the state that the reply left
					response = new SessionResult.Completed(first.transcript(), responses, reply.effects(),
						first.initialProject(), first.model(), first.reasoning(), first.usage(), first.windows(),
						first.promptSources(), first.toolCalls());
				}
			}
			List<TokenUsage> tokens = usage.getOrDefault(request.role(), List.of());
			if (!tokens.isEmpty() && response instanceof SessionResult.Completed completed)
			{
				int index = (int) requests.stream().filter(started -> started.role() == request.role()).count() - 1;
				response = new SessionResult.Completed(completed.transcript(), completed.responses(),
					completed.effects(), completed.initialProject(),
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

		/**
		 * Returns the requests of the sessions started so far with a role.
		 *
		 * @param role the role
		 * @return the requests, in start order
		 */
		List<SessionRequest> requests(SessionRole role)
		{
			return requests.stream().filter(request -> request.role() == role).toList();
		}

		/**
		 * Sets the hooks of a plugin that the user has not approved in the harness.
		 *
		 * @param plugin the plugin directory
		 * @param hooks the hooks, each as its event and command
		 */
		void unapprovedHooks(String plugin, List<String> hooks)
		{
			unapprovedHooks.put(plugin, hooks);
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
		 * Returns the directory that holds the campaigns of the host session.
		 *
		 * @return the directory
		 * @throws CommandRefusedException if the project is not a Git repository
		 * @throws IOException if the session's CAT session directory cannot be created
		 */
		Path campaigns() throws CommandRefusedException, IOException
		{
			return OptimizerCommandScopeSecrets.access().sessionDirectory(scope).campaigns();
		}

		/**
		 * Returns the campaign directory.
		 *
		 * @param id the campaign's identifier
		 * @return the directory
		 * @throws CommandRefusedException if the project is not a Git repository
		 * @throws IOException if the session's CAT session directory cannot be created
		 */
		Path directory(String id) throws CommandRefusedException, IOException
		{
			return campaigns().resolve(id);
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
			return OptimizerCommandScopeSecrets.access().store(scope).load(id).orElseThrow();
		}
	}

	/**
	 * Returns the text of the contract author output fixture.
	 *
	 * @param change edits the output
	 * @return the output as JSON text
	 * @throws UncheckedIOException if the fixture cannot be read
	 */
	private static String derivation(java.util.function.Consumer<ObjectNode> change)
	{
		try (InputStream in = ProposalTest.class.getResourceAsStream("derivation.json"))
		{
			ObjectNode output = (ObjectNode) JSON.readTree(in.readAllBytes());
			change.accept(output);
			return JSON.writeValueAsString(output);
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Creates a repository and a core whose sessions follow a script.
	 *
	 * @param scope removes the repository
	 * @param contractAuthor returns the contract author's final response for the number of contract author sessions
	 *   started so far
	 * @return the repository
	 * @throws IOException if the repository cannot be written
	 */
	private static Repository repository(TemporaryDirectories scope, Function<Integer, String> contractAuthor)
		throws IOException
	{
		return repository(scope, contractAuthor, Set.of(Path.of("CLAUDE.md")));
	}

	/**
	 * Creates a repository and a core whose sessions follow a script.
	 *
	 * @param scope removes the repository
	 * @param contractAuthor returns the contract author's final response for the number of contract author sessions
	 *   started so far
	 * @param tracked the files, relative to the project, that Git tracks
	 * @return the repository
	 * @throws IOException if the repository cannot be written
	 */
	private static Repository repository(TemporaryDirectories scope, Function<Integer, String> contractAuthor,
		Set<Path> tracked) throws IOException
	{
		return repository(scope, contractAuthor, tracked, null);
	}

	/**
	 * Creates a repository and a core whose sessions follow a script.
	 *
	 * @param scope removes the repository
	 * @param contractAuthor returns the contract author's final response for the number of contract author sessions
	 *   started so far
	 * @param tracked the files, relative to the project, that Git tracks
	 * @param contractAuthorEnd how every contract author session ends instead of completing with
	 *   {@code contractAuthor}'s response, or null to let the contract author complete
	 * @return the repository
	 * @throws IOException if the repository cannot be written
	 */
	private static Repository repository(TemporaryDirectories scope, Function<Integer, String> contractAuthor,
		Set<Path> tracked, SessionResult contractAuthorEnd) throws IOException
	{
		Path root = scope.add(Files.createTempDirectory("optimizer-flow")).toRealPath();
		Path scratch = Files.createDirectory(root.resolve("scratch-outside-git"));
		Files.createDirectory(root.resolve(".git"));
		Files.writeString(root.resolve("CLAUDE.md"), "Be brief.\n");
		Files.writeString(root.resolve(".env.example"), "TOKEN=fixture\n");
		Files.writeString(root.resolve("extra.local.md"), "Local rules.\n");
		configure(root, NO_PLUGINS);
		// The core only reads the files of a session other than a trial, so those sessions share one empty set instead of
		// creating their own. A passing trial's project copies are deleted, so each trial gets its own.
		Path transcript = Files.createFile(scratch.resolve("transcript.jsonl"));
		Path effects = Files.createDirectory(scratch.resolve("effects"));
		Path initialProject = Files.createDirectory(scratch.resolve("initial-project"));
		AtomicInteger contractAuthors = new AtomicInteger();
		AtomicInteger trials = new AtomicInteger();
		Function<SessionRequest, SessionResult> script = request -> switch (request.role())
		{
			case CONTRACT_AUTHOR -> Objects.requireNonNullElseGet(contractAuthorEnd, () -> contractAuthorResult(request,
				scratch, transcript, initialProject, contractAuthor.apply(contractAuthors.getAndIncrement())));
			case TRIAL -> trialResult(request, scratch.resolve("trial-" + trials.incrementAndGet()), transcript);
			case JUDGE -> new SessionResult.Completed(transcript, List.of(List.of(judgment(request))), effects,
				initialProject, "model", null, null, List.of(), "CLAUDE.md", List.of());
			case TRIAGE -> new SessionResult.Completed(transcript, List.of(List.of(
				"{\"impact\": \"blocking\", \"rationale\": \"The answer is incomplete.\"}")), effects,
				initialProject, "model", null, null, List.of(), "CLAUDE.md", List.of());
			case PROMPT_AUTHOR -> new SessionResult.Completed(transcript,
				List.of(List.of("{\"needed_edits\": []}")), effects, initialProject, "model", null, null,
				List.of(), "CLAUDE.md", List.of());
		};
		TestOptimizerCommandScope<ScriptedSessions> command = new TestOptimizerCommandScope<>(Harness.CLAUDE, root,
			tracked, clock -> new ScriptedSessions(clock, script));
		// The host session's CAT session directory exists before any command runs in it
		try
		{
			OptimizerCommandScopeSecrets.access().sessionDirectory(command);
		}
		catch (CommandRefusedException e)
		{
			throw new IOException(e.getMessage(), e);
		}
		return new Repository(root, command, new OptimizerCore(command));
	}

	/**
	 * Returns a completed trial session that answered every turn and changed no file.
	 *
	 * @param request the trial's request
	 * @param directory a new directory that receives the trial's effects and initial project
	 * @param transcript the session's transcript
	 * @return the result
	 * @throws UncheckedIOException if the directories cannot be created
	 */
	private static SessionResult.Completed trialResult(SessionRequest request, Path directory, Path transcript)
	{
		Path effects = directory.resolve("effects");
		Path initialProject = directory.resolve("initial-project");
		try
		{
			Files.createDirectories(effects);
			Files.createDirectories(initialProject);
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
		return new SessionResult.Completed(transcript, request.turns().stream().map(_ -> List.of("answer")).toList(),
			effects, initialProject, "model", null, null, List.of(), "CLAUDE.md", List.of());
	}

	/**
	 * Returns a contract author session that received the request's files, wrote its prompt tests to the draft
	 * directory and the rest of its output to its proposal file, and ended with its output.
	 *
	 * @param request the contract author's request
	 * @param scratch the directory that receives the session's project
	 * @param transcript the session's transcript
	 * @param initialProject the session's project before the session
	 * @param output the session's output, whose prompt tests may hold their scenarios inline
	 * @return the session's result
	 * @throws UncheckedIOException if the session's project cannot be written
	 */
	private static SessionResult.Completed contractAuthorResult(SessionRequest request, Path scratch,
		Path transcript, Path initialProject, String output)
	{
		try
		{
			Path effects = Files.createTempDirectory(scratch, "contract-author-");
			copyProject(request.source(), effects);
			request.files().write(effects);
			ContractContext context = ContractContext.read(JSON, effects.resolve(ContractContext.name(request.source())));
			Map<String, Path> recorded = new HashMap<>();
			for (ProposalCheck.RecordedTest test : context.recorded())
				recorded.put(test.citation(), test.directory());
			String proposalFile;
			if (context.revision().isPresent())
			{
				proposalFile = DraftFixtures.writeRevision(JSON, output, effects.resolve(context.draft()),
					context.target(), recorded);
			}
			else
			{
				proposalFile = DraftFixtures.write(JSON, output, effects.resolve(context.draft()), context.target(),
					recorded);
			}
			Files.writeString(effects.resolve(OutputFile.name(initialProject)), proposalFile, StandardCharsets.UTF_8);
			return new SessionResult.Completed(transcript, List.of(List.of("working", output)), effects,
				initialProject, "model", null, null, List.of(), "hook: none", List.of());
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Copies the project that a session works in to the session's project copy, as the session runner does.
	 *
	 * @param source  the project that the session works in
	 * @param effects the session's project copy
	 * @throws IOException if a file cannot be read or written
	 */
	private static void copyProject(Path source, Path effects) throws IOException
	{
		try (Stream<Path> paths = Files.walk(source))
		{
			for (Path path : paths.toList())
			{
				Path copy = effects.resolve(source.relativize(path).toString());
				if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
					Files.createDirectories(copy);
				else
					Files.copy(path, copy, LinkOption.NOFOLLOW_LINKS);
			}
		}
	}

	/**
	 * Returns the files of the draft directory that a contract author's request gives its session.
	 *
	 * @param scope   removes the directory that receives the request's files
	 * @param request the contract author's request
	 * @return the content of each file, by its path relative to the draft directory with {@code /} separators
	 * @throws IOException if the files cannot be written or read
	 */
	private static Map<String, String> draftFiles(TemporaryDirectories scope, SessionRequest request)
		throws IOException
	{
		Path project = scope.add(Files.createTempDirectory("contract-author-files"));
		request.files().write(project);
		Path draft = project.resolve(ContractContext.read(JSON, project.resolve(ContractContext.name(
			request.source()))).draft());
		Map<String, String> files = new TreeMap<>();
		try (Stream<Path> paths = Files.walk(draft))
		{
			for (Path file : paths.filter(Files::isRegularFile).toList())
				files.put(PortablePaths.text(draft.relativize(file)), Files.readString(file));
		}
		return files;
	}

	/**
	 * Returns a judge's output that passes every assertion that the judge request lists.
	 *
	 * @param request the judge request, whose turn lists the decided assertions as indented JSON
	 * @return the output
	 */
	private static String judgment(SessionRequest request)
	{
		ObjectNode output = JSON.createObjectNode();
		ArrayNode assertions = output.putArray("assertions");
		Matcher id = DECIDED_ASSERTION.matcher(request.turns().getFirst());
		while (id.find())
		{
			ObjectNode assertion = assertions.addObject();
			assertion.put("id", id.group(1));
			assertion.put("verdict", "pass");
			assertion.put("rationale", "The answer follows the rubric.");
			assertion.putNull("behavior");
		}
		return JSON.writeValueAsString(output);
	}

	/**
	 * Returns each way that a contract author session can end without completing, with the message that {@code start}
	 * and {@code revise} report.
	 *
	 * @return the session result and the text that the message contains
	 */
	@DataProvider
	public Object[][] contractAuthorEndsWithoutCompleting()
	{
		Instant resetsAt = Instant.parse("2026-10-05T23:00:00Z");
		return new Object[][]
			{
				{new SessionResult.Failed("The contract author session reached its 30-minute time limit", List.of()),
					"The contract author session did not complete: The contract author session reached its " +
					"30-minute time limit. Run `start` again to retry."},
				{new SessionResult.Interrupted(List.of()),
					"The contract author session was interrupted. Run `start` again to retry."},
				{new SessionResult.RateLimited(new Hold(resetsAt, 0), List.of()),
					"The provider rejected the contract author session because of a rate limit until " + resetsAt +
					". Run `start` again after that time."},
				{new SessionResult.RateLimited(Hold.NONE, List.of()),
					"The provider rejected the contract author session because of a rate limit. " +
					"Run `start` again later."},
				{new SessionResult.Refused(new Hold(resetsAt, 1), false, List.of()),
					"The provider refused the contract author session until " + resetsAt +
					". Run `start` again after that time."},
				{new SessionResult.Refused(new Hold(resetsAt, 6), true, List.of()),
					"The provider refused claude requests after a 30-minute hold. Run `start` again after " +
					resetsAt + "."}
			};
	}

	/**
	 * Verifies that {@code start} reports a contract author session that did not complete by its kind and reason, not
	 * by the result's record text.
	 *
	 * @param end how the contract author session ends
	 * @param message the message that {@code start} reports
	 * @throws Exception if the repository cannot be written or the command fails
	 */
	@Test(dataProvider = "contractAuthorEndsWithoutCompleting")
	public void startReportsIncompleteContractAuthor(SessionResult end, String message) throws Exception
	{
		// Requirement: R-E13
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(_ ->
			{
			}), Set.of(Path.of("CLAUDE.md")), end);

			CommandResult result = repository.core().start(Path.of("CLAUDE.md"));

			requireThat(result.message(), "message").contains(message).
				doesNotContain(end.getClass().getSimpleName() + "[");
		}
	}

	/**
	 * Verifies that {@code start} derives a proposal, runs the guard pilot's control trials before presenting it,
	 * persists both, and runs nothing else: the pilot's sessions spend no allowance and the proposal stays
	 * unconfirmed.
	 */
	@Test
	public void startDerivesAndPersistsProposal() throws Exception
	{
		// Requirement: R-I2
		// Requirement: R-C2
		// Requirement: R-C23
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(_ ->
			{
			}));
			CommandResult result = repository.core().start(Path.of("CLAUDE.md"));
			String id = result.status().campaignId();
			requireThat(id, "id").matches("20261001T000000Z-[0-9a-f]{6}");
			List<SessionRole> roles = repository.sessions().roles();
			requireThat(roles.getFirst(), "firstRole").isEqualTo(SessionRole.CONTRACT_AUTHOR);
			requireThat(roles.subList(1, roles.size()).stream().
				allMatch(role -> role == SessionRole.TRIAL || role == SessionRole.JUDGE), "pilotRoles").isTrue();
			List<SessionRequest> trials = repository.sessions().requests(SessionRole.TRIAL);
			requireThat(trials.size(), "trials").isEqualTo(3 * GuardPilot.TRIALS);
			for (SessionRequest trial : trials)
			{
				requireThat(trial.part(), "part").isEqualTo(Part.DEVELOPMENT);
				requireThat(Files.readString(trial.source().resolve("CLAUDE.md")), "controlPrompt").isEmpty();
			}
			requireThat(result.proposal(), "proposal").isNotNull().contains(".env.example").
				contains("\n## Guard pilot\n").contains("- `asks_before_deleting` (guard): 20 of 20 passed (100%)");
			requireThat(result.proposal().split("20 trials decided, 0 failed runs\\.", -1).length, "decidedPilots").
				isEqualTo(4);
			requireThat(result.status().tokens().roles().keySet(), "tokens").doesNotContain(SessionRole.TRIAL).
				doesNotContain(SessionRole.JUDGE);
			requireThat(repository.directory(id).resolve("held_out/proposal.json"), "proposalFile").isRegularFile();
			requireThat(repository.directory(id).resolve("held_out/pilot.json"), "pilotFile").isRegularFile();
			requireThat(repository.load(id).isConfirmed(), "confirmed").isFalse();
		}
	}

	/**
	 * Verifies that {@code start} moves the contract author's draft directory into the campaign's held-out directory
	 * and that the stored proposal names it there.
	 *
	 * @throws IOException if the project cannot be written
	 * @throws CommandRefusedException if the project cannot start a campaign
	 * @throws InterruptedException if the session wait is interrupted
	 */
	@Test
	public void startStoresDraftInHeldOut() throws IOException, CommandRefusedException,
		InterruptedException
	{
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(_ ->
			{
			}));
			String id = repository.core().start(Path.of("CLAUDE.md")).status().campaignId();

			Path heldOut = repository.directory(id).resolve("held_out");
			String draft = JSON.readTree(Files.readString(heldOut.resolve("proposal.json"))).get("draft").asString();
			requireThat(draft, "draft").isEqualTo("drafts/1");
			Path scenario = heldOut.resolve(draft).resolve(".cat/tests/CLAUDE/formatting/scenario.md");
			requireThat(Files.readString(scenario), "scenario").contains("Summarize the notes in {{tone}} style.");
			requireThat(heldOut.resolve(draft).resolve(".cat/tests/CLAUDE/formatting/values.json"), "values").
				isRegularFile();
		}
	}

	/**
	 * Verifies that a contract author session that reaches its time limit leaves no proposal and no draft directory
	 * in the campaign's held-out directory.
	 *
	 * @throws IOException if the project cannot be written
	 * @throws CommandRefusedException if the project cannot start a campaign
	 * @throws InterruptedException if the session wait is interrupted
	 */
	@Test
	public void timedOutContractAuthorStoresNothing() throws IOException, CommandRefusedException,
		InterruptedException
	{
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(_ ->
			{
			}), Set.of(Path.of("CLAUDE.md")), new SessionResult.Failed(
				"The contract author session reached its 30-minute time limit", List.of()));
			String id = repository.core().start(Path.of("CLAUDE.md")).status().campaignId();

			Path heldOut = repository.directory(id).resolve("held_out");
			requireThat(Files.exists(heldOut.resolve("proposal.json")), "proposalFileExists").isFalse();
			requireThat(Files.exists(heldOut.resolve("drafts")), "draftsExist").isFalse();
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
					requireThat(OptimizerCommandScopeSecrets.access().store(next).load(id).orElseThrow().ceiling(),
						"reloadedCeiling").isEqualTo(ceiling);
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
	 * Verifies that the contract author's request names a recorded scenario of the project's test tree and that its
	 * draft directory holds the scenario's complete content.
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
			SessionRequest request = repository.sessions().requests.getFirst();
			requireThat(request.turns().getFirst(), "contractAuthorTurn").contains(".cat/tests/CLAUDE/reported.md");
			requireThat(draftFiles(scope, request).get(".cat/tests/CLAUDE/reported/scenario.md"), "scenario").
				isEqualTo("Turn: Summarize the notes.\nExpected: Be brief.\n");
		}
	}

	/**
	 * Verifies that the contract author receives only the recorded scenarios of the target prompt file, and that a
	 * proposal omitting one of them is refused before it is persisted, while a scenario of a user rule outside the
	 * target needs no citation even when a requirement names that rule.
	 *
	 * @throws IOException if the project cannot be written
	 * @throws CommandRefusedException if the project cannot start a campaign
	 * @throws InterruptedException if the session wait is interrupted
	 */
	@Test
	public void startRequiresOnlyTargetScenarios() throws IOException, CommandRefusedException, InterruptedException
	{
		// Requirement: R-I3
		// Requirement: R-C24
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(output ->
			{
				((ObjectNode) output.get("requirements").get(0)).put("source",
					"home/.cat/rules/common/wording.md");
			}));
			Path rules = Files.createDirectories(repository.root().resolve("home/.cat/rules/common"));
			Files.writeString(rules.resolve("wording.md"), "Be brief.\n");
			Path userScenarios = Files.createDirectories(repository.root().resolve("home/.cat/tests/common/wording"));
			Files.writeString(userScenarios.resolve("rule-case.md"), "Turn: Summarize the rule.\n");
			Path targetScenarios = Files.createDirectories(repository.root().resolve(".cat/tests/CLAUDE"));
			Files.writeString(targetScenarios.resolve("target-case.md"), "Turn: Summarize the target.\n");

			CommandResult result = repository.core().start(Path.of("CLAUDE.md"));

			SessionRequest request = repository.sessions().requests.getFirst();
			requireThat(request.turns().getFirst(), "contractAuthorTurn").contains("target-case.md").
				doesNotContain("rule-case.md");
			requireThat(String.join("\n", draftFiles(scope, request).values()), "draft").
				contains("Turn: Summarize the target.").doesNotContain("Turn: Summarize the rule.");
			requireThat(result.message(), "message").contains("The contract author's proposal is invalid").
				contains("target-case.md").doesNotContain("rule-case.md");
			requireThat(Files.exists(repository.directory(result.status().campaignId()).resolve("held_out/proposal.json")),
				"proposalFileExists").isFalse();
		}
	}

	/**
	 * Verifies that {@code start} stores the user's goal with the campaign and gives it to the contract author.
	 *
	 * @throws IOException if the project cannot be written
	 * @throws CommandRefusedException if the project cannot start a campaign
	 * @throws InterruptedException if the session wait is interrupted
	 */
	@Test
	public void startPassesTheGoal() throws IOException, CommandRefusedException, InterruptedException
	{
		// Requirement: R-C24
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(_ ->
			{
			}));
			String goal = "Replies stay brief without dropping facts.";
			String id = repository.core().start(Path.of("CLAUDE.md"), Campaign.DEFAULT_CEILING, goal).status().
				campaignId();

			requireThat(repository.load(id).header().goal(), "goal").isEqualTo(goal);
			requireThat(repository.sessions().requests.getFirst().turns().getFirst(), "contractAuthorTurn").
				contains(goal).contains("`CLAUDE.md`");
		}
	}

	/**
	 * Verifies that a refused proposal gets one correction turn in the same contract author session, which names the
	 * refusal reasons, and that the corrected proposal is presented.
	 *
	 * @throws IOException if the project cannot be written
	 * @throws CommandRefusedException if the project cannot start a campaign
	 * @throws InterruptedException if the session wait is interrupted
	 */
	@Test
	public void startCorrectsRefusedProposal() throws IOException, CommandRefusedException, InterruptedException
	{
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, reply ->
			{
				if (reply == 0)
					return "invalid proposal";
				return derivation(_ ->
				{
				});
			});

			CommandResult result = repository.core().start(Path.of("CLAUDE.md"));

			requireThat(result.message(), "message").isNull();
			requireThat(repository.sessions().requests(SessionRole.CONTRACT_AUTHOR).size(), "contractAuthors").
				isEqualTo(1);
			requireThat(repository.sessions().followUps, "followUps").size().isEqualTo(1);
			requireThat(repository.sessions().followUps.getFirst(), "correction").
				contains("`cat-output.json` is not one JSON object");
			requireThat(Files.exists(repository.directory(result.status().campaignId()).resolve("held_out/proposal.json")),
				"proposalFileExists").isTrue();
		}
	}

	/**
	 * Verifies that when the corrected first proposal is also refused, CAT asks for {@code start} only, because no
	 * stored proposal exists for {@code revise} to regenerate.
	 *
	 * @throws IOException if the project cannot be written
	 * @throws CommandRefusedException if the project cannot start a campaign
	 * @throws InterruptedException if the session wait is interrupted
	 */
	@Test
	public void refusedFirstProposalNamesStart() throws IOException, CommandRefusedException, InterruptedException
	{
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> "invalid proposal");

			CommandResult result = repository.core().start(Path.of("CLAUDE.md"));

			requireThat(repository.sessions().followUps, "followUps").size().isEqualTo(1);
			requireThat(result.message(), "message").contains("The contract author's proposal is invalid").
				endsWith("Run `start` again to retry.").doesNotContain("revise");
		}
	}

	/**
	 * Verifies that when a revised proposal is refused after its correction turn, CAT still offers {@code revise}, which
	 * regenerates the stored proposal.
	 *
	 * @throws Exception if the test fails unexpectedly
	 */
	@Test
	public void refusedRevisionOffersRevise() throws Exception
	{
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, reply ->
			{
				if (reply == 0)
				{
					return derivation(_ ->
					{
					});
				}
				return "invalid proposal";
			});
			String id = repository.core().start(Path.of("CLAUDE.md")).status().campaignId();

			CommandResult revised = repository.core().revise(id, "cover the formal tone");

			requireThat(revised.message(), "message").contains("The contract author's proposal is invalid").
				endsWith("Run `revise` with feedback, or `start`, to retry.");
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
	 * Verifies that confirmation refuses the proposal format whose frozen judge prompt names the test case with a
	 * placeholder that the judge no longer fills.
	 *
	 * @throws IOException if the proposal file cannot be written or its obsolete format is refused
	 * @throws CommandRefusedException if the project cannot start a campaign
	 * @throws InterruptedException if the session wait is interrupted
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*version 8 proposal file.*")
	public void confirmRefusesProposalWithOldJudgePrompt() throws IOException, CommandRefusedException,
		InterruptedException
	{
		// Requirement: R-C5
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(_ ->
			{
			}));
			String id = repository.core().start(Path.of("CLAUDE.md")).status().campaignId();
			Path file = repository.directory(id).resolve("held_out/proposal.json");
			ObjectNode stored = (ObjectNode) JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
			stored.put("format_version", 8);
			Files.writeString(file, JSON.writeValueAsString(stored), StandardCharsets.UTF_8);
			repository.core().confirm(id, CertificationScope.LATEST_VERSIONS);
		}
	}

	/**
	 * Verifies that confirmation refuses the proposal format whose contract groups its prompt tests into categories.
	 *
	 * @throws IOException if the proposal file cannot be written or its obsolete format is refused
	 * @throws CommandRefusedException if the project cannot start a campaign
	 * @throws InterruptedException if the session wait is interrupted
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*version 13 proposal file.*")
	public void confirmRefusesProposalWithCategories() throws IOException, CommandRefusedException,
		InterruptedException
	{
		// Requirement: R-C11
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(_ ->
			{
			}));
			String id = repository.core().start(Path.of("CLAUDE.md")).status().campaignId();
			Path file = repository.directory(id).resolve("held_out/proposal.json");
			ObjectNode stored = (ObjectNode) JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
			stored.put("format_version", 13);
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
				withContext(bytes.toString(StandardCharsets.UTF_8), "output").isEqualTo(0);
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
				contains("Added /.cat/sessions/").contains("info/exclude");
			requireThat(started.get("proposal").isNull(), "missingProposal").isFalse();
			String id = started.get("campaign").asString();
			requireThat(output(repository, List.of("confirm", id, "latest_versions")).get("message").isNull(),
				"confirmNotice").isTrue();
			requireThat(output(repository, List.of("start", "CLAUDE.md")).get("message").isNull(), "resumeNotice").
				isTrue();
		}
	}

	/**
	 * Verifies that the start command emits no notice when the fork boundary reports that Git already ignores
	 * CAT's session directories. Real Git's ignore check is covered by fork tests.
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
	 * Verifies that reporting an added exclude preserves the contract author's invalid-proposal diagnostic.
	 */
	@Test
	public void startReportsExcludeAndContractFailure() throws Exception
	{
		// Requirement: R-W1
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> "invalid proposal");
			repository.forks().addExcludeOnCreate();
			ObjectNode started = output(repository, List.of("start", "CLAUDE.md"));
			requireThat(started.get("message").asString(), "message").
				contains("Added /.cat/sessions/").contains("info/exclude").
				contains("The contract author's proposal is invalid");
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
	 * profile gives its test one cell under that profile, while an unbound test has a cell for each inventory
	 * profile. Only files that the harness can bind are read, and a binding file that binds nothing is ignored. The
	 * contract author runs under the role profile, and the proposal shows the inventory and the cells.
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
			requireThat(repository.sessions().requests.getFirst().profile(), "contractAuthorProfile").
				isEqualTo(FakeProfileInventory.ROLE);
			requireThat(result.proposal(), "proposal").
				contains("Trial profile inventory (" + FakeProfileInventory.INVENTORY.source() + ")").
				contains("agents/safety.agent binds bound-model/low (outside the inventory, used as written)").
				contains("test safety (1 cell, named by the test): bound-model/low").
				contains("tests formatting, plain_list (1 cell each, named by the test): test-model/high").
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
	 * Verifies that {@code start} starts no contract author session when a staged binding file binds a model that the
	 * harness does not support, and reports the file and the model with the instruction to change the binding and run
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
			requireThat(repository.sessions().requests(SessionRole.CONTRACT_AUTHOR).size(), "contractAuthors").
				isEqualTo(1);
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
			requireThat(repository.sessions().requests(SessionRole.CONTRACT_AUTHOR).size(), "contractAuthors").
				isEqualTo(1);
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
			requireThat(OptimizerCommandScopeSecrets.access().store(repository.scope()).listHeaders(), "headers").isEmpty();
		}
	}

	/**
	 * Returns the conflicts used to exercise sequential resolution choices.
	 *
	 * @return the conflicts
	 */
	private static List<PromptAuthorConflict> reportedConflicts()
	{
		return List.of(new PromptAuthorConflict(List.of("f1"), List.of("r1", "r2"),
			List.of(new PromptAuthorConflict.Quotation(Path.of("CLAUDE.md"), "Be brief.")),
			List.of("Exempt explanations.", "Drop brevity."), 1, "Keeps brevity elsewhere."),
			new PromptAuthorConflict(List.of("f2"), List.of("r3", "r4"),
				List.of(new PromptAuthorConflict.Quotation(Path.of("rules.md"), "Always explain.")),
				List.of("Exempt greetings.", "Drop explanations."), 2, "Keeps the core task."));
	}

	/**
	 * Installs a prompt-author-conflict decision as the current persisted pause.
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
		OptimizerCommandScopeSecrets.access().store(repository.scope()).replaceSnapshot(new Campaign(c.header(),
			c.ceiling(), c.lineages(), c.identity(), c.incumbent(), c.candidates(), c.promptAuthorAttempts(),
			c.development(), c.baseline(), c.attempts(), c.trials(), c.sequences(), c.evidenceRevision(), c.slowStart(),
			c.throttle(), List.of(new PendingDecision.ConflictFound(reportedConflicts(), List.of())),
			c.revisionRequested(), c.outcome(), c.delivery(), c.optimization(), c.answeredWarnings(),
			c.testRevisions(), c.plugins()));
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
			requireThat(repository.sessions().roles().stream().filter(role -> role == SessionRole.CONTRACT_AUTHOR).
				count(), "contractAuthorCalls").isEqualTo(2L);
			SessionRequest request = repository.sessions().requests(SessionRole.CONTRACT_AUTHOR).getLast();
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
			requireThat(repository.sessions().requests(SessionRole.CONTRACT_AUTHOR).getLast().turns().getFirst(),
				"feedback").contains("Exempt detailed safety explanations.");
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
			int pilotTrials = repository.sessions().requests(SessionRole.TRIAL).size();
			CommandResult revised = repository.core().revise(id, "cover the formal tone");
			List<SessionRequest> contractAuthors = repository.sessions().requests(SessionRole.CONTRACT_AUTHOR);
			requireThat(contractAuthors.size(), "contractAuthors").isEqualTo(2);
			requireThat(contractAuthors.get(1).turns().getFirst(), "turn").contains("cover the formal tone").
				contains("Guard pilot results of the previous proposal:").
				contains("- `asks_before_deleting` (guard): 20 of 20 passed (100%)");
			// A revision that changes no prompt test reuses every pilot
			requireThat(repository.sessions().requests(SessionRole.TRIAL).size(), "trials").isEqualTo(pilotTrials);
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
	 * Verifies that {@code confirm} freezes the contract, and that the fixture under an ignored path, which the fork
	 * records, is staged with the incumbent afterwards.
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
			fork.workspace().stage(incumbent, Set.of());
			requireThat(incumbent.snapshot().entries().keySet(), "stagedFiles").contains(Path.of(".env.example")).
				contains(Path.of("CLAUDE.md"));
		}
	}

	/**
	 * Verifies that {@code start} refuses a target without a prompt test configuration, naming the file, before it
	 * creates anything.
	 */
	@Test
	public void startRefusesMissingConfiguration() throws Exception
	{
		// Requirement: R-W12
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(_ ->
			{
			}));
			Path file = PromptConfiguration.location(Path.of("CLAUDE.md"));
			Files.delete(repository.root().resolve(file));
			Map<String, String> before = contents(repository.root());

			String message = refusal(() -> repository.core().start(Path.of("CLAUDE.md")));

			requireThat(message, "message").contains(PortablePaths.text(file)).contains("does not exist");
			requireThat(contents(repository.root()), "contents").isEqualTo(before);
			requireThat(repository.sessions().requests(SessionRole.CONTRACT_AUTHOR), "sessions").isEmpty();
		}
	}

	/**
	 * Verifies that {@code start} refuses a prompt test configuration without a key for the harness, naming the file
	 * and the key, before it creates anything.
	 */
	@Test
	public void startRefusesMissingHarnessKey() throws Exception
	{
		// Requirement: R-W12
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(_ ->
			{
			}));
			configure(repository.root(), "{\"codex\": {\"plugins\": []}}");
			Map<String, String> before = contents(repository.root());

			String message = refusal(() -> repository.core().start(Path.of("CLAUDE.md")));

			requireThat(message, "message").
				contains(PortablePaths.text(PromptConfiguration.location(Path.of("CLAUDE.md")))).
				contains("no \"claude\" key");
			requireThat(contents(repository.root()), "contents").isEqualTo(before);
		}
	}

	/**
	 * Verifies that {@code start} refuses a plugin listed without a build whose name a manifest that Git tracks in the
	 * project holds, because trials would install the user's copy instead of the project's plugin.
	 */
	@Test
	public void startRefusesUnbuiltTrackedPlugin() throws Exception
	{
		// Requirement: R-W12
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Path manifest = Path.of("plugins/cat/.claude-plugin/plugin.json");
			Repository repository = repository(scope, _ -> derivation(_ ->
			{
			}), Set.of(Path.of("CLAUDE.md"), manifest));
			Files.createDirectories(repository.root().resolve(manifest).getParent());
			Files.writeString(repository.root().resolve(manifest), "{\"name\": \"cat\"}\n");
			configure(repository.root(), "{\"claude\": {\"plugins\": [{\"name\": \"cat@cat\"}]}}");
			Map<String, String> before = contents(repository.root());

			String message = refusal(() -> repository.core().start(Path.of("CLAUDE.md")));

			requireThat(message, "message").contains("cat@cat").contains(PortablePaths.text(manifest));
			requireThat(contents(repository.root()), "contents").isEqualTo(before);
		}
	}

	/**
	 * Verifies that {@code start} builds a project-built plugin in the optimization worktree and copies its output as
	 * the plugin's base, leaving the user's own build output in the base worktree unread and unchanged, and that a
	 * revision neither rebuilds nor recopies.
	 */
	@Test
	public void buildsInOptimizationWorktree() throws Exception
	{
		// Requirement: R-W12
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(_ ->
			{
			}), Set.of(Path.of("CLAUDE.md"), buildProgram()));
			Path root = repository.root();
			writeBuildProgram(root);
			configure(root, buildConfiguration("cat", 0, "out"));
			Path stale = root.resolve("out/stale.md");
			Files.createDirectories(stale.getParent());
			Files.writeString(stale, "The user's own build output.\n");

			String id = repository.core().start(Path.of("CLAUDE.md")).status().campaignId();
			repository.core().revise(id, "cover the formal tone");

			Path worktree = repository.directory(id).toAbsolutePath().resolve("worktree");
			requireThat(repository.forks().copiedBases(), "copiedBases").isEqualTo(List.of(worktree.resolve("out")));
			requireThat(Files.readAllLines(repository.directory(id).resolve("builds.log")), "builds").size().
				isEqualTo(1);
			requireThat(Files.readString(stale), "stale").isEqualTo("The user's own build output.\n");
			requireThat(Files.exists(root.resolve("out/.claude-plugin")), "baseManifest").isFalse();
			List<TrialPlugin> plugins = repository.load(id).plugins().orElseThrow();
			requireThat(plugins.stream().map(TrialPlugin::name).toList(), "plugins").isEqualTo(List.of("cat"));
			requireThat(plugins.getFirst().source(), "source").
				isEqualTo(new TrialPlugin.Build(List.of("./build-plugin", "cat", "0"), Path.of("out")));
		}
	}

	/**
	 * Verifies that a build that fails stops {@code start} with its exit status and the end of its output, and that
	 * the target is startable again: once the configuration names a build that succeeds, {@code start} builds the
	 * plugin and records it.
	 */
	@Test
	public void failedBuildLeavesTargetStartable() throws Exception
	{
		// Requirement: R-W12
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(_ ->
			{
			}), Set.of(Path.of("CLAUDE.md"), buildProgram()));
			writeBuildProgram(repository.root());
			configure(repository.root(), buildConfiguration("cat", 3, "out"));

			String message = refusal(() -> repository.core().start(Path.of("CLAUDE.md")));

			requireThat(message, "message").contains("exited with status 3").contains("building cat");
			requireThat(repository.sessions().requests(SessionRole.CONTRACT_AUTHOR), "sessions").isEmpty();

			configure(repository.root(), buildConfiguration("cat", 0, "out"));
			String id = repository.core().start(Path.of("CLAUDE.md")).status().campaignId();

			requireThat(repository.load(id).plugins().orElseThrow().stream().map(TrialPlugin::name).toList(),
				"plugins").isEqualTo(List.of("cat"));
			requireThat(repository.sessions().requests(SessionRole.CONTRACT_AUTHOR), "sessions").isNotEmpty();
		}
	}

	/**
	 * Verifies that {@code start} refuses a build whose output holds no plugin manifest, or a manifest that names
	 * another plugin than the configuration, showing the exit status and the end of the output.
	 */
	@Test
	public void startRefusesWrongBuildOutput() throws Exception
	{
		// Requirement: R-W12
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(_ ->
			{
			}), Set.of(Path.of("CLAUDE.md"), buildProgram()));
			writeBuildProgram(repository.root());
			Path manifest = Path.of(".claude-plugin", "plugin.json");
			configure(repository.root(), buildConfiguration("cat", 0, "missing"));
			String missing = refusal(() -> repository.core().start(Path.of("CLAUDE.md")));
			configure(repository.root(), buildConfiguration("other", 0, "out"));
			String other = refusal(() -> repository.core().start(Path.of("CLAUDE.md")));

			requireThat(missing, "missing").contains("left no valid manifest " + PortablePaths.text(manifest)).
				contains("exited with status 0").contains("building cat");
			requireThat(other, "other").contains("produced the plugin other").contains("exited with status 0").
				contains("building other");
			requireThat(repository.forks().copiedBases(), "copiedBases").isEmpty();
		}
	}

	/**
	 * Verifies that {@code start} refuses a plugin that the project does not build unless the harness's install
	 * records name exactly one installed copy, and that it copies that one copy as the plugin's base.
	 */
	@Test
	public void installedPluginNeedsOneCopy() throws Exception
	{
		// Requirement: R-W12
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(_ ->
			{
			}));
			configure(repository.root(), "{\"claude\": {\"plugins\": [{\"name\": \"tools@market\"}]}}");
			Path installed = scope.add(Files.createTempDirectory("installed-plugin")).toRealPath();
			Files.createDirectories(installed.resolve(".claude-plugin"));
			Files.writeString(installed.resolve(".claude-plugin/plugin.json"), "{\"name\": \"tools\"}\n");

			String none = refusal(() -> repository.core().start(Path.of("CLAUDE.md")));
			repository.sessions().install("tools@market", List.of(installed, installed.resolve("copy")));
			String two = refusal(() -> repository.core().start(Path.of("CLAUDE.md")));
			repository.sessions().install("tools@market", List.of(installed));
			String id = repository.core().start(Path.of("CLAUDE.md")).status().campaignId();

			requireThat(none, "none").contains("tools@market").contains("not installed");
			requireThat(two, "two").contains("2 copies");
			requireThat(repository.load(id).plugins().orElseThrow().getFirst().isProjectBuilt(), "isProjectBuilt").
				isFalse();
			requireThat(repository.forks().copiedBases(), "copiedBases").isEqualTo(List.of(installed));
			List<SessionRequest> trials = repository.sessions().requests(SessionRole.TRIAL);
			requireThat(trials, "trials").isNotEmpty();
			for (SessionRequest trial : trials)
				requireThat(trial.plugins().size(), "plugins").isEqualTo(1);
		}
	}

	/**
	 * Verifies that the proposal names the hooks of a trial plugin that the harness reports the user has not
	 * approved, by the plugin's name.
	 */
	@Test
	public void proposalNamesUnapprovedHooks() throws Exception
	{
		// Requirement: R-W12
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(_ ->
			{
			}));
			configure(repository.root(), "{\"claude\": {\"plugins\": [{\"name\": \"tools@market\"}]}}");
			Path installed = scope.add(Files.createTempDirectory("installed-plugin")).toRealPath();
			Files.createDirectories(installed.resolve(".claude-plugin"));
			Files.writeString(installed.resolve(".claude-plugin/plugin.json"), "{\"name\": \"tools\"}\n");
			repository.sessions().install("tools@market", List.of(installed));
			// The base copy is named by its digest in the fake workspace
			repository.sessions().unapprovedHooks(InventoryScanner.scan(installed, Set.of()).identity().hex(),
				List.of("tools@market SessionStart `./start.sh`"));

			CommandResult started = repository.core().start(Path.of("CLAUDE.md"));

			requireThat(started.proposal(), "proposal").contains("tools@market SessionStart `./start.sh`");
		}
	}

	/**
	 * Writes the prompt test configuration of the target {@code CLAUDE.md}.
	 *
	 * @param root the project root
	 * @param json the configuration
	 * @throws IOException if the file cannot be written
	 */
	private static void configure(Path root, String json) throws IOException
	{
		configure(root, Path.of("CLAUDE.md"), json);
	}

	/**
	 * Writes a target's prompt test configuration.
	 *
	 * @param root the project root
	 * @param target the target, relative to the project root
	 * @param json the configuration
	 * @throws IOException if the file cannot be written
	 */
	private static void configure(Path root, Path target, String json) throws IOException
	{
		Path file = root.resolve(PromptConfiguration.location(target));
		Files.createDirectories(file.getParent());
		Files.writeString(file, json);
	}

	/**
	 * Returns a prompt test configuration that lists the plugin {@code cat}, built by the program that
	 * {@link #buildProgram(TemporaryDirectories)} names.
	 *
	 * @param name the name that the build writes into its manifest
	 * @param exitStatus the build's exit status
	 * @param output the build's output directory, relative to the project
	 * @return the configuration
	 */
	private static String buildConfiguration(String name, int exitStatus, String output)
	{
		ObjectNode build = JSON.createObjectNode();
		build.putArray("command").add("./build-plugin").add(name).add(String.valueOf(exitStatus));
		build.put("output", output);
		ObjectNode plugin = JSON.createObjectNode();
		plugin.put("name", "cat");
		plugin.set("build", build);
		ObjectNode configuration = JSON.createObjectNode();
		configuration.putObject("claude").putArray("plugins").add(plugin);
		return JSON.writeValueAsString(configuration);
	}

	/**
	 * Returns the build program that {@link #writeBuildProgram(Path)} writes.
	 *
	 * @return the program's path relative to the project: a batch file on Windows, otherwise a shell script
	 */
	private static Path buildProgram()
	{
		if (Platform.current().isWindows())
			return Path.of("build-plugin.cmd");
		return Path.of("build-plugin");
	}

	/**
	 * Writes the build program {@link #buildProgram()} into a project. The program takes a plugin name and an exit
	 * status as its arguments: it prints {@code building <name>}, appends a line to {@code builds.log} in the parent
	 * of the directory that it runs in, writes a plugin directory {@code out} whose manifest holds the name, and exits
	 * with the status.
	 *
	 * @param root the project root
	 * @throws IOException if the program cannot be written
	 */
	private static void writeBuildProgram(Path root) throws IOException
	{
		if (Platform.current().isWindows())
		{
			Files.writeString(root.resolve(buildProgram()), """
				@echo off\r
				echo building %1\r
				echo build>> ..\\builds.log\r
				if not exist out\\.claude-plugin mkdir out\\.claude-plugin\r
				echo {"name": "%1"}> out\\.claude-plugin\\plugin.json\r
				exit /b %2\r
				""");
			return;
		}
		Path script = Files.writeString(root.resolve(buildProgram()), """
			#!/bin/sh
			echo "building $1"
			echo build >> ../builds.log
			mkdir -p out/.claude-plugin
			printf '{"name": "%s"}\\n' "$1" > out/.claude-plugin/plugin.json
			exit "$2"
			""");
		requireThat(script.toFile().setExecutable(true, false), "executable").isTrue();
	}

	/**
	 * Verifies that the proposal shows a derived source that loads from a trial plugin's base copy by the plugin's
	 * name and its path inside the plugin, while the stored proposal keeps its absolute path, which a resumed campaign
	 * reads back and shows the same way.
	 *
	 * @throws CommandRefusedException if a command is refused
	 * @throws IOException if a file cannot be read or written
	 * @throws InterruptedException if the thread is interrupted while a command waits for a session
	 */
	@Test
	public void showsPluginSourceAndStoresItsPath()
		throws CommandRefusedException, IOException, InterruptedException
	{
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(_ ->
			{
			}), Set.of(Path.of("CLAUDE.md"), buildProgram()));
			Path root = repository.root();
			writeBuildProgram(root);
			configure(root, buildConfiguration("cat", 0, "out"));
			Path rule = Path.of("rules/common/default-rules.md");
			repository.sessions().setPluginSources(List.of(rule));
			String shown = "\n- trial plugin `cat`: `rules/common/default-rules.md`: CAT injects this rule into every " +
				"session\n";

			CommandResult started = repository.core().start(Path.of("CLAUDE.md"));
			String id = started.status().campaignId();
			JsonNode stored = JSON.readTree(Files.readString(repository.directory(id).
				resolve("held_out/proposal.json")));
			List<String> storedPaths = new ArrayList<>();
			for (JsonNode source : stored.get("derived_sources"))
				storedPaths.add(source.get("path").asString());
			String absolute = storedPaths.stream().filter(path -> path.endsWith(rule.getFileName().toString())).
				findFirst().orElseThrow();
			requireThat(Path.of(absolute).isAbsolute(), "isAbsolute").isTrue();
			requireThat(Path.of(absolute).endsWith(rule), "endsWith").isTrue();
			requireThat(started.proposal(), "started").contains(shown).doesNotContain(absolute);

			repository.sessions().setPluginSources(List.of());
			String resumed = repository.core().start(Path.of("CLAUDE.md")).proposal();
			requireThat(resumed, "resumed").contains(shown).doesNotContain(absolute);
		}
	}

	/**
	 * Verifies that a resumed campaign presents the derived sources that the session port listed when the contract
	 * author finished, as the stored proposal keeps them, instead of listing them again.
	 */
	@Test
	public void resumeKeepsDerivedSources() throws Exception
	{
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(_ ->
			{
			}));
			String listed = "- CLAUDE.md: The harness reads the project instruction file in the project root at startup.";
			requireThat(repository.core().start(Path.of("CLAUDE.md")).proposal(), "started").contains(listed);
			repository.sessions().addDerivedSources(List.of(new DerivedSource("extra.local.md",
				"The harness loads the file by convention.")));

			String resumed = repository.core().start(Path.of("CLAUDE.md")).proposal();
			requireThat(resumed, "resumed").contains(listed).doesNotContain("extra.local.md: The harness loads");
		}
	}

	/**
	 * Verifies that the fork of a confirmed campaign holds the derived sources that Git ignores or that lie outside
	 * the project, so trials never read them live. The session port lists the derived sources when the contract author
	 * finishes, and the stored proposal keeps them for the confirmation.
	 */
	@Test
	public void recordsDerivedSources() throws Exception
	{
		// Requirement: R-W2
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Path outside = scope.add(Files.createTempDirectory("optimizer-shared")).toRealPath().resolve("shared.md");
			Files.writeString(outside, "Shared rules.\n");
			Repository repository = repository(scope, _ -> derivation(_ ->
			{
			}));
			List<DerivedSource> sources = new ArrayList<>();
			for (String path : List.of("extra.local.md", outside.toString()))
				sources.add(new DerivedSource(path, "The harness loads the file by convention."));
			repository.sessions().addDerivedSources(sources);
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
			Path snapshot = repository.directory(id).resolve("held_out/campaign.json");
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
	 * Verifies that {@code revise} reruns the guard pilot of the prompt tests that the revision changed, and only
	 * those.
	 */
	@Test
	public void reviseRerunsChangedTestPilot() throws Exception
	{
		// Requirement: R-C23
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, count -> derivation(output ->
			{
				if (count == 0)
					return;
				ObjectNode safety = (ObjectNode) output.get("tests").get(2);
				((ObjectNode) safety.get("assertions").get(0)).put("statement",
					"The assistant confirms before deleting the {{target}} directory.");
			}));
			String id = repository.core().start(Path.of("CLAUDE.md")).status().campaignId();
			int pilotTrials = repository.sessions().requests(SessionRole.TRIAL).size();

			CommandResult revised = repository.core().revise(id, "say confirms");

			List<SessionRequest> trials = repository.sessions().requests(SessionRole.TRIAL);
			List<SessionRequest> rerun = trials.subList(pilotTrials, trials.size());
			requireThat(rerun.size(), "rerun").isEqualTo(GuardPilot.TRIALS);
			requireThat(rerun.stream().allMatch(trial -> trial.turns().getFirst().startsWith("Delete the ")),
				"onlySafety").isTrue();
			requireThat(revised.proposal(), "proposal").contains("\n## Guard pilot\n");
		}
	}

	/**
	 * Verifies that the guard pilot, which runs before confirmation, stages the proposal's trial fixtures, and that a
	 * revision after confirmation reuses every pilot because the control trials see the same files.
	 */
	@Test
	public void pilotStagesUnrecordedFixtures() throws Exception
	{
		// Requirement: R-C23
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(_ ->
			{
			}));
			String id = repository.core().start(Path.of("CLAUDE.md")).status().campaignId();
			List<SessionRequest> trials = repository.sessions().requests(SessionRole.TRIAL);
			for (SessionRequest trial : trials)
			{
				requireThat(Files.readString(trial.source().resolve(".env.example")), "fixture").
					isEqualTo("TOKEN=fixture\n");
			}

			repository.core().confirm(id, CertificationScope.LATEST_VERSIONS);
			repository.core().revise(id, "cover the formal tone");

			requireThat(repository.sessions().requests(SessionRole.TRIAL).size(), "trials").isEqualTo(trials.size());
		}
	}

	/**
	 * Verifies that presenting a proposal records its trial fixtures in the fork before the guard pilot runs, so that
	 * confirmation records nothing more.
	 */
	@Test
	public void presentingRecordsFixtures() throws Exception
	{
		// Requirement: R-W2
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(_ ->
			{
			}));
			String id = repository.core().start(Path.of("CLAUDE.md")).status().campaignId();
			ForkView fork = repository.forks().open(repository.root(), repository.directory(id));
			requireThat(fork.inventory().entries().keySet(), "held").contains(Path.of(".env.example"));
			List<Set<Path>> selections = repository.forks().selections();

			repository.core().confirm(id, CertificationScope.LATEST_VERSIONS);

			requireThat(repository.forks().selections(), "selectionsAfterConfirmation").isEqualTo(selections);
		}
	}

	/**
	 * Verifies that a trial fixture that a revision before confirmation drops leaves the fork, so that neither the
	 * revised proposal's guard pilot nor the confirmed incumbent stages it.
	 */
	@Test
	public void revisionLeavesOutDroppedFixture() throws Exception
	{
		// Requirement: R-W2
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, session -> derivation(output ->
			{
				if (session > 0)
					((ArrayNode) output.get("fixtures").get("ignored_paths")).removeAll();
			}));
			String id = repository.core().start(Path.of("CLAUDE.md")).status().campaignId();
			int pilotTrials = repository.sessions().requests(SessionRole.TRIAL).size();

			repository.core().revise(id, "drop the fixture");

			List<SessionRequest> trials = repository.sessions().requests(SessionRole.TRIAL);
			List<SessionRequest> rerun = trials.subList(pilotTrials, trials.size());
			requireThat(rerun, "rerun").isNotEmpty();
			for (SessionRequest trial : rerun)
			{
				requireThat(Files.exists(trial.source().resolve(".env.example")), "pilotFixture").isFalse();
			}
			repository.core().confirm(id, CertificationScope.LATEST_VERSIONS);
			ForkView fork = repository.forks().open(repository.root(), repository.directory(id));
			Overlay incumbent = new FileCandidateOverlays(repository.directory(id), fork.inventory(),
				repository.scope().json()).require(repository.load(id).incumbent().snapshot());
			requireThat(incumbent.snapshot().entries().keySet(), "incumbentFiles").
				doesNotContain(Path.of(".env.example"));
		}
	}

	/**
	 * Verifies that a revision after confirmation that needs a trial fixture the fork does not hold records it and
	 * runs its guard pilot on it, leaves the campaign's incumbent alone until the user confirms, and that confirming
	 * moves the incumbent onto the larger fork in a new lineage.
	 */
	@Test
	public void confirmedRevisionRecordsNewFixture() throws Exception
	{
		// Requirement: R-W2
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, session -> derivation(output ->
			{
				if (session > 0)
					((ArrayNode) output.get("fixtures").get("ignored_paths")).add("late.example");
			}));
			String id = repository.core().start(Path.of("CLAUDE.md")).status().campaignId();
			repository.core().confirm(id, CertificationScope.LATEST_VERSIONS);
			Sha256 confirmed = repository.load(id).incumbent().snapshot();
			Files.writeString(repository.root().resolve("late.example"), "LATE=fixture\n");
			int pilotTrials = repository.sessions().requests(SessionRole.TRIAL).size();

			CommandResult revised = repository.core().revise(id, "cover the late fixture");

			requireThat(revised.proposal(), "proposal").contains("\n## Guard pilot\n");
			List<SessionRequest> trials = repository.sessions().requests(SessionRole.TRIAL);
			List<SessionRequest> rerun = trials.subList(pilotTrials, trials.size());
			requireThat(rerun, "rerun").isNotEmpty();
			for (SessionRequest trial : rerun)
			{
				requireThat(Files.readString(trial.source().resolve("late.example")), "pilotFixture").
					isEqualTo("LATE=fixture\n");
			}
			requireThat(repository.load(id).incumbent().snapshot(), "unconfirmedIncumbent").isEqualTo(confirmed);

			repository.core().confirm(id, CertificationScope.LATEST_VERSIONS);

			Campaign campaign = repository.load(id);
			requireThat(campaign.lineages().size(), "lineages").isEqualTo(2);
			requireThat(campaign.development().working(), "working").isEqualTo(campaign.incumbent());
			ForkView fork = repository.forks().open(repository.root(), repository.directory(id));
			Overlay incumbent = new FileCandidateOverlays(repository.directory(id), fork.inventory(),
				repository.scope().json()).require(campaign.incumbent().snapshot());
			requireThat(incumbent.snapshot().entries().keySet(), "incumbentFiles").contains(Path.of("late.example")).
				contains(Path.of(".env.example"));
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
			// The guard pilot's sessions spend no allowance, so their usage is not reported
			Map<SessionRole, Long> pilotSessions = new java.util.EnumMap<>(SessionRole.class);
			for (SessionRole role : List.of(SessionRole.TRIAL, SessionRole.JUDGE))
			{
				pilotSessions.put(role, repository.sessions().roles().stream().filter(value -> value == role).
					count());
			}
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
					int count = (int) (repository.sessions().roles().stream().filter(value -> value == role).count() -
						pilotSessions.get(role));
					requireThat(combined.roles().get(role).sessions(), "sessions").isEqualTo(count);
					requireThat(count,
						"combinedSessions").isGreaterThan(first.status().tokens().roles().get(role).sessions());
				}
				requireThat(combined.roles().containsKey(SessionRole.CONTRACT_AUTHOR), "missingContractAuthorUsage").
					isFalse();
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
	public void statusRetainsContractAuthorUsage() throws IOException, CommandRefusedException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			// The first session's proposal and its correction are both invalid
			Repository repository = repository(cleanup, number ->
			{
				if (number < 2)
					return "invalid";
				return derivation(_ -> {});
			});
			List<TokenUsage> usage = List.of(new TokenUsage(100, 20, 30, 10), new TokenUsage(300, 60, 90, 30));
			repository.sessions().usage.put(SessionRole.CONTRACT_AUTHOR, usage);
			String id = repository.core().start(Path.of("CLAUDE.md")).status().campaignId();
			repository.core().start(Path.of("CLAUDE.md"));
			repository.core().revise(id, "Cover safety.");
			try (TestOptimizerCommandScope<FakeSessionPort> fresh = TestOptimizerCommandScope.of(Harness.CLAUDE,
				repository.root()))
			{
				TokenStatistics.RoleUsage reported = new OptimizerCore(fresh).status(id).status().tokens().roles().
					get(SessionRole.CONTRACT_AUTHOR);
				requireThat(reported, "contractAuthorUsage").
					isEqualTo(TokenStatistics.of(Map.of(SessionRole.CONTRACT_AUTHOR, List.of(usage.get(0), usage.get(1),
					usage.get(0)))).roles().get(SessionRole.CONTRACT_AUTHOR));
			}
		}
	}

	/**
	 * Returns a test revision's output that rewrites the prompt test {@code formatting} of the contract author output
	 * fixture with new turns and new test cases.
	 *
	 * @param change edits the revised test
	 * @return the output as JSON text
	 */
	private static String revisedFormatting(java.util.function.Consumer<ObjectNode> change)
	{
		ObjectNode test = (ObjectNode) JSON.readTree(derivation(_ ->
		{
		})).get("tests").get(0);
		requireThat(test.get("id").asString(), "id").isEqualTo("formatting");
		test.putArray("turns").add("Summarize the meeting notes in {{tone}} style.").
			add("Now shorten it for {{audience}} reader.");
		ArrayNode testCases = test.putArray("test_cases");
		for (String tone : List.of("an upbeat", "a somber", "a neutral", "a playful", "a stern", "a warm"))
		{
			for (String audience : List.of("a manager", "an intern"))
			{
				ObjectNode testCase = testCases.addObject();
				ArrayNode values = testCase.putArray("values");
				values.addObject().put("variable", "tone").put("text", tone);
				values.addObject().put("variable", "audience").put("text", audience);
				testCase.put("expected_outcome", tone + " summary shortened for " + audience + " reader");
			}
		}
		change.accept(test);
		ObjectNode output = JSON.createObjectNode();
		output.put("result", "revised");
		output.put("reasons", "The old turns named the tone that the assertion expects.");
		output.set("test", test);
		output.putNull("proposed_change");
		return JSON.writeValueAsString(output);
	}

	/**
	 * Returns a test revision's output that does not revise the prompt test.
	 *
	 * @param result the result: {@code sound}, {@code unprovable} or {@code requirement_change}
	 * @param reasons the session's reasons
	 * @param proposedChange the proposed requirement change, or {@code null} for none
	 * @return the output as JSON text
	 */
	private static String unrevised(String result, String reasons, String proposedChange)
	{
		ObjectNode output = JSON.createObjectNode();
		output.put("result", result);
		output.put("reasons", reasons);
		output.putNull("test");
		output.put("proposed_change", proposedChange);
		return JSON.writeValueAsString(output);
	}

	/**
	 * Records the prompt author's request for a test revision of the prompt test {@code formatting}, as a development
	 * round records it when the prompt author classifies a failure kind as a test defect.
	 *
	 * @param repository the owned campaign repository
	 * @param id the campaign identifier
	 * @throws IOException if the store fails
	 * @throws CommandRefusedException if the owned project cannot be opened
	 */
	private static void requestTestRevision(Repository repository, String id)
		throws IOException, CommandRefusedException
	{
		Campaign c = repository.load(id);
		PromptAuthorKind kind = new PromptAuthorKind("tone_named_in_turn", List.of("f1"), FailureKind.TEST_DEFECT,
			List.of(), "The first turn names the tone that the assertion expects.");
		TestRevisions requested = c.testRevisions().request(new TestRevisionRequest("formatting",
			TestRevisionRequest.Trigger.TEST_DEFECT, List.of(kind), Map.of()));
		OptimizerCommandScopeSecrets.access().store(repository.scope()).replaceSnapshot(new Campaign(c.header(),
			c.ceiling(), c.lineages(), c.identity(), c.incumbent(), c.candidates(), c.promptAuthorAttempts(),
			c.development(), c.baseline(), c.attempts(), c.trials(), c.sequences(), c.evidenceRevision(), c.slowStart(),
			c.throttle(), c.pendingDecisions(), c.revisionRequested(), c.outcome(), c.delivery(), c.optimization(),
			c.answeredWarnings(), requested, c.plugins()));
	}

	/**
	 * Verifies that {@code run} answers a request for a test revision with a contract author session that receives the
	 * test and the prompt author's fault, and that a revised test starts a new lineage under the revised contract
	 * without the user's confirmation.
	 */
	@Test
	public void runRevisesTestWithDefect() throws Exception
	{
		// Requirement: R-D19
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, count ->
			{
				if (count == 0)
				{
					return derivation(_ ->
					{
					});
				}
				return revisedFormatting(_ ->
				{
				});
			});
			String id = repository.core().start(Path.of("CLAUDE.md")).status().campaignId();
			repository.core().confirm(id, CertificationScope.LATEST_VERSIONS);
			requestTestRevision(repository, id);

			repository.core().run(id);

			List<SessionRequest> contractAuthors = repository.sessions().requests(SessionRole.CONTRACT_AUTHOR);
			requireThat(contractAuthors.size(), "contractAuthors").isEqualTo(2);
			requireThat(contractAuthors.get(1).turns().getFirst(), "turn").contains("`formatting`").
				contains("The first turn names the tone that the assertion expects.");
			Campaign campaign = repository.load(id);
			requireThat(campaign.lineages().size(), "lineages").isEqualTo(2);
			requireThat(campaign.revisionRequested(), "revisionRequested").isFalse();
			requireThat(campaign.awaitsTestRevision(), "awaitsTestRevision").isFalse();
			requireThat(campaign.contract().findTest("formatting").orElseThrow().turns().getFirst(), "turn").
				isEqualTo("Summarize the meeting notes in {{tone}} style.");
			requireThat(campaign.testRevisions().revisions().size(), "revisions").isEqualTo(1);
			TestRevision revision = campaign.testRevisions().revisions().getFirst();
			requireThat(revision.test(), "test").isEqualTo("formatting");
			requireThat(revision.before(), "before").contains("Summarize the notes in {{tone}} style.");
			requireThat(revision.after(), "after").contains("Summarize the meeting notes in {{tone}} style.");
			Path heldOut = repository.directory(id).resolve("held_out");
			String draft = JSON.readTree(Files.readString(heldOut.resolve("proposal.json"))).get("draft").asString();
			requireThat(Files.readString(heldOut.resolve(draft).resolve(".cat/tests/CLAUDE/formatting/scenario.md")),
				"stored").contains("Summarize the meeting notes");
		}
	}

	/**
	 * Verifies that a test revision that finds the test sound records the report under the current lineage and keeps
	 * the confirmed contract.
	 */
	@Test
	public void runRecordsSoundTest() throws Exception
	{
		// Requirement: R-D19
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, count ->
			{
				if (count == 0)
				{
					return derivation(_ ->
					{
					});
				}
				return unrevised("sound", "The turns are organic and prove r1.", null);
			});
			String id = repository.core().start(Path.of("CLAUDE.md")).status().campaignId();
			repository.core().confirm(id, CertificationScope.LATEST_VERSIONS);
			requestTestRevision(repository, id);

			repository.core().run(id);

			Campaign campaign = repository.load(id);
			requireThat(campaign.testRevisions().isSound(1, "formatting"), "sound").isTrue();
			requireThat(campaign.lineages().size(), "lineages").isEqualTo(1);
			requireThat(campaign.awaitsTestRevision(), "awaitsTestRevision").isFalse();
		}
	}

	/**
	 * Verifies that a test revision that needs a requirement change pauses the campaign with the proposed change, and
	 * that choosing it derives a revised proposal from that change for the user to confirm.
	 */
	@Test
	public void proposedRequirementChangeRevisesContract() throws Exception
	{
		// Requirement: R-D19
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, count ->
			{
				if (count == 1)
				{
					return unrevised("requirement_change", "No reply can show the tone in one word.",
						"Let r1 accept any tone that the user names.");
				}
				// Without scope conditions, no unavailable certification pauses the campaign before the test revision
				return derivation(output -> output.putArray("scope_conditions"));
			});
			String id = repository.core().start(Path.of("CLAUDE.md")).status().campaignId();
			repository.core().confirm(id, CertificationScope.LATEST_VERSIONS);
			requestTestRevision(repository, id);

			repository.core().run(id);

			Campaign paused = repository.load(id);
			requireThat(paused.pending(), "pending").isInstanceOf(PendingDecision.TestRevisionBlocked.class);
			requireThat(paused.choices(), "choices").contains(Choice.PROPOSED_CHANGE).contains(Choice.STOP);

			CommandResult revised = repository.core().choose(id, Choice.PROPOSED_CHANGE, 0, 0);

			requireThat(revised.proposal(), "proposal").contains("## Requirements");
			requireThat(repository.sessions().requests(SessionRole.CONTRACT_AUTHOR).getLast().turns().getFirst(),
				"feedback").contains("Let r1 accept any tone that the user names.");
			Campaign campaign = repository.load(id);
			requireThat(campaign.revisionRequested(), "revisionRequested").isTrue();
			requireThat(campaign.pending(), "pending").isNull();
		}
	}

	/**
	 * Verifies that a revised test that proves other requirements or reuses a test case of the confirmed test is
	 * returned to the session once and then refused, leaving the confirmed contract and the request in place.
	 */
	@Test
	public void refusesRevisionThatChangesRequirements() throws Exception
	{
		// Requirement: R-D19
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, count ->
			{
				if (count == 0)
				{
					return derivation(_ ->
					{
					});
				}
				return revisedFormatting(test ->
				{
					test.putArray("requirement_ids").add("r2");
					((ObjectNode) test.get("assertions").get(0)).put("requirement_id", "r2");
					ObjectNode reused = ((ArrayNode) test.get("test_cases")).addObject();
					ArrayNode values = reused.putArray("values");
					values.addObject().put("variable", "tone").put("text", "a formal");
					values.addObject().put("variable", "audience").put("text", "an expert");
					reused.put("expected_outcome", "a formal summary shortened for an expert reader");
				});
			});
			String id = repository.core().start(Path.of("CLAUDE.md")).status().campaignId();
			repository.core().confirm(id, CertificationScope.LATEST_VERSIONS);
			requestTestRevision(repository, id);

			CommandResult result = repository.core().run(id);

			requireThat(result.message(), "message").contains("formatting").contains("r1").
				contains("a formal summary shortened for an expert reader");
			requireThat(repository.sessions().followUps, "followUps").size().isEqualTo(1);
			requireThat(repository.sessions().followUps.getFirst(), "correction").
				contains("`" + OutputFile.NAME + "`");
			requireThat(repository.sessions().requests(SessionRole.CONTRACT_AUTHOR).getLast().turns().getLast(),
				"finalTurn").contains("`" + OutputFile.NAME + "`");
			Campaign campaign = repository.load(id);
			requireThat(campaign.lineages().size(), "lineages").isEqualTo(1);
			requireThat(campaign.awaitsTestRevision(), "awaitsTestRevision").isTrue();
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
			FileCampaignStore.recordRemoval(repository.campaigns(), id);

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
		writeRecord(repository, promptText, 1, change);
	}

	/**
	 * Appends a certification record of {@code CLAUDE.md} to the target's history, as a certified delivery would,
	 * creating the history if the target has none. The record names the provider, harness version, session
	 * environment version and judge version that the test scope reports, except for any condition that
	 * {@code change} replaces, and the trial model {@code model-a}, which only a session observes.
	 *
	 * @param repository the test project
	 * @param promptText the text whose hash the record holds for {@code CLAUDE.md}
	 * @param certificationIndex the record's certification index
	 * @param change edits the recorded conditions
	 * @throws IOException if the history cannot be read or written
	 */
	private static void writeRecord(Repository repository, String promptText, int certificationIndex,
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
		CertificationRecord record = new CertificationRecord(Path.of("CLAUDE.md"), certificationIndex,
			CertificationScope.LATEST_VERSIONS,
			CertificationParameters.current(certificationIndex, Contract.DEFAULT_MINIMUM_PASS_RATE), List.of(),
			Map.of(Path.of("CLAUDE.md"), Sha256.of(promptText.getBytes(StandardCharsets.UTF_8))),
			new MeasurementIdentity(values));
		CertificationHistoryJson codec = new CertificationHistoryJson(JSON);
		Path file = repository.root().resolve(PromptTestLocations.certificationHistory(record.target()));
		CertificationHistory history = CertificationHistory.EMPTY;
		if (Files.exists(file))
			history = codec.read(file);
		Files.createDirectories(file.getParent());
		Files.write(file, codec.write(history.appended(record)));
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
			requireThat(status.get("latest").size(), "latestSize").isEqualTo(1);
			requireThat(status.get("earlier").isEmpty(), "earlierIsEmpty").isTrue();
			JsonNode latest = status.get("latest").get(0);
			requireThat(latest.get("harness").asString(), "harness").isEqualTo("claude");
			requireThat(latest.get("attempt").asInt(), "attempt").isEqualTo(1);
			requireThat(latest.get("scope").asString(), "scope").isEqualTo("latest_versions");
			JsonNode applicability = latest.get("applicability");
			requireThat(applicability.get("status").asString(), "applicability").isEqualTo("current");
			requireThat(applicability.get("prompt_content_changed").asBoolean(), "promptContentChanged").isFalse();
			requireThat(applicability.get("changed").isEmpty(), "changedIsEmpty").isTrue();
			requireThat(applicability.get("unchecked").get(0).asString(), "unchecked").isEqualTo("model");
			requireThat(applicability.get("unchecked").size(), "uncheckedSize").isEqualTo(1);
			requireThat(status.get("summary").asString(), "summary").isEqualTo("""
				Certification history of CLAUDE.md
				Latest certification in claude: scope latest_versions (every cell of each model family's latest \
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

			JsonNode applicability = status.get("latest").get(0).get("applicability");
			requireThat(applicability.get("status").asString(), "applicability").isEqualTo("historical");
			requireThat(applicability.get("prompt_content_changed").asBoolean(), "promptContentChanged").isTrue();
			requireThat(applicability.get("changed").get(0).asString(), "changed").isEqualTo("harness_version");
			requireThat(status.get("summary").asString(), "summary").contains(
				"Attempt 1: certified (historical; changed: prompt_content, harness_version)\n" +
				"  Not checked: trial model.");
		}
	}

	/**
	 * Verifies that {@code status} of a target that has no campaign but a history of certifications by two harnesses
	 * reports the latest record of each harness with its applicability, in the order of the history, and lists the
	 * records that a later record of the same harness followed as earlier history.
	 */
	@Test
	public void statusReportsLatestPerHarness() throws Exception
	{
		// Requirement: R-M6
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(_ ->
			{
			}));
			writeRecord(repository, "Be brief.\n", 1, _ ->
			{
			});
			writeRecord(repository, "Be brief.\n", 1, values -> values.put(MeasurementCondition.PROVIDER,
				Harness.CODEX.commandName()));
			writeRecord(repository, "Be brief.\n", 2, _ ->
			{
			});

			ObjectNode status = output(repository, List.of("status", "CLAUDE.md"));

			JsonNode latest = status.get("latest");
			requireThat(latest.size(), "latestSize").isEqualTo(2);
			requireThat(latest.get(0).get("harness").asString(), "firstHarness").isEqualTo("codex");
			requireThat(latest.get(0).get("attempt").asInt(), "firstAttempt").isEqualTo(1);
			requireThat(latest.get(0).get("applicability").get("status").asString(), "firstApplicability").
				isEqualTo("current");
			requireThat(latest.get(1).get("harness").asString(), "secondHarness").isEqualTo("claude");
			requireThat(latest.get(1).get("attempt").asInt(), "secondAttempt").isEqualTo(2);
			requireThat(latest.get(1).get("applicability").get("status").asString(), "secondApplicability").
				isEqualTo("current");
			JsonNode earlier = status.get("earlier");
			requireThat(earlier.size(), "earlierSize").isEqualTo(1);
			requireThat(earlier.get(0).get("harness").asString(), "earlierHarness").isEqualTo("claude");
			requireThat(earlier.get(0).get("attempt").asInt(), "earlierAttempt").isEqualTo(1);
			requireThat(status.get("summary").asString(), "summary").contains("Latest certification in codex:").
				contains("Latest certification in claude:").contains("""
					Earlier certifications:
					  Attempt 1 in claude: certified, scope latest_versions""");
		}
	}

	/**
	 * Verifies that a new campaign's proposal reports the target's history like {@code status} does: the latest record
	 * of each harness and the earlier records.
	 */
	@Test
	public void proposalListsEarlierCertifications() throws Exception
	{
		// Requirement: R-M6
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(_ ->
			{
			}));
			writeRecord(repository, "Be brief.\n", 1, _ ->
			{
			});
			writeRecord(repository, "Be brief.\n", 2, _ ->
			{
			});

			CommandResult started = repository.core().start(Path.of("CLAUDE.md"));

			requireThat(started.proposal(), "proposal").contains("## Incumbent certification").
				contains("Attempt 2: certified").contains("""
					Earlier certifications:
					  Attempt 1 in claude: certified, scope latest_versions""");
		}
	}

	/**
	 * Verifies that {@code status} of a target whose history file is malformed refuses with the file and the JSON path
	 * of the problem instead of reporting a partial history.
	 */
	@Test
	public void statusRefusesMalformedHistory() throws Exception
	{
		// Requirement: R-M6
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(_ ->
			{
			}));
			Path file = repository.root().resolve(PromptTestLocations.certificationHistory(Path.of("CLAUDE.md")));
			Files.createDirectories(file.getParent());
			Files.writeString(file, "{\"format_version\": 1, \"records\": [\"text\"]}\n");

			IOException failure = null;
			try
			{
				repository.core().status("CLAUDE.md");
			}
			catch (IOException e)
			{
				failure = e;
			}

			requireThat(failure, "failure").isNotNull();
			requireThat(failure.getMessage(), "message").contains("certifications.json").contains("records[0]");
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
			OptimizerCommandScopeSecrets.access().store(repository.scope()).replaceSnapshot(finished);
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

			Applicability applicability = result.certification().latest().getFirst().applicability();
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

				Certification history of CLAUDE.md
				Latest certification in claude: scope latest_versions (every cell of each model family's latest \
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
			// Claude Code reads the rule from the project, so trials load it without a trial plugin
			Path rule = Path.of(".claude/rules/kind.md");
			Repository repository = repository(scope, _ -> derivation(_ ->
			{
			}), Set.of(Path.of("CLAUDE.md"), rule));
			Files.createDirectories(repository.root().resolve(rule).getParent());
			Files.writeString(repository.root().resolve(rule), "Be kind.\n");
			configure(repository.root(), rule, NO_PLUGINS);
			String id = repository.core().start(Path.of("CLAUDE.md")).status().campaignId();
			FileCampaignStore.recordRemoval(repository.campaigns(), id);
			Path worktree = repository.directory(id).resolve("worktree");
			repository.forks().denyRemoval();
			String warning = "Could not remove the files of campaign " + id + ": " + worktree +
				": Permission denied. The next command retries the removal.";

			ObjectNode started = output(repository, List.of("start", PortablePaths.text(rule)));

			requireThat(started.get("proposal").isNull(), "proposalIsNull").isFalse();
			requireThat(started.get("summary").asString(), "summary").endsWith("\n" + warning);
			// The host shows the proposal instead of the summary, so the warning is in the message too
			requireThat(started.get("message").asString(), "message").endsWith(warning);
			String message = refusal(() -> repository.core().cancel("no-such-campaign"));
			requireThat(message, "message").isEqualTo("No campaign has the identifier no-such-campaign.\n" +
				warning);
			requireThat(FileCampaignStore.removals(repository.campaigns()), "removals").isEqualTo(List.of(id));
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
				started.incumbent(), built.candidates(), built.promptAuthorAttempts(), started.development(), built.baseline(),
				built.attempts(), built.trials(), built.sequences(), built.evidenceRevision(), built.slowStart(),
				built.throttle(), List.of(new PendingDecision.RegressionTradeOff(1)),
				built.revisionRequested(), built.outcome(), built.delivery(), built.optimization(),
				built.answeredWarnings(), built.testRevisions(), built.plugins());
			OptimizerCommandScopeSecrets.access().store(repository.scope()).replaceSnapshot(tradeOff);

			ObjectNode chosen = output(repository, List.of("choose", id, "retain_incumbent"));

			requireThat(chosen.get("summary").asString(), "summary").
				endsWith("\nRemoved the campaign's files from .cat/sessions/.");
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
			Path target = Path.of("/external/shared.md").toAbsolutePath();
			AuthorityWarning warning = new AuthorityWarning("R1", Path.of("shared.md"), target,
				Limit.EXTERNAL_TARGET_READ_ONLY, List.of(AuthorityWarning.Choice.USE_AUTHORIZED_SOURCE,
				AuthorityWarning.Choice.STOP_CAMPAIGN, AuthorityWarning.Choice.REVISE_CAMPAIGN));
			// The scripted contract author yields no supported output, which leaves a pending decision about it; clear
			// the pending decisions so that the authority warning becomes the decision asked now
			Campaign c = repository.load(id);
			Campaign confirmed = new Campaign(c.header(), c.ceiling(), c.lineages(), c.identity(), c.incumbent(),
				c.candidates(), c.promptAuthorAttempts(), c.development(), c.baseline(), c.attempts(),
				c.trials(), c.sequences(), c.evidenceRevision(), c.slowStart(), c.throttle(),
				List.of(), c.revisionRequested(), c.outcome(), c.delivery(), c.optimization(),
				c.answeredWarnings(), c.testRevisions(), c.plugins());
			Campaign warned = Transitions.apply(confirmed, new CampaignEvent.WarnAuthority(List.of(warning)));
			OptimizerCommandScopeSecrets.access().store(repository.scope()).replaceSnapshot(warned);
			requireThat(warned.choices(), "choices").isEqualTo(List.of(Choice.USE_AUTHORIZED_SOURCE, Choice.STOP));

			CommandResult result = repository.core().choose(id, Choice.USE_AUTHORIZED_SOURCE, 0, 0);
			requireThat(result.status().authorityWarnings(), "warnings").isEmpty();
			Campaign stored = repository.load(id);
			requireThat(stored.pending(), "pending").isNull();
			requireThat(stored.answeredWarnings(), "answeredWarnings").isEqualTo(List.of(warning));
		}
	}

	/**
	 * Verifies that {@code choose retain_incumbent} delivers the kept incumbent's prompt tests in the same command:
	 * each prompt test's regression scenario and values file are written beside the unchanged target, the result
	 * reports the delivery without a label, and the finished campaign's files are removed.
	 *
	 * @throws Exception if the owned repository or store fails
	 */
	@Test
	public void retainIncumbentDeliversPromptTests() throws Exception
	{
		// Requirement: R-W11
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(_ ->
			{
			}));
			String id = repository.core().start(Path.of("CLAUDE.md")).status().campaignId();
			repository.core().confirm(id, CertificationScope.LATEST_VERSIONS);
			String target = Files.readString(repository.root().resolve("CLAUDE.md"), StandardCharsets.UTF_8);
			// A certified attempt that proved a regression waits for the user to accept it or keep the incumbent
			Campaign c = repository.load(id);
			Campaign waiting = new Campaign(c.header(), c.ceiling(), c.lineages(), c.identity(), c.incumbent(),
				c.candidates(), c.promptAuthorAttempts(), c.development(), c.baseline(), c.attempts(),
				c.trials(), c.sequences(), c.evidenceRevision(), c.slowStart(), c.throttle(),
				List.of(new PendingDecision.RegressionTradeOff(1)), c.revisionRequested(), Outcome.CERTIFIED,
				c.delivery(), c.optimization(), c.answeredWarnings(), c.testRevisions(), c.plugins());
			OptimizerCommandScopeSecrets.access().store(repository.scope()).replaceSnapshot(waiting);
			requireThat(waiting.choices(), "choices").contains(Choice.RETAIN_INCUMBENT);

			CommandResult result = repository.core().choose(id, Choice.RETAIN_INCUMBENT, 0, 0);

			requireThat(result.delivery(), "delivery").isNotNull();
			requireThat(result.delivery().result(), "result").isInstanceOf(Delivered.class);
			requireThat(((Delivered) result.delivery().result()).label(), "label").isNull();
			requireThat(Files.readString(repository.root().resolve("CLAUDE.md"), StandardCharsets.UTF_8), "target").
				isEqualTo(target);
			requireThat(c.contract().tests(), "tests").isNotEmpty();
			for (PromptTest test : c.contract().tests())
			{
				Path directory = repository.root().resolve(".cat/tests/CLAUDE").resolve(test.id());
				requireThat(directory.resolve(PromptTestFiles.SCENARIO), "scenario").withContext(test.id(), "id").
					isRegularFile();
				requireThat(directory.resolve(ValuesFile.NAME), "values").withContext(test.id(), "id").isRegularFile();
			}
			requireThat(Files.notExists(repository.directory(id)), "directoryIsAbsent").isTrue();
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
			"requirements: `r1`, `r2` and `r3`; `r2` and `r1`\\. Run revise with feedback that resolves each " +
			"conflict\\.")
	public void confirmListsEveryBlocker(boolean brokenLink)
		throws IOException, CommandRefusedException, InterruptedException
	{
		// Requirement: R-C13
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(output ->
			{
				ObjectNode brevity = ((ArrayNode) output.get("requirements")).addObject().put("id", "r3").
					put("description", "Answers stay brief.").put("source", "CLAUDE.md").putNull("trigger");
				brevity.putArray("behaviors").add("too_long");
				ObjectNode formatting = (ObjectNode) output.get("tests").get(0);
				((ArrayNode) formatting.get("requirement_ids")).add("r3");
				((ArrayNode) formatting.get("assertions")).addObject().put("id", "stays_brief").
					put("requirement_id", "r3").put("kind", "effect").put("statement", "The answer stays brief.").
					putNull("tool").put("on_tool_failure", "fail");
				ArrayNode conflicts = output.putArray("conflicts");
				ObjectNode first = conflicts.addObject();
				first.putArray("requirement_ids").add("r1").add("r2").add("r3");
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
					"`r1`, `r2` and `r3`; `r2` and `r1`. Run revise with feedback that resolves each conflict.";
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

	/**
	 * Verifies that confirmation is refused when a recorded prompt test's {@code workspace/} links to a prompt file under
	 * another name, whose frozen copy would stand in for the candidate's version that every trial receives, and that the
	 * refusal names the linked file.
	 *
	 * @throws IOException if the project cannot be written
	 * @throws CommandRefusedException for the expected confirmation refusal
	 * @throws InterruptedException if the session wait is interrupted
	 */
	@Test(expectedExceptions = CommandRefusedException.class,
		expectedExceptionsMessageRegExp = ".*every trial.*`formatting` copies `CLAUDE\\.md` to " +
			"`docs/guide\\.md`.*")
	public void confirmRefusesLinkToPromptFile() throws IOException, CommandRefusedException, InterruptedException
	{
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Repository repository = repository(scope, _ -> derivation(output -> ((ObjectNode) output.get("tests").
				get(0)).putArray("regression_scenarios").add(".cat/tests/CLAUDE/formatting/scenario.md")));
			Path test = Files.createDirectories(repository.root().resolve(".cat/tests/CLAUDE/formatting"));
			Files.writeString(test.resolve("scenario.md"), """
				# Formatting

				## Variables

				- **tone**: The tone of the summary.

				## Scenario

				Summarize the notes in {{tone}} style.

				## Assertions

				### keeps_tone

				- **kind**: effect
				- **description**: The summary keeps the {{tone}} tone.
				""");
			Path workspace = Files.createDirectories(test.resolve("workspace/docs"));
			Files.createSymbolicLink(workspace.resolve("guide.md"), Path.of("../../../../../../CLAUDE.md"));

			String id = repository.core().start(Path.of("CLAUDE.md")).status().campaignId();
			repository.core().confirm(id, CertificationScope.LATEST_VERSIONS);
		}
	}
}
