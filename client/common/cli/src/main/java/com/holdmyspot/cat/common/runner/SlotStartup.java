package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.ExclusiveFileLock;
import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.runner.policy.HarnessProcesses;
import com.holdmyspot.cat.common.runner.policy.ProcessDescription;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Persists the identity of the broker spawn that owns a session slot and fences a runner before it mutates that slot.
 * <p>
 * Every read or write of the startup record is made while the caller holds the stable lock returned by
 * {@link #acquire(FileLocks, Path)}. The lock file is a sibling of the removable slot root, so emptying the slot cannot
 * replace the object that concurrent processes lock.
 */
final class SlotStartup
{
	/**
	 * The startup record kept inside a slot.
	 */
	static final String FILE_NAME = "startup-permit.json";
	private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";
	private static final int FORMAT_VERSION = 1;
	private static final int MAXIMUM_BYTES = 16_384;
	private static final long LOCK_RETRY_NANOS = 10_000_000L;
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final Set<String> FIELDS = Set.of("format_version", "generation", "broker_pid", "broker_started_at",
		"state", "runner_pid", "runner_started_at", "manual_resume");

	/**
	 * The state of one spawn generation.
	 */
	enum State
	{
		PENDING,
		CLAIMED,
		CANCELLED
	}

	/**
	 * A durable slot spawn generation and the exact processes that issued and claimed it.
	 *
	 * @param generation the opaque UUID token passed to the child runner
	 * @param brokerPid the broker process identifier
	 * @param brokerStart the exact start identity of the broker process
	 * @param state the generation's lifecycle state
	 * @param runnerPid the claiming runner identifier, or {@code 0} until claimed
	 * @param runnerStart the claiming runner's exact start identity, or {@code null} until claimed
	 * @param manualResume whether an unguarded public resume has made this slot ineligible for optimizer recovery
	 */
	record Permit(String generation, long brokerPid, String brokerStart, State state, long runnerPid, String runnerStart,
		boolean manualResume)
	{
		/**
		 * Validates the durable permit fields.
		 *
		 * @param generation the opaque UUID token
		 * @param brokerPid the issuing broker process identifier
		 * @param brokerStart the issuing broker's exact start identity
		 * @param state the generation lifecycle state
		 * @param runnerPid the claiming runner process identifier, or zero before claim
		 * @param runnerStart the claiming runner's exact start identity, or null before claim
		 * @param manualResume whether public resume provenance is recorded
		 */
		Permit
		{
			requireThat(generation, "generation").matches(UUID_PATTERN);
			requireThat(brokerPid, "brokerPid").isPositive();
			requireThat(brokerStart, "brokerStart").matches(ProcessDescription.START_PATTERN);
			requireThat(state, "state").isNotNull();
			if (state == State.CLAIMED)
			{
				requireThat(runnerPid, "runnerPid").isPositive();
				requireThat(runnerStart, "runnerStart").matches(ProcessDescription.START_PATTERN);
			}
			else
			{
				requireThat(runnerPid, "runnerPid").isEqualTo(0L);
				requireThat(runnerStart, "runnerStart").isNull();
			}
		}
	}

	/**
	 * Prevents direct construction; all permits are issued through the locked helpers.
	 */
	private SlotStartup()
	{
	}

	/**
	 * Tries to take the stable lock beside a slot root, creating that regular lock file once if necessary.
	 *
	 * @param fileLocks the process-wide lock coordinator
	 * @param root the absolute slot root
	 * @return the lock, or empty when another process owns it
	 * @throws NullPointerException if an argument is null
	 * @throws IOException if the parent, lock file or lock cannot be inspected or opened
	 */
	static Optional<ExclusiveFileLock> tryLock(FileLocks fileLocks, Path root) throws IOException
	{
		requireThat(fileLocks, "fileLocks").isNotNull();
		Path lockFile = lockFile(root);
		Path parent = lockFile.getParent();
		if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Slot lock parent is not a directory: " + parent);
		try
		{
			Files.createFile(lockFile);
		}
		catch (FileAlreadyExistsException _)
		{
			// The stable sibling remains after the slot root is emptied.
		}
		if (Files.isSymbolicLink(lockFile) || !Files.isRegularFile(lockFile, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Slot lock is not a regular file: " + lockFile);
		return fileLocks.tryLock(lockFile, file -> FileChannel.open(file, StandardOpenOption.WRITE,
			LinkOption.NOFOLLOW_LINKS));
	}

	/**
	 * Waits for the stable slot lock.
	 *
	 * @param fileLocks the process-wide lock coordinator
	 * @param root the absolute slot root
	 * @return the held lock
	 * @throws NullPointerException if an argument is null
	 * @throws IOException if lock acquisition fails or the thread is interrupted
	 */
	static ExclusiveFileLock acquire(FileLocks fileLocks, Path root) throws IOException
	{
		while (true)
		{
			Optional<ExclusiveFileLock> lock = tryLock(fileLocks, root);
			if (lock.isPresent())
				return lock.get();
			LockSupport.parkNanos(LOCK_RETRY_NANOS);
			if (Thread.interrupted())
			{
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while waiting for the slot startup lock");
			}
		}
	}

	/**
	 * Indicates whether a value is a generation token.
	 *
	 * @param value the candidate token
	 * @return {@code true} if it is a canonical UUID
	 */
	static boolean isGeneration(String value)
	{
		return value != null && value.matches(UUID_PATTERN);
	}

	/**
	 * Returns this process's start identity.
	 *
	 * @return the process start identity
	 * @throws IOException if the process table cannot establish the start identity
	 */
	static String currentProcessStart() throws IOException
	{
		long pid = ProcessHandle.current().pid();
		String start = HarnessProcesses.system().find(pid).flatMap(ProcessDescription::start).orElse(null);
		if (start == null || !start.matches(ProcessDescription.START_PATTERN))
			throw new IOException("Cannot establish the current process start identity");
		return start;
	}

	/**
	 * Writes a fresh generation for a broker spawn. A public manual resume remains sticky until the slot is emptied.
	 * The caller must hold the stable slot lock.
	 *
	 * @param root the absolute slot root
	 * @param brokerPid the broker process identifier
	 * @param brokerStart the broker's exact process start identity
	 * @param manualResume whether this spawn is an unguarded public resume
	 * @return the opaque generation token that the child must present
	 * @throws IOException if a previous generation is malformed or still pending, or the record cannot be replaced
	 */
	static String issue(Path root, long brokerPid, String brokerStart, boolean manualResume) throws IOException
	{
		Optional<Permit> previous = readIfPresent(root);
		if (previous.isPresent() && previous.get().state() == State.PENDING)
			throw new IOException("A previous runner spawn is still pending for slot: " + root);
		boolean manual = manualResume || previous.map(Permit::manualResume).orElse(false);
		String generation = UUID.randomUUID().toString();
		write(root, new Permit(generation, brokerPid, brokerStart, State.PENDING, 0, null, manual));
		return generation;
	}

	/**
	 * Reads the generation record, rejecting malformed, foreign or oversized content.
	 *
	 * @param root the absolute slot root
	 * @return the permit
	 * @throws IOException if the record is absent, malformed or cannot be read
	 */
	static Permit read(Path root) throws IOException
	{
		return readFile(permitFile(root));
	}

	/**
	 * Confirms that a runner presents this pending generation and that the exact broker process is still alive.
	 * The caller must hold the stable slot lock.
	 *
	 * @param root the absolute slot root
	 * @param generation the token passed on the runner command line
	 * @param brokerLiveness checks exact broker process identities
	 * @throws IOException if the generation is stale, cancelled or its broker identity is no longer live
	 */
	static void validatePending(Path root, String generation, HarnessProcessLiveness brokerLiveness) throws IOException
	{
		requireThat(generation, "generation").matches(UUID_PATTERN);
		requireThat(brokerLiveness, "brokerLiveness").isNotNull();
		Permit permit = read(root);
		if (!permit.generation().equals(generation) || permit.state() != State.PENDING)
			throw new IOException("The runner startup permit is stale or has already been claimed");
		if (!brokerLiveness.isAlive(permit.brokerPid(), permit.brokerStart()))
			throw new IOException("The broker process that issued the runner startup permit is no longer alive");
	}

	/**
	 * Publishes the runner identity after its nested-session manifest has been written.
	 * The caller must hold the stable slot lock.
	 *
	 * @param root the absolute slot root
	 * @param generation the token passed on the runner command line
	 * @param brokerLiveness checks exact broker process identities
	 * @param runnerPid the runner process identifier
	 * @param runnerStart the runner's exact process start identity
	 * @throws IOException if the permit changed, the broker process ended, or the record cannot be replaced
	 */
	static void claim(Path root, String generation, HarnessProcessLiveness brokerLiveness, long runnerPid,
		String runnerStart) throws IOException
	{
		validatePending(root, generation, brokerLiveness);
		Permit pending = read(root);
		write(root, new Permit(generation, pending.brokerPid(), pending.brokerStart(), State.CLAIMED, runnerPid,
			runnerStart, pending.manualResume()));
	}

	/**
	 * Cancels only the exact pending generation while preserving its durable provenance.
	 * The caller must hold the stable slot lock.
	 *
	 * @param root the absolute slot root
	 * @param generation the expected generation
	 * @throws IOException if the current record names another generation or cannot be replaced
	 */
	static void cancel(Path root, String generation) throws IOException
	{
		Optional<Permit> found = readIfPresent(root);
		if (found.isEmpty())
			return;
		Permit permit = found.get();
		if (!permit.generation().equals(generation))
			throw new IOException("The current startup generation does not match the one being cancelled");
		if (permit.state() == State.PENDING)
			write(root, new Permit(generation, permit.brokerPid(), permit.brokerStart(), State.CANCELLED, 0, null,
				permit.manualResume()));
	}

	/**
	 * Finds the persistent lock path outside the removable slot root.
	 *
	 * @param root the absolute slot root
	 * @return the sibling lock path
	 */
	private static Path lockFile(Path root)
	{
		requireThat(root, "root").isNotNull().isAbsolute();
		Path name = root.getFileName();
		Path parent = root.getParent();
		if (name == null || parent == null)
			throw new IllegalArgumentException("A slot root must have a parent and name: " + root);
		return parent.resolve("." + name + ".lock");
	}

	/**
	 * Finds the permit path within the slot root.
	 *
	 * @param root the absolute slot root
	 * @return the permit path
	 */
	private static Path permitFile(Path root)
	{
		requireThat(root, "root").isNotNull().isAbsolute();
		return root.resolve(FILE_NAME);
	}

	/**
	 * Reads the permit when present, distinguishing true absence from unreadable paths.
	 *
	 * @param root the absolute slot root
	 * @return the parsed permit, or empty only when the permit file is absent
	 * @throws IOException if the permit exists but cannot be safely read
	 */
	private static Optional<Permit> readIfPresent(Path root) throws IOException
	{
		try
		{
			return Optional.of(readFile(permitFile(root)));
		}
		catch (NoSuchFileException _)
		{
			return Optional.empty();
		}
	}

	/**
	 * Reads and validates one bounded, regular, non-link permit file.
	 *
	 * @param file the permit file
	 * @return its validated permit
	 * @throws IOException if the file is unsafe or malformed
	 */
	private static Permit readFile(Path file) throws IOException
	{
		BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		if (!attributes.isRegularFile())
			throw new IOException("Runner startup record is not a regular file: " + file);
		byte[] bytes;
		try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))
		{
			ByteBuffer content = ByteBuffer.allocate(MAXIMUM_BYTES + 1);
			while (content.hasRemaining() && channel.read(content) >= 0)
			{
			}
			if (content.position() > MAXIMUM_BYTES)
				throw new IOException("Runner startup record is too large: " + file);
			bytes = Arrays.copyOf(content.array(), content.position());
		}
		JsonNode document = JSON.readerFor(JsonNode.class).with(StreamReadFeature.STRICT_DUPLICATE_DETECTION).
			readValue(new ByteArrayInputStream(bytes));
		if (document == null || !document.isObject() || document.size() != FIELDS.size())
			throw new IOException("Runner startup record is malformed: " + file);
		for (String field : document.propertyNames())
		{
			if (!FIELDS.contains(field))
				throw new IOException("Runner startup record has an unsupported field: " + file);
		}
		if (document.path("format_version").asInt(-1) != FORMAT_VERSION)
			throw new IOException("Runner startup record has an unsupported format version: " + file);
		try
		{
			String generation = document.path("generation").asString();
			long brokerPid = document.path("broker_pid").asLong(-1);
			String brokerStart = document.path("broker_started_at").asString();
			State state = State.valueOf(document.path("state").asString().toUpperCase(Locale.ROOT));
			long runnerPid = document.path("runner_pid").asLong(-1);
			JsonNode runnerStartNode = document.path("runner_started_at");
			String runnerStart = null;
			if (!runnerStartNode.isNull())
				runnerStart = runnerStartNode.asString();
			JsonNode manualNode = document.path("manual_resume");
			if (!manualNode.isBoolean())
				throw new IllegalArgumentException("manual_resume must be a boolean");
			return new Permit(generation, brokerPid, brokerStart, state, runnerPid, runnerStart, manualNode.asBoolean());
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException("Runner startup record is malformed: " + file, e);
		}
	}

	/**
	 * Durably replaces the permit through a same-directory temporary file.
	 *
	 * @param root the absolute slot root
	 * @param permit the replacement permit
	 * @throws IOException if the root or replacement cannot be safely persisted
	 */
	private static void write(Path root, Permit permit) throws IOException
	{
		if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root))
			throw new IOException("Runner startup root is not an owned directory: " + root);
		Path file = permitFile(root);
		if (Files.isSymbolicLink(file))
			throw new IOException("Runner startup record is a symbolic link: " + file);
		ObjectNode document = JSON.createObjectNode().put("format_version", FORMAT_VERSION).
			put("generation", permit.generation()).put("broker_pid", permit.brokerPid()).
			put("broker_started_at", permit.brokerStart()).put("state", permit.state().name().toLowerCase(Locale.ROOT)).
			put("runner_pid", permit.runnerPid());
		if (permit.runnerStart() == null)
			document.putNull("runner_started_at");
		else
			document.put("runner_started_at", permit.runnerStart());
		document.put("manual_resume", permit.manualResume());
		byte[] bytes = JSON.writeValueAsBytes(document);
		Path temporary = Files.createTempFile(root, ".startup-permit-", ".tmp");
		try
		{
			try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
				StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS))
			{
				ByteBuffer content = ByteBuffer.wrap(bytes);
			while (content.hasRemaining())
				channel.write(content);
				channel.force(true);
			}
			Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			if (!Platform.current().isWindows())
			{
				try (FileChannel directory = FileChannel.open(root, StandardOpenOption.READ))
				{
					directory.force(true);
				}
			}
		}
		finally
		{
			Files.deleteIfExists(temporary);
		}
	}
}
