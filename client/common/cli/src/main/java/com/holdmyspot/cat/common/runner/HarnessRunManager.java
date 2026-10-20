package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.FileVisitResult.CONTINUE;
import static java.nio.file.FileVisitResult.SKIP_SUBTREE;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.COPY_ATTRIBUTES;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;

import com.holdmyspot.cat.common.PluginInfo;
import com.holdmyspot.cat.common.PortableFiles;
import com.holdmyspot.cat.common.secrets.HarnessRunManagerAccess;
import com.holdmyspot.cat.common.secrets.HarnessRunManagerSecrets;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Creates, records, and deletes the isolated directories used by harness executions.
 */
public final class HarnessRunManager
{
	static
	{
		HarnessRunManagerSecrets.setAccess(new HarnessRunManagerAccess()
		{
			@Override
			public int maximumManifestBytes()
			{
				return MAXIMUM_MANIFEST_BYTES;
			}

			@Override
			public HarnessRunManager newHarnessRunManager(Path root, Clock clock, HarnessProcessLiveness processLiveness)
			{
				return new HarnessRunManager(root, clock, processLiveness);
			}
		});
	}

	/**
	 * Default retention period for terminal diagnostic runs.
	 */
	public static final Duration DEFAULT_RETENTION = Duration.ofDays(7);
	static final int MAXIMUM_MANIFEST_BYTES = 1_048_576;
	/**
	 * The name of a run directory, which is a generated run identifier.
	 */
	private static final Pattern RUN_ID = Pattern.compile("[a-z0-9]+-[a-z0-9]+");
	private static final Pattern MANIFEST = Pattern.compile("""
		\\A\\{
		  "schema_version": 3,
		  "cat_version": "(?<catVersion>[^\"]+)",
		  "harness": "(?<harness>[a-z0-9]+)",
		  "id": "(?<id>[a-z0-9]+-[a-z0-9]+)",
		  "state": "(?<state>running|completed|failed)",
		  "phase": "(?<phase>initializing|idle|working|completed|failed)",
		  "owner_pid": "?(?<ownerPid>[1-9][0-9]*)"?,
		  "owner_started_at": "(?<ownerStartedAt>[^\"]+)",
		  "harness_pid": (?<harnessPid>null|[1-9][0-9]*),
		  "harness_started_at": (?<harnessStartedAt>null|"(?<harnessStartedAtValue>[^\"]+)"),
		  "created_at": "(?<createdAt>[^\"]+)",
		  "artifact_path": "(?<artifactPath>(?:[^"\\\\]|\\\\.)*)",
		  "project_path": "(?<projectPath>(?:[^"\\\\]|\\\\.)*)",
		  "transport": "(?<transport>(?:[^"\\\\]|\\\\.)*)",
		  "harness_command": "(?<harnessCommand>(?:[^"\\\\]|\\\\.)*)",
		  "started_at": (?<startedAt>null|"(?<startedAtValue>[^\"]+)"),
		  "turn_number": (?<turnNumber>[0-9]+),
		  "exit_code": (?<exitCode>null|-?[0-9]+),
		  "finished_at": (?<finishedAt>null|"(?<finishedAtValue>[^\"]+)"),
		  "diagnostic": "(?:[^"\\\\]|\\\\.)*"
		}\\n\\z""");
	private final Path root;
	private final Clock clock;
	private final HarnessProcessLiveness processLiveness;

	/**
	 * Creates a manager rooted at an absolute directory.
	 *
	 * @param root the absolute owned-run root
	 * @param clock the clock used for generated identifiers
	 * @throws NullPointerException if {@code root} or {@code clock} are null
	 * @throws IllegalArgumentException if {@code root} is relative
	 */
	public HarnessRunManager(Path root, Clock clock)
	{
		this(root, clock, new ProcessHandleLiveness());
	}

	/**
	 * Creates a manager with process liveness support.
	 *
	 * @param root the absolute owned-run root
	 * @param clock the clock used for generated identifiers
	 * @param processLiveness the exact-process liveness service
	 * @throws NullPointerException if {@code root}, {@code clock}, or {@code processLiveness} are null
	 * @throws IllegalArgumentException if {@code root} is relative
	 */
	HarnessRunManager(Path root, Clock clock, HarnessProcessLiveness processLiveness)
	{
		requireThat(root, "root").isNotNull();
		requireThat(clock, "clock").isNotNull();
		requireThat(processLiveness, "processLiveness").isNotNull();
		if (!root.isAbsolute())
			throw new IllegalArgumentException("root must be absolute");
		this.root = root.normalize();
		this.clock = clock;
		this.processLiveness = processLiveness;
	}

	/**
	 * Returns the directory that holds every run and the workspace files of every run.
	 *
	 * @return the absolute, normalized run root
	 */
	public Path root()
	{
		return root;
	}

