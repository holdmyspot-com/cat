package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.FileVisitResult.CONTINUE;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.COPY_ATTRIBUTES;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;

import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.PluginInfo;
import com.holdmyspot.cat.common.PortableFiles;
import com.holdmyspot.cat.common.runner.policy.HarnessProcesses;
import com.holdmyspot.cat.common.runner.policy.ProcessDescription;
import com.holdmyspot.cat.common.secrets.NestedSessionManagerAccess;
import com.holdmyspot.cat.common.secrets.NestedSessionManagerSecrets;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Creates, records, and deletes the isolated directories used by harness executions.
 */
public final class NestedSessionManager
{
	static
	{
		NestedSessionManagerSecrets.setNestedSessionManagerAccess(new NestedSessionManagerAccess()
		{
			@Override
			public int maximumManifestBytes()
			{
				return MAXIMUM_MANIFEST_BYTES;
			}

			@Override
			public NestedSessionManager newNestedSessionManager(FileLocks fileLocks, Path root, Clock clock,
				FileReplacer replacer, HarnessProcessLiveness processLiveness)
			{
				return new NestedSessionManager(fileLocks, root, clock, replacer, processLiveness);
			}
		});
	}

	/**
	 * Default retention period for terminal diagnostic nested sessions.
	 */
	public static final Duration DEFAULT_RETENTION = Duration.ofDays(7);
	static final int MAXIMUM_MANIFEST_BYTES = 1_048_576;
	/**
	 * The name of a session folder, which is a generated slot ID.
	 */
	private static final Pattern SLOT_ID = Pattern.compile("[a-z0-9]+-[a-z0-9]+");
	/**
	 * The syntax of the identifier that a harness gives a session: Claude Code's session ID and Codex's thread ID.
	 */
	public static final Pattern HARNESS_SESSION_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
	private static final Pattern MANIFEST = Pattern.compile("""
		\\A\\{
		  "schema_version": (?<schemaVersion>[3-6]),
		  "cat_version": "(?<catVersion>[^\"]+)",
		  "harness": "(?<harness>[a-z0-9]+)",
		  "id": "(?<id>[A-Za-z0-9][A-Za-z0-9._:-]{0,127})",
		  "state": "(?<state>running|completed|failed)",
		  "phase": "(?<phase>initializing|idle|working|completed|failed)",
		  "owner_pid": "?(?<ownerPid>[1-9][0-9]*)"?,
		  "owner_started_at": (?<ownerStart>null|"(?<ownerStartValue>[^\"]+)"),\
		(?:\\n  "harness_process_state": "(?<harnessProcessState>absent|starting|recorded)",)?
		  "harness_pid": (?<harnessPid>null|[1-9][0-9]*),
		  "harness_started_at": (?<harnessStart>null|"(?<harnessStartValue>[^\"]+)"),
		  "created_at": "(?<createdAt>[^\"]+)",\
		(?:\\n  "harness_session_id": (?<harnessSessionId>null|\
		"(?<harnessSessionIdValue>[A-Za-z0-9][A-Za-z0-9._:-]{0,127})"),)?\
		(?:\\n  "requests_used": (?<requestsUsed>0|[1-9][0-9]*),)?
		  "artifact_path": "(?<artifactPath>[^"\\\\]*+(?:\\\\.[^"\\\\]*+)*+)",
		  "project_path": "(?<projectPath>[^"\\\\]*+(?:\\\\.[^"\\\\]*+)*+)",
		  "transport": "(?<transport>[^"\\\\]*+(?:\\\\.[^"\\\\]*+)*+)",
		  "harness_command": "(?<harnessCommand>[^"\\\\]*+(?:\\\\.[^"\\\\]*+)*+)",
		  "started_at": (?<startedAt>null|"(?<startedAtValue>[^\"]+)"),
		  "turn_number": (?<turnNumber>[0-9]+),
		  "exit_code": (?<exitCode>null|-?[0-9]+),
		  "finished_at": (?<finishedAt>null|"(?<finishedAtValue>[^\"]+)"),
		  "diagnostic": "[^"\\\\]*+(?:\\\\.[^"\\\\]*+)*+"
		}\\n\\z""");

	/**
	 * Opens the results folder of a runner command that is starting. It first deletes the root's stale nested sessions,
	 * as {@link #deleteStaleSessions(Duration) deleteStaleSessions(DEFAULT_RETENTION)} does, so that a nested session
	 * whose owner ended without recording a terminal result is marked failed. A nested session whose owner is alive stays
	 * running.
	 *
	 * @param fileLocks the process-owned lock coordinator
	 * @param root      the absolute owned-results folder
	 * @param clock     the clock used for generated identifiers
	 * @param replacer  replaces the nested sessions' manifests and output files
	 * @return a manager of the nested sessions in {@code root}
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code root} is relative
	 * @throws IOException              if session folders cannot be inspected or deleted
	 */
	public static NestedSessionManager openResultsFolder(FileLocks fileLocks, Path root, Clock clock,
		FileReplacer replacer) throws IOException
	{
		NestedSessionManager manager = new NestedSessionManager(fileLocks, root, clock, replacer);
		manager.deleteStaleSessions(DEFAULT_RETENTION);
		return manager;
	}

	private final FileLocks fileLocks;
	private final Path root;
	private final Clock clock;
	private final FileReplacer replacer;
	private final HarnessProcessLiveness processLiveness;
	/**
	 * The identifier of every nested session that {@link #create(String)} makes, or {@code null} to generate one for each
	 * nested session.
	 */
	private final String fixedSlotId;
	/**
	 * The launch options that each created nested session records as fixed at its start, or {@code null} if the nested
	 * sessions record none.
	 */
	private final List<String> launchArguments;

	/**
	 * Creates a manager rooted at an absolute directory.
	 *
	 * @param fileLocks the process-owned lock coordinator
	 * @param root the absolute owned-results folder
	 * @param clock the clock used for generated identifiers
	 * @param replacer replaces the nested sessions' manifests and output files
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code root} is relative
	 */
	public NestedSessionManager(FileLocks fileLocks, Path root, Clock clock, FileReplacer replacer)
	{
		this(fileLocks, root, clock, replacer, new ProcessHandleLiveness());
	}

	/**
	 * Creates a manager with process liveness support.
	 *
	 * @param fileLocks the process-owned lock coordinator
	 * @param root the absolute owned-results folder
	 * @param clock the clock used for generated identifiers
	 * @param replacer replaces the nested sessions' manifests and output files
	 * @param processLiveness the exact-process liveness service for the nested sessions' owner and harness processes
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code root} is relative
	 */
	NestedSessionManager(FileLocks fileLocks, Path root, Clock clock, FileReplacer replacer,
		HarnessProcessLiveness processLiveness)
	{
		this(fileLocks, root, clock, replacer, processLiveness, null, null);
	}

	/**
	 * Creates a manager whose nested sessions may all have one identifier.
	 *
	 * @param fileLocks the process-owned lock coordinator
	 * @param root the absolute owned-results folder
	 * @param clock the clock used for generated identifiers
	 * @param replacer replaces the nested sessions' manifests and output files
	 * @param processLiveness the exact-process liveness service for the nested sessions' owner and harness processes
	 * @param fixedSlotId the identifier of every nested session that the manager creates, or {@code null} to generate one
	 *                    for each
	 * @param launchArguments the launch options that each created nested session records as fixed at its start, or {@code
	 *   null} if the nested sessions record none
	 * @throws NullPointerException if any of the arguments other than {@code fixedSlotId} and {@code launchArguments} are
	 *   null
	 * @throws IllegalArgumentException if {@code root} is relative, or {@code fixedSlotId} is not a slot ID
	 */
	private NestedSessionManager(FileLocks fileLocks, Path root, Clock clock, FileReplacer replacer,
		HarnessProcessLiveness processLiveness, String fixedSlotId, List<String> launchArguments)
	{
		requireThat(fileLocks, "fileLocks").isNotNull();
		requireThat(root, "root").isNotNull();
		requireThat(clock, "clock").isNotNull();
		requireThat(replacer, "replacer").isNotNull();
		requireThat(processLiveness, "processLiveness").isNotNull();
		if (!root.isAbsolute())
			throw new IllegalArgumentException("root must be absolute");
		if (fixedSlotId != null && !SLOT_ID.matcher(fixedSlotId).matches())
			throw new IllegalArgumentException("fixedSlotId must be a slot ID: " + fixedSlotId);
		this.fileLocks = fileLocks;
		this.root = root.normalize();
		this.clock = clock;
		this.replacer = replacer;
		this.processLiveness = processLiveness;
		this.fixedSlotId = fixedSlotId;
		if (launchArguments == null)
			this.launchArguments = null;
		else
			this.launchArguments = List.copyOf(launchArguments);
	}

	/**
	 * Returns the directory that holds every nested session and the workspace files of every nested session.
	 *
	 * @return the absolute, normalized results folder
	 */
	public Path root()
	{
		return root;
	}

	/**
	 * Returns a manager over the same root whose {@link #create(String) created} runs all have one identifier, so that
	 * each nested session lives at the same path as the one before it. The caller must empty the nested session's
	 * directory before every nested session, and must keep it from running two nested sessions at once.
	 *
	 * @param slotId the identifier of every nested session that the returned manager creates
	 * @return the manager
	 * @throws NullPointerException     if {@code slotId} is null
	 * @throws IllegalArgumentException if {@code slotId} is not a slot ID, which is two groups of lowercase
	 *                                  letters and digits joined by a hyphen
	 */
	public NestedSessionManager withFixedSlotId(String slotId)
	{
		requireThat(slotId, "slotId").isNotNull();
		return new NestedSessionManager(fileLocks, root, clock, replacer, processLiveness, slotId, launchArguments);
	}

	/**
	 * Returns a manager over the same root whose {@link #create(String) created} runs each record, in
	 * {@value NestedSessionLaunch#FILE_NAME} beside their manifest, the launch options that their start fixed, so that a
	 * later runner can continue a nested session's harness session as the nested session started it.
	 *
	 * @param arguments the fixed options and their values, as {@link NestedSessionLaunch#fixedArguments(String[])}
	 *                  returns them
	 * @return the manager
	 * @throws NullPointerException if {@code arguments} is null
	 */
	public NestedSessionManager withLaunchArguments(List<String> arguments)
	{
		requireThat(arguments, "arguments").isNotNull();
		return new NestedSessionManager(fileLocks, root, clock, replacer, processLiveness, fixedSlotId, arguments);
	}

	/**
	 * Returns the launch options that a nested session's start fixed.
	 *
	 * @param nestedSession the nested session
	 * @return the fixed options and their values, in the order its launch named them
	 * @throws NullPointerException if {@code nestedSession} is null
	 * @throws IOException          if the nested session records no launch, or its record is malformed
	 */
	public List<String> launchArguments(NestedSession nestedSession) throws IOException
	{
		requireThat(nestedSession, "nestedSession").isNotNull();
		return NestedSessionLaunch.read(JsonMapper.builder().build(), nestedSession.directory());
	}

