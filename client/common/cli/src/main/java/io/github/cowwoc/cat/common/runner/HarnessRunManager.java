package io.github.cowwoc.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.FileVisitResult.CONTINUE;
import static java.nio.file.FileVisitResult.SKIP_SUBTREE;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.COPY_ATTRIBUTES;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;

import io.github.cowwoc.cat.common.PluginInfo;
import io.github.cowwoc.cat.common.TemporaryDirectories;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Creates, records, and deletes the isolated directories used by harness executions.
 */
public final class HarnessRunManager
{
	/**
	 * Default retention period for terminal diagnostic runs.
	 */
	public static final Duration DEFAULT_RETENTION = Duration.ofDays(7);
	static final int MAXIMUM_MANIFEST_BYTES = 1_048_576;
	private static final Pattern MANIFEST = Pattern.compile("""
		\\A\\{
		  "schemaVersion": 3,
		  "catVersion": "(?<catVersion>[^\"]+)",
		  "harness": "(?<harness>[a-z0-9]+)",
		  "id": "(?<id>[a-z0-9]+-[a-z0-9]+)",
		  "state": "(?<state>running|completed|failed)",
		  "phase": "(?<phase>initializing|idle|working|completed|failed)",
		  "ownerPid": "?(?<ownerPid>[1-9][0-9]*)"?,
		  "ownerStartedAt": "(?<ownerStartedAt>[^\"]+)",
		  "nativePid": (?<nativePid>null|[1-9][0-9]*),
		  "nativeStartedAt": (?<nativeStartedAt>null|"(?<nativeStartedAtValue>[^\"]+)"),
		  "createdAt": "(?<createdAt>[^\"]+)",
		  "artifactPath": "(?<artifactPath>(?:[^"\\\\]|\\\\.)*)",
		  "projectPath": "(?<projectPath>(?:[^"\\\\]|\\\\.)*)",
		  "transport": "(?<transport>(?:[^"\\\\]|\\\\.)*)",
		  "nativeCommand": "(?<nativeCommand>(?:[^"\\\\]|\\\\.)*)",
		  "startedAt": (?<startedAt>null|"(?<startedAtValue>[^\"]+)"),
		  "turnNumber": (?<turnNumber>[0-9]+),
		  "exitCode": (?<exitCode>null|-?[0-9]+),
		  "finishedAt": (?<finishedAt>null|"(?<finishedAtValue>[^\"]+)"),
		  "diagnostic": "(?<diagnostic>(?:[^"\\\\]|\\\\.)*)"
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
		this(root, clock, new NativeProcessLiveness());
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
	 * Creates one owned run directory.
	 *
	 * @param harness the harness name
	 * @return the created run
	 * @throws IOException if the directory cannot be created
	 */
	public HarnessRun create(String harness) throws IOException
	{
		return create(harness, generateRunId());
	}

	/**
	 * Generates a run identity that a caller can retain before allowing native start.
	 *
	 * @return a runner-format run identity
	 */
	public String generateRunId()
	{
		return Long.toUnsignedString(clock.millis(), 36) + "-" + Long.toUnsignedString(System.nanoTime(), 36);
	}

	/**
	 * Creates one owned run under an identity retained before native start.
	 *
	 * @param harness the harness name
	 * @param id the preallocated run identity
	 * @return the exclusively created run
	 * @throws IOException if the run already exists or its directory cannot be created
	 */
	public HarnessRun create(String harness, String id) throws IOException
	{
		requireThat(harness, "harness").isNotBlank();
		requireThat(id, "id").isNotBlank();
		Path directory = root.resolve(harness).resolve(id).normalize();
		HarnessRun run = new HarnessRun(harness, id, directory);
		if (!directory.startsWith(root))
			throw new IOException("Run directory escapes the configured root");
		Files.createDirectories(directory.getParent());
		Files.createDirectory(directory);
		setOwnerOnlyDirectoryPermissions(directory);
		Files.createDirectories(directory.resolve("project"));
		Files.createDirectories(directory.resolve("home"));
		Files.createDirectories(directory.resolve("artifacts"));
		Files.createFile(directory.resolve(".lock"));
		ProcessHandle owner = ProcessHandle.current();
		Instant ownerStartedAt = owner.info().startInstant().orElse(clock.instant());
		writeManifest(new Manifest(harness, id, "running", "initializing", owner.pid(), ownerStartedAt, null, null,
			clock.instant(),
			"", "", "", "", null, 0, null, null, ""), directory);
		return run;
	}

	/**
	 * Reads one exact owned run without changing its lifecycle or deciding whether its native turn is safe to retry.
	 *
	 * @param harness the native harness identifier
	 * @param id the preallocated run identifier
	 * @return the validated retained run, or empty when no owned directory is presently available; empty alone does not
	 *     prove that no run was ever created
	 * @throws IOException if a present run has an unsafe path or unreadable or inconsistent manifest
	 */
	public Optional<HarnessRunObservation> observe(String harness, String id) throws IOException
	{
		Path harnessDirectory = root.resolve(harness);
		HarnessRun run = new HarnessRun(harness, id, harnessDirectory.resolve(id).normalize());
		if (Files.isSymbolicLink(root) || Files.isSymbolicLink(harnessDirectory) ||
			Files.isSymbolicLink(run.directory()))
			throw new IOException("Runner observation crosses a symbolic link: " + run.directory());
		if (Files.exists(root, NOFOLLOW_LINKS) && !Files.isDirectory(root, NOFOLLOW_LINKS) ||
			Files.exists(harnessDirectory, NOFOLLOW_LINKS) && !Files.isDirectory(harnessDirectory, NOFOLLOW_LINKS))
			throw new IOException("Runner observation root is not a directory: " + harnessDirectory);
		if (Files.notExists(run.directory(), NOFOLLOW_LINKS))
			return Optional.empty();
		if (!Files.isDirectory(run.directory(), NOFOLLOW_LINKS) ||
			Files.isSymbolicLink(run.directory().resolve("run.json")))
			throw new IOException("Runner observation is not an owned directory and manifest: " + run.directory());
		Manifest manifest = readManifest(run);
		return Optional.of(new HarnessRunObservation(run,
			HarnessRunObservation.State.valueOf(manifest.state().toUpperCase(Locale.ROOT)),
			HarnessRunObservation.Phase.valueOf(manifest.phase().toUpperCase(Locale.ROOT)),
			Optional.ofNullable(manifest.nativePid()), Optional.ofNullable(manifest.nativeStartedAt()),
			manifest.turnNumber(), Optional.ofNullable(manifest.exitCode()), Optional.ofNullable(manifest.finishedAt()),
			isOwnerAlive(manifest), isNativeProcessAlive(manifest), manifest.diagnostic()));
	}

	/**
	 * Records non-secret metadata required to investigate an owned native process.
	 *
	 * @param run the owned run
	 * @param artifact the complete harness artifact
	 * @param project the source project
	 * @param transport the persistent native transport identifier
	 * @param command the native command with any secret values redacted
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
			previous.ownerStartedAt(), previous.nativePid(), previous.nativeStartedAt(), previous.createdAt(),
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
	 * Records the identity of the native process owned by a live run.
	 *
	 * @param run the owned run
	 * @param nativePid the positive native process identifier
	 * @param nativeStartedAt the native process start marker
	 * @throws NullPointerException if {@code run} or {@code nativeStartedAt} are null
	 * @throws IllegalArgumentException if {@code nativePid} is not positive
	 * @throws IOException if the manifest cannot be persisted
	 */
	public void recordNativeProcess(HarnessRun run, long nativePid, Instant nativeStartedAt) throws IOException
	{
		requireThat(run, "run").isNotNull();
		requireThat(nativeStartedAt, "nativeStartedAt").isNotNull();
		requireThat(nativePid, "nativePid").isPositive();
		Manifest previous = readManifest(run);
		if (!previous.state().equals("running"))
			throw new IOException("Cannot record a native process for a terminal run");
		writeManifest(new Manifest(previous.harness(), previous.id(), previous.state(), previous.phase(),
			previous.ownerPid(),
			previous.ownerStartedAt(), nativePid, nativeStartedAt, previous.createdAt(), previous.artifactPath(),
			previous.projectPath(), previous.transport(), previous.nativeCommand(), previous.startedAt(),
			previous.turnNumber(), previous.exitCode(), previous.finishedAt(), previous.diagnostic()), run.directory());
	}

	/**
	 * Replaces the captured standard streams of a native command for failed-run investigation.
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
	 * Records one native turn's raw events and error stream for later investigation.
	 *
	 * @param run the owned run
	 * @param turnNumber the positive one-based turn number
	 * @param result the captured native turn result
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
			previous.ownerStartedAt(), previous.nativePid(), previous.nativeStartedAt(), previous.createdAt(),
			previous.artifactPath(), previous.projectPath(), previous.transport(), previous.nativeCommand(),
			previous.startedAt(), turnNumber, result.exitCode(), previous.finishedAt(), previous.diagnostic()),
			run.directory());
	}

	/**
	 * Appends one native event while its turn remains active, making current progress inspectable before terminal output
	 * is available.
	 *
	 * @param run the owned run
	 * @param turnNumber the positive one-based turn number
	 * @param event one non-blank native event line
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
	 * Copies a project into an owned Git repository with a clean baseline and without source Git metadata. Links to
	 * project content remain links to the copied content; links outside the project are rejected before native launch.
	 *
	 * @param source the source project directory
	 * @param run the owned run
	 * @throws IOException if the source is not a real directory, contains an external or excluded symbolic-link target,
	 *   or copying fails
	 */
	public void copyProject(Path source, HarnessRun run) throws IOException
	{
		requireThat(source, "source").isNotNull();
		requireThat(run, "run").isNotNull();
		Path normalized = source.toAbsolutePath().normalize();
		if (Files.isSymbolicLink(normalized) || !Files.isDirectory(normalized, NOFOLLOW_LINKS))
			throw new IOException("Project source is not a real directory");
		Path sourceReal = normalized.toRealPath();
		Path destination = run.directory().resolve("project");
		Path excludedRoot = root.toRealPath();
		boolean rootIsNested = excludedRoot.startsWith(sourceReal);
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
				Path relative = normalized.relativize(file);
				if (relative.startsWith(".git"))
					return CONTINUE;
				if (Files.isSymbolicLink(file))
				{
					Path target = file.toRealPath();
					if (!target.startsWith(sourceReal))
						throw new IOException("Project symbolic link target is outside the project: " + relative);
					Path targetRelative = sourceReal.relativize(target);
					if (targetRelative.startsWith(".git") || rootIsNested && target.startsWith(excludedRoot))
						throw new IOException("Project symbolic link target is excluded from the copy: " + relative);
					Path copiedLink = destination.resolve(relative);
					Path linkText = Files.readSymbolicLink(file);
					if (linkText.isAbsolute())
						linkText = copiedLink.getParent().relativize(destination.resolve(targetRelative));
					Files.createSymbolicLink(copiedLink, linkText);
					return CONTINUE;
				}
				Files.copy(file, destination.resolve(relative), COPY_ATTRIBUTES);
				return CONTINUE;
			}
		});
		HarnessProjectRepository.initialize(destination, run.directory());
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
			if (isNativeProcessAlive(manifest))
			{
				terminateNativeProcess(manifest);
				if (isNativeProcessAlive(manifest))
					throw new IOException("Native process is still running for run: " + id);
			}
			writeManifest(new Manifest(manifest.harness(), manifest.id(), "failed", "failed", manifest.ownerPid(),
				manifest.ownerStartedAt(), manifest.nativePid(), manifest.nativeStartedAt(), manifest.createdAt(),
				manifest.artifactPath(), manifest.projectPath(), manifest.transport(), manifest.nativeCommand(),
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
			TemporaryDirectories.deleteRecursively(directory);
		}
		deleteIfEmpty(harnessDirectory);
		deleteIfEmpty(root);
	}

	/**
	 * Removes disposable isolated runtime state after a receipt retained the project, native events, and raw response.
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
				TemporaryDirectories.deleteRecursively(candidate);
		}
	}

	/**
	 * Removes disposable runtime state from every terminal run owned by one harness root.
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
								TemporaryDirectories.deleteRecursively(run);
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
	 * @throws IOException if the manifest cannot be read, the native process cannot be stopped, or terminal state cannot
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
			if (isNativeProcessAlive(manifest))
			{
				terminateNativeProcess(manifest);
				if (isNativeProcessAlive(manifest))
					return false;
			}
			writeManifest(new Manifest(manifest.harness(), manifest.id(), "failed", "failed", manifest.ownerPid(),
				manifest.ownerStartedAt(), manifest.nativePid(), manifest.nativeStartedAt(), manifest.createdAt(),
				manifest.artifactPath(), manifest.projectPath(), manifest.transport(), manifest.nativeCommand(),
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
			previous.nativePid(), previous.nativeStartedAt(), previous.createdAt(), previous.artifactPath(),
			previous.projectPath(), previous.transport(), previous.nativeCommand(), previous.startedAt(),
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
			previous.ownerStartedAt(), previous.nativePid(), previous.nativeStartedAt(), previous.createdAt(),
			previous.artifactPath(), previous.projectPath(), previous.transport(), previous.nativeCommand(),
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
		String content = "{\n  \"schemaVersion\": 3,\n  \"catVersion\": \"" + PluginInfo.CAT_VERSION +
			"\",\n  \"harness\": \"" + manifest.harness() + "\",\n  \"id\": \"" + manifest.id() +
			"\",\n  \"state\": \"" + manifest.state() + "\",\n  \"phase\": \"" + manifest.phase() +
			"\",\n  \"ownerPid\": " + manifest.ownerPid() +
			",\n  \"ownerStartedAt\": \"" + manifest.ownerStartedAt() + "\",\n  \"nativePid\": " +
			nullableNumber(manifest.nativePid()) + ",\n  \"nativeStartedAt\": " +
			nullableInstant(manifest.nativeStartedAt()) + ",\n  \"createdAt\": \"" + manifest.createdAt() +
			"\",\n  \"artifactPath\": \"" + escape(manifest.artifactPath()) + "\",\n  \"projectPath\": \"" +
			escape(manifest.projectPath()) + "\",\n  \"transport\": \"" + escape(manifest.transport()) +
			"\",\n  \"nativeCommand\": \"" + escape(manifest.nativeCommand()) + "\",\n  \"startedAt\": " +
			nullableInstant(manifest.startedAt()) + ",\n  \"turnNumber\": " + manifest.turnNumber() +
			",\n  \"exitCode\": " + nullableNumber(manifest.exitCode()) + ",\n  \"finishedAt\": " +
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
			Long nativePid = null;
			if (!matcher.group("nativePid").equals("null"))
				nativePid = Long.parseLong(matcher.group("nativePid"));
			Instant nativeStartedAt = null;
			if (!matcher.group("nativeStartedAt").equals("null"))
				nativeStartedAt = Instant.parse(matcher.group("nativeStartedAtValue"));
			if ((nativePid == null) != (nativeStartedAt == null))
				throw new IOException("Run manifest has an incomplete native process identity: " + file);
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
				ownerStartedAt, nativePid, nativeStartedAt, createdAt, unescape(matcher.group("artifactPath")),
				unescape(matcher.group("projectPath")), unescape(matcher.group("transport")),
				unescape(matcher.group("nativeCommand")), startedAt, Integer.parseInt(matcher.group("turnNumber")),
				exitCode, finishedAt, unescape(matcher.group("diagnostic")));
		}
		catch (DateTimeParseException e)
		{
			throw new IOException("Run manifest contains an invalid timestamp: " + file, e);
		}
	}

	/**
	 * Determines whether the recorded owner process still exists with the same start marker.
	 *
	 * @param manifest the validated run manifest
	 * @return {@code true} if the original owner remains alive
	 */
	private static boolean isOwnerAlive(Manifest manifest)
	{
		assert manifest != null;
		return new NativeProcessLiveness().isAlive(manifest.ownerPid(), manifest.ownerStartedAt());
	}

	/**
	 * Determines whether the recorded native process remains alive.
	 *
	 * @param manifest the validated run manifest
	 * @return {@code true} if the native process is recorded and remains alive
	 */
	private boolean isNativeProcessAlive(Manifest manifest)
	{
		assert manifest != null;
		return manifest.nativePid() != null && processLiveness.isAlive(manifest.nativePid(),
			manifest.nativeStartedAt());
	}

	/**
	 * Requests termination of the recorded native process.
	 *
	 * @param manifest the validated run manifest
	 */
	private void terminateNativeProcess(Manifest manifest)
	{
		assert manifest != null;
		assert manifest.nativePid() != null;
		processLiveness.terminate(manifest.nativePid(), manifest.nativeStartedAt());
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
	 * @param nativePid the native process identifier, if started
	 * @param nativeStartedAt the native process start marker, if started
	 * @param createdAt the immutable creation timestamp
	 * @param artifactPath the complete artifact path
	 * @param projectPath the source project path
	 * @param transport the native persistent transport
	 * @param nativeCommand the redacted native command
	 * @param startedAt the native-run start timestamp, if initialized
	 * @param turnNumber the latest completed turn number
	 * @param exitCode the latest observed native-turn or externally reported runner exit status, if available
	 * @param finishedAt the terminal timestamp, if terminal
	 * @param diagnostic the optional diagnostic
	 */
	private record Manifest(String harness, String id, String state, String phase, long ownerPid, Instant ownerStartedAt,
		Long nativePid, Instant nativeStartedAt, Instant createdAt, String artifactPath, String projectPath,
		String transport, String nativeCommand, Instant startedAt, int turnNumber, Integer exitCode, Instant finishedAt,
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
				Files.move(temporary, output, ATOMIC_MOVE, REPLACE_EXISTING);
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
	 * Redacts values following secret-bearing option names in a native command.
	 *
	 * @param command the native command
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
	 * Determines whether lock acquisition failed because the run remains active.
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
