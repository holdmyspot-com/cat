package com.holdmyspot.cat.common.runner.launcher;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.ExclusiveFileLock;
import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.HarnessProcessTermination;
import com.holdmyspot.cat.common.runner.RequestArchive;
import com.holdmyspot.cat.common.runner.RequestLimits;
import com.holdmyspot.cat.common.runner.RequestedSessionStarter;
import com.holdmyspot.cat.common.runner.SessionLauncherChannel;
import com.holdmyspot.cat.common.runner.SessionLauncherChannel.Request;
import com.holdmyspot.cat.common.runner.SessionLauncherChannel.Response;
import com.holdmyspot.cat.common.runner.SessionLauncherChannel.Status;
import com.holdmyspot.cat.common.runner.policy.ChildNeeds;
import com.holdmyspot.cat.common.runner.policy.ChildPolicy;
import com.holdmyspot.cat.common.runner.policy.ExtraPermissions;
import com.holdmyspot.cat.common.runner.policy.MissingPermissionsException;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyBoundFile;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.ReentrantLock;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Serves the session-level requests of one harness process: the unsandboxed process that starts the child sessions
 * that the harness's sandboxed commands ask for.
 * <p>
 * A command publishes a request in the inbox after starting a holder process whose arguments name the request and
 * the digest of its file. The launcher accepts the request only when such a holder is alive, the first harness with a
 * launcher on the holder's ancestry is the one that it serves, and its private copy of the file has that digest. It
 * never writes below the inbox: it reads and deletes there, and keeps its copies and answers in its state folder.
 * <p>
 * Each accepted request runs on its own task of the executor. The task starts the requested harness's runner within
 * the parent's sandbox policy, feeds it the request's records, and publishes its {@link Response}. When the holder of
 * a request ends before the request is answered, the launcher stops the runner and its descendants; once the request
 * is answered and its holder has ended, it deletes the request's folder.
 * <p>
 * The launcher ends when its harness process ends, when another process reuses that process's identifier, or when
 * its folder's {@link SessionLauncherChannel#STOP_FILE} appears. It then stops every child, and deletes its requests'
 * folders, their inbox files, and its own folder.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class SessionLauncher implements Closeable
{
	private static final String STAGING_DIRECTORY = "staging";
	private static final String ARCHIVE_FILE = "request.tar";
	private static final String INPUTS_DIRECTORY = "inputs";
	private static final String TURN_PREFIX = "turn-";
	private static final int HOLDER_ARGUMENTS = 6;
	private static final int MAXIMUM_DELETED_DEPTH = 32;
	private static final int STDERR_TAIL_BYTES = 4096;
	private final SessionLauncherChannel channel;
	private final ProcessIdentity harnessProcess;
	private final ProcessTable processes;
	private final ParentPolicySource policy;
	private final ChildNeedsSource needs;
	private final RequestedSessionStarter starter;
	private final FileLocks fileLocks;
	private final Clock clock;
	private final ExecutorService executor;
	private final JsonMapper json = JsonMapper.builder().build();
	private final Path directory;
	/**
	 * Serializes {@link #start()}, {@link #poll()} and {@link #close()}.
	 */
	private final ReentrantLock pollLock = new ReentrantLock();
	private final Map<String, Served> served = new LinkedHashMap<>();
	private Set<InboxEntry> declined = new HashSet<>();
	private ExclusiveFileLock lock;
	private boolean started;
	private volatile boolean ended;
	private Instant lastHeartbeat = Instant.MIN;
	private ParentPolicy snapshot;
	private String startRefusal = "";

	/**
	 * Creates a launcher, which does nothing until it {@link #start() starts}.
	 *
	 * @param channel        the locations and names that the launcher shares with its senders
	 * @param harness        the identifier of the served harness, which names the launcher's folder
	 * @param harnessProcess the served harness process
	 * @param processes      the operating system's processes
	 * @param policy         the served session's sandbox policy
	 * @param needs          what a child session's commands need
	 * @param starter        starts the runner of a requested child
	 * @param fileLocks      the locks of this JVM
	 * @param clock          the clock that dates the heartbeat and the age of abandoned inbox files
	 * @param executor       runs each accepted request on a task of its own
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code harness} is not a lower-case identifier
	 */
	public SessionLauncher(SessionLauncherChannel channel, String harness, ProcessIdentity harnessProcess,
		ProcessTable processes, ParentPolicySource policy, ChildNeedsSource needs, RequestedSessionStarter starter,
		FileLocks fileLocks, Clock clock, ExecutorService executor)
	{
		requireThat(channel, "channel").isNotNull();
		requireThat(harnessProcess, "harnessProcess").isNotNull();
		requireThat(processes, "processes").isNotNull();
		requireThat(policy, "policy").isNotNull();
		requireThat(needs, "needs").isNotNull();
		requireThat(starter, "starter").isNotNull();
		requireThat(fileLocks, "fileLocks").isNotNull();
		requireThat(clock, "clock").isNotNull();
		requireThat(executor, "executor").isNotNull();
		this.channel = channel;
		this.harnessProcess = harnessProcess;
		this.processes = processes;
		this.policy = policy;
		this.needs = needs;
		this.starter = starter;
		this.fileLocks = fileLocks;
		this.clock = clock;
		this.executor = executor;
		this.directory = channel.launcherDirectory(harness, harnessProcess.pid(), harnessProcess.start());
	}

	/**
	 * Returns the launcher's folder, which holds its lock, heartbeat, stop file and log.
	 *
	 * @return the folder below {@link SessionLauncherChannel#launchers()}
	 */
	public Path directory()
	{
		return directory;
	}

	/**
	 * Starts serving. The launcher locks its folder, writes its heartbeat, reads the session's sandbox policy, and
	 * deletes what ended launchers and their senders left behind. A launcher whose folder another launcher already
	 * locked changes nothing.
	 *
	 * @return {@code true} if the launcher serves the harness; {@code false} if another launcher already does
	 * @throws IllegalStateException if the launcher was already started
	 * @throws IOException           if the launcher's state cannot be written
	 */
	public boolean start() throws IOException
	{
		pollLock.lock();
		try
		{
			if (started)
				throw new IllegalStateException("The launcher was already started");
			started = true;
			Files.createDirectories(directory);
			Optional<ExclusiveFileLock> acquired = fileLocks.tryLock(directory.resolve(
				SessionLauncherChannel.LOCK_FILE), file -> FileChannel.open(file, StandardOpenOption.CREATE,
				StandardOpenOption.WRITE));
			if (acquired.isEmpty())
				return false;
			lock = acquired.get();
			beat();
			Path staging = directory.resolve(STAGING_DIRECTORY);
			if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS))
				TemporaryDirectories.deleteRecursively(staging);
			Files.createDirectories(channel.requests());
			readSnapshot();
			sweepLaunchers();
			sweepRequests();
			sweepInbox();
			return true;
		}
		finally
		{
			pollLock.unlock();
		}
	}

	/**
	 * Does one round of serving: ends the launcher if its harness has ended or its stop file exists, renews the
	 * heartbeat when it is due, stops the children of requests whose holders have ended, and accepts the requests and
	 * turns that arrived in the inbox.
	 *
	 * @return {@code true} if the launcher still serves; {@code false} if it has ended, or never started
	 * @throws IOException if the launcher's state cannot be read or written
	 */
	public boolean poll() throws IOException
	{
		pollLock.lock();
		try
		{
			if (lock == null || ended)
				return false;
			if (!processes.isAlive(harnessProcess) ||
				Files.exists(directory.resolve(SessionLauncherChannel.STOP_FILE), LinkOption.NOFOLLOW_LINKS))
			{
				end();
				return false;
			}
			if (Duration.between(lastHeartbeat, clock.instant()).compareTo(SessionLauncherChannel.HEARTBEAT_INTERVAL) >= 0)
				beat();
			checkHolders();
			scanInbox();
			return true;
		}
		finally
		{
			pollLock.unlock();
		}
	}

	/**
	 * Serves until the launcher ends, pausing between polls. An entry point passes
	 * {@code Pause.sleeping(SessionLauncherChannel.INBOX_POLL)}.
	 *
	 * @param pause the wait between polls
	 * @throws NullPointerException if {@code pause} is null
	 * @throws IOException          if the launcher's state cannot be read or written, or the pause is cut short
	 */
	public void serve(Pause pause) throws IOException
	{
		requireThat(pause, "pause").isNotNull();
		while (poll())
			pause.pause();
	}

	/**
	 * Ends a launcher that serves: stops its children, and deletes its requests' folders, their inbox files and its
	 * own folder. Does nothing for a launcher that does not serve.
	 *
	 * @throws IOException if the launcher's state cannot be deleted
	 */
	@Override
	public void close() throws IOException
	{
		pollLock.lock();
		try
		{
			if (lock != null && !ended)
				end();
		}
		finally
		{
			pollLock.unlock();
		}
	}

	/**
	 * Renews the heartbeat.
	 *
	 * @throws IOException if the heartbeat cannot be written
	 */
	private void beat() throws IOException
	{
		Instant now = clock.instant();
		Path heartbeat = directory.resolve(SessionLauncherChannel.HEARTBEAT_FILE);
		if (!Files.exists(heartbeat, LinkOption.NOFOLLOW_LINKS))
			Files.writeString(heartbeat, "");
		Files.setLastModifiedTime(heartbeat, FileTime.from(now));
		lastHeartbeat = now;
	}

	/**
	 * Reads the policy that the session had when the launcher started, which bounds every child. A policy that cannot
	 * be read, or that lets the session's commands write the launcher's state, refuses every request.
	 */
	private void readSnapshot()
	{
		try
		{
			ParentPolicy initial = policy.read();
			initial.requireTrusted(channel.stateRoot());
			snapshot = initial;
		}
		catch (PolicyRefusal e)
		{
			startRefusal = e.messageFor(policy.harnessName());
		}
		catch (IOException e)
		{
			startRefusal = unreadablePolicy(e);
		}
	}

	/**
	 * Returns the reason for refusing a request whose session's policy cannot be read.
	 *
	 * @param failure the failure to read it
	 * @return the reason
	 */
	private String unreadablePolicy(IOException failure)
	{
		return "CAT cannot start the session: it cannot read this " + policy.harnessName() + " session's sandbox " +
			"policy: " + failure.getMessage();
	}

	/**
	 * Deletes the folders of launchers whose harness process has ended and whose lock no launcher holds.
	 *
	 * @throws IOException if the launchers' folder cannot be read
	 */
	private void sweepLaunchers() throws IOException
	{
		Path launchers = channel.launchers();
		for (Path entry : list(launchers))
		{
			if (entry.equals(directory))
				continue;
			Optional<ProcessIdentity> owner = launcherProcess(entry.getFileName().toString());
			if (owner.isEmpty() || processes.isAlive(owner.get()) ||
				!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS))
			{
				continue;
			}
			Optional<ExclusiveFileLock> free;
			try
			{
				free = fileLocks.tryLock(entry.resolve(SessionLauncherChannel.LOCK_FILE),
					file -> FileChannel.open(file, StandardOpenOption.WRITE));
			}
			catch (NoSuchFileException _)
			{
				TemporaryDirectories.deleteRecursively(entry);
				continue;
			}
			if (free.isEmpty())
				continue;
			try (ExclusiveFileLock _ = free.get())
			{
				TemporaryDirectories.deleteRecursively(entry);
			}
		}
	}

	/**
	 * Returns the harness process that a launcher folder's name identifies.
	 *
	 * @param name the folder's name, {@code <harness>-<pid>-<start in epoch milliseconds>}
	 * @return the process, or empty if the name has another form
	 */
	private static Optional<ProcessIdentity> launcherProcess(String name)
	{
		int startSeparator = name.lastIndexOf('-');
		if (startSeparator <= 0)
			return Optional.empty();
		int pidSeparator = name.lastIndexOf('-', startSeparator - 1);
		if (pidSeparator <= 0)
			return Optional.empty();
		try
		{
			long pid = Long.parseLong(name.substring(pidSeparator + 1, startSeparator));
			long start = Long.parseLong(name.substring(startSeparator + 1));
			if (pid <= 0)
				return Optional.empty();
			return Optional.of(new ProcessIdentity(pid, Instant.ofEpochMilli(start)));
		}
		catch (NumberFormatException _)
		{
			return Optional.empty();
		}
	}

	/**
	 * Deletes the request folders whose launcher's folder is gone, that this launcher's earlier run accepted, or whose
	 * acceptance record cannot be read.
	 *
	 * @throws IOException if the requests' folder cannot be read
	 */
	private void sweepRequests() throws IOException
	{
		String ownName = directory.getFileName().toString();
		for (Path entry : list(channel.requests()))
		{
			if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS))
			{
				Files.deleteIfExists(entry);
				continue;
			}
			boolean abandoned;
			try
			{
				String launcher = AcceptedRequest.read(json, entry.resolve(SessionLauncherChannel.ACCEPTED_FILE)).
					launcher();
				abandoned = launcher.equals(ownName) || launcherProcess(launcher).isEmpty() ||
					!Files.isDirectory(channel.launchers().resolve(launcher), LinkOption.NOFOLLOW_LINKS);
			}
			catch (IOException _)
			{
				abandoned = true;
			}
			if (abandoned)
				TemporaryDirectories.deleteRecursively(entry);
		}
	}

	/**
	 * Deletes the inbox files older than {@link SessionLauncherChannel#ABANDONED_MESSAGE_AGE} that no live holder
	 * names: the messages and partial files of senders that ended.
	 *
	 * @throws IOException if the inbox cannot be read
	 */
	private void sweepInbox() throws IOException
	{
		Path inbox = channel.inbox();
		if (!Files.isDirectory(inbox, LinkOption.NOFOLLOW_LINKS))
			return;
		Set<String> held = new HashSet<>();
		for (ProcessIdentity process : processes.all())
		{
			Optional<List<String>> arguments = processes.arguments(process);
			if (arguments.isPresent() && isHolder(arguments.get()))
				held.add(arguments.get().get(3) + "-" + arguments.get().get(4));
		}
		Instant oldest = clock.instant().minus(SessionLauncherChannel.ABANDONED_MESSAGE_AGE);
		for (Path entry : list(inbox))
		{
			String name = entry.getFileName().toString();
			Optional<MessageName> message = MessageName.parse(name);
			boolean partial = name.startsWith(SessionLauncherChannel.TEMPORARY_PREFIX);
			if (message.isEmpty() && !partial)
				continue;
			if (message.isPresent() && held.contains(message.get().id() + "-" + message.get().number()))
				continue;
			BasicFileAttributes attributes;
			try
			{
				attributes = Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
			}
			catch (NoSuchFileException _)
			{
				continue;
			}
			if (attributes.lastModifiedTime().toInstant().isBefore(oldest) && !attributes.isDirectory())
				Files.deleteIfExists(entry);
		}
	}

	/**
	 * Indicates whether a process's arguments are those of a holder.
	 *
	 * @param arguments the arguments
	 * @return {@code true} if they have the form of {@link SessionLauncherChannel#holderArguments}
	 */
	private static boolean isHolder(List<String> arguments)
	{
		return arguments.size() == HOLDER_ARGUMENTS && arguments.get(0).equals("-c") &&
			arguments.get(1).equals("read _") && arguments.get(2).equals("cat-request");
	}

	/**
	 * Stops the children of requests whose holder has ended before they were answered, and deletes the folders of
	 * requests that were answered and whose holder has ended.
	 *
	 * @throws IOException if a request's folder or inbox files cannot be deleted
	 */
	private void checkHolders() throws IOException
	{
		List<Served> finished = new ArrayList<>();
		for (Served request : served.values())
		{
			if (processes.isAlive(request.holder))
				continue;
			if (request.task.isDone())
				finished.add(request);
			else
				request.cancel();
		}
		if (finished.isEmpty())
			return;
		Set<String> ids = new HashSet<>();
		for (Served request : finished)
		{
			ids.add(request.id);
			served.remove(request.id);
			if (Files.exists(request.directory, LinkOption.NOFOLLOW_LINKS))
				TemporaryDirectories.deleteRecursively(request.directory);
		}
		deleteInboxFiles(ids);
	}

	/**
	 * Accepts the requests and turns in the inbox that this launcher can prove came from its harness's commands,
	 * in the order of their request identifier and turn number.
	 *
	 * @throws IOException if the inbox cannot be read, or the launcher's state cannot be written
	 */
	private void scanInbox() throws IOException
	{
		Path inbox = channel.inbox();
		if (!Files.isDirectory(inbox, LinkOption.NOFOLLOW_LINKS))
			return;
		List<Message> messages = new ArrayList<>();
		Set<InboxEntry> stillDeclined = new HashSet<>();
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(inbox))
		{
			for (Path entry : entries)
			{
				Optional<MessageName> name = MessageName.parse(entry.getFileName().toString());
				if (name.isEmpty())
					continue;
				BasicFileAttributes attributes;
				try
				{
					attributes = Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
				}
				catch (NoSuchFileException _)
				{
					continue;
				}
				InboxEntry key = new InboxEntry(entry.getFileName().toString(), attributes.lastModifiedTime(),
					attributes.size(), attributes.fileKey());
				if (declined.contains(key))
				{
					stillDeclined.add(key);
					continue;
				}
				if (!attributes.isRegularFile())
				{
					if (!deleteUnread(entries, entry, attributes))
						stillDeclined.add(key);
					continue;
				}
				messages.add(new Message(name.get(), entry, key));
			}
		}
		declined = stillDeclined;
		// Only the next turn of a request that this launcher serves, or a later one, can be accepted
		messages.removeIf(message -> message.number() != SessionLauncherChannel.REQUEST_MESSAGE &&
			(!served.containsKey(message.id()) || message.number() < served.get(message.id()).nextTurn));
		if (messages.isEmpty())
			return;
		messages.sort(Comparator.comparing(Message::id).thenComparingInt(Message::number));
		Map<String, List<Holder>> holders = holders();
		for (Message message : messages)
		{
			if (message.number() == SessionLauncherChannel.REQUEST_MESSAGE)
				acceptRequest(message, holders);
			else
				acceptTurn(message, holders);
		}
	}

	/**
	 * Deletes an inbox entry that is a link or not a regular file, without reading it or following it.
	 *
	 * @param inbox      the open inbox
	 * @param entry      the entry
	 * @param attributes the entry's attributes
	 * @return {@code true} if it was deleted; {@code false} if it was left
	 * @throws IOException if it cannot be deleted
	 */
	private static boolean deleteUnread(DirectoryStream<Path> inbox, Path entry, BasicFileAttributes attributes)
		throws IOException
	{
		if (!attributes.isDirectory())
		{
			Files.deleteIfExists(entry);
			return true;
		}
		if (!(inbox instanceof SecureDirectoryStream<Path> secure))
			return false;
		Path name = entry.getFileName();
		try (SecureDirectoryStream<Path> folder = secure.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS))
		{
			if (!deleteContents(folder, 1))
				return false;
		}
		secure.deleteDirectory(name);
		return true;
	}

	/**
	 * Deletes the contents of a folder without following links.
	 *
	 * @param folder the open folder
	 * @param depth  how deep below the inbox the folder lies
	 * @return {@code true} if every entry was deleted; {@code false} if the folder is too deep
	 * @throws IOException if an entry cannot be deleted
	 */
	private static boolean deleteContents(SecureDirectoryStream<Path> folder, int depth) throws IOException
	{
		if (depth > MAXIMUM_DELETED_DEPTH)
			return false;
		List<Path> names = new ArrayList<>();
		for (Path entry : folder)
			names.add(entry.getFileName());
		for (Path name : names)
		{
			BasicFileAttributes attributes = folder.getFileAttributeView(name, BasicFileAttributeView.class,
				LinkOption.NOFOLLOW_LINKS).readAttributes();
			if (!attributes.isDirectory())
			{
				folder.deleteFile(name);
				continue;
			}
			try (SecureDirectoryStream<Path> child = folder.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS))
			{
				if (!deleteContents(child, depth + 1))
					return false;
			}
			folder.deleteDirectory(name);
		}
		return true;
	}

	/**
	 * Accepts a request whose holder runs in the served harness's process tree and names its file's digest.
	 *
	 * @param message the request's inbox file
	 * @param holders the live holders that the served harness's commands started, by message
	 * @throws IOException if the launcher's state cannot be written
	 */
	private void acceptRequest(Message message, Map<String, List<Holder>> holders) throws IOException
	{
		if (served.containsKey(message.id()))
		{
			declined.add(message.key());
			return;
		}
		List<Holder> candidates = holders.getOrDefault(message.id() + "-" + message.number(), List.of());
		if (candidates.isEmpty())
		{
			declined.add(message.key());
			return;
		}
		Path staging = directory.resolve(STAGING_DIRECTORY).resolve(message.id());
		if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS))
			TemporaryDirectories.deleteRecursively(staging);
		Files.createDirectories(staging);
		Path archive = staging.resolve(ARCHIVE_FILE);
		Optional<Holder> holder = copyProven(message, archive, candidates);
		Path target = channel.requestDirectory(message.id());
		if (holder.isEmpty() || Files.exists(target, LinkOption.NOFOLLOW_LINKS))
		{
			TemporaryDirectories.deleteRecursively(staging);
			declined.add(message.key());
			return;
		}
		new AcceptedRequest(directory.getFileName().toString(), clock.instant()).write(json,
			staging.resolve(SessionLauncherChannel.ACCEPTED_FILE));
		try
		{
			Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (FileAlreadyExistsException _)
		{
			TemporaryDirectories.deleteRecursively(staging);
			declined.add(message.key());
			return;
		}
		Files.deleteIfExists(message.path());
		Served request = new Served(message.id(), target, holder.get().process());
		served.put(request.id, request);
		request.task = executor.submit(() ->
		{
			serveRequest(request);
			return null;
		});
	}

	/**
	 * Accepts the next turn of a streamed request whose holder runs in the served harness's process tree and names the
	 * turn file's digest. A later turn waits for the turns before it.
	 *
	 * @param message the turn's inbox file
	 * @param holders the live holders that the served harness's commands started, by message
	 * @throws IOException if the launcher's state cannot be written
	 */
	private void acceptTurn(Message message, Map<String, List<Holder>> holders) throws IOException
	{
		Served request = served.get(message.id());
		if (request == null || message.number() != request.nextTurn || request.inputEnded())
			return;
		List<Holder> candidates = holders.getOrDefault(message.id() + "-" + message.number(), List.of());
		if (candidates.isEmpty())
		{
			declined.add(message.key());
			return;
		}
		Path copy = request.directory.resolve(TURN_PREFIX + message.number() + ".json");
		Files.deleteIfExists(copy);
		if (copyProven(message, copy, candidates).isEmpty())
		{
			Files.deleteIfExists(copy);
			declined.add(message.key());
			return;
		}
		Files.writeString(request.directory.resolve(SessionLauncherChannel.TURNS_FILE), message.number() + "\n", UTF_8,
			StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		request.nextTurn += 1;
		Files.deleteIfExists(message.path());
		request.queue.add(new Turn(copy));
	}

	/**
	 * Copies a message into the launcher's state, if its private copy has the digest that one of its holders names.
	 *
	 * @param message    the message
	 * @param copy       the private copy, which must not exist
	 * @param candidates the message's holders in the served harness's tree
	 * @return the holder that names the copy's digest, or empty if none does or the message cannot be copied
	 * @throws IOException if the launcher's state cannot be written
	 */
	private static Optional<Holder> copyProven(Message message, Path copy, List<Holder> candidates)
		throws IOException
	{
		try
		{
			RequestArchive.copy(message.path(), copy, RequestLimits.UNBOUNDED);
		}
		catch (IOException _)
		{
			// The sender replaced or removed the file, or it is not a single regular file
			return Optional.empty();
		}
		String digest = SessionLauncherChannel.digest(copy);
		for (Holder candidate : candidates)
		{
			if (candidate.digest().equals(digest))
				return Optional.of(candidate);
		}
		return Optional.empty();
	}

	/**
	 * Returns the live holders in the served harness's process tree whose nearest harness with a launcher is the
	 * served harness.
	 *
	 * @return the holders, by the request identifier and message number that they name
	 * @throws IOException if the launchers' folder cannot be read
	 */
	private Map<String, List<Holder>> holders() throws IOException
	{
		Map<String, List<Holder>> holders = new LinkedHashMap<>();
		Set<ProcessIdentity> registered = registeredHarnesses();
		for (ProcessIdentity process : processes.descendants(harnessProcess))
		{
			Optional<List<String>> arguments = processes.arguments(process);
			if (arguments.isEmpty() || !isHolder(arguments.get()) || !servedBy(process, registered))
				continue;
			List<String> values = arguments.get();
			holders.computeIfAbsent(values.get(3) + "-" + values.get(4), _ -> new ArrayList<>()).
				add(new Holder(process, values.get(5)));
		}
		return holders;
	}

	/**
	 * Returns the harness processes that have a launcher folder.
	 *
	 * @return the processes that the folders' names identify
	 * @throws IOException if the launchers' folder cannot be read
	 */
	private Set<ProcessIdentity> registeredHarnesses() throws IOException
	{
		Set<ProcessIdentity> registered = new HashSet<>();
		for (Path entry : list(channel.launchers()))
			launcherProcess(entry.getFileName().toString()).ifPresent(registered::add);
		return registered;
	}

	/**
	 * Indicates whether the first harness with a launcher folder on a process's ancestry is the served harness.
	 *
	 * @param process    the process
	 * @param registered the harness processes that have a launcher folder
	 * @return {@code true} if the served harness is the first one
	 */
	private boolean servedBy(ProcessIdentity process, Set<ProcessIdentity> registered)
	{
		Optional<ProcessIdentity> ancestor = processes.parent(process);
		while (ancestor.isPresent())
		{
			if (ancestor.get().equals(harnessProcess))
				return true;
			if (registered.contains(ancestor.get()))
				return false;
			ancestor = processes.parent(ancestor.get());
		}
		return false;
	}

	/**
	 * Lists a folder.
	 *
	 * @param folder the folder
	 * @return its entries, or an empty list if it does not exist
	 * @throws IOException if it cannot be read
	 */
	private static List<Path> list(Path folder) throws IOException
	{
		List<Path> entries = new ArrayList<>();
		if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS))
			return entries;
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder))
		{
			for (Path entry : stream)
				entries.add(entry);
		}
		return entries;
	}

	/**
	 * Deletes the inbox files of some requests.
	 *
	 * @param ids the requests' identifiers
	 * @throws IOException if the inbox cannot be read, or a file cannot be deleted
	 */
	private void deleteInboxFiles(Set<String> ids) throws IOException
	{
		Path inbox = channel.inbox();
		for (Path entry : list(inbox))
		{
			Optional<MessageName> message = MessageName.parse(entry.getFileName().toString());
			if (message.isPresent() && ids.contains(message.get().id()))
				Files.deleteIfExists(entry);
		}
	}

	/**
	 * Ends the launcher: stops every child, waits for their tasks, and deletes the requests' folders, their inbox
	 * files and the launcher's folder.
	 *
	 * @throws IOException if the launcher's state cannot be deleted
	 */
	private void end() throws IOException
	{
		ended = true;
		for (Served request : served.values())
			request.cancel();
		for (Served request : served.values())
		{
			try
			{
				request.task.get();
			}
			catch (ExecutionException e)
			{
				log("The task of request " + request.id + " failed: " + e.getCause());
			}
			catch (InterruptedException _)
			{
				Thread.currentThread().interrupt();
				break;
			}
		}
		deleteInboxFiles(served.keySet());
		for (Served request : served.values())
		{
			if (Files.exists(request.directory, LinkOption.NOFOLLOW_LINKS))
				TemporaryDirectories.deleteRecursively(request.directory);
		}
		served.clear();
		try (ExclusiveFileLock _ = lock)
		{
			TemporaryDirectories.deleteRecursively(directory);
		}
	}

	/**
	 * Appends a line to the launcher's log.
	 *
	 * @param line the line
	 * @throws IOException if the log cannot be written
	 */
	private void log(String line) throws IOException
	{
		Files.writeString(directory.resolve(SessionLauncherChannel.LOG_FILE), clock.instant() + " " + line + "\n",
			UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
	}

	/**
	 * Serves an accepted request and publishes its response.
	 *
	 * @param request the request
	 * @throws IOException if the response cannot be published
	 */
	private void serveRequest(Served request) throws IOException
	{
		Response response;
		try
		{
			response = run(request);
		}
		catch (InterruptedException _)
		{
			Thread.currentThread().interrupt();
			response = request.answer(Status.STOPPED, "The session launcher stopped the session");
		}
		catch (IOException | RuntimeException e)
		{
			response = request.answer(Status.FAILED, "The session launcher failed to run the session: " + e);
		}
		response.write(json, request.directory.resolve(SessionLauncherChannel.RESPONSE_FILE));
	}

	/**
	 * Runs an accepted request's child.
	 *
	 * @param request the request
	 * @return its response
	 * @throws IOException          if the request's files cannot be read or written
	 * @throws InterruptedException if the thread is interrupted
	 */
	private Response run(Served request) throws IOException, InterruptedException
	{
		Path archive = request.directory.resolve(ARCHIVE_FILE);
		Path inputs = request.directory.resolve(INPUTS_DIRECTORY);
		Request choices;
		try
		{
			RequestArchive.unpack(archive, inputs, RequestLimits.UNBOUNDED);
			choices = RequestArchive.readSession(json, inputs);
		}
		catch (IOException e)
		{
			return request.answer(Status.REFUSED, e.getMessage());
		}
		finally
		{
			Files.deleteIfExists(archive);
		}
		request.harness = choices.harness();
		if (request.cancelled())
			return request.answer(Status.STOPPED, stopReason());
		if (!startRefusal.isEmpty())
			return request.answer(Status.REFUSED, startRefusal);

		Path runRoot = request.directory.resolve(SessionLauncherChannel.RUN_DIRECTORY);
		ChildPolicy child;
		try
		{
			ParentPolicy current = policy.read();
			current.requireTrusted(channel.stateRoot());
			ParentPolicy bound = snapshot.intersect(current);
			ChildNeeds childNeeds = needs.needs(choices.harness(), runRoot, choices.deniedReads(), bound).
				with(choices.extraPermissions());
			child = ChildPolicy.intersect(bound, childNeeds);
		}
		catch (PolicyRefusal e)
		{
			return request.answer(Status.REFUSED, e.messageFor(policy.harnessName()));
		}
		catch (MissingPermissionsException e)
		{
			String entries = "";
			if (!e.writes().isEmpty() || !e.hosts().isEmpty())
				entries = policy.permissionEntries(e.writes(), e.hosts());
			return request.answer(Status.REFUSED, e.messageFor(policy.harnessName(), entries));
		}
		catch (IOException e)
		{
			return request.answer(Status.REFUSED, unreadablePolicy(e));
		}
		Files.createDirectories(runRoot);
		Path policyBound = request.directory.resolve(PolicyBoundFile.FILE_NAME);
		PolicyBoundFile.write(json, policyBound, child);
		if (!choices.extraPermissions().isEmpty())
			choices.extraPermissions().write(json, request.directory.resolve(ExtraPermissions.FILE_NAME));

		List<String> arguments = RunnerArguments.of(choices, request.directory, runRoot, policyBound);
		try
		{
			request.process = starter.start(choices.harness(), arguments, request.directory);
		}
		catch (IOException | IllegalArgumentException e)
		{
			return request.answer(Status.FAILED, "The requested runner could not start: " + e.getMessage());
		}
		request.process.onExit().thenRun(() -> request.queue.add(new Exited()));
		boolean stopped = feed(request, choices.turns());
		if (stopped)
			return request.result(Status.STOPPED, stopReason(), Optional.empty(), choices.keepRun());
		return exitResponse(request, choices.keepRun());
	}

	/**
	 * Returns the reason for stopping a child.
	 *
	 * @return the reason
	 */
	private String stopReason()
	{
		if (ended)
			return "The session launcher stopped the session because the requesting session ended";
		return "The session launcher stopped the session because its sender ended";
	}

	/**
	 * Feeds a runner its records until it exits or is stopped.
	 *
	 * @param request the request
	 * @param turns   the request's records, or empty if its sender streams them as turn files
	 * @return {@code true} if the runner was stopped; {@code false} if it exited
	 * @throws IOException          if a record cannot be read
	 * @throws InterruptedException if the thread is interrupted
	 */
	private boolean feed(Served request, Optional<Path> turns) throws IOException, InterruptedException
	{
		if (turns.isPresent())
		{
			try (InputStream records = Files.newInputStream(turns.get()))
			{
				records.transferTo(request.process.getOutputStream());
				request.process.getOutputStream().flush();
			}
			catch (IOException _)
			{
				// The runner exited before it read every record; its exit status says why
			}
			closeInput(request);
		}
		while (true)
		{
			Event event = request.queue.take();
			switch (event)
			{
				case Turn turn -> write(request, turn.file());
				case Exited _ ->
				{
					return false;
				}
				case Cancelled _ ->
				{
					HarnessProcessTermination.terminate(request.process, request.harness);
					return true;
				}
			}
		}
	}

	/**
	 * Writes a streamed turn to a runner's standard input, closing it after the {@code complete} record.
	 *
	 * @param request the request
	 * @param file    the launcher's copy of the turn
	 * @throws IOException if the copy cannot be read
	 */
	private void write(Served request, Path file) throws IOException
	{
		if (request.inputEnded())
			return;
		byte[] record = Files.readAllBytes(file);
		boolean complete;
		try
		{
			complete = json.readTree(record).path("type").asString("").equals("complete");
		}
		catch (JacksonException _)
		{
			// The runner refuses a malformed record and exits
			complete = false;
		}
		try
		{
			request.process.getOutputStream().write(record);
			request.process.getOutputStream().flush();
		}
		catch (IOException _)
		{
			// The runner exited before it read the record; its exit status says why
			complete = true;
		}
		if (complete)
			closeInput(request);
	}

	/**
	 * Closes a runner's standard input.
	 *
	 * @param request the request
	 */
	private static void closeInput(Served request)
	{
		request.endInput();
		try
		{
			request.process.getOutputStream().close();
		}
		catch (IOException _)
		{
			// The runner already exited
		}
	}

	/**
	 * Returns the response for a runner that exited.
	 *
	 * @param request the request
	 * @param keepRun {@code true} if the request keeps the child's run
	 * @return the response
	 * @throws IOException if the runner's diagnostics cannot be read
	 */
	private Response exitResponse(Served request, boolean keepRun) throws IOException
	{
		int exitStatus = request.process.exitValue();
		Optional<Integer> code = Optional.of(exitStatus);
		String tail = stderrTail(request.directory.resolve(SessionLauncherChannel.RUNNER_STDERR_FILE));
		if (exitStatus == Status.COMPLETED.exitStatus())
		{
			if (keepRun && !Files.isRegularFile(request.directory.resolve(RunnerArguments.RECEIPT_FILE)))
			{
				return request.result(Status.FAILED, "The requested runner exited without writing its receipt", code,
					true);
			}
			return request.result(Status.COMPLETED, "", code, keepRun);
		}
		if (exitStatus == Status.CAPACITY.exitStatus())
			return request.result(Status.CAPACITY, "The requested runner found no capacity: " + tail, code, keepRun);
		if (exitStatus == Status.REFUSED.exitStatus())
			return request.result(Status.REFUSED, "The requested runner refused the session: " + tail, code, keepRun);
		return request.result(Status.FAILED, "The requested runner exited with status " + exitStatus + ": " + tail,
			code, keepRun);
	}

	/**
	 * Returns the end of a runner's standard error.
	 *
	 * @param file the runner's standard error
	 * @return its last {@value #STDERR_TAIL_BYTES} bytes, trimmed, or an empty string if it does not exist
	 * @throws IOException if it cannot be read
	 */
	private static String stderrTail(Path file) throws IOException
	{
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
			return "";
		try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))
		{
			long start = Math.max(0, channel.size() - STDERR_TAIL_BYTES);
			channel.position(start);
			return new String(Channels.newInputStream(channel).readAllBytes(), UTF_8).strip();
		}
	}

	/**
	 * A file in the inbox whose content and identity have not changed.
	 *
	 * @param name     the file's name
	 * @param modified its modification time
	 * @param size     its size
	 * @param fileKey  its file system identity, or null if the file system has none
	 */
	private record InboxEntry(String name, FileTime modified, long size, Object fileKey)
	{
	}

	/**
	 * A live holder of a message.
	 *
	 * @param process the holder
	 * @param digest  the digest that it names
	 */
	private record Holder(ProcessIdentity process, String digest)
	{
	}

	/**
	 * The name of a message in the inbox: a request or one of its turns.
	 *
	 * @param id     the request's identifier
	 * @param number {@link SessionLauncherChannel#REQUEST_MESSAGE} for the request, or the turn number
	 */
	private record MessageName(String id, int number)
	{
		private static final int ID_LENGTH = 32;

		/**
		 * Parses the name of an inbox file.
		 *
		 * @param name the name
		 * @return the message's name, or empty if the name is not that of a request or turn file
		 */
		static Optional<MessageName> parse(String name)
		{
			if (name.startsWith(SessionLauncherChannel.REQUEST_PREFIX))
			{
				int idEnd = SessionLauncherChannel.REQUEST_PREFIX.length() + ID_LENGTH;
				if (name.length() < idEnd)
					return Optional.empty();
				String id = name.substring(SessionLauncherChannel.REQUEST_PREFIX.length(), idEnd);
				if (!isId(id) || !SessionLauncherChannel.requestFileName(id).equals(name))
					return Optional.empty();
				return Optional.of(new MessageName(id, SessionLauncherChannel.REQUEST_MESSAGE));
			}
			if (!name.startsWith(TURN_PREFIX))
				return Optional.empty();
			int idEnd = TURN_PREFIX.length() + ID_LENGTH;
			int numberEnd = name.lastIndexOf('.');
			if (name.length() <= idEnd + 1 || name.charAt(idEnd) != '-' || numberEnd <= idEnd + 1)
				return Optional.empty();
			String id = name.substring(TURN_PREFIX.length(), idEnd);
			if (!isId(id))
				return Optional.empty();
			try
			{
				int number = Integer.parseInt(name.substring(idEnd + 1, numberEnd));
				if (number <= 0 || !SessionLauncherChannel.turnFileName(id, number).equals(name))
					return Optional.empty();
				return Optional.of(new MessageName(id, number));
			}
			catch (NumberFormatException _)
			{
				return Optional.empty();
			}
		}

		/**
		 * Indicates whether text is a request identifier.
		 *
		 * @param text the text
		 * @return {@code true} if it consists of lower-case hexadecimal digits
		 */
		private static boolean isId(String text)
		{
			for (int index = 0; index < text.length(); ++index)
			{
				char value = text.charAt(index);
				if ((value < '0' || value > '9') && (value < 'a' || value > 'f'))
					return false;
			}
			return true;
		}
	}

	/**
	 * A message file in the inbox.
	 *
	 * @param name its name
	 * @param path the file
	 * @param key  the file's identity
	 */
	private record Message(MessageName name, Path path, InboxEntry key)
	{
		/**
		 * Returns the request's identifier.
		 *
		 * @return the identifier
		 */
		String id()
		{
			return name.id();
		}

		/**
		 * Returns the message number.
		 *
		 * @return {@link SessionLauncherChannel#REQUEST_MESSAGE} for the request, or the turn number
		 */
		int number()
		{
			return name.number();
		}
	}

	/**
	 * What a request's task waits for.
	 */
	private sealed interface Event permits Turn, Exited, Cancelled
	{
	}

	/**
	 * A streamed turn to write to the runner.
	 *
	 * @param file the launcher's copy of the turn
	 */
	private record Turn(Path file) implements Event
	{
	}

	/**
	 * The runner exited.
	 */
	private record Exited() implements Event
	{
	}

	/**
	 * The request's sender or the launcher ended, so the runner must stop.
	 */
	private record Cancelled() implements Event
	{
	}

	/**
	 * An accepted request. The poll thread owns its {@code task} and {@code nextTurn}; its task owns
	 * {@code harness} and {@code process}.
	 */
	private static final class Served
	{
		private final String id;
		private final Path directory;
		private final ProcessIdentity holder;
		private final BlockingQueue<Event> queue = new LinkedBlockingQueue<>();
		private Future<?> task;
		private int nextTurn = 1;
		private String harness = "";
		private Process process;
		private boolean cancelled;
		private boolean inputEnded;

		/**
		 * Creates a request.
		 *
		 * @param id        its identifier
		 * @param directory its folder
		 * @param holder    the holder of its request file
		 */
		Served(String id, Path directory, ProcessIdentity holder)
		{
			this.id = id;
			this.directory = directory;
			this.holder = holder;
		}

		/**
		 * Asks the request's task to stop its runner, once.
		 */
		void cancel()
		{
			synchronized (this)
			{
				if (cancelled)
					return;
				cancelled = true;
			}
			queue.add(new Cancelled());
		}

		/**
		 * Indicates whether the request was cancelled.
		 *
		 * @return {@code true} if it was
		 */
		boolean cancelled()
		{
			synchronized (this)
			{
				return cancelled;
			}
		}

		/**
		 * Records that the runner's standard input is closed.
		 */
		void endInput()
		{
			synchronized (this)
			{
				inputEnded = true;
			}
		}

		/**
		 * Indicates whether the runner's standard input is closed.
		 *
		 * @return {@code true} if it is
		 */
		boolean inputEnded()
		{
			synchronized (this)
			{
				return inputEnded;
			}
		}

		/**
		 * Returns the response of a request that ran no runner.
		 *
		 * @param status how it ended
		 * @param reason why
		 * @return the response
		 */
		Response answer(Status status, String reason)
		{
			return new Response(id, harness, status, reason, Optional.empty(), Optional.empty(), Optional.empty(),
				Optional.empty(), Optional.empty(), Optional.empty());
		}

		/**
		 * Returns the response of a request whose runner ended.
		 *
		 * @param status     how it ended
		 * @param reason     why it did not complete, or an empty string
		 * @param exitStatus the runner's exit status, or empty if it was stopped
		 * @param keepRun    {@code true} if the request keeps the child's run
		 * @return the response, naming the files that the runner left
		 * @throws IOException if the run folder cannot be read
		 */
		Response result(Status status, String reason, Optional<Integer> exitStatus, boolean keepRun)
			throws IOException
		{
			Optional<Path> transcript = existing(directory.resolve(SessionLauncherChannel.TRANSCRIPT_FILE));
			Optional<Path> receipt = existing(directory.resolve(RunnerArguments.RECEIPT_FILE));
			Path runRoot = directory.resolve(SessionLauncherChannel.RUN_DIRECTORY);
			Optional<Path> childRun = list(runRoot).stream().
				filter(entry -> Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)).sorted().findFirst();
			Optional<String> childRunId = childRun.map(entry -> entry.getFileName().toString());
			Optional<Path> sessionTranscripts = childRun.flatMap(entry ->
				existing(entry.resolve("artifacts").resolve("session-transcripts")));
			Optional<Path> run = Optional.empty();
			if (keepRun)
				run = childRun;
			return new Response(id, harness, status, reason, exitStatus, childRunId, transcript, receipt,
				sessionTranscripts, run);
		}

		/**
		 * Returns a path if it exists.
		 *
		 * @param path the path
		 * @return {@code path}, or empty if it does not exist
		 */
		private static Optional<Path> existing(Path path)
		{
			if (Files.exists(path, LinkOption.NOFOLLOW_LINKS))
				return Optional.of(path);
			return Optional.empty();
		}
	}
}