	/**
	 * Indicates whether text is the identifier of a nested session, which is also the name of its directory.
	 *
	 * @param text the text
	 * @return {@code true} if the text is two groups of lowercase letters and digits joined by a hyphen
	 * @throws NullPointerException if {@code text} is null
	 */
	public static boolean isSlotId(String text)
	{
		requireThat(text, "text").isNotNull();
		return SLOT_ID.matcher(text).matches();
	}

	/**
	 * Writes the manifest of a nested session into the directory of its copy under the identifier by which the copy is
	 * kept, naming {@code project} as the nested session's project. The copy's manifest is replaced in one step, so a
	 * reader sees either no manifest, the manifest that was there, or the new one. A nested session that has no manifest
	 * leaves the copy without one.
	 *
	 * @param source    the directory of the nested session
	 * @param directory the directory of the copy, which exists
	 * @param id        the identifier under which the copy is kept, which the harness gave the nested session's harness
	 *                  session
	 * @param project   the project that the copy's manifest names
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code id} is not a harness session identifier
	 * @throws IOException              if the nested session's manifest is malformed or the copy's cannot be written
	 */
	public static void copyManifest(Path source, Path directory, String id, Path project) throws IOException
	{
		requireThat(source, "source").isNotNull();
		requireThat(directory, "directory").isNotNull();
		requireThat(id, "id").matches(HARNESS_SESSION_ID);
		requireThat(project, "project").isNotNull();
		String projectPath = escape(project.toAbsolutePath().normalize().toString());
		writeCopyManifest(source, directory, id, _ -> projectPath);
	}

	/**
	 * Writes the manifest of a nested session into the directory of its copy with another identifier and project.
	 *
	 * @param source    the directory of the nested session
	 * @param directory the directory of the copy, which exists
	 * @param id        the identifier under which the copy is kept
	 * @param project   returns the project path that the copy's manifest names, as JSON string content, from the one
	 *                  that the nested session's manifest names
	 * @throws IOException if the nested session's manifest is malformed or the copy's cannot be written
	 */
	private static void writeCopyManifest(Path source, Path directory, String id, UnaryOperator<String> project)
		throws IOException
	{
		Path manifest = source.resolve("session.json");
		if (!Files.isRegularFile(manifest, NOFOLLOW_LINKS))
			return;
		String content = Files.readString(manifest);
		Matcher matcher = MANIFEST.matcher(content);
		if (!matcher.matches())
			throw new IOException("Session manifest is malformed or unsupported: " + manifest);
		Files.createDirectories(directory);
		Path replacement = Files.createTempFile(directory, "run-", ".json");
		Files.writeString(replacement, content.substring(0, matcher.start("id")) + id +
			content.substring(matcher.end("id"), matcher.start("projectPath")) +
			project.apply(matcher.group("projectPath")) + content.substring(matcher.end("projectPath")));
		Files.move(replacement, directory.resolve("session.json"), REPLACE_EXISTING);
	}

	/**
	 * Deletes a nested session's directory, including its read-only files and directories, and deletes the nested
	 * session's manifest after everything else in it. A deletion that stops partway, such as one whose process is killed,
	 * then leaves the manifest, which names the nested session's processes for the cleanup that comes after it. Entries
	 * that a concurrent cleanup removes first are skipped.
	 *
	 * @param directory the nested session's directory
	 * @throws NullPointerException if {@code directory} is null
	 * @throws IOException          if the directory cannot be deleted
	 */
	public static void deleteSessionFolder(Path directory) throws IOException
	{
		requireThat(directory, "directory").isNotNull();
		Path manifest = directory.resolve("session.json");
		List<Path> entries;
		try
		{
			// Removing an entry needs the right to write its directory
			if (PortableFiles.isReadOnly(directory))
				PortableFiles.clearReadOnly(directory);
			try (Stream<Path> listed = Files.list(directory))
			{
				entries = listed.filter(entry -> !entry.equals(manifest)).toList();
			}
		}
		catch (NoSuchFileException _)
		{
			// A concurrent cleanup already removed the nested session
			return;
		}
		for (Path entry : entries)
			delete(entry);
		delete(manifest);
		delete(directory);
	}

	/**
	 * Returns the directory of a nested session that holds the read-only copies of the targets of the project's symbolic
	 * links outside the project, each copy in a folder of its own beside the project. The directory exists only in a
	 * nested session whose copied project has such a link.
	 *
	 * @param sessionFolder the nested session's directory
	 * @return the nested session's {@code link-targets} directory
	 * @throws NullPointerException if {@code sessionFolder} is null
	 */
	public static Path linkTargets(Path sessionFolder)
	{
		requireThat(sessionFolder, "sessionFolder").isNotNull();
		return sessionFolder.resolve(RequestArchive.LINK_TARGETS);
	}

	/**
	 * Creates one owned session folder, named by the manager's fixed slot ID if it has one.
	 *
	 * @param harness the harness name
	 * @return the created nested session
	 * @throws IOException if the directory cannot be created, or a nested session with the fixed identifier exists
	 *                     already
	 */
	public NestedSession create(String harness) throws IOException
	{
		requireThat(harness, "harness").isNotBlank();
		String id = fixedSlotId;
		if (id == null)
			id = newSlotId(clock);
		Path directory = root.resolve(harness).resolve(id).normalize();
		if (!directory.startsWith(root))
			throw new IOException("Session folder escapes the configured root");
		if (fixedSlotId != null)
		{
			// A nested session that reuses a path must start from nothing, so that it sees no file of the nested session
			// before it
			Files.createDirectories(directory.getParent());
			try
			{
				Files.createDirectory(directory);
			}
			catch (FileAlreadyExistsException e)
			{
				throw new IOException("The session folder is not empty, so a nested session cannot reuse it: " + directory, e);
			}
		}
		else
			Files.createDirectories(directory);
		setOwnerOnlyDirectoryPermissions(directory);
		Files.createDirectories(directory.resolve("project"));
		Files.createDirectories(directory.resolve("home"));
		Files.createDirectories(directory.resolve("artifacts"));
		Files.createFile(directory.resolve(".lock"));
		NestedSession nestedSession = new NestedSession(harness, id, directory);
		ProcessHandle owner = ProcessHandle.current();
		String ownerStart = ownerStart(owner, directory);
		writeManifest(new Manifest(harness, id, "running", "initializing", HarnessProcessState.ABSENT,
			owner.pid(), ownerStart, null, null,
			clock.instant(), null, 0,
			"", "", "", "", null, 0, null, null, ""), directory);
		if (launchArguments != null)
			NestedSessionLaunch.write(JsonMapper.builder().build(), directory, launchArguments, replacer);
		return nestedSession;
	}

	/**
	 * Returns the start of the process that owns a nested session.
	 *
	 * @param owner     the process
	 * @param directory the nested session's directory
	 * @return the process's {@link ProcessDescription#start() start}
	 * @throws IOException if the system reports no start for the process
	 */
	private static String ownerStart(ProcessHandle owner, Path directory) throws IOException
	{
		// An owner without a start cannot be told apart from a later process, so its nested session is never taken for
		// abandoned
		String ownerStart = HarnessProcesses.system().find(owner.pid()).flatMap(ProcessDescription::start).orElse(null);
		if (ownerStart == null || !ownerStart.matches(ProcessDescription.START_PATTERN))
			throw new IOException("Cannot record the owner process identity for session folder: " + directory);
		return ownerStart;
	}

	/**
	 * Reopens a nested session whose runner ended before the nested session did, so that a new runner continues the
	 * nested session's harness session in the nested session's directory. The nested session must still be running, its
	 * harness must have reported its session, and neither its runner nor its harness process may live. The current
	 * process becomes the nested session's owner, and the nested session keeps its project, its home, its turns, its
	 * harness session and its count of accepted session requests. Reopening deletes no stale nested session, whatever the
	 * nested session's age.
	 *
	 * @param harness the nested session's harness
	 * @param slotId  the nested session's identifier
	 * @return the reopened nested session
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code harness} is blank, or {@code slotId} is not a slot ID
	 * @throws IOException              if the nested session is missing, another process holds its lock, it has ended,
	 *                                  its harness reported no session, its harness launch state is unknown, or its
	 *                                  runner or harness process is still alive
	 */
	public NestedSession reopen(String harness, String slotId) throws IOException
	{
		synchronized (this)
		{
			requireThat(harness, "harness").isNotBlank();
			requireThat(slotId, "slotId").matches(SLOT_ID);
			Path directory = root.resolve(harness).resolve(slotId).normalize();
			NestedSession nestedSession = new NestedSession(harness, slotId, directory);
			try (NestedSessionLock lock = acquireLock(nestedSession))
			{
				if (!lock.isHeld())
					throw new IOException("Another process holds the nested session: " + slotId);
				Manifest manifest = readManifest(nestedSession);
				if (!manifest.state().equals("running"))
					throw new IOException("The nested session has ended, so its harness session cannot continue: " + slotId);
				if (manifest.harnessSessionId() == null)
				{
					throw new IOException("The nested session's harness reported no session, so the nested session has none " +
						"to continue: " + slotId);
				}
				if (manifest.harnessProcessState() == HarnessProcessState.STARTING)
					throw new IOException("The nested session's harness launch state is unknown: " + slotId);
				if (isOwnerAlive(manifest))
					throw new IOException("The nested session is still owned by a live process: " + slotId);
				if (isHarnessProcessAlive(manifest))
					throw new IOException("The nested session's harness process is still alive: " + slotId);
				ProcessHandle owner = ProcessHandle.current();
				writeManifest(new Manifest(manifest.harness(), manifest.id(), "running", "initializing",
					HarnessProcessState.ABSENT, owner.pid(), ownerStart(owner, directory), null, null, manifest.createdAt(),
					manifest.harnessSessionId(), manifest.requestsUsed(), manifest.artifactPath(), manifest.projectPath(),
					manifest.transport(),
					manifest.harnessCommand(), manifest.startedAt(), manifest.turnNumber(), null, null, ""), directory);
			}
			return nestedSession;
		}
	}

	/**
	 * Reopens the nested session of this manager's slot, as {@link #reopen(String, String)} does, so that a new runner
	 * continues the harness session that the nested session records.
	 *
	 * @param harness          the nested session's harness
	 * @param harnessSessionId the harness session that the nested session must record
	 * @return the reopened nested session
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code harness} is blank
	 * @throws IllegalStateException    if the manager has no fixed slot ID
	 * @throws IOException              if the nested session cannot be reopened, or it records another harness session
	 */
	public NestedSession resume(String harness, String harnessSessionId) throws IOException
	{
		requireThat(harnessSessionId, "harnessSessionId").isNotNull();
		if (fixedSlotId == null)
			throw new IllegalStateException("Only the nested session of a slot can be resumed");
		NestedSession nestedSession = new NestedSession(harness, fixedSlotId,
			root.resolve(harness).resolve(fixedSlotId).normalize());
		if (Files.isRegularFile(nestedSession.directory().resolve("session.json")))
		{
			String recorded = readManifest(nestedSession).harnessSessionId();
			if (!harnessSessionId.equals(recorded))
			{
				throw new IOException("The nested session " + fixedSlotId + " records the harness session " + recorded +
					", not " + harnessSessionId);
			}
		}
		return reopen(harness, fixedSlotId);
	}