	/**
	 * Creates one owned run directory.
	 *
	 * @param harness the harness name
	 * @return the created run
	 * @throws IOException if the directory cannot be created
	 */
	public HarnessRun create(String harness) throws IOException
	{
		requireThat(harness, "harness").isNotBlank();
		String id = Long.toUnsignedString(clock.millis(), 36) + "-" + Long.toUnsignedString(System.nanoTime(), 36);
		Path directory = root.resolve(harness).resolve(id).normalize();
		if (!directory.startsWith(root))
			throw new IOException("Run directory escapes the configured root");
		Files.createDirectories(directory);
		setOwnerOnlyDirectoryPermissions(directory);
		Files.createDirectories(directory.resolve("project"));
		Files.createDirectories(directory.resolve("home"));
		Files.createDirectories(directory.resolve("artifacts"));
		Files.createFile(directory.resolve(".lock"));
		HarnessRun run = new HarnessRun(harness, id, directory);
		ProcessHandle owner = ProcessHandle.current();
		Instant ownerStartedAt = owner.info().startInstant().orElse(clock.instant());
		writeManifest(new Manifest(harness, id, "running", "initializing", owner.pid(), ownerStartedAt, null, null,
			clock.instant(),
			"", "", "", "", null, 0, null, null, ""), directory);
		return run;
	}

	/**
	 * Records non-secret metadata required to investigate an owned harness process.
	 *
	 * @param run the owned run
	 * @param artifact the complete harness artifact
	 * @param project the source project
	 * @param transport the persistent harness transport identifier
	 * @param command the harness command with any secret values redacted
	 * @throws IOException if the manifest cannot be persisted
	 */
	public void initialize(HarnessRun run, Path artifact, Path project, String transport, List<String> command)
		throws IOException
	{
		requireThat(run, "run").isNotNull();
		requireThat(artifact, "artifact").isNotNull();
		requireThat(project, "project").isNotNull();
		requireThat(transport, "transport").isNotBlank();
		requireThat(command, "command").isNotEmpty();
		Manifest previous = readManifest(run);
		if (!previous.state().equals("running"))
			throw new IOException("Cannot initialize a terminal run");
		writeManifest(new Manifest(previous.harness(), previous.id(), previous.state(), previous.phase(),
			previous.ownerPid(),
			previous.ownerStartedAt(), previous.harnessPid(), previous.harnessStartedAt(), previous.createdAt(),
			artifact.toAbsolutePath().normalize().toString(), project.toAbsolutePath().normalize().toString(), transport,
			redact(command), clock.instant(), previous.turnNumber(), previous.exitCode(), previous.finishedAt(),
			previous.diagnostic()), run.directory());
	}

	/**
	 * Replaces the output file with persisted content before deleting a successful run.
	 *
	 * @param run the owned run
	 * @param output the durable output path
	 * @param content the output content
	 * @throws IOException if output cannot be persisted or cleanup fails
	 */
	public void complete(HarnessRun run, Path output, String content) throws IOException
	{
		complete(run, output, content, false);
	}

	/**
	 * Replaces the output file with persisted content and optionally retains a completed owned run.
	 *
	 * @param run the owned run
	 * @param output the durable output path
	 * @param content the output content
	 * @param keepRun whether to retain the completed run
	 * @throws IOException if output cannot be persisted or cleanup fails
	 */
	public void complete(HarnessRun run, Path output, String content, boolean keepRun) throws IOException
	{
		requireThat(run, "run").isNotNull();
		requireThat(output, "output").isNotNull();
		requireThat(content, "content").isNotNull();
		Path parent = output.toAbsolutePath().normalize().getParent();
		if (parent == null)
			throw new IOException("Output path cannot be the filesystem root: " + output);
		Files.createDirectories(parent);
		writeAtomically(output, content);
		markCompleted(run);
		if (!keepRun)
			deleteRun(run);
	}

	/**
	 * Records that a harness run completed successfully before cleanup begins.
	 *
	 * @param run the owned run
	 * @throws IOException if the manifest cannot be persisted
	 */
	public void markCompleted(HarnessRun run) throws IOException
	{
		requireThat(run, "run").isNotNull();
		writeManifest(run, "completed", "");
	}

	/**
	 * Records that a started harness is waiting for its next request.
	 *
	 * @param run the owned run
	 * @throws IOException if the manifest cannot be persisted
	 */
	public void markIdle(HarnessRun run) throws IOException
	{
		setPhase(run, "idle");
	}

	/**
	 * Records that the harness is processing a submitted request.
	 *
	 * @param run the owned run
	 * @throws IOException if the manifest cannot be persisted
	 */
	public void markWorking(HarnessRun run) throws IOException
	{
		setPhase(run, "working");
	}

