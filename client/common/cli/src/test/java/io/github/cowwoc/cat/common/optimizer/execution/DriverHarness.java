package io.github.cowwoc.cat.common.optimizer.execution;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.fakes.ManualClock;
import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.Builder;
import io.github.cowwoc.cat.common.optimizer.campaign.MeasurementCondition;
import io.github.cowwoc.cat.common.optimizer.campaign.MeasurementIdentity;
import io.github.cowwoc.cat.common.optimizer.campaign.Verdict;
import io.github.cowwoc.cat.common.optimizer.execution.prompt.JudgePrompt;
import io.github.cowwoc.cat.common.optimizer.inventory.Authority;
import io.github.cowwoc.cat.common.optimizer.inventory.FileContent;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.RegularFile;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.Overlay;
import io.github.cowwoc.cat.common.optimizer.inventory.OverlayEntry.Write;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;
import io.github.cowwoc.cat.common.optimizer.port.FakeHostInterruptSignal;
import io.github.cowwoc.cat.common.optimizer.port.FakeSessionPort;
import io.github.cowwoc.cat.common.optimizer.port.FakeSessionPort.FakeSession;
import io.github.cowwoc.cat.common.optimizer.port.FakeWorkspacePort;
import io.github.cowwoc.cat.common.optimizer.port.SessionRequest;
import io.github.cowwoc.cat.common.optimizer.port.SessionResult;
import io.github.cowwoc.cat.common.optimizer.port.SessionRole;
import io.github.cowwoc.cat.common.optimizer.store.InMemoryCampaignStore;
import io.github.cowwoc.cat.common.optimizer.store.InMemoryOverlays;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The ports, fakes and session script of one driver test.
 * <p>
 * Trial sessions reply with the response that the {@link #trials(Function) trial script} chooses. Judge sessions
 * return scripted judgment JSON that {@link JudgePrompt} parses, and author sessions write a new {@code CLAUDE.md}
 * into their effects directory.
 * <p>
 * <b>Thread Safety:</b> This class is not thread-safe.
 */
public final class DriverHarness
{
	/**
	 * The campaign's identifier.
	 */
	public static final String CAMPAIGN = "campaign-1";
	/**
	 * When each test starts.
	 */
	public static final Instant START = Instant.parse("2026-10-01T12:00:00Z");
	/**
	 * The prompt file of the fork.
	 */
	public static final Path PROMPT = Path.of("CLAUDE.md");
	/**
	 * The model that trial sessions observe.
	 */
	public static final String MODEL = "model-a";
	/**
	 * The model that judge sessions observe.
	 */
	public static final String JUDGE_MODEL = "judge-1";
	/**
	 * The prompt sources that every session observes loading, as the campaign recorded them.
	 */
	public static final String PROMPT_SOURCES = Builder.OBSERVED.values().get(
		MeasurementCondition.PROMPT_SOURCES);
	/**
	 * The permissions of every prompt file.
	 */
	private static final Set<PosixFilePermission> PERMISSIONS = PosixFilePermissions.fromString("rw-r--r--");
	/**
	 * Fail a test after this many driver steps instead of waiting indefinitely for its stopping condition.
	 */
	private static final int STEP_LIMIT = 100_000;
	private final Path directory;
	private final ManualClock clock = new ManualClock(START);
	private final InMemoryCampaignStore store = new InMemoryCampaignStore();
	private final CrashingStore crashingStore = new CrashingStore(store);
	private final FakeSessionPort sessions = new FakeSessionPort();
	private final FakeWorkspacePort fakeWorkspace;
	private final FakeHostInterruptSignal interrupts = new FakeHostInterruptSignal();
	private final InMemoryOverlays overlays = new InMemoryOverlays();
	private final RecordingAuthorProtocol author = new RecordingAuthorProtocol();
	private final JsonMapper json = JsonMapper.builder().build();
	private final JudgePrompt judge;
	private final Map<Path, Judgment> judgments = new HashMap<>();
	private final RecordingDeliverer deliverer = new RecordingDeliverer();
	private final RecordingTerminator terminator = new RecordingTerminator();
	private final FileInventory fork;
	private Function<SessionRequest, SessionResult> trials = _ -> passed();
	private Function<SessionRequest, SessionResult> authors = request -> edit(request, PROMPT);
	private Function<SessionRequest, SessionResult> judges = this::scriptedJudgment;
	private final Map<MeasurementCondition, String> environmentChanges = new EnumMap<>(MeasurementCondition.class);
	private CapCalibrator calibrator = new FixedCalibrator(FixedCalibrator.STANDARD_CAP);
	private SliceSettings settings = SliceSettings.DEFAULT;
	private final List<Integer> openTrials = new ArrayList<>();
	private int ended;
	private int edits;
	private int trialResults;

	/**
	 * Creates the ports of a test and persists a campaign.
	 *
	 * @param directory a directory that the test owns
	 * @param campaign the campaign, whose working candidate is in the optimization worktree
	 * @throws IOException if the campaign cannot be persisted
	 */
	public DriverHarness(Path directory, Campaign campaign) throws IOException
	{
		this(directory, campaign, new FileInventory(Path.of("/project"), Map.of(PROMPT, new RegularFile(
			Sha256.of("fork".getBytes(UTF_8)), PERMISSIONS))));
	}

	/**
	 * Creates the ports of a test on a fork and persists a campaign.
	 *
	 * @param directory a directory that the test owns
	 * @param campaign the campaign, whose working candidate is in the optimization worktree
	 * @param fork the files recorded at the fork, which hold {@link #PROMPT}
	 * @throws IOException if the campaign cannot be persisted
	 */
	public DriverHarness(Path directory, Campaign campaign, FileInventory fork) throws IOException
	{
		this(directory, campaign, fork, null);
	}

	/**
	 * Creates ports with recorded project files available to staged-source measurements.
	 *
	 * @param directory the fixture directory
	 * @param campaign the initial campaign
	 * @param fork the recorded inventory
	 * @param project the fixture's project files, or {@code null} for an in-memory workspace
	 * @throws IOException if the campaign cannot be persisted
	 */
	public DriverHarness(Path directory, Campaign campaign, FileInventory fork, Path project) throws IOException
	{
		this.directory = directory;
		this.fork = fork;
		this.judge = new JudgePrompt(json, Files.createDirectories(directory.resolve("judge")));
		overlays.put(Builder.INCUMBENT, Overlay.of(fork));
		for (int number = 1; number <= 5; ++number)
			overlays.put(Builder.snapshot("candidate-" + number), candidate("candidate-" + number));
		FileInventory working = overlays.require(campaign.development().working().snapshot()).snapshot();
		if (project == null)
			this.fakeWorkspace = new FakeWorkspacePort(directory.resolve("workspace"), working);
		else
			this.fakeWorkspace = new FakeWorkspacePort(directory.resolve("workspace"), working, project);
		fakeWorkspace.supplyFile(PROMPT, new FileContent("fork".getBytes(UTF_8)));
		store.replaceSnapshot(campaign);
	}

	/**
	 * Returns an overlay on the fork whose prompt holds a text.
	 *
	 * @param text the prompt's content
	 * @return the overlay
	 */
	public Overlay candidate(String text)
	{
		return Overlay.of(fork).write(PROMPT, new FileContent(text.getBytes(UTF_8)), PERMISSIONS);
	}

	/**
	 * Returns the driver's context.
	 *
	 * @return the context
	 */
	public DriverContext context()
	{
		Map<MeasurementCondition, String> environment = new EnumMap<>(MeasurementCondition.class);
		environment.putAll(Builder.OBSERVED.values());
		environment.remove(MeasurementCondition.MODEL);
		environment.remove(MeasurementCondition.REASONING);
		environment.remove(MeasurementCondition.JUDGE);
		environment.remove(MeasurementCondition.PROMPT_SOURCES);
		environment.putAll(environmentChanges);
		return new DriverContext(crashingStore, sessions, fakeWorkspace, interrupts, clock, overlays,
			judge, author, deliverer, calibrator, terminator, new LeftoverRuns(directory.resolve("runs"),
			JsonMapper.builder().build()), new Authority(Set.of(PROMPT), Set.of(), Set.of()), Set.of(PROMPT),
			new MeasurementIdentity(environment), directory.resolve("campaign.lock"), new Random(7), settings);
	}

	/**
	 * Opens a driver for the campaign.
	 *
	 * @return the driver
	 * @throws IOException if the driver cannot be opened
	 */
	public Driver open() throws IOException
	{
		return Driver.open(context(), CAMPAIGN).orElseThrow();
	}

	/**
	 * Replaces the trial script.
	 *
	 * @param script returns each trial session's result
	 */
	public void trials(Function<SessionRequest, SessionResult> script)
	{
		this.trials = script;
	}

	/**
	 * Replaces the author script.
	 *
	 * @param script returns each author session's result
	 */
	public void authors(Function<SessionRequest, SessionResult> script)
	{
		this.authors = script;
	}

	/**
	 * Replaces the judge script.
	 *
	 * @param script returns each judge session's result
	 */
	public void judges(Function<SessionRequest, SessionResult> script)
	{
		this.judges = script;
	}

	/**
	 * Changes a measurement condition that drivers opened later observe outside trials.
	 *
	 * @param condition the condition
	 * @param value its observed value
	 */
	public void observe(MeasurementCondition condition, String value)
	{
		environmentChanges.put(condition, value);
	}

	/**
	 * Returns the scripted judgment associated with the trial's read-only effects directory. Trial text does not
	 * determine this verdict; {@link #completed(String, String, String)} scripts a pass and {@link #failed()}
	 * scripts a failure.
	 *
	 * @param request the judge session's request
	 * @return the result
	 */
	public SessionResult.Completed scriptedJudgment(SessionRequest request)
	{
		Judgment judgment = judgments.get(request.readOnly().getFirst());
		if (judgment == null)
			throw new AssertionError("No judgment scripted for trial evidence: " + request.readOnly());
		return judged(judgment);
	}

	/**
	 * Returns a judge session's schema-valid final response, preserving its rationale exactly.
	 *
	 * @param judgment the scripted verdict and rationale
	 * @return the completed judge session
	 */
	public SessionResult.Completed judged(Judgment judgment)
	{
		String verdict = switch (judgment.verdict())
		{
			case PASS -> "pass";
			case FAIL -> "fail";
		};
		return judged(json.createObjectNode().put("verdict", verdict).put("rationale", judgment.rationale()).toString());
	}

	/**
	 * Returns the result of a judge session.
	 *
	 * @param response the judge's response
	 * @return the result
	 */
	public SessionResult.Completed judged(String response)
	{
		return new SessionResult.Completed(directory.resolve("judge-transcript"), List.of(List.of(response)),
			directory.resolve("judge-effects"), JUDGE_MODEL, null, null, List.of(), PROMPT_SOURCES, List.of());
	}

	/**
	 * Replaces the cap calibrator.
	 *
	 * @param value the calibrator
	 */
	public void calibrator(CapCalibrator value)
	{
		this.calibrator = value;
	}

	/**
	 * Replaces the slice settings.
	 *
	 * @param value the settings
	 */
	public void settings(SliceSettings value)
	{
		this.settings = value;
	}

	/**
	 * Returns a completed trial session that the judge passes.
	 *
	 * @return the result
	 */
	public SessionResult.Completed passed()
	{
		return completed("pass", MODEL);
	}

	/**
	 * Returns a completed trial session that the judge fails.
	 *
	 * @return the result
	 */
	public SessionResult.Completed failed()
	{
		return failed(MODEL);
	}

	/**
	 * Returns a completed trial under an observed model, with a scripted failing judge response.
	 *
	 * @param model the model observed by the trial session
	 * @return the trial result
	 */
	public SessionResult.Completed failed(String model)
	{
		SessionResult.Completed result = completed("fail", model);
		judgments.put(result.effects(), new Judgment(Verdict.FAIL, "The trial did not meet the requirements."));
		return result;
	}

	/**
	 * Returns a completed trial session with a scripted passing judge response.
	 *
	 * @param response the trial's response, supplied to the judge as evidence
	 * @param model the observed model
	 * @return the result
	 */
	public SessionResult.Completed completed(String response, String model)
	{
		return completed(response, model, PROMPT_SOURCES);
	}

	/**
	 * Returns a completed trial session with a scripted passing judge response and distinct evidence paths.
	 *
	 * @param response the trial's response, supplied to the judge as evidence
	 * @param model the observed model
	 * @param promptSources the prompt sources that the session observed loading
	 * @return the result
	 */
	public SessionResult.Completed completed(String response, String model, String promptSources)
	{
		++trialResults;
		Path evidence = directory.resolve("trial-evidence").resolve(String.valueOf(trialResults));
		Path effects = evidence.resolve("project");
		judgments.put(effects, new Judgment(Verdict.PASS, "The trial met the requirements."));
		return new SessionResult.Completed(evidence.resolve("artifacts/transcript"), List.of(List.of(response)),
			effects, model, "reasoning-1", null, List.of(), promptSources, List.of());
	}

	/**
	 * Returns the category of a trial session.
	 *
	 * @param request the session's request
	 * @return {@code safety} or {@code formatting}
	 */
	public static String cell(SessionRequest request)
	{
		if (request.turns().getFirst().startsWith("Delete"))
			return "safety";
		return "formatting";
	}

	/**
	 * Returns the candidate snapshot that a trial session runs.
	 *
	 * @param request the session's request
	 * @return the snapshot that the campaign records
	 */
	public Sha256 snapshot(SessionRequest request)
	{
		Sha256 identity = new Sha256(request.source().getFileName().toString());
		return overlays.snapshotOf(identity).orElse(identity);
	}

	/**
	 * Writes an author's edit of one file into a new effects directory.
	 *
	 * @param request the author session's request
	 * @param file the edited file, relative to the project
	 * @return the completed author session
	 */
	public SessionResult edit(SessionRequest request, Path file)
	{
		return edit(request, file, "author-" + (edits + 1));
	}

	/**
	 * Writes specific prompt bytes as an author's edit.
	 *
	 * @param request the author request
	 * @param file the changed prompt path
	 * @param text the edited content
	 * @return the completed session
	 */
	public SessionResult edit(SessionRequest request, Path file, String text)
	{
		++edits;
		Path effects = directory.resolve("effects").resolve(String.valueOf(edits));
		byte[] content = text.getBytes(UTF_8);
		try
		{
			Files.createDirectories(effects);
			Overlay working = overlays.require(campaign().development().working().snapshot());
			Write entry = (Write) working.entries().get(file);
			byte[] before = "fork".getBytes(UTF_8);
			if (entry != null)
				before = entry.content().bytes();
			else if (file.isAbsolute() && Files.isRegularFile(file))
				before = Files.readAllBytes(file);
			Files.write(effects.resolve("before"), before);
			Files.writeString(effects.resolve("changed-path"), file.toString(), UTF_8);
			Files.write(effects.resolve(file), content);
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
		FileInventory before = fakeWorkspace.scanAuthoringCopy(request.source());
		Map<Path, FileEntry> entries = new HashMap<>(before.entries());
		entries.put(file, new RegularFile(Sha256.of(content), PERMISSIONS));
		// The fake rejects an unprepared copy, so an author request cannot edit a project the driver did not supply
		fakeWorkspace.editAuthoringCopy(request.source(), new FileInventory(effects, entries));
		return new SessionResult.Completed(directory.resolve("author-transcript"), List.of(List.of("done")), effects, MODEL,
			"reasoning-1", null, List.of(), PROMPT_SOURCES, List.of());
	}

	/**
	 * Ends every running session with its scripted result.
	 *
	 * @return the number of sessions ended
	 */
	public int endSessions()
	{
		List<FakeSession> running = running();
		int trialCount = 0;
		for (FakeSession session : running)
		{
			if (session.request().role() == SessionRole.TRIAL)
				++trialCount;
		}
		openTrials.add(trialCount);
		for (FakeSession session : running)
			session.end(respond(session.request()));
		return running.size();
	}

	/**
	 * Indicates whether a running session was ended. Ends the oldest one with its scripted result.
	 *
	 * @return {@code true} if a session was running
	 */
	public boolean endOldest()
	{
		List<FakeSession> running = running();
		if (running.isEmpty())
			return false;
		FakeSession oldest = running.getFirst();
		oldest.end(respond(oldest.request()));
		return true;
	}

	/**
	 * Returns the sessions that have not ended.
	 *
	 * @return the sessions, in start order
	 */
	public List<FakeSession> running()
	{
		List<FakeSession> started = sessions.sessions();
		while (ended < started.size() && started.get(ended).hasEnded())
			++ended;
		List<FakeSession> running = new ArrayList<>();
		for (FakeSession session : started.subList(ended, started.size()))
		{
			if (!session.hasEnded())
				running.add(session);
		}
		return running;
	}

	/**
	 * Returns the running trial sessions.
	 *
	 * @return the sessions, in start order
	 */
	public List<FakeSession> runningTrials()
	{
		List<FakeSession> trialSessions = new ArrayList<>();
		for (FakeSession session : running())
		{
			if (session.request().role() == SessionRole.TRIAL)
				trialSessions.add(session);
		}
		return trialSessions;
	}

	/**
	 * Steps a driver and ends only its judge sessions, until no judge session runs, so that every trial session
	 * that the test ended is judged and adopted.
	 *
	 * @param driver the driver
	 * @return the last step
	 * @throws IOException if a step fails
	 */
	public Step settle(Driver driver) throws IOException
	{
		for (int iteration = 0; iteration < STEP_LIMIT; ++iteration)
		{
			Step step = driver.step();
			int judges = 0;
			for (FakeSession session : running())
			{
				if (session.request().role() == SessionRole.JUDGE)
				{
					session.end(respond(session.request()));
					++judges;
				}
			}
			if (judges == 0)
				return step;
		}
		throw new AssertionError("The judges did not settle after " + STEP_LIMIT + " steps");
	}

	/**
	 * Returns the number of running trial sessions before each time {@link #endSessions()} ended them.
	 *
	 * @return the counts, oldest first
	 */
	public List<Integer> openTrials()
	{
		return List.copyOf(openTrials);
	}

	/**
	 * Forgets the counts that {@link #openTrials()} returns.
	 */
	public void clearOpenTrials()
	{
		openTrials.clear();
	}

	/**
	 * Returns the scripted result of a session.
	 *
	 * @param request the session's request
	 * @return the result
	 */
	private SessionResult respond(SessionRequest request)
	{
		return switch (request.role())
		{
			case TRIAL -> trials.apply(request);
			case JUDGE -> judges.apply(request);
			case AUTHOR -> authors.apply(request);
			case DERIVER -> throw new AssertionError("The driver started a deriver session: " + request);
		};
	}

	/**
	 * Steps a driver, ending its sessions after each step, until the slice stops. When no session runs, the clock
	 * moves to the time that the driver asks to be woken.
	 *
	 * @param driver the driver
	 * @return the slice's result
	 * @throws IOException if a step fails
	 */
	public SliceResult runSlice(Driver driver) throws IOException
	{
		for (int iteration = 0; iteration < STEP_LIMIT; ++iteration)
		{
			Step step = driver.step();
			switch (step)
			{
				case Step.Stopped stopped ->
				{
					return stopped.result();
				}
				case Step.Continue next ->
				{
					if (endSessions() == 0 && next.wakeBy().isAfter(clock.instant()))
						clock.set(next.wakeBy());
				}
			}
		}
		throw new AssertionError("The slice did not stop after " + STEP_LIMIT + " steps");
	}

	/**
	 * Steps a driver, ending its sessions after each step, until the persisted campaign meets a condition.
	 *
	 * @param driver the driver
	 * @param condition the condition
	 * @return the persisted campaign that meets the condition
	 * @throws IOException if a step fails
	 * @throws AssertionError if the slice stops first
	 */
	public Campaign runUntil(Driver driver, Predicate<Campaign> condition) throws IOException
	{
		for (int iteration = 0; iteration < STEP_LIMIT; ++iteration)
		{
			Step step = driver.step();
			Campaign campaign = campaign();
			if (condition.test(campaign))
				return campaign;
			switch (step)
			{
				case Step.Stopped stopped -> throw new AssertionError("The slice stopped first: " + stopped.result());
				case Step.Continue next ->
				{
					if (endSessions() == 0 && next.wakeBy().isAfter(clock.instant()))
						clock.set(next.wakeBy());
				}
			}
		}
		throw new AssertionError("The condition did not hold after " + STEP_LIMIT + " steps");
	}

	/**
	 * Returns the persisted campaign.
	 *
	 * @return the campaign
	 * @throws IOException if the store cannot be read
	 */
	public Campaign campaign() throws IOException
	{
		return store.load(CAMPAIGN).orElseThrow();
	}

	/**
	 * Returns the test's directory.
	 *
	 * @return the directory
	 */
	public Path directory()
	{
		return directory;
	}

	/**
	 * Returns the clock.
	 *
	 * @return the clock
	 */
	public ManualClock clock()
	{
		return clock;
	}

	/**
	 * Returns the store.
	 *
	 * @return the store
	 */
	public InMemoryCampaignStore store()
	{
		return store;
	}

	/**
	 * Returns the store that the driver uses, which can simulate a crash.
	 *
	 * @return the store
	 */
	public CrashingStore crashingStore()
	{
		return crashingStore;
	}

	/**
	 * Returns the session port.
	 *
	 * @return the port
	 */
	public FakeSessionPort sessions()
	{
		return sessions;
	}

	/**
	 * Returns the workspace.
	 *
	 * @return the workspace
	 */
	public FakeWorkspacePort workspace()
	{
		return fakeWorkspace;
	}

	/**
	 * Returns the host interrupt signal.
	 *
	 * @return the signal
	 */
	public FakeHostInterruptSignal interrupts()
	{
		return interrupts;
	}

	/**
	 * Returns the candidate overlays.
	 *
	 * @return the overlays
	 */
	public InMemoryOverlays overlays()
	{
		return overlays;
	}

	/**
	 * Returns the author protocol.
	 *
	 * @return the protocol
	 */
	public RecordingAuthorProtocol author()
	{
		return author;
	}

	/**
	 * Returns the deliverer.
	 *
	 * @return the deliverer
	 */
	public RecordingDeliverer deliverer()
	{
		return deliverer;
	}

	/**
	 * Returns the process terminator.
	 *
	 * @return the terminator
	 */
	public RecordingTerminator terminator()
	{
		return terminator;
	}
}