	/**
	 * Returns the number of the last turn of a nested session that ended.
	 *
	 * @param nestedSession the nested session
	 * @return the turn's number, or {@code 0} if no turn ended
	 * @throws NullPointerException if {@code nestedSession} is null
	 * @throws IOException          if the manifest cannot be read
	 */
	public int turnNumber(NestedSession nestedSession) throws IOException
	{
		requireThat(nestedSession, "nestedSession").isNotNull();
		return readManifest(nestedSession).turnNumber();
	}

	/**
	 * Records the identifier that the nested session's harness gives its session, once the harness reports it. A nested
	 * session records one identifier: a later report of the same identifier keeps it.
	 *
	 * @param nestedSession    the owned nested session
	 * @param harnessSessionId the harness's identifier of the nested session's harness session
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code harnessSessionId} does not match {@link #HARNESS_SESSION_ID}
	 * @throws IOException              if the nested session has ended, it recorded another identifier, or the manifest
	 *                                  cannot be persisted
	 */
	public void recordHarnessSession(NestedSession nestedSession, String harnessSessionId) throws IOException
	{
		synchronized (this)
		{
			requireThat(nestedSession, "nestedSession").isNotNull();
			requireThat(harnessSessionId, "harnessSessionId").matches(HARNESS_SESSION_ID);
			Manifest previous = readManifest(nestedSession);
			if (!previous.state().equals("running"))
				throw new IOException("Cannot record the harness session of a terminal nested session");
			if (previous.harnessSessionId() != null)
			{
				if (previous.harnessSessionId().equals(harnessSessionId))
					return;
				throw new IOException("The harness reported session " + harnessSessionId +
					", but the nested session continues session " + previous.harnessSessionId());
			}
			writeManifest(new Manifest(previous.harness(), previous.id(), previous.state(), previous.phase(),
				previous.harnessProcessState(), previous.ownerPid(), previous.ownerStart(), previous.harnessPid(),
				previous.harnessStart(), previous.createdAt(), harnessSessionId, previous.requestsUsed(),
				previous.artifactPath(),
				previous.projectPath(), previous.transport(), previous.harnessCommand(), previous.startedAt(),
				previous.turnNumber(), previous.exitCode(), previous.finishedAt(), previous.diagnostic()),
				nestedSession.directory());
		}
	}

	/**
	 * Returns the identifier that the nested session's harness gave its session.
	 *
	 * @param nestedSession the owned nested session
	 * @return the identifier, or empty if the harness has not reported it
	 * @throws NullPointerException if {@code nestedSession} is null
	 * @throws IOException          if the manifest is malformed or does not identify the nested session
	 */
	public Optional<String> harnessSessionId(NestedSession nestedSession) throws IOException
	{
		requireThat(nestedSession, "nestedSession").isNotNull();
		return Optional.ofNullable(readManifest(nestedSession).harnessSessionId());
	}

	/**
	 * Returns the identifier that the harness gave the session of the nested session in a directory, such as a slot's
	 * nested session that its requester's runner reads once the nested session has ended.
	 *
	 * @param directory the nested session's directory
	 * @return the identifier, or empty if the nested session has no manifest or the harness has not reported it
	 * @throws NullPointerException if {@code directory} is null
	 * @throws IOException          if the manifest is malformed or cannot be read
	 */
	public static Optional<String> harnessSessionId(Path directory) throws IOException
	{
		requireThat(directory, "directory").isNotNull();
		Path manifest = directory.resolve("session.json");
		if (!Files.isRegularFile(manifest, NOFOLLOW_LINKS))
			return Optional.empty();
		return Optional.ofNullable(readManifest(manifest).harnessSessionId());
	}

	/**
	 * Records how many of its session's session requests a running nested session has accepted, over every runner that
	 * served the nested session, so that a runner that resumes the nested session continues the count. The call may
	 * overlap this manager's other calls that update the nested session's manifest, and keeps what they record.
	 *
	 * @param nestedSession the owned nested session
	 * @param requestsUsed  how many requests the nested session has accepted
	 * @throws NullPointerException     if {@code nestedSession} is null
	 * @throws IllegalArgumentException if {@code requestsUsed} is negative
	 * @throws IOException              if the nested session has ended, or the manifest cannot be read or persisted
	 */
	public void recordRequestsUsed(NestedSession nestedSession, int requestsUsed) throws IOException
	{
		synchronized (this)
		{
			requireThat(nestedSession, "nestedSession").isNotNull();
			requireThat(requestsUsed, "requestsUsed").isNotNegative();
			Manifest previous = readManifest(nestedSession);
			if (!previous.state().equals("running"))
				throw new IOException("Cannot record the session requests of a terminal nested session");
			writeManifest(new Manifest(previous.harness(), previous.id(), previous.state(), previous.phase(),
				previous.harnessProcessState(), previous.ownerPid(), previous.ownerStart(), previous.harnessPid(),
				previous.harnessStart(), previous.createdAt(), previous.harnessSessionId(), requestsUsed,
				previous.artifactPath(), previous.projectPath(), previous.transport(), previous.harnessCommand(),
				previous.startedAt(), previous.turnNumber(), previous.exitCode(), previous.finishedAt(),
				previous.diagnostic()), nestedSession.directory());
		}
	}

	/**
	 * Returns how many of its session's session requests a nested session has accepted, over every runner that served the
	 * nested session.
	 *
	 * @param nestedSession the owned nested session
	 * @return the number of accepted requests
	 * @throws NullPointerException if {@code nestedSession} is null
	 * @throws IOException          if the manifest is malformed or does not identify the nested session
	 */
	public int requestsUsed(NestedSession nestedSession) throws IOException
	{
		requireThat(nestedSession, "nestedSession").isNotNull();
		return readManifest(nestedSession).requestsUsed();
	}

	/**
	 * Generates the identifier of a new nested session, which is unique across nested sessions started at different
	 * times.
	 *
	 * @param clock the clock that gives the time
	 * @return the identifier
	 * @throws NullPointerException if {@code clock} is null
	 */
	public static String newSlotId(Clock clock)
	{
		requireThat(clock, "clock").isNotNull();
		return Long.toUnsignedString(clock.millis(), 36) + "-" + Long.toUnsignedString(System.nanoTime(), 36);
	}

	/**
	 * Records non-secret metadata required to investigate an owned harness process.
	 *
	 * @param nestedSession the owned nested session
	 * @param artifact the complete harness artifact
	 * @param project the source project
	 * @param transport the persistent harness transport identifier
	 * @param command the harness command with any secret values redacted
	 * @throws IOException if the manifest cannot be persisted
	 */
	public void initialize(NestedSession nestedSession, Path artifact, Path project, String transport,
		List<String> command) throws IOException
	{
		synchronized (this)
		{
			requireThat(nestedSession, "nestedSession").isNotNull();
			requireThat(artifact, "artifact").isNotNull();
			requireThat(project, "project").isNotNull();
			requireThat(transport, "transport").isNotBlank();
			requireThat(command, "command").isNotEmpty();
			Manifest previous = readManifest(nestedSession);
			if (!previous.state().equals("running"))
				throw new IOException("Cannot initialize a terminal nested session");
			writeManifest(new Manifest(previous.harness(), previous.id(), previous.state(), previous.phase(),
				previous.harnessProcessState(),
				previous.ownerPid(),
				previous.ownerStart(), previous.harnessPid(), previous.harnessStart(), previous.createdAt(),
				previous.harnessSessionId(), previous.requestsUsed(),
				artifact.toAbsolutePath().normalize().toString(), project.toAbsolutePath().normalize().toString(), transport,
				redact(command), clock.instant(), previous.turnNumber(), previous.exitCode(), previous.finishedAt(),
				previous.diagnostic()), nestedSession.directory());
		}
	}

	/**
	 * Replaces the output file with persisted content and marks the owned nested session completed, which keeps it.
	 *
	 * @param nestedSession the owned nested session
	 * @param output the durable output path
	 * @param content the output content
	 * @throws IOException if output cannot be persisted
	 */
	public void complete(NestedSession nestedSession, Path output, String content) throws IOException
	{
		requireThat(nestedSession, "nestedSession").isNotNull();
		requireThat(output, "output").isNotNull();
		requireThat(content, "content").isNotNull();
		Path parent = output.toAbsolutePath().normalize().getParent();
		if (parent == null)
			throw new IOException("Output path cannot be the filesystem root: " + output);
		Files.createDirectories(parent);
		writeAtomically(output, content);
		markCompleted(nestedSession);
	}

	/**
	 * Records that a nested session completed successfully before cleanup begins.
	 *
	 * @param nestedSession the owned nested session
	 * @throws IOException if the manifest cannot be persisted, or this process is
	 *   {@link ProcessTreeStop#stopping() stopping}, which leaves a running nested session unended
	 */
	public void markCompleted(NestedSession nestedSession) throws IOException
	{
		requireThat(nestedSession, "nestedSession").isNotNull();
		writeManifest(nestedSession, "completed", "");
	}

	/**
	 * Records that a started harness is waiting for its next request.
	 *
	 * @param nestedSession the owned nested session
	 * @throws IOException if the manifest cannot be persisted
	 */
	public void markIdle(NestedSession nestedSession) throws IOException
	{
		setPhase(nestedSession, "idle");
	}

	/**
	 * Records that the harness is processing a submitted request.
	 *
	 * @param nestedSession the owned nested session
	 * @throws IOException if the manifest cannot be persisted
	 */
	public void markWorking(NestedSession nestedSession) throws IOException
	{
		setPhase(nestedSession, "working");
	}