	/**
	 * Records the identity of the harness process owned by a live run.
	 *
	 * @param run the owned run
	 * @param harnessPid the positive harness process identifier
	 * @param harnessStartedAt the harness process start marker
	 * @throws NullPointerException if {@code run} or {@code harnessStartedAt} are null
	 * @throws IllegalArgumentException if {@code harnessPid} is not positive
	 * @throws IOException if the manifest cannot be persisted
	 */
	public void recordHarnessProcess(HarnessRun run, long harnessPid, Instant harnessStartedAt) throws IOException
	{
		requireThat(run, "run").isNotNull();
		requireThat(harnessStartedAt, "harnessStartedAt").isNotNull();
		requireThat(harnessPid, "harnessPid").isPositive();
		Manifest previous = readManifest(run);
		if (!previous.state().equals("running"))
			throw new IOException("Cannot record a harness process for a terminal run");
		writeManifest(new Manifest(previous.harness(), previous.id(), previous.state(), previous.phase(),
			previous.ownerPid(),
			previous.ownerStartedAt(), harnessPid, harnessStartedAt, previous.createdAt(), previous.artifactPath(),
			previous.projectPath(), previous.transport(), previous.harnessCommand(), previous.startedAt(),
			previous.turnNumber(), previous.exitCode(), previous.finishedAt(), previous.diagnostic()), run.directory());
	}

	/**
	 * Replaces the captured standard streams of a harness command for failed-run investigation.
	 *
	 * @param run the owned run
	 * @param commandName the generated command name
	 * @param standardOutput the captured standard output
	 * @param standardError the captured standard error
	 * @throws IOException if the owned artifact files cannot be persisted
	 */
	public void writeCommandOutput(HarnessRun run, String commandName, String standardOutput, String standardError)
		throws IOException
	{
		requireThat(run, "run").isNotNull();
		requireThat(commandName, "commandName").isNotBlank();
		requireThat(standardOutput, "standardOutput").isNotNull();
		requireThat(standardError, "standardError").isNotNull();
		if (!commandName.matches("[a-z0-9]+(?:-[a-z0-9]+)*"))
			throw new IllegalArgumentException("commandName must be a generated artifact name");
		Path artifacts = run.directory().resolve("artifacts");
		if (Files.isSymbolicLink(artifacts) || !Files.isDirectory(artifacts, NOFOLLOW_LINKS))
			throw new IOException("Run artifacts directory is not owned by this manager");
		writeAtomically(artifacts.resolve(commandName + ".stdout"), standardOutput);
		writeAtomically(artifacts.resolve(commandName + ".stderr"), standardError);
	}

	/**
	 * Records one harness turn's raw events and error stream for later investigation.
	 *
	 * @param run the owned run
	 * @param turnNumber the positive one-based turn number
	 * @param result the captured harness turn result
	 * @throws IOException if the owned turn artifacts cannot be persisted
	 */
	public void writeTurnOutput(HarnessRun run, int turnNumber, HarnessProcessResult result) throws IOException
	{
		requireThat(run, "run").isNotNull();
		requireThat(result, "result").isNotNull();
		requireThat(turnNumber, "turnNumber").isPositive();
		Path artifacts = run.directory().resolve("artifacts");
		if (Files.isSymbolicLink(artifacts) || !Files.isDirectory(artifacts, NOFOLLOW_LINKS))
			throw new IOException("Run artifacts directory is not owned by this manager");
		String prefix = "turn-" + turnNumber;
		writeAtomically(artifacts.resolve(prefix + ".events"), result.standardOutput());
		writeAtomically(artifacts.resolve(prefix + ".stderr"), result.standardError());
		Manifest previous = readManifest(run);
		writeManifest(new Manifest(previous.harness(), previous.id(), previous.state(), previous.phase(),
			previous.ownerPid(),
			previous.ownerStartedAt(), previous.harnessPid(), previous.harnessStartedAt(), previous.createdAt(),
			previous.artifactPath(), previous.projectPath(), previous.transport(), previous.harnessCommand(),
			previous.startedAt(), turnNumber, result.exitCode(), previous.finishedAt(), previous.diagnostic()),
			run.directory());
	}

	/**
	 * Appends one harness event while its turn remains active, making current progress inspectable before terminal output
	 * is available.
	 *
	 * @param run the owned run
	 * @param turnNumber the positive one-based turn number
	 * @param event one non-blank harness event line
	 * @throws IOException if the event cannot be persisted
	 */
	public void appendTurnEvent(HarnessRun run, int turnNumber, String event) throws IOException
	{
		requireThat(run, "run").isNotNull();
		requireThat(turnNumber, "turnNumber").isPositive();
		requireThat(event, "event").isNotBlank();
		Path artifacts = run.directory().resolve("artifacts");
		if (Files.isSymbolicLink(artifacts) || !Files.isDirectory(artifacts, NOFOLLOW_LINKS))
			throw new IOException("Run artifacts directory is not owned by this manager");
		if (!readManifest(run).state().equals("running"))
			throw new IOException("Cannot append an event to a terminal run");
		Files.writeString(artifacts.resolve("turn-" + turnNumber + ".events"), event + "\n", CREATE, APPEND);
	}