	/**
	 * Records the identity of the harness process owned by a live nested session.
	 *
	 * @param nestedSession the owned nested session
	 * @param harnessPid the positive harness process identifier
	 * @param harnessStart the harness process's {@link ProcessDescription#start() start}
	 * @throws NullPointerException if {@code nestedSession} or {@code harnessStart} are null
	 * @throws IllegalArgumentException if {@code harnessPid} is not positive, or {@code harnessStart} does not match
	 *   {@link ProcessDescription#START_PATTERN}
	 * @throws IOException if the manifest cannot be persisted
	 */
	public void recordHarnessProcess(NestedSession nestedSession, long harnessPid, String harnessStart) throws IOException
	{
		synchronized (this)
		{
			requireThat(nestedSession, "nestedSession").isNotNull();
			requireThat(harnessStart, "harnessStart").matches(ProcessDescription.START_PATTERN);
			requireThat(harnessPid, "harnessPid").isPositive();
			Manifest previous = readManifest(nestedSession);
			if (!previous.state().equals("running"))
				throw new IOException("Cannot record a harness process for a terminal nested session");
			if (previous.harnessProcessState() != HarnessProcessState.STARTING)
				throw new IOException("Cannot record a harness process unless launch is marked starting");
			writeManifest(new Manifest(previous.harness(), previous.id(), previous.state(), previous.phase(),
				HarnessProcessState.RECORDED,
				previous.ownerPid(),
				previous.ownerStart(), harnessPid, harnessStart, previous.createdAt(), previous.harnessSessionId(),
				previous.requestsUsed(),
				previous.artifactPath(),
				previous.projectPath(), previous.transport(), previous.harnessCommand(), previous.startedAt(),
				previous.turnNumber(), previous.exitCode(), previous.finishedAt(), previous.diagnostic()),
				nestedSession.directory());
		}
	}

	/**
	 * Persists that a harness spawn is about to begin.
	 *
	 * @param nestedSession the owned nested session
	 * @throws IOException if the nested session is terminal, already started, or the marker cannot be persisted
	 */
	public void markHarnessStarting(NestedSession nestedSession) throws IOException
	{
		synchronized (this)
		{
			requireThat(nestedSession, "nestedSession").isNotNull();
			Manifest previous = readManifest(nestedSession);
			if (!previous.state().equals("running") || previous.harnessProcessState() != HarnessProcessState.ABSENT)
				throw new IOException("Cannot begin harness launch from the current nested-session state: " +
					nestedSession.directory());
			writeManifest(withHarnessProcess(previous, HarnessProcessState.STARTING, null, null), nestedSession.directory());
		}
	}

	/**
	 * Persists that no harness process remains after a launch attempt.
	 *
	 * @param nestedSession the owned nested session
	 * @throws IOException if the launch state is not starting or the marker cannot be persisted
	 */
	public void recordNoHarnessProcess(NestedSession nestedSession) throws IOException
	{
		synchronized (this)
		{
			requireThat(nestedSession, "nestedSession").isNotNull();
			Manifest previous = readManifest(nestedSession);
			if (!previous.state().equals("running") || previous.harnessProcessState() != HarnessProcessState.STARTING)
				throw new IOException("Cannot clear harness launch state from the current nested-session state: " +
					nestedSession.directory());
			writeManifest(withHarnessProcess(previous, HarnessProcessState.ABSENT, null, null), nestedSession.directory());
		}
	}

	/**
	 * Returns a manifest with updated harness ownership.
	 *
	 * @param previous the previous manifest
	 * @param processState the durable launch state
	 * @param pid the harness process identifier, if recorded
	 * @param start the harness process start, if recorded
	 * @return the updated manifest
	 */
	private static Manifest withHarnessProcess(Manifest previous, HarnessProcessState processState, Long pid,
		String start)
	{
		return new Manifest(previous.harness(), previous.id(), previous.state(), previous.phase(), processState,
			previous.ownerPid(), previous.ownerStart(), pid, start, previous.createdAt(), previous.harnessSessionId(),
			previous.requestsUsed(),
			previous.artifactPath(),
			previous.projectPath(), previous.transport(), previous.harnessCommand(), previous.startedAt(),
			previous.turnNumber(), previous.exitCode(), previous.finishedAt(), previous.diagnostic());
	}

	/**
	 * Replaces the captured standard streams of a harness command for failed-session investigation.
	 *
	 * @param nestedSession the owned nested session
	 * @param commandName the generated command name
	 * @param standardOutput the captured standard output
	 * @param standardError the captured standard error
	 * @throws IOException if the owned artifact files cannot be persisted
	 */
	public void writeCommandOutput(NestedSession nestedSession, String commandName, String standardOutput,
		String standardError) throws IOException
	{
		requireThat(nestedSession, "nestedSession").isNotNull();
		requireThat(commandName, "commandName").isNotBlank();
		requireThat(standardOutput, "standardOutput").isNotNull();
		requireThat(standardError, "standardError").isNotNull();
		if (!commandName.matches("[a-z0-9]+(?:-[a-z0-9]+)*"))
			throw new IllegalArgumentException("commandName must be a generated artifact name");
		Path artifacts = nestedSession.directory().resolve("artifacts");
		if (Files.isSymbolicLink(artifacts) || !Files.isDirectory(artifacts, NOFOLLOW_LINKS))
			throw new IOException("Session artifacts directory is not owned by this manager");
		writeAtomically(artifacts.resolve(commandName + ".stdout"), standardOutput);
		writeAtomically(artifacts.resolve(commandName + ".stderr"), standardError);
	}

	/**
	 * Records one harness turn's raw events and error stream for later investigation.
	 *
	 * @param nestedSession the owned nested session
	 * @param turnNumber the positive one-based turn number
	 * @param result the captured harness turn result
	 * @throws IOException if the owned turn artifacts cannot be persisted
	 */
	public void writeTurnOutput(NestedSession nestedSession, int turnNumber,
		HarnessProcessResult result) throws IOException
	{
		synchronized (this)
		{
			requireThat(nestedSession, "nestedSession").isNotNull();
			requireThat(result, "result").isNotNull();
			requireThat(turnNumber, "turnNumber").isPositive();
			Path artifacts = nestedSession.directory().resolve("artifacts");
			if (Files.isSymbolicLink(artifacts) || !Files.isDirectory(artifacts, NOFOLLOW_LINKS))
				throw new IOException("Session artifacts directory is not owned by this manager");
			String prefix = "turn-" + turnNumber;
			writeAtomically(artifacts.resolve(prefix + ".events"), result.standardOutput());
			writeAtomically(artifacts.resolve(prefix + ".stderr"), result.standardError());
			Manifest previous = readManifest(nestedSession);
			writeManifest(new Manifest(previous.harness(), previous.id(), previous.state(), previous.phase(),
				previous.harnessProcessState(),
				previous.ownerPid(),
				previous.ownerStart(), previous.harnessPid(), previous.harnessStart(), previous.createdAt(),
				previous.harnessSessionId(), previous.requestsUsed(),
				previous.artifactPath(), previous.projectPath(), previous.transport(), previous.harnessCommand(),
				previous.startedAt(), turnNumber, result.exitCode(), previous.finishedAt(), previous.diagnostic()),
				nestedSession.directory());
		}
	}

	/**
	 * Appends one harness event while its turn remains active, making current progress inspectable before terminal output
	 * is available.
	 *
	 * @param nestedSession the owned nested session
	 * @param turnNumber the positive one-based turn number
	 * @param event one non-blank harness event line
	 * @throws IOException if the event cannot be persisted
	 */
	public void appendTurnEvent(NestedSession nestedSession, int turnNumber, String event) throws IOException
	{
		requireThat(nestedSession, "nestedSession").isNotNull();
		requireThat(turnNumber, "turnNumber").isPositive();
		requireThat(event, "event").isNotBlank();
		Path artifacts = nestedSession.directory().resolve("artifacts");
		if (Files.isSymbolicLink(artifacts) || !Files.isDirectory(artifacts, NOFOLLOW_LINKS))
			throw new IOException("Session artifacts directory is not owned by this manager");
		if (!readManifest(nestedSession).state().equals("running"))
			throw new IOException("Cannot append an event to a terminal nested session");
		Files.writeString(artifacts.resolve("turn-" + turnNumber + ".events"), event + "\n", CREATE, APPEND);
	}

	/**
	 * Copies project files into an owned nested session without source Git metadata or the placeholders that the sandbox
	 * lays over files that the session may not read. A symbolic link whose target stays inside the project stays a link
	 * with the same target; any other link stays a link too, retargeted to a read-only copy of the file or directory tree
	 * that it leads to, which lies in a folder of its own in the nested session's {@link #linkTargets(Path) link-targets}
	 * directory beside the project and which the nested session records so that {@link #changedLinkCopies(NestedSession)}
	 * can tell whether its session changed the copy.
	 *
	 * @param source the source project directory
	 * @param nestedSession the owned nested session
	 * @throws IOException if the source is not a real directory, a symbolic link in it leads to a missing, unreadable or
	 *                     hidden target, or copying fails
	 */
	public void copyProject(Path source, NestedSession nestedSession) throws IOException
	{
		copyProject(source, nestedSession, RequestArchive.NULL_DEVICE);
	}

	/**
	 * Copies project files into an owned nested session without source Git metadata or the placeholders that the sandbox
	 * lays over files that the session may not read. A symbolic link whose target stays inside the project stays a link
	 * with the same target. Any other link stays a link too, with a relative target that leads to a copy of the file or
	 * directory tree that the original leads to. The copy lies in its own folder of the nested session's {@link
	 * #linkTargets(Path) link-targets} directory, named by how many copies precede it, and is read-only as {@link
	 * PortableFiles#setReadOnly(Path)} makes it, so that the nested session can neither change the target nor change its
	 * copy. The project's link therefore still leaves the project, as the original does. The nested session records each
	 * copy's paths, types, modes and file digests in its own directory, where its session may not write, so that {@link
	 * #changedLinkCopies(NestedSession)} can tell whether the session changed a copy anyway.
	 *
	 * @param source        the source project directory
	 * @param nestedSession the owned nested session
	 * @param nullDevice    the null device, which a sandbox mounts over each file that the session may not read; the
	 *                      copy leaves out each project file that is that device. Where it does not exist, as on Windows,
	 *                      the copy leaves out no file.
	 * @throws NullPointerException if any argument is null
	 * @throws IOException          if the source is not a real directory, a symbolic link in it leads to a missing,
	 *                              unreadable or hidden target, to a directory that holds the project, or to neither a
	 *                              file nor a directory, or copying fails
	 */
	public void copyProject(Path source, NestedSession nestedSession, Path nullDevice) throws IOException
	{
		requireThat(source, "source").isNotNull();
		requireThat(nestedSession, "nestedSession").isNotNull();
		requireThat(nullDevice, "nullDevice").isNotNull();
		Path normalized = source.toAbsolutePath().normalize();
		if (Files.isSymbolicLink(normalized) || !Files.isDirectory(normalized, NOFOLLOW_LINKS))
			throw new IOException("Project source is not a real directory");
		Path destination = nestedSession.directory().resolve("project");
		Path linkTargets = linkTargets(nestedSession.directory());
		Path gitMetadata = normalized.toRealPath().resolve(".git");
		Path realRoot = root.toRealPath();
		Map<Path, Path> links = new LinkedHashMap<>();
		List<Path> readOnlyDirectories = new ArrayList<>();
		// The copy of each directory of a link's target, by its path in the project, and the folder of each copy's root
		Map<Path, Path> copiedDirectories = new HashMap<>();
		Map<Path, Path> copyFolders = new LinkedHashMap<>();
		ProjectTree.copy(normalized, nullDevice, path -> path.equals(gitMetadata) || path.equals(realRoot),
			new ProjectTree.Writer()
			{
				@Override
				public void directory(Path relative, Path directory, boolean copy) throws IOException
				{
					if (!copy)
					{
						Files.createDirectories(destination.resolve(relative));
						return;
					}
					Path copied = Files.createDirectories(copyOf(relative));
					copiedDirectories.put(relative, copied);
					readOnlyDirectories.add(copied);
				}

				@Override
				public void file(Path relative, Path file, boolean copy) throws IOException
				{
					if (!copy)
					{
						Files.copy(file, destination.resolve(relative), COPY_ATTRIBUTES);
						return;
					}
					Path copied = copyOf(relative);
					Files.copy(file, copied, COPY_ATTRIBUTES);
					PortableFiles.setReadOnly(copied);
				}

				/**
				 * Returns where a file or directory of a copy of a link's target goes. The root of a copy goes into a
				 * folder of its own in {@code link-targets}, and the project's link to it leads there.
				 *
				 * @param relative its path relative to the project
				 * @return the path in the nested session that holds its copy
				 * @throws IOException if the {@code link-targets} directory cannot be created
				 */
				private Path copyOf(Path relative) throws IOException
				{
					// A copy's entries follow its root, and the project's own directory is never a copy
					Path holder = copiedDirectories.get(relative.getParent());
					if (holder != null)
						return holder.resolve(relative.getFileName());
					Path folder = Files.createDirectories(linkTargets).resolve(String.valueOf(copyFolders.size()));
					copyFolders.put(relative, folder);
					return folder;
				}

				@Override
				public void link(Path relative, Path target)
				{
					links.put(destination.resolve(relative), target);
				}
			});
		ProjectTree.createLinks(destination, links);
		// The copies exist, so that a link to a directory is created as one on Windows
		List<Path> copies = new ArrayList<>();
		for (Entry<Path, Path> copy : copyFolders.entrySet())
		{
			Path link = destination.resolve(copy.getKey());
			Files.createSymbolicLink(link, link.getParent().relativize(copy.getValue()));
			copies.add(nestedSession.directory().relativize(copy.getValue()));
		}
		// A directory becomes read-only only once nothing more is added to it
		for (Path directory : readOnlyDirectories)
			PortableFiles.setReadOnly(directory);
		LinkCopies.write(nestedSession.directory(), copies, nestedSession.directory().resolve(LinkCopies.FILE_NAME));
	}