	/**
	 * Copies project files into an owned run without source Git metadata or symlinks.
	 *
	 * @param source the source project directory
	 * @param run the owned run
	 * @throws IOException if the source is not a real directory, contains symbolic links, or copying fails
	 */
	public void copyProject(Path source, HarnessRun run) throws IOException
	{
		requireThat(source, "source").isNotNull();
		requireThat(run, "run").isNotNull();
		Path normalized = source.toAbsolutePath().normalize();
		if (Files.isSymbolicLink(normalized) || !Files.isDirectory(normalized, NOFOLLOW_LINKS))
			throw new IOException("Project source is not a real directory");
		Path destination = run.directory().resolve("project");
		boolean rootIsNested = root.startsWith(normalized);
		Files.walkFileTree(normalized, new SimpleFileVisitor<>()
		{
			@Override
			public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
				throws IOException
			{
				if (Files.isSymbolicLink(directory))
					throw new IOException("Project contains a symbolic-link directory");
				if (rootIsNested && directory.equals(root))
					return SKIP_SUBTREE;
				Path relative = normalized.relativize(directory);
				if (relative.startsWith(".git"))
					return SKIP_SUBTREE;
				Files.createDirectories(destination.resolve(relative));
				return CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
				throws IOException
			{
				if (Files.isSymbolicLink(file))
					throw new IOException("Project contains a symbolic-link file");
				Path relative = normalized.relativize(file);
				if (!relative.startsWith(".git"))
					Files.copy(file, destination.resolve(relative), COPY_ATTRIBUTES);
				return CONTINUE;
			}
		});
	}

	/**
	 * Copies workspace files into the project of an owned run, on top of the files that {@link #copyProject(Path,
	 * HarnessRun)} copied. Nothing is replaced: a workspace file whose path exists in the project already is refused. A
	 * symbolic link is copied as a link with the same relative target and is never followed, so that a link of a draft
	 * directory reaches the project file that it names.
	 *
	 * @param source the directory that holds the workspace files at their paths relative to the project root
	 * @param run the owned run
	 * @throws IOException if the source is not a real directory, contains a symbolic-link directory, contains a link
	 *   whose target is absolute or lies outside the project, a workspace file's path exists in the project already, or
	 *   copying fails
	 */
	public void copyWorkspace(Path source, HarnessRun run) throws IOException
	{
		requireThat(source, "source").isNotNull();
		requireThat(run, "run").isNotNull();
		Path normalized = source.toAbsolutePath().normalize();
		if (Files.isSymbolicLink(normalized) || !Files.isDirectory(normalized, NOFOLLOW_LINKS))
			throw new IOException("Workspace source is not a real directory");
		Path destination = run.directory().resolve("project");
		Files.walkFileTree(normalized, new SimpleFileVisitor<>()
		{
			@Override
			public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
				throws IOException
			{
				if (Files.isSymbolicLink(directory))
					throw new IOException("Workspace files contain a symbolic-link directory");
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
		});
	}

	/**
	 * Returns the existing files and directories under the run root that a harness session must not read: all of them
	 * except the files and directories that the session uses, their contents, and the directories that contain them. The
	 * session therefore cannot read another run, the workspace files of any run, or the runner's records of its own run.
	 * A file or directory that is created after this method returns, such as the directory of a run that starts later, is
	 * not included.
	 *
	 * @param accessible the files and directories that the session reads or writes; those outside the run root are
	 *   ignored
	 * @return the paths to deny, none of which contains another
	 * @throws NullPointerException if {@code accessible} is null
	 * @throws IOException if a directory cannot be listed
	 */
	public List<Path> deniedReads(List<Path> accessible) throws IOException
	{
		requireThat(accessible, "accessible").isNotNull();
		List<Path> kept = new ArrayList<>();
		for (Path path : accessible)
		{
			Path normalized = path.toAbsolutePath().normalize();
			if (normalized.startsWith(root))
				kept.add(normalized);
		}
		List<Path> denied = new ArrayList<>();
		if (Files.isDirectory(root, NOFOLLOW_LINKS))
			addDeniedReads(root, kept, denied);
		return denied;
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
	 * Copies the project of an owned run to its {@code initial-project} directory, so that the project's files can be
	 * compared with the files as they were before the harness changed them.
	 *
	 * @param run the owned run
	 * @throws IOException if copying fails
	 */
	public void snapshotProject(HarnessRun run) throws IOException
	{
		requireThat(run, "run").isNotNull();
		Path source = run.directory().resolve("project");
		Path destination = run.directory().resolve("initial-project");
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
	 * Records a failed run without deleting its diagnostics.
	 *
	 * @param run the owned run
	 * @param diagnostic the non-secret failure diagnostic
	 * @throws IOException if the manifest cannot be persisted
	 */
	public void fail(HarnessRun run, String diagnostic) throws IOException
	{
		requireThat(run, "run").isNotNull();
		requireThat(diagnostic, "diagnostic").isNotBlank();
		writeManifest(run, "failed", diagnostic);
	}

	/**
	 * Records an externally observed process failure for an owned run that no live runner owns.
	 *
	 * @param harness the harness owning the run
	 * @param id the generated run identifier
	 * @param diagnostic the non-secret supervisor diagnostic
	 * @param exitCode the observed non-negative process exit status
	 * @throws IOException if the run is unknown, active, terminal, or cannot be persisted
	 */
	public void fail(String harness, String id, String diagnostic, int exitCode) throws IOException
	{
		requireThat(harness, "harness").isNotBlank();
		requireThat(id, "id").isNotBlank();
		requireThat(diagnostic, "diagnostic").isNotBlank();
		requireThat(exitCode, "exitCode").isNotNegative();
		HarnessRun run = new HarnessRun(harness, id, root.resolve(harness).resolve(id).normalize());
		Manifest manifest = readManifest(run);
		if (isOwnerAlive(manifest))
			throw new IOException("Run is still owned by a live process: " + id);
		try (HarnessRunLock lock = acquireLock(run))
		{
			if (!lock.isHeld())
				throw new IOException("Unable to acquire run lock");
			manifest = readManifest(run);
			if (manifest.state().equals("failed"))
				return;
			if (!manifest.state().equals("running"))
				throw new IOException("Run is already terminal: " + id);
			if (isOwnerAlive(manifest))
				throw new IOException("Run is still owned by a live process: " + id);
			if (isHarnessProcessAlive(manifest))
			{
				terminateHarnessProcess(manifest);
				if (isHarnessProcessAlive(manifest))
					throw new IOException("Harness process is still running for run: " + id);
			}
			writeManifest(new Manifest(manifest.harness(), manifest.id(), "failed", "failed", manifest.ownerPid(),
				manifest.ownerStartedAt(), manifest.harnessPid(), manifest.harnessStartedAt(), manifest.createdAt(),
				manifest.artifactPath(), manifest.projectPath(), manifest.transport(), manifest.harnessCommand(),
				manifest.startedAt(), manifest.turnNumber(), exitCode, clock.instant(), diagnostic), run.directory());
		}
	}

	/**
	 * Deletes an explicitly selected owned run.
	 *
	 * @param run the owned run
	 * @throws IOException if the run is outside this manager's root or cannot be deleted
	 */
	public void deleteRun(HarnessRun run) throws IOException
	{
		requireThat(run, "run").isNotNull();
		Path directory = run.directory().toAbsolutePath().normalize();
		Path harnessDirectory = root.resolve(run.harness()).normalize();
		if (!directory.getParent().equals(harnessDirectory) || !directory.startsWith(root) ||
			Files.isSymbolicLink(harnessDirectory) || Files.isSymbolicLink(directory) ||
			!Files.isRegularFile(directory.resolve("run.json"), NOFOLLOW_LINKS))
		{
			throw new IOException("Run is not owned by this manager");
		}
		Manifest manifest = readManifest(run);
		if (manifest.state().equals("running") && isOwnerAlive(manifest))
			throw new IOException("Run is still owned by a live process: " + run.id());
		try (HarnessRunLock lock = acquireLock(run))
		{
			if (!lock.isHeld())
				throw new IOException("Unable to acquire run lock");
			delete(directory);
		}
		deleteIfEmpty(harnessDirectory);
		deleteIfEmpty(root);
	}

	/**
	 * Removes disposable isolated runtime state after a receipt retained the project, harness events, and raw response.
	 *
	 * @param run the completed retained run
	 * @throws IOException if the run is not terminal or its disposable state cannot be removed
	 */
	public void pruneRuntime(HarnessRun run) throws IOException
	{
		requireThat(run, "run").isNotNull();
		Manifest manifest = readManifest(run);
		if (manifest.state().equals("running"))
			throw new IOException("Cannot prune a running harness run");
		for (String directory : List.of("home", "claude"))
		{
			Path candidate = run.directory().resolve(directory);
			if (Files.exists(candidate))
				delete(candidate);
		}
	}

	/**
	 * Removes disposable runtime state from every terminal run owned by one harness root. Entries whose names are not
	 * generated run identifiers are not runs and stay as they are, such as the {@code .claude} directories that a
	 * harness sandbox creates in the directories above its working directory.
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
				if (!RUN_ID.matcher(entry.getFileName().toString()).matches())
					continue;
				if (Files.isSymbolicLink(entry) || !Files.isDirectory(entry, NOFOLLOW_LINKS))
					throw new IOException("Harness run is not an owned directory: " + entry);
				HarnessRun run = new HarnessRun(harness, entry.getFileName().toString(), entry);
				if (!readManifest(run).state().equals("running"))
					pruneRuntime(run);
			}
		}
	}

	/**
	 * Deletes one explicitly selected owned run.
	 *
	 * @param harness the harness owning the run
	 * @param id the generated run identifier
	 * @throws IOException if the run is outside this manager's root, missing, active, or cannot be deleted
	 */
	public void deleteRun(String harness, String id) throws IOException
	{
		requireThat(harness, "harness").isNotBlank();
		requireThat(id, "id").isNotBlank();
		Path directory = root.resolve(harness).resolve(id).normalize();
		HarnessRun run = new HarnessRun(harness, id, directory);
		Manifest manifest = readManifest(run);
		if (manifest.state().equals("running") && isOwnerAlive(manifest))
			throw new IOException("Run is still owned by a live process: " + id);
		deleteRun(run);
	}

	/**
	 * Acquires the exclusive lock for one validated owned run.
	 *
	 * @param run the owned run
	 * @return the acquired run lock
	 * @throws IOException if the run is outside this manager's root, unknown, or active
	 */
	public HarnessRunLock acquireLock(HarnessRun run) throws IOException
	{
		requireThat(run, "run").isNotNull();
		Path directory = run.directory().toAbsolutePath().normalize();
		Path harnessDirectory = root.resolve(run.harness()).normalize();
		if (!directory.getParent().equals(harnessDirectory) || !directory.startsWith(root) ||
			Files.isSymbolicLink(harnessDirectory) || Files.isSymbolicLink(directory))
		{
			throw new IOException("Run is not owned by this manager");
		}
		Path lock = directory.resolve(".lock");
		if (!Files.isRegularFile(lock, NOFOLLOW_LINKS))
			throw new IOException("Run is missing its lock file");
		return HarnessRunLock.acquire(lock);
	}

	/**
	 * Deletes stale CAT-owned runs older than the retention period when no owner holds their lock.
	 *
	 * @param retention the positive retention period
	 * @throws IOException if run directories cannot be inspected or deleted
	 */
	public void deleteStaleRuns(Duration retention) throws IOException
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
				try (Stream<Path> runs = Files.list(harness))
				{
					for (Path run : runs.filter(path -> Files.isDirectory(path, NOFOLLOW_LINKS)).toList())
					{
						Path manifest = run.resolve("run.json");
						if (!Files.isRegularFile(manifest, NOFOLLOW_LINKS))
							continue;
						Manifest parsed;
						try
						{
							parsed = readManifest(manifest);
						}
						catch (IOException _)
						{
							continue;
						}
						if (!parsed.harness().equals(harnessName) || !parsed.id().equals(run.getFileName().toString()))
							continue;
						HarnessRun ownedRun = new HarnessRun(harnessName, run.getFileName().toString(), run);
						if (parsed.state().equals("running"))
						{
							if (isOwnerAlive(parsed))
								continue;
							if (!markAbandonedRunFailed(ownedRun))
								continue;
							// Retain the just-recorded diagnostic for the full terminal retention period.
							continue;
						}
						Instant reference = parsed.finishedAt();
						if (reference.plus(retention).isAfter(clock.instant()))
							continue;
						try (HarnessRunLock lock = acquireLock(ownedRun))
						{
							if (lock.isHeld())
								delete(run);
						}
						catch (IOException e)
						{
							if (!isActiveRun(e))
								throw e;
						}
					}
				}
			}
		}
	}

	/**
	 * Marks an unlocked run failed when its owner ended without recording a terminal result.
	 *
	 * @param run the owned run whose recorded owner is no longer alive
	 * @return {@code true} if the run was made terminal, or {@code false} if it is still active or unavailable
	 * @throws IOException if the manifest cannot be read, the harness process cannot be stopped, or terminal state cannot
	 *     be persisted
	 */
	private boolean markAbandonedRunFailed(HarnessRun run) throws IOException
	{
		assert run != null;
		try (HarnessRunLock lock = acquireLock(run))
		{
			if (!lock.isHeld())
				return false;
			Manifest manifest = readManifest(run);
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
			writeManifest(new Manifest(manifest.harness(), manifest.id(), "failed", "failed", manifest.ownerPid(),
				manifest.ownerStartedAt(), manifest.harnessPid(), manifest.harnessStartedAt(), manifest.createdAt(),
				manifest.artifactPath(), manifest.projectPath(), manifest.transport(), manifest.harnessCommand(),
				manifest.startedAt(), manifest.turnNumber(), manifest.exitCode(), clock.instant(),
				"Runner process ended without recording a terminal result"), run.directory());
			return true;
		}
		catch (IOException e)
		{
			if (isActiveRun(e))
				return false;
			throw e;
		}
	}

	/**
	 * Restricts a run directory to its owner on POSIX filesystems.
	 *
	 * @param directory the owned run directory
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
	 * Replaces a non-secret run manifest.
	 *
	 * @param run the owned run
	 * @param state the terminal or active state
	 * @param diagnostic the optional non-secret diagnostic
	 * @throws IOException if the manifest cannot be persisted
	 */
	private void writeManifest(HarnessRun run, String state, String diagnostic) throws IOException
	{
		assert run != null;
		assert state != null;
		assert diagnostic != null;
		Manifest previous = readManifest(run);
		Instant finishedAt = null;
		if (!state.equals("running"))
			finishedAt = clock.instant();
		writeManifest(new Manifest(run.harness(), run.id(), state, state, previous.ownerPid(), previous.ownerStartedAt(),
			previous.harnessPid(), previous.harnessStartedAt(), previous.createdAt(), previous.artifactPath(),
			previous.projectPath(), previous.transport(), previous.harnessCommand(), previous.startedAt(),
			previous.turnNumber(), previous.exitCode(), finishedAt, diagnostic), run.directory());
	}

	/**
	 * Replaces the active phase of a non-terminal run.
	 *
	 * @param run the owned run
	 * @param phase the active phase
	 * @throws IOException if the manifest cannot be persisted
	 */
	private void setPhase(HarnessRun run, String phase) throws IOException
	{
		assert run != null;
		assert phase != null;
		Manifest previous = readManifest(run);
		if (!previous.state().equals("running"))
			throw new IOException("Cannot update the phase of a terminal run");
		writeManifest(new Manifest(previous.harness(), previous.id(), previous.state(), phase, previous.ownerPid(),
			previous.ownerStartedAt(), previous.harnessPid(), previous.harnessStartedAt(), previous.createdAt(),
			previous.artifactPath(), previous.projectPath(), previous.transport(), previous.harnessCommand(),
			previous.startedAt(), previous.turnNumber(), previous.exitCode(), previous.finishedAt(),
			previous.diagnostic()), run.directory());
	}

	/**
	 * Replaces one structured non-secret manifest.
	 *
	 * @param manifest the manifest to write
	 * @param directory the owned run directory
	 * @throws IOException if the manifest cannot be persisted
	 */
	private static void writeManifest(Manifest manifest, Path directory) throws IOException
	{
		assert manifest != null;
		assert directory != null;
		String content = "{\n  \"schema_version\": 3,\n  \"cat_version\": \"" + PluginInfo.CAT_VERSION +
			"\",\n  \"harness\": \"" + manifest.harness() + "\",\n  \"id\": \"" + manifest.id() +
			"\",\n  \"state\": \"" + manifest.state() + "\",\n  \"phase\": \"" + manifest.phase() +
			"\",\n  \"owner_pid\": " + manifest.ownerPid() +
			",\n  \"owner_started_at\": \"" + manifest.ownerStartedAt() + "\",\n  \"harness_pid\": " +
			nullableNumber(manifest.harnessPid()) + ",\n  \"harness_started_at\": " +
			nullableInstant(manifest.harnessStartedAt()) + ",\n  \"created_at\": \"" + manifest.createdAt() +
			"\",\n  \"artifact_path\": \"" + escape(manifest.artifactPath()) + "\",\n  \"project_path\": \"" +
			escape(manifest.projectPath()) + "\",\n  \"transport\": \"" + escape(manifest.transport()) +
			"\",\n  \"harness_command\": \"" + escape(manifest.harnessCommand()) + "\",\n  \"started_at\": " +
			nullableInstant(manifest.startedAt()) + ",\n  \"turn_number\": " + manifest.turnNumber() +
			",\n  \"exit_code\": " + nullableNumber(manifest.exitCode()) + ",\n  \"finished_at\": " +
			nullableInstant(manifest.finishedAt()) + ",\n  \"diagnostic\": \"" +
			escape(manifest.diagnostic()) + "\"\n}\n";
		writeAtomically(directory.resolve("run.json"), content);
	}

	/**
	 * Reads and validates one owned manifest.
	 *
	 * @param run the expected owned run
	 * @return the validated manifest
	 * @throws IOException if the manifest is malformed or does not identify the run
	 */
	private static Manifest readManifest(HarnessRun run) throws IOException
	{
		assert run != null;
		Manifest manifest = readManifest(run.directory().resolve("run.json"));
		if (!manifest.harness().equals(run.harness()) || !manifest.id().equals(run.id()))
			throw new IOException("Run manifest does not identify the owned run");
		return manifest;
	}

	/**
	 * Reads and validates one manifest file without trusting its path-derived identity.
	 *
	 * @param file the manifest file
	 * @return the parsed manifest
	 * @throws IOException if the manifest is malformed or unsupported
	 */
	private static Manifest readManifest(Path file) throws IOException
	{
		assert file != null;
		if (Files.size(file) > MAXIMUM_MANIFEST_BYTES)
		{
			throw new IOException("Run manifest exceeds the maximum supported size of " + MAXIMUM_MANIFEST_BYTES +
				" bytes: " + file);
		}
		String content = Files.readString(file);
		Matcher matcher = MANIFEST.matcher(content);
		if (!matcher.matches() || !matcher.group("catVersion").equals(PluginInfo.CAT_VERSION))
			throw new IOException("Run manifest is malformed or unsupported: " + file);
		try
		{
			long ownerPid = Long.parseLong(matcher.group("ownerPid"));
			Instant ownerStartedAt = Instant.parse(matcher.group("ownerStartedAt"));
			Long harnessPid = null;
			if (!matcher.group("harnessPid").equals("null"))
				harnessPid = Long.parseLong(matcher.group("harnessPid"));
			Instant harnessStartedAt = null;
			if (!matcher.group("harnessStartedAt").equals("null"))
				harnessStartedAt = Instant.parse(matcher.group("harnessStartedAtValue"));
			if ((harnessPid == null) != (harnessStartedAt == null))
				throw new IOException("Run manifest has an incomplete harness process identity: " + file);
			Instant createdAt = Instant.parse(matcher.group("createdAt"));
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
				throw new IOException("Run manifest has an invalid terminal timestamp: " + file);
			String state = matcher.group("state");
			String phase = matcher.group("phase");
			if (state.equals("running") == (phase.equals("completed") || phase.equals("failed")))
				throw new IOException("Run manifest has an invalid lifecycle phase: " + file);
			if (!state.equals("running") && !phase.equals(state))
				throw new IOException("Run manifest has an invalid terminal phase: " + file);
			return new Manifest(matcher.group("harness"), matcher.group("id"), state, phase, ownerPid,
				ownerStartedAt, harnessPid, harnessStartedAt, createdAt, unescape(matcher.group("artifactPath")),
				unescape(matcher.group("projectPath")), unescape(matcher.group("transport")),
				unescape(matcher.group("harnessCommand")), startedAt, Integer.parseInt(matcher.group("turnNumber")),
				exitCode, finishedAt, "");
		}
		catch (DateTimeParseException e)
		{
			throw new IOException("Run manifest contains an invalid timestamp: " + file, e);
		}
	}

	/**
	 * Indicates whether the recorded owner process still exists with the same start marker.
	 *
	 * @param manifest the validated run manifest
	 * @return {@code true} if the original owner remains alive
	 */
	private static boolean isOwnerAlive(Manifest manifest)
	{
		assert manifest != null;
		return new ProcessHandleLiveness().isAlive(manifest.ownerPid(), manifest.ownerStartedAt());
	}

	/**
	 * Indicates whether the recorded harness process remains alive.
	 *
	 * @param manifest the validated run manifest
	 * @return {@code true} if the harness process is recorded and remains alive
	 */
	private boolean isHarnessProcessAlive(Manifest manifest)
	{
		assert manifest != null;
		return manifest.harnessPid() != null && processLiveness.isAlive(manifest.harnessPid(),
			manifest.harnessStartedAt());
	}

	/**
	 * Requests termination of the recorded harness process.
	 *
	 * @param manifest the validated run manifest
	 */
	private void terminateHarnessProcess(Manifest manifest)
	{
		assert manifest != null;
		assert manifest.harnessPid() != null;
		processLiveness.terminate(manifest.harnessPid(), manifest.harnessStartedAt());
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
	 * Formats an optional JSON timestamp.
	 *
	 * @param value the optional timestamp
	 * @return the JSON representation
	 */
	private static String nullableInstant(Instant value)
	{
		if (value == null)
			return "null";
		return "\"" + value + "\"";
	}

	/**
	 * Non-secret structured state for an owned run.
	 *
	 * @param harness the harness identifier
	 * @param id the run identifier
	 * @param state the lifecycle state
	 * @param phase the current activity or terminal phase
	 * @param ownerPid the orchestrator process identifier
	 * @param ownerStartedAt the orchestrator process start marker
	 * @param harnessPid the harness process identifier, if started
	 * @param harnessStartedAt the harness process start marker, if started
	 * @param createdAt the immutable creation timestamp
	 * @param artifactPath the complete artifact path
	 * @param projectPath the source project path
	 * @param transport the harness persistent transport
	 * @param harnessCommand the redacted harness command
	 * @param startedAt the harness-run start timestamp, if initialized
	 * @param turnNumber the latest completed turn number
	 * @param exitCode the latest observed harness-turn or externally reported runner exit status, if available
	 * @param finishedAt the terminal timestamp, if terminal
	 * @param diagnostic the optional diagnostic
	 */
	private record Manifest(String harness, String id, String state, String phase, long ownerPid, Instant ownerStartedAt,
		Long harnessPid, Instant harnessStartedAt, Instant createdAt, String artifactPath, String projectPath,
		String transport, String harnessCommand, Instant startedAt, int turnNumber, Integer exitCode, Instant finishedAt,
		String diagnostic)
	{
	}

	/**
	 * Writes text by atomically replacing the target where the filesystem supports it.
	 *
	 * @param output the target file
	 * @param content the file content
	 * @throws IOException if the file cannot be written
	 */
	private static void writeAtomically(Path output, String content) throws IOException
	{
		assert output != null;
		assert content != null;
		Path temporary = Files.createTempFile(output.getParent(), output.getFileName().toString(), ".tmp");
		try
		{
			Files.writeString(temporary, content);
			try
			{
				PortableFiles.replace(temporary, output);
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
		return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
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
		return value.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
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
	 * Deletes a run directory after its output has been persisted.
	 *
	 * @param directory the directory to delete
	 * @throws IOException if deletion fails
	 */
	private static void delete(Path directory) throws IOException
	{
		try
		{
			Files.walkFileTree(directory, new SimpleFileVisitor<>()
			{
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException
				{
					Files.deleteIfExists(file);
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
	 * Removes one empty runner grouping directory without disturbing another process that created a sibling run.
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
			// Another run still owns a child directory.
		}
	}

	/**
	 * Indicates whether lock acquisition failed because the run remains active.
	 *
	 * @param failure the lock-acquisition failure
	 * @return true if the run remains active
	 */
	private static boolean isActiveRun(IOException failure)
	{
		assert failure != null;
		String message = failure.getMessage();
		return message != null && message.startsWith("Run is already active:");
	}
}