	/**
	 * Compares the read-only copies that {@link #copyProject(Path, NestedSession, Path)} made of the targets of the
	 * project's links outside it with the record that it kept of them. Once the nested session's harness session has
	 * ended, a change means that the session changed a copy, and the nested session's result rests on files that differ
	 * from the targets. A nested session whose project was never copied has no copies.
	 *
	 * @param nestedSession the owned nested session
	 * @return a diagnostic that names each changed path, or empty if no copy changed
	 * @throws NullPointerException if {@code nestedSession} is null
	 * @throws IOException          if the record is malformed or a copy cannot be read
	 */
	public Optional<String> changedLinkCopies(NestedSession nestedSession) throws IOException
	{
		requireThat(nestedSession, "nestedSession").isNotNull();
		Path record = nestedSession.directory().resolve(LinkCopies.FILE_NAME);
		if (Files.notExists(record, NOFOLLOW_LINKS))
			return Optional.empty();
		return LinkCopies.changes(nestedSession.directory(), record);
	}

	/**
	 * Copies workspace files into the project of an owned nested session, on top of the files that {@link
	 * #copyProject(Path, NestedSession)} copied. Nothing is replaced: a workspace file whose path exists in the project
	 * already is refused, and so is one whose path is or lies below a link to a read-only copy of the target of a link
	 * outside the project. A symbolic link is copied as a link with the same relative target and is never followed, so
	 * that a link of a draft directory reaches the project file that it names. A workspace file that the sandbox has
	 * replaced with a placeholder is left out.
	 *
	 * @param source the directory that holds the workspace files at their paths relative to the project root
	 * @param nestedSession the owned nested session
	 * @throws IOException if the source is not a real directory, contains a symbolic-link directory, contains a link
	 *   whose target is absolute or lies outside the project, a workspace file's path exists in the project already or
	 *   is or lies below a link to a read-only copy of a link's target, or copying fails
	 */
	public void copyWorkspace(Path source, NestedSession nestedSession) throws IOException
	{
		copyWorkspace(source, nestedSession, RequestArchive.NULL_DEVICE);
	}

	/**
	 * Copies workspace files into the project of an owned nested session, on top of the files that {@link
	 * #copyProject(Path, NestedSession, Path)} copied, as {@link #copyWorkspace(Path, NestedSession)} does.
	 *
	 * @param source        the directory that holds the workspace files at their paths relative to the project root
	 * @param nestedSession the owned nested session
	 * @param nullDevice    the null device, which a sandbox mounts over each file that the session may not read; the
	 *                      copy leaves out each workspace file that is that device. Where it does not exist, as on
	 *                      Windows, the copy leaves out no file.
	 * @throws NullPointerException if any argument is null
	 * @throws IOException          if the source is not a real directory, contains a symbolic-link directory, contains
	 *                              a link whose target is absolute or lies outside the project, a workspace file's path
	 *                              exists in the project already or is or lies below a link to a read-only copy of a
	 *                              link's target, or copying fails
	 */
	public void copyWorkspace(Path source, NestedSession nestedSession, Path nullDevice) throws IOException
	{
		requireThat(source, "source").isNotNull();
		requireThat(nestedSession, "nestedSession").isNotNull();
		requireThat(nullDevice, "nullDevice").isNotNull();
		Path normalized = source.toAbsolutePath().normalize();
		if (Files.isSymbolicLink(normalized) || !Files.isDirectory(normalized, NOFOLLOW_LINKS))
			throw new IOException("Workspace source is not a real directory");
		Path destination = nestedSession.directory().resolve("project");
		Path linkTargets = linkTargets(nestedSession.directory());
		Files.walkFileTree(normalized, new SimpleFileVisitor<>()
		{
			@Override
			public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
				throws IOException
			{
				if (Files.isSymbolicLink(directory))
					throw new IOException("Workspace files contain a symbolic-link directory");
				requireOutsideCopies(directory);
				Path target = destination.resolve(normalized.relativize(directory));
				if (Files.isSymbolicLink(target))
					throw new IOException("The project holds a symbolic link where a workspace directory goes: " + target);
				Files.createDirectories(target);
				return CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
				throws IOException
			{
				if (SandboxPlaceholders.isPlaceholder(file, attributes, nullDevice))
					return CONTINUE;
				requireOutsideCopies(file);
				Path copy = destination.resolve(normalized.relativize(file));
				if (Files.isSymbolicLink(file))
				{
					Path target = Files.readSymbolicLink(file);
					if (target.isAbsolute() || !copy.getParent().resolve(target).normalize().startsWith(destination))
					{
						throw new IOException("The workspace file " + normalized.relativize(file) + " is a symbolic link " +
							"to " + target + ", which lies outside the project");
					}
				}
				// Without REPLACE_EXISTING, a workspace file that collides with a project file is refused
				Files.copy(file, copy, NOFOLLOW_LINKS);
				return CONTINUE;
			}

			/**
			 * Refuses a workspace file or directory whose path in the project is or lies below a link to a read-only
			 * copy of a link's target, which the nested session checks after its session for changes.
			 *
			 * @param path the workspace file or directory
			 * @throws IOException if such a link holds its path in the project
			 */
			private void requireOutsideCopies(Path path) throws IOException
			{
				Path relative = normalized.relativize(path);
				for (Path name = relative; name != null && !name.toString().isEmpty(); name = name.getParent())
				{
					Path link = destination.resolve(name);
					if (!Files.isSymbolicLink(link))
						continue;
					Path target = link.getParent().resolve(Files.readSymbolicLink(link)).normalize();
					if (target.startsWith(linkTargets))
					{
						throw new IOException("The workspace file " + relative + " goes into " + name + ", a link to " +
							"a read-only copy of the target of a symbolic link outside the project");
					}
				}
			}
		});
	}

	/**
	 * Returns the existing files and directories under the results folder that a harness session must not read: all of
	 * them except the files and directories that the session uses, their contents, and the directories that contain
	 * them. The session therefore cannot read another nested session, the workspace files of any nested session, or the
	 * runner's records of its own nested session. A file or directory that is created after this method returns, such as
	 * the directory of a nested session that starts later, is not included.
	 *
	 * @param accessible the files and directories that the session reads or writes; those outside the results folder
	 *   are ignored
	 * @return the paths to deny, none of which contains another
	 * @throws NullPointerException if {@code accessible} is null
	 * @throws IOException if a directory cannot be listed
	 */
	public List<Path> deniedReads(List<Path> accessible) throws IOException
	{
		return deniedReads(root, accessible);
	}

	/**
	 * Returns the existing files and directories under a directory that a harness session must not read: all of them
	 * except the files and directories that the session uses, their contents, and the directories that contain them. A
	 * file or directory that is created after this method returns is not included.
	 *
	 * @param directory  an absolute, normalized directory
	 * @param accessible the files and directories that the session reads or writes; those outside {@code directory}
	 *   are ignored
	 * @return the paths to deny, none of which contains another, or an empty list if {@code directory} is not a
	 *   directory
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if a directory cannot be listed
	 */
	public static List<Path> deniedReads(Path directory, List<Path> accessible) throws IOException
	{
		requireThat(directory, "directory").isNotNull();
		requireThat(accessible, "accessible").isNotNull();
		List<Path> kept = new ArrayList<>();
		for (Path path : accessible)
		{
			Path normalized = path.toAbsolutePath().normalize();
			if (normalized.startsWith(directory))
				kept.add(normalized);
		}
		List<Path> denied = new ArrayList<>();
		if (Files.isDirectory(directory, NOFOLLOW_LINKS))
			addDeniedReads(directory, kept, denied);
		return denied;
	}

	/**
	 * Indicates whether a directory contains any of several paths.
	 *
	 * @param directory an absolute, normalized directory
	 * @param paths     the paths
	 * @return {@code true} if a path, once made absolute and normalized, lies at or under {@code directory}
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static boolean containsAny(Path directory, List<Path> paths)
	{
		requireThat(directory, "directory").isNotNull();
		requireThat(paths, "paths").isNotNull();
		for (Path path : paths)
		{
			if (path.toAbsolutePath().normalize().startsWith(directory))
				return true;
		}
		return false;
	}

	/**
	 * Adds the entries of a directory that a session must not read.
	 *
	 * @param directory a directory that contains an accessible path
	 * @param accessible the absolute, normalized paths that the session reads or writes
	 * @param denied the paths to deny, to which the entries are added
	 * @throws IOException if the directory cannot be listed
	 */
	private static void addDeniedReads(Path directory, List<Path> accessible, List<Path> denied) throws IOException
	{
		try (Stream<Path> entries = Files.list(directory))
		{
			for (Path entry : entries.sorted().toList())
			{
				if (accessible.contains(entry))
					continue;
				boolean containsAccessible = false;
				for (Path path : accessible)
				{
					if (path.startsWith(entry))
					{
						containsAccessible = true;
						break;
					}
				}
				if (containsAccessible && Files.isDirectory(entry, NOFOLLOW_LINKS))
					addDeniedReads(entry, accessible, denied);
				else
					denied.add(entry);
			}
		}
	}

	/**
	 * Copies the project of an owned nested session to its {@code initial-project} directory, so that the project's files
	 * can be compared with the files as they were before the harness changed them.
	 *
	 * @param nestedSession the owned nested session
	 * @throws IOException if copying fails
	 */
	public void snapshotProject(NestedSession nestedSession) throws IOException
	{
		requireThat(nestedSession, "nestedSession").isNotNull();
		Path source = nestedSession.directory().resolve("project");
		Path destination = nestedSession.directory().resolve("initial-project");
		Files.walkFileTree(source, new SimpleFileVisitor<>()
		{
			@Override
			public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
				throws IOException
			{
				Files.createDirectory(destination.resolve(source.relativize(directory)));
				return CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
				throws IOException
			{
				Files.copy(file, destination.resolve(source.relativize(file)), COPY_ATTRIBUTES, NOFOLLOW_LINKS);
				return CONTINUE;
			}
		});
	}

	/**
	 * Records a failed nested session without deleting its diagnostics.
	 *
	 * @param nestedSession the owned nested session
	 * @param diagnostic the non-secret failure diagnostic
	 * @throws IOException if the manifest cannot be persisted, or this process is
	 *   {@link ProcessTreeStop#stopping() stopping}, which leaves a running nested session unended
	 */
	public void fail(NestedSession nestedSession, String diagnostic) throws IOException
	{
		requireThat(nestedSession, "nestedSession").isNotNull();
		requireThat(diagnostic, "diagnostic").isNotBlank();
		writeManifest(nestedSession, "failed", diagnostic);
	}

	/**
	 * Deletes an explicitly selected owned nested session.
	 *
	 * @param nestedSession the owned nested session
	 * @throws IOException if the nested session is outside this manager's root or cannot be deleted
	 */
	public void deleteSession(NestedSession nestedSession) throws IOException
	{
		requireThat(nestedSession, "nestedSession").isNotNull();
		Path directory = nestedSession.directory().toAbsolutePath().normalize();
		Path harnessDirectory = root.resolve(nestedSession.harness()).normalize();
		if (!directory.getParent().equals(harnessDirectory) || !directory.startsWith(root) ||
			Files.isSymbolicLink(harnessDirectory) || Files.isSymbolicLink(directory) ||
			!Files.isRegularFile(directory.resolve("session.json"), NOFOLLOW_LINKS))
		{
			throw new IOException("Nested session is not owned by this manager");
		}
		readManifest(nestedSession);
		try (NestedSessionLock lock = acquireLock(nestedSession))
		{
			if (!lock.isHeld())
				throw new IOException("Unable to acquire nested-session lock");
			Manifest manifest = readManifest(nestedSession);
			if (manifest.state().equals("running") && isOwnerAlive(manifest))
				throw new IOException("Nested session is still owned by a live process: " + nestedSession.id());
			if (manifest.harnessProcessState() == HarnessProcessState.STARTING)
				throw new IOException("Cannot delete a nested session while its harness launch state is unknown: " +
					nestedSession.id());
			if (isHarnessProcessAlive(manifest))
			{
				terminateHarnessProcess(manifest);
				if (isHarnessProcessAlive(manifest))
					throw new IOException("Harness process remains alive; refusing to delete nested session: " +
						nestedSession.id());
			}
			deleteSessionFolder(directory);
		}
		deleteIfEmpty(harnessDirectory);
		deleteIfEmpty(root);
	}

	/**
	 * Removes disposable isolated runtime state after a receipt retained the project, harness events, and raw response.
	 *
	 * @param nestedSession the completed retained nested session
	 * @throws IOException if the nested session is not terminal or its disposable state cannot be removed
	 */
	public void pruneRuntime(NestedSession nestedSession) throws IOException
	{
		requireThat(nestedSession, "nestedSession").isNotNull();
		Manifest manifest = readManifest(nestedSession);
		if (manifest.state().equals("running"))
			throw new IOException("Cannot prune a running nested session");
		if (manifest.harnessProcessState() == HarnessProcessState.STARTING || isHarnessProcessAlive(manifest))
			throw new IOException("Cannot prune runtime while the harness process may remain: " + nestedSession.id());
		for (String directory : List.of("home", "claude"))
		{
			Path candidate = nestedSession.directory().resolve(directory);
			if (Files.exists(candidate))
				delete(candidate);
		}
	}

	/**
	 * Removes disposable runtime state from every terminal nested session owned by one harness root. Entries whose names
	 * are neither slot IDs nor session identifiers are not nested sessions and stay as they are, such as the {@code
	 * .claude} directories that a harness sandbox creates in the directories above its working directory. A session
	 * folder that has no manifest stays as it is too: a requester's copy of a requested session's nested session has none
	 * until the session ends, or ever if the session was stopped, and a nested session that was being created or deleted
	 * at that moment has none either.
	 *
	 * @param harness the owning harness name
	 * @throws IOException if a manifest is malformed or disposable state cannot be removed
	 */
	public void pruneTerminalRuntimes(String harness) throws IOException
	{
		requireThat(harness, "harness").isNotBlank();
		Path harnessRoot = root.resolve(harness);
		if (Files.notExists(harnessRoot))
			return;
		if (Files.isSymbolicLink(harnessRoot) || !Files.isDirectory(harnessRoot, NOFOLLOW_LINKS))
			throw new IOException("Harness root is not an owned directory: " + harnessRoot);
		try (Stream<Path> entries = Files.list(harnessRoot))
		{
			for (Path entry : entries.toList())
			{
				if (!HARNESS_SESSION_ID.matcher(entry.getFileName().toString()).matches())
					continue;
				if (Files.isSymbolicLink(entry) || !Files.isDirectory(entry, NOFOLLOW_LINKS))
					throw new IOException("Nested session is not an owned directory: " + entry);
				NestedSession nestedSession = new NestedSession(harness, entry.getFileName().toString(), entry);
				try
				{
					if (!readManifest(nestedSession).state().equals("running"))
						pruneRuntime(nestedSession);
				}
				catch (NoSuchFileException _)
				{
					// The nested session has no manifest, or lost it while this loop ran: it belongs to no terminal nested
					// session to prune
				}
			}
		}
	}

	/**
	 * Acquires the exclusive lock for one validated owned nested session.
	 *
	 * @param nestedSession the owned nested session
	 * @return the acquired nested-session lock
	 * @throws IOException if the nested session is outside this manager's root, unknown, or active
	 */
	public NestedSessionLock acquireLock(NestedSession nestedSession) throws IOException
	{
		requireThat(nestedSession, "nestedSession").isNotNull();
		Path directory = nestedSession.directory().toAbsolutePath().normalize();
		Path harnessDirectory = root.resolve(nestedSession.harness()).normalize();
		if (!directory.getParent().equals(harnessDirectory) || !directory.startsWith(root) ||
			Files.isSymbolicLink(harnessDirectory) || Files.isSymbolicLink(directory))
		{
			throw new IOException("Nested session is not owned by this manager");
		}
		Path lock = directory.resolve(".lock");
		if (!Files.isRegularFile(lock, NOFOLLOW_LINKS))
			throw new IOException("Nested session is missing its lock file");
		return NestedSessionLock.acquire(fileLocks, lock);
	}

	/**
	 * Deletes stale CAT-owned nested sessions older than the retention period when no owner holds their lock.
	 *
	 * @param retention the positive retention period
	 * @throws IOException if session folders cannot be inspected or deleted
	 */
	public void deleteStaleSessions(Duration retention) throws IOException
	{
		requireThat(retention, "retention").isNotNull();
		requireThat(retention, "retention").isGreaterThan(Duration.ZERO);
		if (!Files.isDirectory(root, NOFOLLOW_LINKS))
			return;
		try (Stream<Path> harnesses = Files.list(root))
		{
			for (Path harness : harnesses.filter(path -> Files.isDirectory(path, NOFOLLOW_LINKS)).toList())
			{
				String harnessName = harness.getFileName().toString();
				try (Stream<Path> sessionFolders = Files.list(harness))
				{
					for (Path sessionFolder : sessionFolders.filter(path -> Files.isDirectory(path, NOFOLLOW_LINKS)).toList())
					{
						Path manifest = sessionFolder.resolve("session.json");
						if (!Files.isRegularFile(manifest, NOFOLLOW_LINKS))
							continue;
						Manifest parsed = readManifest(manifest);
						if (!parsed.harness().equals(harnessName) || !parsed.id().equals(sessionFolder.getFileName().toString()))
							throw new IOException("Session manifest does not match its directory: " + manifest);
						NestedSession ownedSession = new NestedSession(harnessName, sessionFolder.getFileName().toString(),
							sessionFolder);
						if (parsed.state().equals("running"))
						{
							if (isOwnerAlive(parsed))
								continue;
							if (!markAbandonedSessionFailed(ownedSession))
								continue;
							// Retain the just-recorded diagnostic for the full terminal retention period.
							continue;
						}
						if (parsed.harnessProcessState() == HarnessProcessState.STARTING)
							throw new IOException("Cannot delete a terminal nested session with an uncertain harness " +
								"launch: " + manifest);
						if (isHarnessProcessAlive(parsed))
						{
							terminateHarnessProcess(parsed);
							if (isHarnessProcessAlive(parsed))
								continue;
						}
						Instant reference = parsed.finishedAt();
						if (reference.plus(retention).isAfter(clock.instant()))
							continue;
						try (NestedSessionLock lock = acquireLock(ownedSession))
						{
							if (lock.isHeld())
								deleteSessionFolder(sessionFolder);
						}
						catch (IOException e)
						{
							if (!isActiveSession(e))
								throw e;
						}
					}
				}
			}
		}
	}

	/**
	 * Marks an unlocked nested session failed when its owner ended without recording a terminal result.
	 *
	 * @param nestedSession the owned nested session whose recorded owner is no longer alive
	 * @return {@code true} if the nested session was made terminal, or {@code false} if it is still active or unavailable
	 * @throws IOException if the manifest cannot be read, the harness process cannot be stopped, or terminal state cannot
	 *     be persisted
	 */
	private boolean markAbandonedSessionFailed(NestedSession nestedSession) throws IOException
	{
		synchronized (this)
		{
			assert nestedSession != null;
			try (NestedSessionLock lock = acquireLock(nestedSession))
			{
				if (!lock.isHeld())
					return false;
				Manifest manifest = readManifest(nestedSession);
				if (!manifest.state().equals("running"))
					return true;
				if (isOwnerAlive(manifest))
					return false;
				if (isHarnessProcessAlive(manifest))
				{
					terminateHarnessProcess(manifest);
					if (isHarnessProcessAlive(manifest))
						return false;
				}
				if (manifest.harnessProcessState() == HarnessProcessState.STARTING)
					throw new IOException("Cannot abandon nested session while harness launch state is unknown: " +
					nestedSession.directory());
				writeManifest(new Manifest(manifest.harness(), manifest.id(), "failed", "failed",
					manifest.harnessProcessState(), manifest.ownerPid(),
					manifest.ownerStart(), manifest.harnessPid(), manifest.harnessStart(), manifest.createdAt(),
					manifest.harnessSessionId(), manifest.requestsUsed(),
					manifest.artifactPath(), manifest.projectPath(), manifest.transport(), manifest.harnessCommand(),
					manifest.startedAt(), manifest.turnNumber(), manifest.exitCode(), clock.instant(),
					"Runner process ended without recording a terminal result"), nestedSession.directory());
				return true;
			}
			catch (IOException e)
			{
				if (isActiveSession(e))
					return false;
				throw e;
			}
		}
	}

	/**
	 * Restricts a session folder to its owner on POSIX filesystems.
	 *
	 * @param directory the owned session folder
	 * @throws IOException if permissions cannot be set on a POSIX filesystem
	 */
	private static void setOwnerOnlyDirectoryPermissions(Path directory) throws IOException
	{
		assert directory != null;
		if (directory.getFileSystem().supportedFileAttributeViews().contains("posix"))
		{
			Files.setPosixFilePermissions(directory, Set.of(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE));
		}
	}

	/**
	 * Replaces a non-secret session manifest.
	 *
	 * @param nestedSession the owned nested session
	 * @param state the terminal or active state
	 * @param diagnostic the optional non-secret diagnostic
	 * @throws IOException if the manifest cannot be persisted, or {@code state} ends a running nested session while this
	 *   process is {@link ProcessTreeStop#stopping() stopping}
	 */
	private void writeManifest(NestedSession nestedSession, String state, String diagnostic) throws IOException
	{
		synchronized (this)
		{
			assert nestedSession != null;
			assert state != null;
			assert diagnostic != null;
			Manifest previous = readManifest(nestedSession);
			Instant finishedAt = null;
			if (!state.equals("running"))
			{
				// A runner that is being stopped did not see its nested session end, so the nested session's harness session
				// can continue
				if (ProcessTreeStop.stopping() && previous.state().equals("running"))
					throw new IOException("The runner is stopping, so run " + nestedSession.id() + " records no end");
				finishedAt = clock.instant();
			}
			writeManifest(new Manifest(nestedSession.harness(), nestedSession.id(), state, state,
				previous.harnessProcessState(), previous.ownerPid(), previous.ownerStart(),
				previous.harnessPid(), previous.harnessStart(), previous.createdAt(), previous.harnessSessionId(),
				previous.requestsUsed(),
				previous.artifactPath(),
				previous.projectPath(), previous.transport(), previous.harnessCommand(), previous.startedAt(),
				previous.turnNumber(), previous.exitCode(), finishedAt, diagnostic), nestedSession.directory());
		}
	}

	/**
	 * Replaces the active phase of a non-terminal nested session.
	 *
	 * @param nestedSession the owned nested session
	 * @param phase the active phase
	 * @throws IOException if the manifest cannot be persisted
	 */
	private void setPhase(NestedSession nestedSession, String phase) throws IOException
	{
		synchronized (this)
		{
			assert nestedSession != null;
			assert phase != null;
			Manifest previous = readManifest(nestedSession);
			if (!previous.state().equals("running"))
				throw new IOException("Cannot update the phase of a terminal nested session");
			writeManifest(new Manifest(previous.harness(), previous.id(), previous.state(), phase,
				previous.harnessProcessState(), previous.ownerPid(),
				previous.ownerStart(), previous.harnessPid(), previous.harnessStart(), previous.createdAt(),
				previous.harnessSessionId(), previous.requestsUsed(),
				previous.artifactPath(), previous.projectPath(), previous.transport(), previous.harnessCommand(),
				previous.startedAt(), previous.turnNumber(), previous.exitCode(), previous.finishedAt(),
				previous.diagnostic()), nestedSession.directory());
		}
	}

	/**
	 * Replaces one structured non-secret manifest. A terminal manifest also removes the nested session's login copies: a
	 * nested session becomes terminal only after its harness has stopped, and a nested session kept after that holds no
	 * login.
	 *
	 * @param manifest the manifest to write
	 * @param directory the owned session folder
	 * @throws IOException if the manifest cannot be persisted or a login copy cannot be removed
	 */
	private void writeManifest(Manifest manifest, Path directory) throws IOException
	{
		assert manifest != null;
		assert directory != null;
		String content = "{\n  \"schema_version\": 6,\n  \"cat_version\": \"" + PluginInfo.CAT_VERSION +
			"\",\n  \"harness\": \"" + manifest.harness() + "\",\n  \"id\": \"" + manifest.id() +
			"\",\n  \"state\": \"" + manifest.state() + "\",\n  \"phase\": \"" + manifest.phase() +
			"\",\n  \"owner_pid\": " + manifest.ownerPid() +
			",\n  \"owner_started_at\": " + nullableString(manifest.ownerStart()) +
			",\n  \"harness_process_state\": \"" + manifest.harnessProcessState().serialized() +
			"\",\n  \"harness_pid\": " +
			nullableNumber(manifest.harnessPid()) + ",\n  \"harness_started_at\": " +
			nullableString(manifest.harnessStart()) + ",\n  \"created_at\": \"" + manifest.createdAt() +
			"\",\n  \"harness_session_id\": " + nullableString(manifest.harnessSessionId()) +
			",\n  \"requests_used\": " + manifest.requestsUsed() +
			",\n  \"artifact_path\": \"" + escape(manifest.artifactPath()) + "\",\n  \"project_path\": \"" +
			escape(manifest.projectPath()) + "\",\n  \"transport\": \"" + escape(manifest.transport()) +
			"\",\n  \"harness_command\": \"" + escape(manifest.harnessCommand()) + "\",\n  \"started_at\": " +
			nullableString(manifest.startedAt()) + ",\n  \"turn_number\": " + manifest.turnNumber() +
			",\n  \"exit_code\": " + nullableNumber(manifest.exitCode()) + ",\n  \"finished_at\": " +
			nullableString(manifest.finishedAt()) + ",\n  \"diagnostic\": \"" +
			escape(manifest.diagnostic()) + "\"\n}\n";
		writeAtomically(directory.resolve("session.json"), content);
		if (!manifest.state().equals("running"))
			HarnessCredentials.removeLoginCopies(directory);
	}

	/**
	 * Reads and validates one owned manifest.
	 *
	 * @param nestedSession the expected owned nested session
	 * @return the validated manifest
	 * @throws IOException if the manifest is malformed or does not identify the nested session
	 */
	private static Manifest readManifest(NestedSession nestedSession) throws IOException
	{
		assert nestedSession != null;
		Manifest manifest = readManifest(nestedSession.directory().resolve("session.json"));
		if (!manifest.harness().equals(nestedSession.harness()) || !manifest.id().equals(nestedSession.id()))
			throw new IOException("Session manifest does not identify the owned nested session");
		return manifest;
	}

	/**
	 * Reads and validates one manifest file without trusting its path-derived identity.
	 *
	 * @param file the manifest file
	 * @return the parsed manifest
	 * @throws IOException if the manifest is malformed or unsupported
	 */
	static Manifest readManifest(Path file) throws IOException
	{
		assert file != null;
		if (Files.size(file) > MAXIMUM_MANIFEST_BYTES)
		{
			throw new IOException("Session manifest exceeds the maximum supported size of " + MAXIMUM_MANIFEST_BYTES +
				" bytes: " + file);
		}
		String content = Files.readString(file);
		Matcher matcher = MANIFEST.matcher(content);
		if (!matcher.matches() || !matcher.group("catVersion").equals(PluginInfo.CAT_VERSION))
			throw new IOException("Session manifest is malformed or unsupported: " + file);
		try
		{
			int schemaVersion = Integer.parseInt(matcher.group("schemaVersion"));
			long ownerPid = Long.parseLong(matcher.group("ownerPid"));
			String ownerStart = null;
			if (!matcher.group("ownerStart").equals("null"))
				ownerStart = matcher.group("ownerStartValue");
			Long harnessPid = null;
			if (!matcher.group("harnessPid").equals("null"))
				harnessPid = Long.parseLong(matcher.group("harnessPid"));
			String harnessStart = null;
			if (!matcher.group("harnessStart").equals("null"))
				harnessStart = matcher.group("harnessStartValue");
			if ((harnessPid == null) != (harnessStart == null))
				throw new IOException("Session manifest has an incomplete harness process identity: " + file);
			if (ownerPid <= 0 || ownerStart == null || !ownerStart.matches(ProcessDescription.START_PATTERN))
				throw new IOException("Session manifest has an incomplete owner process identity: " + file);
			if (harnessStart != null && !harnessStart.matches(ProcessDescription.START_PATTERN))
				throw new IOException("Session manifest has an invalid harness process identity: " + file);
			HarnessProcessState processState;
			if (schemaVersion == 3)
			{
				if (harnessPid == null)
					processState = HarnessProcessState.STARTING;
				else
					processState = HarnessProcessState.RECORDED;
			}
			else
			{
				String state = matcher.group("harnessProcessState");
				if (state == null)
					throw new IOException("Schema " + schemaVersion + " manifest has no harness process state: " + file);
				processState = HarnessProcessState.from(state);
				if ((processState == HarnessProcessState.RECORDED) != (harnessPid != null))
					throw new IOException("Session manifest has inconsistent harness process state: " + file);
			}
			Instant createdAt = Instant.parse(matcher.group("createdAt"));
			// Manifests before schema 5 predate the harness session's identifier, so their harness reported none
			String harnessSessionId = null;
			String session = matcher.group("harnessSessionId");
			if (schemaVersion >= 5)
			{
				if (session == null)
					throw new IOException("Schema " + schemaVersion + " manifest has no harness session identifier: " +
						file);
				if (!session.equals("null"))
					harnessSessionId = matcher.group("harnessSessionIdValue");
			}
			else if (session != null)
				throw new IOException("Schema " + schemaVersion + " manifest records a harness session: " + file);
			// Manifests before schema 6 predate the count of the nested session's session requests, so their nested sessions
			// made none
			int requestsUsed = 0;
			String requests = matcher.group("requestsUsed");
			if (schemaVersion == 6)
			{
				if (requests == null)
					throw new IOException("Schema 6 manifest has no count of session requests: " + file);
				requestsUsed = Integer.parseInt(requests);
			}
			else if (requests != null)
				throw new IOException("Schema " + schemaVersion + " manifest counts session requests: " + file);
			Instant startedAt = null;
			if (!matcher.group("startedAt").equals("null"))
				startedAt = Instant.parse(matcher.group("startedAtValue"));
			Integer exitCode = null;
			if (!matcher.group("exitCode").equals("null"))
				exitCode = Integer.valueOf(matcher.group("exitCode"));
			Instant finishedAt = null;
			if (!matcher.group("finishedAt").equals("null"))
				finishedAt = Instant.parse(matcher.group("finishedAtValue"));
			if (matcher.group("state").equals("running") == (finishedAt != null))
				throw new IOException("Session manifest has an invalid terminal timestamp: " + file);
			String state = matcher.group("state");
			String phase = matcher.group("phase");
			if (state.equals("running") == (phase.equals("completed") || phase.equals("failed")))
				throw new IOException("Session manifest has an invalid lifecycle phase: " + file);
			if (!state.equals("running") && !phase.equals(state))
				throw new IOException("Session manifest has an invalid terminal phase: " + file);
			return new Manifest(matcher.group("harness"), matcher.group("id"), state, phase, processState, ownerPid,
				ownerStart, harnessPid, harnessStart, createdAt, harnessSessionId, requestsUsed,
				unescape(matcher.group("artifactPath")),
				unescape(matcher.group("projectPath")), unescape(matcher.group("transport")),
				unescape(matcher.group("harnessCommand")), startedAt, Integer.parseInt(matcher.group("turnNumber")),
				exitCode, finishedAt, "");
		}
		catch (JacksonException e)
		{
			throw new IOException("Session manifest contains an invalid JSON string: " + file, e);
		}
		catch (DateTimeParseException e)
		{
			throw new IOException("Session manifest contains an invalid timestamp: " + file, e);
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException("Session manifest contains an invalid value: " + file, e);
		}
	}

	/**
	 * Indicates whether the recorded owner process might still be alive.
	 *
	 * @param manifest the validated session manifest
	 * @return {@code true} if the original owner remains alive, or if the manifest records no
	 *   {@link ProcessDescription#start() start} for it, because the owner cannot then be told apart from a later
	 *   process
	 */
	private boolean isOwnerAlive(Manifest manifest) throws IOException
	{
		assert manifest != null;
		return processLiveness.isAlive(manifest.ownerPid(), manifest.ownerStart());
	}

	/**
	 * Indicates whether the recorded harness process remains alive.
	 *
	 * @param manifest the validated session manifest
	 * @return {@code true} if the harness process is recorded and remains alive
	 */
	private boolean isHarnessProcessAlive(Manifest manifest) throws IOException
	{
		assert manifest != null;
		if (manifest.harnessProcessState() == HarnessProcessState.STARTING)
			throw new IOException("Harness launch state is unknown for nested session " + manifest.id());
		return manifest.harnessProcessState() == HarnessProcessState.RECORDED &&
			processLiveness.isAlive(manifest.harnessPid(), manifest.harnessStart());
	}

	/**
	 * Requests termination of the recorded harness process.
	 *
	 * @param manifest the validated session manifest
	 */
	private void terminateHarnessProcess(Manifest manifest) throws IOException
	{
		assert manifest != null;
		assert manifest.harnessPid() != null;
		processLiveness.terminate(manifest.harnessPid(), manifest.harnessStart());
	}

	/**
	 * Formats an optional JSON number.
	 *
	 * @param value the optional number
	 * @return the JSON representation
	 */
	private static String nullableNumber(Number value)
	{
		if (value == null)
			return "null";
		return value.toString();
	}

	/**
	 * Formats an optional JSON string whose text needs no escaping.
	 *
	 * @param value the optional value, whose text is the string
	 * @return the JSON representation
	 */
	private static String nullableString(Object value)
	{
		if (value == null)
			return "null";
		return "\"" + value + "\"";
	}

	/**
	 * Non-secret structured state for an owned nested session.
	 *
	 * @param harness the harness identifier
	 * @param id the slot ID
	 * @param state the lifecycle state
	 * @param phase the current activity or terminal phase
	 * @param harnessProcessState the durable harness launch state
	 * @param ownerPid the orchestrator process identifier
	 * @param ownerStart the orchestrator process's {@link ProcessDescription#start() start} as the manifest records it,
	 *   or null if the system reported none
	 * @param harnessPid the harness process identifier, if started
	 * @param harnessStart the harness process's start as the manifest records it, if started
	 * @param createdAt the immutable creation timestamp
	 * @param harnessSessionId the harness's own identifier of the nested session's harness session, or null if the
	 *   harness has not reported it
	 * @param requestsUsed how many of the session requests of the nested session's harness session its runner has
	 *   accepted, over every runner that served the nested session
	 * @param artifactPath the complete artifact path
	 * @param projectPath the source project path
	 * @param transport the harness persistent transport
	 * @param harnessCommand the redacted harness command
	 * @param startedAt the nested-session start timestamp, if initialized
	 * @param turnNumber the latest completed turn number
	 * @param exitCode the latest observed harness-turn or externally reported runner exit status, if available
	 * @param finishedAt the terminal timestamp, if terminal
	 * @param diagnostic the optional diagnostic
	 */
	record Manifest(String harness, String id, String state, String phase,
		HarnessProcessState harnessProcessState, long ownerPid, String ownerStart,
		Long harnessPid, String harnessStart, Instant createdAt, String harnessSessionId, int requestsUsed,
		String artifactPath,
		String projectPath,
		String transport, String harnessCommand, Instant startedAt, int turnNumber, Integer exitCode, Instant finishedAt,
		String diagnostic)
	{
	}

	/**
	 * Durable evidence about whether the harness launch produced an owned child.
	 */
	enum HarnessProcessState
	{
		ABSENT("absent"), STARTING("starting"), RECORDED("recorded");

		private final String serialized;

		/**
		 * Creates a durable process state.
		 *
		 * @param serialized the manifest spelling
		 */
		HarnessProcessState(String serialized)
		{
			this.serialized = serialized;
		}

		/**
		 * Returns the manifest value.
		 *
		 * @return the lowercase manifest value
		 */
		String serialized()
		{
			return serialized;
		}

		/**
		 * Reads a manifest value.
		 *
		 * @param value the manifest value
		 * @return the matching state
		 * @throws IllegalArgumentException if the value is unsupported
		 */
		static HarnessProcessState from(String value)
		{
			for (HarnessProcessState state : values())
			{
				if (state.serialized.equals(value))
					return state;
			}
			throw new IllegalArgumentException("Unsupported harness process state: " + value);
		}
	}

	/**
	 * Writes text by atomically replacing the target where the filesystem supports it.
	 *
	 * @param output the target file
	 * @param content the file content
	 * @throws IOException if the file cannot be written
	 */
	private void writeAtomically(Path output, String content) throws IOException
	{
		assert output != null;
		assert content != null;
		Path temporary = Files.createTempFile(output.getParent(), output.getFileName().toString(), ".tmp");
		try
		{
			Files.writeString(temporary, content);
			try
			{
				replacer.replace(temporary, output);
			}
			catch (AtomicMoveNotSupportedException _)
			{
				Files.move(temporary, output, REPLACE_EXISTING);
			}
		}
		finally
		{
			Files.deleteIfExists(temporary);
		}
	}

	/**
	 * Escapes a JSON string value.
	 *
	 * @param value a non-null value
	 * @return the escaped value
	 */
	private static String escape(String value)
	{
		assert value != null;
		String quoted = JsonMapper.builder().build().writeValueAsString(value);
		return quoted.substring(1, quoted.length() - 1);
	}

	/**
	 * Decodes the JSON string escapes emitted by {@link #escape(String)}.
	 *
	 * @param value the escaped value
	 * @return the decoded value
	 */
	private static String unescape(String value)
	{
		assert value != null;
		return JsonMapper.builder().build().readValue('"' + value + '"', String.class);
	}

	/**
	 * Redacts values following secret-bearing option names in a harness command.
	 *
	 * @param command the harness command
	 * @return the printable redacted command
	 */
	private static String redact(List<String> command)
	{
		assert command != null;
		StringBuilder result = new StringBuilder();
		boolean redactNext = false;
		for (String argument : command)
		{
			if (!result.isEmpty())
				result.append(' ');
			String normalized = argument.toLowerCase(Locale.ROOT);
			if (redactNext)
				result.append("<redacted>");
			else
				result.append(argument);
			redactNext = normalized.contains("key") || normalized.contains("token") || normalized.contains("secret") ||
				normalized.contains("password");
		}
		return result.toString();
	}

	/**
	 * Deletes a file, or a directory with everything in it, including its read-only files and directories. Entries
	 * that a concurrent cleanup removes first are skipped.
	 *
	 * @param directory the file or directory to delete
	 * @throws IOException if deletion fails
	 */
	private static void delete(Path directory) throws IOException
	{
		try
		{
			Files.walkFileTree(directory, new SimpleFileVisitor<>()
			{
				@Override
				public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
					throws IOException
				{
					// Removing an entry needs the right to write its directory
					try
					{
						if (PortableFiles.isReadOnly(directory))
							PortableFiles.clearReadOnly(directory);
					}
					catch (NoSuchFileException _)
					{
						// A concurrent terminal cleanup already removed this directory
					}
					return CONTINUE;
				}

				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException
				{
					try
					{
						Files.deleteIfExists(file);
					}
					catch (AccessDeniedException e)
					{
						// Windows refuses to delete a file whose read-only attribute is set
						if (attributes.isSymbolicLink() || !PortableFiles.clearReadOnly(file))
							throw e;
						Files.deleteIfExists(file);
					}
					return CONTINUE;
				}

				@Override
				public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException
				{
					if (failure instanceof NoSuchFileException)
						return CONTINUE;
					throw failure;
				}

				@Override
				public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException
				{
					if (failure != null && !(failure instanceof NoSuchFileException))
						throw failure;
					Files.deleteIfExists(directory);
					return CONTINUE;
				}
			});
		}
		catch (NoSuchFileException _)
		{
			// A concurrent terminal cleanup already removed this owned disposable directory.
		}
	}

	/**
	 * Removes one empty runner grouping directory without disturbing another process that created a sibling nested
	 * session.
	 *
	 * @param directory the candidate empty directory
	 * @throws IOException if deletion fails for a reason other than a concurrent child creation
	 */
	private static void deleteIfEmpty(Path directory) throws IOException
	{
		try
		{
			Files.deleteIfExists(directory);
		}
		catch (DirectoryNotEmptyException _)
		{
			// Another nested session still owns a child directory.
		}
	}

	/**
	 * Indicates whether lock acquisition failed because the nested session remains active.
	 *
	 * @param failure the lock-acquisition failure
	 * @return true if the nested session remains active
	 */
	private static boolean isActiveSession(IOException failure)
	{
		assert failure != null;
		String message = failure.getMessage();
		return message != null && message.startsWith("Nested session is already active:");
	}
}
