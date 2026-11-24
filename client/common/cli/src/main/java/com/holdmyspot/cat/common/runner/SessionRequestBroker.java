package com.holdmyspot.cat.common.runner;

import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.ExclusiveFileLock;
import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.SessionDirectory;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.workspace.GitWorktreeRegistrar;
import com.holdmyspot.cat.common.runner.RequestArchive.LaunchRequest;
import com.holdmyspot.cat.common.runner.launcher.RunnerArguments;
import com.holdmyspot.cat.common.runner.policy.ExtraPermissions;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Validates session requests, snapshots their inputs before starting children, and publishes retained evidence.
 * A nested session whose options allow no session request gets each request refused with
 * {@link SessionRequestAccess#NO_REQUESTS_REASON}, and one that has accepted all that its options allow gets each
 * further request refused with {@link SessionRequestAccess#limitReason(int)}. A nested session counts the requests that
 * it accepted over its whole life, across every runner that resumed it. A request that resumes a session that the
 * nested session requested continues that session: it is not a new request, so the nested session's limits neither
 * refuse nor count it.
 * <p>
 * A request whose records follow it, as {@link StreamedRequest} publishes them, gets each record on its child's
 * standard input as it arrives, and its child is stopped once the request's sender ends. Closing the broker stops
 * child process trees and answers unprocessed requests as stopped.
 * <p>
 * A worktree request, whose archive's name starts with {@link WorktreeRequest#PREFIX}, is not a requested session: it
 * takes no slot and no count of the nested session's limits, and the broker carries it out on a thread of its own, one
 * request at a time, so that it never delays a launch.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class SessionRequestBroker implements Closeable
{
	/**
	 * The response status for a session that reached a ready checkpoint and released its runner.
	 */
	static final String SUSPENDED = "suspended";

	/**
	 * The maximum bytes the broker reads from a requested runner's stderr.
	 */
	private static final int MAXIMUM_RUNNER_DIAGNOSTIC_BYTES = 65_536;
	/**
	 * The size of the largest receipt whose authentication record a response copies.
	 */
	private static final int MAXIMUM_RECEIPT_BYTES = 1_048_576;
	/** The directory in a response that holds the request's extracted inputs. */
	static final String INPUTS = "inputs";
	/**
	 * The directory of a response directory that holds the results copy of the requested session, which the requester
	 * reads.
	 */
	static final String RESULTS_COPY = "results-copy";
	/**
	 * The response's field that holds the path of the {@link #RESULTS_COPY}, present only when the copy exists.
	 */
	static final String RESULTS_COPY_FIELD = "results_copy";
	/**
	 * The response's field that holds the identifier that the harness gave the requested session, or an empty string if
	 * the harness reported none.
	 */
	static final String SESSION_ID = "session_id";
	/**
	 * The record in a slot that identifies the request that first took it. The broker writes it before starting
	 * the runner, and it stays until the slot is emptied.
	 */
	static final String ORIGINAL_REQUEST_FILE = "launch-request.json";
	/**
	 * Prefix of the files that fence admission of an original trial launch, in the site's private responses root.
	 * Each fence survives removal of the request's response directory and stays for the lifetime of the site.
	 */
	private static final String TRIAL_ADMISSION_FENCE = ".retained-trial-fence-";
	/**
	 * The directory of a response directory that holds the copy of a requested session's project and initial project
	 * as of the end of its last completed turn, for a request that asks for turn copies. The requester may still be
	 * reading it when the session ends, so it stays with the response, like {@link #RESULTS_COPY}, until the response
	 * directory is removed.
	 */
	static final String TURN_COPY = "turn-copy";
	/**
	 * The status of a request whose harness could not continue the session that the request resumes. The session's nested
	 * session stays retained.
	 */
	static final String UNRESUMABLE = "unresumable";
	/**
	 * The reason of a request whose requesting session ended before the request was answered.
	 */
	private static final String ENDED = "ended";

	/**
	 * Returns the read denials that keep a requested session from reading the results folder that holds its requesting
	 * nested session, except for the CAT plugin installed in the requesting session's home, whose launchers the
	 * request's prompt may name. The denials let the session read and run the plugin's files; they let it write
	 * nothing. When the plugin lies inside the results folder, the denials name every file and directory in the folder
	 * that exists when this method returns, other than the plugin, its contents and the directories that lead to it; a
	 * file or directory that is created later beside one of those directories is not denied.
	 *
	 * @param results   the results folder
	 * @param catPlugin the directory of the CAT plugin that the requesting session's commands run, or {@code null} if
	 *                  the requesting session has none
	 * @return the paths to deny, none of which contains {@code catPlugin}
	 * @throws NullPointerException if {@code results} is null
	 * @throws IOException          if a directory of the results folder cannot be listed
	 */
	public static List<Path> resultsDenials(Path results, Path catPlugin) throws IOException
	{
		requireThat(results, "results").isNotNull();
		Path directory = results.toAbsolutePath().normalize();
		if (catPlugin == null)
			return List.of(directory);
		List<Path> readable = List.of(catPlugin.toAbsolutePath().normalize());
		if (!NestedSessionManager.containsAny(directory, readable))
			return List.of(directory);
		return NestedSessionManager.deniedReads(directory, readable);
	}

	private final Site site;
	private final JsonMapper json = JsonMapper.builder().build();
	private final ExecutorService children = Executors.newVirtualThreadPerTaskExecutor();
	/**
	 * Carries out the worktree requests one at a time, since Git's operations on a repository's worktrees must not
	 * overlap.
	 */
	private final ExecutorService worktrees = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
	/**
	 * Live child processes, guarded with all other broker state by this instance's monitor.
	 */
	private final Map<String, Process> processes = new HashMap<>();
	private final Map<String, String> harnesses = new HashMap<>();
	/**
	 * Original request IDs by accepted request ID, guarded by this instance's monitor until slot cleanup ends.
	 */
	private final Map<String, String> activeLaunches = new HashMap<>();
	/**
	 * The results folder of each request that holds a slot, which holds its child's nested session below the child's
	 * harness.
	 */
	private final Map<String, Path> childRoots = new HashMap<>();
	/**
	 * The slot of each request whose child sessions in it, by request identifier, until the child has ended.
	 */
	private final Map<String, SessionSlots.Slot> slots = new HashMap<>();
	private final Set<String> cancelled = new HashSet<>();
	/**
	 * The records of each accepted request whose records follow it, by request identifier, until its child ends.
	 */
	private final Map<String, Streamed> streams = new HashMap<>();
	/**
	 * The response directory of each accepted request whose records came with it and whose launcher holds the
	 * request's lock while it runs, by request identifier, until its child ends.
	 */
	private final Map<String, Path> held = new HashMap<>();
	private Closeable monitor;
	private IOException failure;
	private int accepted;
	private boolean stopped;

	/**
	 * Creates a broker for an already prepared nested session whose session has no CAT plugin that its requested sessions
	 * read. The runner creates its access directories before sandbox setup.
	 *
	 * @param nestedSession the requesting nested session
	 * @param manager       records the nested session's lifecycle in its manifest, including how many requests the broker
	 *                      has accepted; the broker keeps it
	 * @param options       the requesting runner's options
	 * @param starter       resolves and starts child runners
	 */
	public SessionRequestBroker(NestedSession nestedSession, NestedSessionManager manager, HarnessRunnerOptions options,
		RequestedSessionStarter starter)
	{
		this(nestedSession, manager, options, null, starter);
	}

	/**
	 * Creates a broker for an already prepared nested session whose requested sessions may read the CAT plugin that the
	 * nested session's commands run. The runner creates its access directories before sandbox setup.
	 *
	 * @param nestedSession the requesting nested session
	 * @param manager       records the nested session's lifecycle in its manifest, including how many requests the broker
	 *                      has accepted; the broker keeps it
	 * @param options       the requesting runner's options
	 * @param catPlugin     the directory of the CAT plugin installed in the nested session's harness session's home,
	 *                      whose launchers the session's commands run, or {@code null} if the session has none
	 * @param starter       resolves and starts child runners
	 */
	public SessionRequestBroker(NestedSession nestedSession, NestedSessionManager manager, HarnessRunnerOptions options,
		Path catPlugin, RequestedSessionStarter starter)
	{
		this(nestedSession, manager, options, catPlugin, starter, RequestLimits.IN_NESTED_SESSION,
			new ProcessHandleLiveness());
	}

	/**
	 * Creates a broker for an already prepared nested session whose request archives must stay within the given bounds.
	 *
	 * @param nestedSession the requesting nested session
	 * @param manager       records the nested session's lifecycle in its manifest, including how many requests the broker
	 *                      has accepted; the broker keeps it
	 * @param options       the requesting runner's options
	 * @param starter       resolves and starts child runners
	 * @param limits        the bounds that each request's archive must stay within
	 */
	public SessionRequestBroker(NestedSession nestedSession, NestedSessionManager manager, HarnessRunnerOptions options,
		RequestedSessionStarter starter, RequestLimits limits)
	{
		this(nestedSession, manager, options, starter, limits, new ProcessHandleLiveness());
	}

	/**
	 * Creates a broker for an already prepared nested session whose request archives must stay within the given bounds.
	 * Its children run in as many slots, in the nested session's directory, as the nested session's options allow to run
	 * at once.
	 *
	 * @param nestedSession the requesting nested session
	 * @param manager       records the nested session's lifecycle in its manifest, including how many requests the broker
	 *                      has accepted; the broker keeps it
	 * @param options       the requesting runner's options
	 * @param starter       resolves and starts child runners
	 * @param limits        the bounds that each request's archive must stay within
	 * @param liveness      checks and stops the processes that a slot's previous session left
	 */
	public SessionRequestBroker(NestedSession nestedSession, NestedSessionManager manager, HarnessRunnerOptions options,
		RequestedSessionStarter starter, RequestLimits limits, HarnessProcessLiveness liveness)
	{
		this(nestedSession, manager, options, null, starter, limits, liveness);
	}

	/**
	 * Creates a broker for an already prepared nested session whose request archives must stay within the given bounds.
	 * Its children run in as many slots, in the nested session's directory, as the nested session's options allow to run
	 * at once.
	 *
	 * @param nestedSession the requesting nested session
	 * @param manager       records the nested session's lifecycle in its manifest, including how many requests the broker
	 *                      has accepted; the broker keeps it
	 * @param options       the requesting runner's options
	 * @param catPlugin     the directory of the CAT plugin that the nested session's commands run, which the nested
	 *                      session's requested sessions may read, or {@code null} if the nested session has none
	 * @param starter       resolves and starts child runners
	 * @param limits        the bounds that each request's archive must stay within
	 * @param liveness      checks and stops the processes that a slot's previous session left
	 */
	private SessionRequestBroker(NestedSession nestedSession, NestedSessionManager manager, HarnessRunnerOptions options,
		Path catPlugin, RequestedSessionStarter starter, RequestLimits limits, HarnessProcessLiveness liveness)
	{
		this(new NestedSessionSite(nestedSession, manager, options, catPlugin, limits, new SessionSlots(
			SessionRequestAccess.requestedSlotsDirectory(nestedSession), options.maximumConcurrency(), liveness,
			manager.fileLocks()), starter));
	}

	/**
	 * Creates a broker that serves the requests of a site.
	 *
	 * @param site    where the requests arrive and what their children receive
	 * @throws NullPointerException if any of the arguments are null
	 */
	SessionRequestBroker(Site site)
	{
		requireThat(site, "site").isNotNull();
		this.site = site;
	}

	/**
	 * Starts paced discovery using the session's ticker, after stating the nested session's limits in its
	 * {@link SessionRequestAccess#LIMITS_FILE}. The broker continues the count of the requests that the nested session
	 * accepted before this broker started. Any discovery failure is reported by {@link #close()}.
	 *
	 * @param ticker the injected scan schedule
	 * @throws IOException if the nested session's count of requests cannot be read, or its limits cannot be written
	 */
	public void start(HeartbeatTicker ticker) throws IOException
	{
		synchronized (this)
		{
			requireThat(ticker, "ticker").isNotNull();
			if (monitor != null || stopped)
				throw new IllegalStateException("The session request broker has already started or stopped");
			accepted = site.acceptedBefore();
			site.accepted(json, accepted);
			monitor = ticker.start(() ->
			{
				// A failure that scan() hands to this thread must be kept for close() before close() can run
				synchronized (this)
				{
					try
					{
						scan();
					}
					catch (IOException e)
					{
						recordFailure(e);
					}
				}
			});
		}
	}

	/**
	 * Discovers new non-temporary request archives once. Invalid requests receive a refusal without starting a child.
	 * A request whose archive another handle still holds open on Windows gets no response and is taken by a later scan.
	 *
	 * @throws IOException if discovery or response publication fails, or if the broker failed while it served an
	 *                     earlier request, such as when it could not write that request's answer. Each such failure is
	 *                     thrown once, either here or by {@link #close()}.
	 */
	public void scan() throws IOException
	{
		synchronized (this)
		{
			if (failure != null)
			{
				// A caller that closes the broker in a try-with-resources statement would otherwise receive the same
				// exception from close(), and adding an exception to itself as suppressed fails with an
				// IllegalArgumentException that replaces the failure
				IOException reported = failure;
				failure = null;
				throw reported;
			}
			Path requests = site.requests();
			List<String> pending = RequestArchive.pendingIds(requests, "");
			// A recovery request can fence a dead sender's archive before this scan would admit its launch.
			pending.sort(Comparator.comparing(id -> !id.startsWith(RetainedTrialRequest.PREFIX)));
			// The requests are listed before the ended launchers are stopped, so that a launch that resumes or discards
			// the session of a launcher that ended before the launch was made finds that session being stopped, and
			// waits for it instead of being refused as if another launch held it
			serveStreams(requests);
			for (String id : pending)
			{
				Path file = requests.resolve(id + RequestArchive.EXTENSION);
				Path directory = site.responses().resolve(id);
				if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS))
					continue;
				Files.createDirectory(directory);
				boolean busy = false;
				try
				{
					busy = !take(id, file, directory);
				}
				finally
				{
					if (!busy)
						site.taken(file);
				}
			}
			// The requests that this scan took receive their first records without waiting for the next scan
			serveStreams(requests);
		}
	}

	/**
	 * Answers or starts one request whose response directory this scan created. When another handle to the request's
	 * archive is open, the program that wrote it may not have closed it yet, so the request is not answered, counted or
	 * released, and its response directory is removed so that a later scan takes it.
	 *
	 * @param id        the request ID
	 * @param file      the request archive
	 * @param directory the request's response directory
	 * @return {@code true} if the request was answered or started, or {@code false} if its archive is busy
	 * @throws IOException if a response cannot be published
	 */
	private boolean take(String id, Path file, Path directory) throws IOException
	{
		if (id.startsWith(WorktreeRequest.PREFIX))
			return takeWorktree(id, file, directory);
		if (id.startsWith(DiscardRequest.PREFIX))
			return takeDiscard(id, file, directory);
		if (id.startsWith(RetainedTrialRequest.PREFIX))
			return takeRetainedTrial(id, file, directory);
		if (id.startsWith(HookComparisonRequest.PREFIX))
			return takeHookComparison(id, file, directory);
		if (stopped)
		{
			respond(id, "", site.closedStatus(), site.closedReason(), "", directory);
			return true;
		}
		if (!Files.notExists(trialAdmissionFence(id), LinkOption.NOFOLLOW_LINKS))
		{
			respond(id, "", "stopped", "Recovery settled this original trial launch", "", directory);
			return true;
		}
		try
		{
			LaunchRequest request = read(file, directory);
			// A guarded resume has its own envelope, separate from the original launch's admission fence.
			if (request.retainedTrialClaim().isPresent() && !StreamedRequest.senderRuns(site.requests(), id))
			{
				respond(id, request.harness(), "stopped", "The launcher that requested the guarded resume ended before " +
					"admission", "", directory);
				return true;
			}
			// A request that resumes a session continues the session that an earlier request started
			if (request.resume().isEmpty())
			{
				Optional<String> refusal = site.refusal(accepted);
				if (refusal.isPresent())
				{
					respond(id, "", "refused", refusal.get(), "", directory);
					return true;
				}
				accepted += 1;
				site.accepted(json, accepted);
			}
			harnesses.put(id, request.harness());
			List<String> arguments = arguments(request, directory);
			if (request.streamed())
				streams.put(id, new Streamed(directory));
			else if (Files.isRegularFile(site.requests().resolve(StreamedRequest.lockFileName(id)),
				LinkOption.NOFOLLOW_LINKS))
			{
				// A launcher holds the lock of its request until it ends. A request without one, such as one that
				// request-session makes, may outlive the program that made it.
				held.put(id, directory);
			}
			submitLaunch(id, request, arguments, directory);
		}
		catch (RequestArchiveBusyException _)
		{
			Files.delete(directory);
			return false;
		}
		catch (IOException | IllegalArgumentException | JacksonException e)
		{
			respond(id, "", "refused", e.getMessage(), "", directory);
		}
		return true;
	}

	/**
	 * Tracks accepted launches while their worker can still take, start or release a slot. The admitting thread
	 * holds this instance's monitor. A guarded resume runs under a new envelope for the original request's slot.
	 *
	 * @param id        the accepted request ID
	 * @param request   the captured launch
	 * @param arguments the child runner options
	 * @param directory the request's response directory
	 */
	private void submitLaunch(String id, LaunchRequest request, List<String> arguments, Path directory)
	{
		String original = request.retainedTrialClaim().map(RetainedTrialClaim::requestId).map(UUID::toString).orElse(id);
		activeLaunches.put(id, original);
		children.submit(() ->
		{
			try
			{
				execute(id, request, arguments, directory);
			}
			finally
			{
				synchronized (this)
				{
					activeLaunches.remove(id);
				}
			}
		});
	}

	/**
	 * Takes a hook comparison for a site that has an authenticated native comparison service.
	 *
	 * @param id the request identity
	 * @param file the published archive
	 * @param directory the broker-owned response directory
	 * @return whether the broker took the request, false if the sender still holds the archive open
	 * @throws IOException if the response cannot be published
	 */
	private boolean takeHookComparison(String id, Path file, Path directory) throws IOException
	{
		Optional<HookComparisonService> service = site.hookComparison();
		if (stopped)
		{
			WorktreeResponse.failed(site.closedReason()).write(json, id, directory);
			return true;
		}
		if (service.isEmpty())
		{
			WorktreeResponse.refused("This request server cannot compare Codex plugin hooks").write(json, id,
				directory);
			return true;
		}
		Path inputs = directory.resolve(INPUTS);
		HookComparisonRequest request;
		try
		{
			unpack(file, directory, inputs);
			request = HookComparisonRequest.read(json, inputs);
		}
		catch (RequestArchiveBusyException _)
		{
			Files.delete(directory);
			return false;
		}
		catch (IOException | IllegalArgumentException | JacksonException e)
		{
			TemporaryDirectories.deleteRecursively(inputs);
			WorktreeResponse.refused(String.valueOf(e.getMessage())).write(json, id, directory);
			return true;
		}
		children.submit(() -> serveHookComparison(id, directory, inputs, request, service.orElseThrow()));
		return true;
	}

	/**
	 * Compares the captured inputs and cleans them up before publishing an answer.
	 *
	 * @param id the request identity
	 * @param directory the broker-owned response directory
	 * @param inputs the extracted request archive
	 * @param request the validated workspace and plugins
	 * @param service the broker's authenticated native runner service
	 */
	private void serveHookComparison(String id, Path directory, Path inputs, HookComparisonRequest request,
		HookComparisonService service)
	{
		try
		{
			HookComparisonResult result;
			try
			{
				result = service.compare(request, directory);
			}
			finally
			{
				TemporaryDirectories.deleteRecursively(inputs);
			}
			result.write(json, id, directory);
		}
		catch (InterruptedException _)
		{
			writeComparisonFailure(id, directory, "The request server stopped the hook comparison");
			Thread.currentThread().interrupt();
		}
		catch (IOException | IllegalArgumentException e)
		{
			writeComparisonFailure(id, directory, String.valueOf(e.getMessage()));
		}
	}

	/**
	 * Reports a comparison failure without publishing an incomplete result.
	 *
	 * @param id the request identity
	 * @param directory the broker-owned response directory
	 * @param reason the failure message
	 */
	private void writeComparisonFailure(String id, Path directory, String reason)
	{
		try
		{
			WorktreeResponse.failed(reason).write(json, id, directory);
		}
		catch (IOException e)
		{
			synchronized (this)
			{
				recordFailure(e);
			}
		}
	}

	/**
	 * Answers or submits one worktree request whose response directory this scan created. A request whose archive is
	 * busy is left for a later scan, as a launch is.
	 *
	 * @param id        the request ID
	 * @param file      the request archive
	 * @param directory the request's response directory
	 * @return {@code true} if the request was answered or submitted, or {@code false} if its archive is busy
	 * @throws IOException if a response cannot be published
	 */
	private boolean takeWorktree(String id, Path file, Path directory) throws IOException
	{
		if (stopped)
		{
			WorktreeResponse.failed(site.closedReason()).write(json, id, directory);
			return true;
		}
		WorktreeRequest request;
		Path inputs = directory.resolve(INPUTS);
		try
		{
			unpack(file, directory, inputs);
			request = WorktreeRequest.read(json, inputs);
		}
		catch (RequestArchiveBusyException _)
		{
			Files.delete(directory);
			return false;
		}
		catch (IOException | IllegalArgumentException | JacksonException e)
		{
			WorktreeResponse.refused(String.valueOf(e.getMessage())).write(json, id, directory);
			return true;
		}
		finally
		{
			if (Files.exists(inputs, LinkOption.NOFOLLOW_LINKS))
				TemporaryDirectories.deleteRecursively(inputs);
		}
		worktrees.submit(() -> serveWorktree(id, request, directory));
		return true;
	}

	/**
	 * Takes a discard request, which takes no slot, no permit and no count of the requests that the site allows: the
	 * broker carries it out on its worktree thread, so that the wait for the nested session's processes to stop never
	 * delays a launch.
	 *
	 * @param id        the request ID
	 * @param file      the request archive
	 * @param directory the request's response directory
	 * @return {@code true} if the request was answered or started, or {@code false} if its archive is busy
	 * @throws IOException if a response cannot be published
	 */
	private boolean takeDiscard(String id, Path file, Path directory) throws IOException
	{
		if (stopped)
		{
			WorktreeResponse.failed(site.closedReason()).write(json, id, directory);
			return true;
		}
		DiscardRequest request;
		Path inputs = directory.resolve(INPUTS);
		try
		{
			unpack(file, directory, inputs);
			request = DiscardRequest.read(json, inputs);
		}
		catch (RequestArchiveBusyException _)
		{
			Files.delete(directory);
			return false;
		}
		catch (IOException | IllegalArgumentException | JacksonException e)
		{
			WorktreeResponse.refused(String.valueOf(e.getMessage())).write(json, id, directory);
			return true;
		}
		finally
		{
			if (Files.exists(inputs, LinkOption.NOFOLLOW_LINKS))
				TemporaryDirectories.deleteRecursively(inputs);
		}
		worktrees.submit(() -> serveDiscard(id, request, directory));
		return true;
	}

	/**
	 * Carries out a discard request on the broker's worktree thread and publishes its response.
	 *
	 * @param id        the request ID
	 * @param request   the request
	 * @param directory the request's response directory
	 */
	private void serveDiscard(String id, DiscardRequest request, Path directory)
	{
		try
		{
			WorktreeResponse response;
			try
			{
				String result = DiscardRequest.ABSENT;
				if (site.slots().discard(request.sessionId()))
					result = DiscardRequest.DISCARDED;
				response = new WorktreeResponse(WorktreeRequest.DONE, "", Optional.of(result));
			}
			catch (SessionRefusedException e)
			{
				response = WorktreeResponse.refused(String.valueOf(e.getMessage()));
			}
			catch (IOException e)
			{
				response = WorktreeResponse.failed(String.valueOf(e.getMessage()));
			}
			catch (InterruptedException _)
			{
				Thread.currentThread().interrupt();
				response = WorktreeResponse.failed(site.closedReason());
			}
			response.write(json, id, directory);
		}
		catch (IOException e)
		{
			synchronized (this)
			{
				recordFailure(e);
			}
		}
	}

	/**
	 * Takes a retained-trial request under the admission monitor. When the original sender and any accepted worker
	 * have ended, creates the admission fence before the durable slot operation can report absence or discard.
	 *
	 * @param id        the recovery request's envelope ID
	 * @param file      the recovery request's archive
	 * @param directory the recovery request's response directory
	 * @return {@code true} if the request was answered or submitted, or {@code false} if its archive is busy
	 * @throws IOException if a response cannot be published
	 */
	private boolean takeRetainedTrial(String id, Path file, Path directory) throws IOException
	{
		if (stopped)
		{
			WorktreeResponse.failed(site.closedReason()).write(json, id, directory);
			return true;
		}
		RetainedTrialRequest request;
		Path inputs = directory.resolve(INPUTS);
		try
		{
			unpack(file, directory, inputs);
			request = RetainedTrialRequest.read(json, inputs);
		}
		catch (RequestArchiveBusyException _)
		{
			Files.delete(directory);
			return false;
		}
		catch (IOException | IllegalArgumentException | JacksonException e)
		{
			WorktreeResponse.refused(String.valueOf(e.getMessage())).write(json, id, directory);
			return true;
		}
		finally
		{
			if (Files.exists(inputs, LinkOption.NOFOLLOW_LINKS))
				TemporaryDirectories.deleteRecursively(inputs);
		}
		settleTrial(id, request, directory);
		return true;
	}

	/**
	 * Checks whether the original launch can still start, and otherwise fences it before submitting the slot
	 * operation. The admitting thread holds this instance's monitor.
	 *
	 * @param id        the recovery request's envelope ID
	 * @param request   the recovery operation and original request ID
	 * @param directory the recovery request's response directory
	 * @throws IOException if a response cannot be published
	 */
	private void settleTrial(String id, RetainedTrialRequest request, Path directory) throws IOException
	{
		String original = request.requestId().toString();
		boolean busy;
		try
		{
			busy = activeLaunches.containsValue(original) || StreamedRequest.senderRuns(site.requests(), original);
			if (!busy)
			{
				try
				{
					Files.createFile(trialAdmissionFence(original));
				}
				catch (FileAlreadyExistsException _)
				{
					// A previous recovery request already fenced the launch.
				}
			}
		}
		catch (IOException e)
		{
			WorktreeResponse.failed(String.valueOf(e.getMessage())).write(json, id, directory);
			return;
		}
		if (busy)
			busyTrial(request.requestId()).write(json, id, directory);
		else
			worktrees.submit(() -> serveRetainedTrial(id, request, directory));
	}

	/**
	 * Inspects or discards the bound slot on the broker's control thread, outside the admission monitor. A guarded
	 * resume that the broker accepted during inspection makes the observation busy until that worker settles.
	 *
	 * @param id        the recovery request's envelope ID
	 * @param request   the recovery operation and original request ID
	 * @param directory the recovery request's response directory
	 */
	private void serveRetainedTrial(String id, RetainedTrialRequest request, Path directory)
	{
		try
		{
			RetainedTrialResponse response;
			try
			{
				response = switch (request.operation())
				{
					case STATUS -> site.slots().inspectTrial(request.requestId());
					case DISCARD -> site.slots().discardTrial(request.requestId(), request.identity());
				};
			}
			catch (IOException e)
			{
				WorktreeResponse.failed(String.valueOf(e.getMessage())).write(json, id, directory);
				return;
			}
			synchronized (this)
			{
				if (activeLaunches.containsValue(request.requestId().toString()))
					response = busyTrial(request.requestId());
				response.write(json, id, directory);
			}
		}
		catch (IOException e)
		{
			synchronized (this)
			{
				recordFailure(e);
			}
		}
	}

	/**
	 * Names the durable admission fence separately from the original request's response directory.
	 *
	 * @param original the original request ID
	 * @return the path of the fence
	 */
	private Path trialAdmissionFence(String original)
	{
		return site.responses().resolve(TRIAL_ADMISSION_FENCE + original);
	}

	/**
	 * Creates a busy response for an original trial launch.
	 *
	 * @param original the original request ID
	 * @return the response
	 */
	private static RetainedTrialResponse busyTrial(UUID original)
	{
		return new RetainedTrialResponse(original, RetainedTrialResponse.Status.BUSY, Optional.empty(),
			OptionalInt.empty());
	}

	/**
	 * Carries out a worktree request on the broker's worktree thread and publishes its response.
	 *
	 * @param id        the request ID
	 * @param request   the request
	 * @param directory the request's response directory
	 */
	private void serveWorktree(String id, WorktreeRequest request, Path directory)
	{
		try
		{
			site.worktree(request).write(json, id, directory);
		}
		catch (IOException e)
		{
			synchronized (this)
			{
				recordFailure(e);
			}
		}
	}

	/**
	 * Takes the records that each streamed request's sender has published since the last scan, in order, and stops the
	 * child of each request whose sender has ended or published an invalid record.
	 *
	 * @param requests the nested session's requests directory
	 * @throws IOException if a child cannot be stopped or its response cannot be published
	 */
	private void serveStreams(Path requests) throws IOException
	{
		if (stopped)
			return;
		for (Iterator<Map.Entry<String, Streamed>> entries = streams.entrySet().iterator(); entries.hasNext();)
		{
			Map.Entry<String, Streamed> entry = entries.next();
			String id = entry.getKey();
			Streamed stream = entry.getValue();
			if (cancelled.contains(id))
			{
				// The requesting turn stopped the child, and answered the request
				entries.remove();
				stream.records.add(Optional.empty());
				continue;
			}
			String stop = "";
			try
			{
				if (StreamedRequest.senderRuns(requests, id))
					stream.take(id, requests);
				else
					stop = "The launcher that requested the session ended";
			}
			catch (IOException | JacksonException e)
			{
				stop = "The launcher that requested the session sent an invalid record: " + e.getMessage();
			}
			if (stop.isEmpty())
				continue;
			entries.remove();
			cancel(id);
			if (processes.containsKey(id))
				HarnessProcessTermination.stopRunner(processes.get(id));
			stream.records.add(Optional.empty());
			respond(id, harnesses.get(id), "stopped", stop, copyChildEvidence(id, stream.directory), stream.directory);
		}
		for (Iterator<Map.Entry<String, Path>> entries = held.entrySet().iterator(); entries.hasNext();)
		{
			Map.Entry<String, Path> entry = entries.next();
			String id = entry.getKey();
			if (cancelled.contains(id))
			{
				entries.remove();
				continue;
			}
			if (StreamedRequest.senderRuns(requests, id))
				continue;
			entries.remove();
			cancel(id);
			if (processes.containsKey(id))
				HarnessProcessTermination.stopRunner(processes.get(id));
			Path directory = entry.getValue();
			respond(id, harnesses.get(id), "stopped", "The launcher that requested the session ended",
				copyChildEvidence(id, directory), directory);
		}
	}

	/**
	 * Copies and extracts a request before parsing its bounded manifest and captured prompts.
	 *
	 * @param file      the request archive
	 * @param directory the broker-owned response directory
	 * @return the validated inputs, with prompt bytes already captured
	 * @throws IOException              if the document or any input is invalid
	 * @throws IllegalArgumentException if the request names no model or no effort
	 */
	private LaunchRequest read(Path file, Path directory) throws IOException
	{
		Path inputs = directory.resolve(INPUTS);
		unpack(file, directory, inputs);
		return site.read(json, inputs);
	}

	/**
	 * Copies a request's archive into its response directory and extracts it there.
	 *
	 * @param file      the request archive
	 * @param directory the broker-owned response directory
	 * @param inputs    the new directory that receives the archive's entries
	 * @throws RequestArchiveBusyException if another handle to the archive is open on Windows
	 * @throws IOException                 if the archive is invalid or cannot be copied or extracted
	 */
	private void unpack(Path file, Path directory, Path inputs) throws IOException
	{
		Path copy = directory.resolve("request.tar");
		// The broker has the user's permissions while the requesting session keeps running. Walking its input
		// directories would let it swap a link or junction into the path and make us read a file it may not read.
		// The launcher packs inside its sandbox, which refuses that read. We open only this single archive in
		// requests, whose path has no session-controlled directory, without following links and with one hard link.
		try
		{
			RequestArchive.copy(file, copy, site.limits());
			Files.createDirectory(inputs);
			RequestArchive.unpack(copy, inputs, site.limits());
		}
		finally
		{
			Files.deleteIfExists(copy);
		}
	}

	/**
	 * Builds the runner options that every child receives from its request's private copies: its project,
	 * transcript and receipt, and the request's options, which only narrow the child or describe its own
	 * inputs. The child's results folder and the site's own options are added when the child starts.
	 *
	 * @param request   the validated request
	 * @param directory the response directory
	 * @return the runner options
	 * @throws IOException if the request's extra permissions cannot be written for the runner
	 */
	private List<String> arguments(LaunchRequest request, Path directory) throws IOException
	{
		List<String> arguments = new ArrayList<>();
		if (request.resume().isPresent())
			arguments.addAll(List.of(RunnerArguments.RESUME_SLOT_OPTION, request.resume().get()));
		else
			arguments.addAll(List.of("--project", request.project().toString()));
		arguments.addAll(List.of("--output", directory.resolve("transcript.jsonl").toString(),
			"--receipt", directory.resolve("receipt.json").toString()));
		if (request.turnCopy())
		{
			arguments.addAll(List.of("--turn-copy", directory.resolve(TURN_COPY).toString()));
			request.resultProjectLinks().ifPresent(name -> arguments.addAll(List.of(
				RunnerArguments.RESULT_PROJECT_LINKS_OPTION, name.toString())));
		}
		if (request.conversation())
			arguments.add(RunnerArguments.CONTINUE_AFTER_FAILED_TURN_OPTION);
		for (Path denied : request.deniedReads())
			arguments.addAll(List.of("--deny-read", denied.toString()));
		request.workspace().ifPresent(workspace -> arguments.addAll(List.of("--workspace", workspace.toString())));
		for (Path evidence : request.readOnlyEvidence())
			arguments.addAll(List.of("--read-only-evidence", evidence.toString()));
		request.instructionFiles().ifPresent(files -> arguments.addAll(List.of("--instruction-files", files)));
		request.sessionRole().ifPresent(role -> arguments.addAll(List.of("--session-role", role)));
		request.toolOutputTokenLimit().ifPresent(limit ->
			arguments.addAll(List.of("--tool-output-token-limit", String.valueOf(limit))));
		for (TrialPluginInput plugin : request.plugins())
			plugin.appendArguments(arguments);
		request.agent().ifPresent(agent -> arguments.addAll(List.of("--agent", agent)));
		if (!request.model().isEmpty())
			arguments.addAll(List.of("--model", request.model()));
		if (!request.effort().isEmpty())
			arguments.addAll(List.of("--effort", request.effort()));
		if (request.sessionTranscripts())
			arguments.add("--session-transcripts");
		if (request.installCat())
			arguments.add("--install-cat");
		if (!request.extraPermissions().isEmpty())
		{
			Path file = directory.resolve(ExtraPermissions.FILE_NAME);
			request.extraPermissions().write(json, file);
			arguments.addAll(List.of(ExtraPermissions.OPTION, file.toString()));
		}
		return arguments;
	}

	/**
	 * Starts and feeds a child, then classifies its exit and retains its native transcripts.
	 *
	 * @param id the request id
	 * @param request the captured request
	 * @param arguments the child runner options
	 * @param directory the private response directory
	 */
	private void execute(String id, LaunchRequest request, List<String> arguments, Path directory)
	{
		Process process = null;
		SessionSlots.Slot slot = null;
		String startupGeneration = null;
		try
		{
			if (request.retainedTrialClaim().isPresent())
			{
				RetainedTrialClaim claim = request.retainedTrialClaim().get();
				slot = site.slots().acquireRetained(claim.requestId(), claim.identity(), claim.completedTurns()).
					orElseThrow(() -> new SessionRefusedException("The retained trial checkpoint changed"));
				Path nestedSession = slot.root().resolve(request.harness()).resolve(slot.slotId());
				RetainedTrialPrefix.publish(nestedSession, directory, claim.completedTurns());
			}
			else if (request.resume().isPresent())
			{
				String sessionId = request.resume().get();
				Optional<SessionSlots.Slot> retained = site.slots().acquireRetained(sessionId, request.harness());
				if (retained.isEmpty())
					throw new SessionRefusedException(site.unknownSession(sessionId));
				slot = retained.get();
			}
			else
			{
				slot = site.slots().acquire(request.heldOut());
				// Recovery needs the request ID even if the launcher exits before it reads the harness session ID
				ObjectNode binding = json.createObjectNode().put("format_version", 1).put("request_id", id);
				SessionRequestCli.write(json, slot.root().resolve(ORIGINAL_REQUEST_FILE), binding);
			}
			boolean manualResume = request.resume().isPresent() && request.retainedTrialClaim().isEmpty();
			try (ExclusiveFileLock _ = SlotStartup.acquire(site.fileLocks(), slot.root()))
			{
				startupGeneration = SlotStartup.issue(slot.root(), ProcessHandle.current().pid(),
					SlotStartup.currentProcessStart(), manualResume);
			}
			List<String> options = new ArrayList<>(List.of("--results", slot.root().toString(), "--slot-id",
				slot.slotId()));
			options.addAll(List.of(RunnerArguments.STARTUP_PERMIT_OPTION, startupGeneration));
			options.addAll(arguments);
			RequestedRunner.Start launch = site.prepare(request, slot.root());
			synchronized (this)
			{
				if (stopped || cancelled.contains(id))
					return;
				childRoots.put(id, slot.root().resolve(request.harness()));
				slots.put(id, slot);
			}
			process = launch.run(options, directory);
			synchronized (this)
			{
				processes.put(id, process);
				if (stopped || cancelled.contains(id))
				{
					HarnessProcessTermination.stopRunner(process);
					return;
				}
			}
			try (OutputStream input = process.getOutputStream())
			{
				OptionalInt suspension = OptionalInt.empty();
				if (request.streamed())
					suspension = feed(id, process, input);
				else
				{
					for (String prompt : request.prompts())
					{
						ObjectNode turn = json.createObjectNode().put("type", "turn").put("prompt", prompt);
						input.write((json.writer().without(SerializationFeature.INDENT_OUTPUT).writeValueAsString(turn) +
							"\n").getBytes(StandardCharsets.UTF_8));
					}
					input.write("{\"type\":\"complete\"}\n".getBytes(StandardCharsets.UTF_8));
					input.flush();
				}
				int exit = process.waitFor();
				String status = "failed";
				String reason = "Requested runner exited with status " + exit;
				if (exit == 0)
				{
					if (suspension.isPresent())
					{
						verifySuspension(slot, directory, suspension.getAsInt());
						status = SUSPENDED;
					}
					else
						status = "completed";
					reason = "";
				}
				else if (exit == HarnessCapacityWaitExpiredException.EXIT_STATUS)
				{
					status = "capacity";
					reason = "capacity";
				}
				else if (exit == HarnessRunnerOptions.REFUSED_LAUNCH_STATUS)
				{
					status = "refused";
					reason = runnerDiagnostic(directory, reason);
				}
				else if (request.resume().isPresent() && SessionSlots.isRetained(slot))
				{
					// The runner ended before it recorded an end, so the harness did not continue the session
					status = UNRESUMABLE;
					reason = runnerDiagnostic(directory, "The harness could not continue the session " +
						request.resume().get());
				}
				else
					reason = runnerDiagnostic(directory, reason);
				finish(id, request, status, reason, directory);
			}
		}
		catch (InterruptedException _)
		{
			Thread.currentThread().interrupt();
			publishFailure(id, request.harness(), "stopped", "Requested session interrupted", directory);
		}
		catch (IllegalArgumentException | SessionRefusedException e)
		{
			publishFailure(id, request.harness(), "refused", e.getMessage(), directory);
		}
		catch (IOException e)
		{
			// A slot that cannot be emptied fails only the request that waits for it: kept as the broker's failure, it
			// would end the request server's service of every later request and leave this requester without its reason
			publishFailure(id, request.harness(), "failed", e.getMessage(), directory);
		}
		finally
		{
			if (process != null && process.isAlive())
				HarnessProcessTermination.terminateImmediately(process);
			synchronized (this)
			{
				processes.remove(id);
				streams.remove(id);
				held.remove(id);
				slots.remove(id);
			}
			if (slot != null)
			{
				try
				{
					if (startupGeneration != null)
						cancelStartup(slot, startupGeneration);
					// A nested session that recorded its harness session and no end was interrupted, and may continue. Any other
					// nested session has ended, and its response is published, with its results copied out of the slot.
					if (SessionSlots.isRetained(slot))
						site.slots().retain(slot);
					else
						site.slots().release(slot);
				}
				catch (IOException _)
				{
					// The request has its answer, and the slot is free again. The next request to take the slot checks it
					// again and fails with this reason if it still holds; kept as the broker's failure, it would end the
					// broker's service of every later request.
				}
			}
		}
	}

	/**
	 * Cancels the permit if this broker still owns the pending startup.
	 *
	 * @param slot the reserved slot
	 * @param generation the specific permit this launch issued
	 */
	private void cancelStartup(SessionSlots.Slot slot, String generation)
	{
		boolean interrupted = Thread.interrupted();
		try (ExclusiveFileLock _ = SlotStartup.acquire(site.fileLocks(), slot.root()))
		{
			SlotStartup.cancel(slot.root(), generation);
		}
		catch (IOException _)
		{
			// Recovery still checks the durable slot manifest if cancellation could not take the lock.
		}
		finally
		{
			if (interrupted)
				Thread.currentThread().interrupt();
		}
	}

	/**
	 * Stops serving a request whose child is being stopped. A launch that resumes or discards the child's nested session
	 * waits for the child's slot until the child has ended, instead of being refused as if another launch held it. Call
	 * it while holding this instance's monitor.
	 *
	 * @param id the request identifier
	 */
	private void cancel(String id)
	{
		cancelled.add(id);
		SessionSlots.Slot slot = slots.get(id);
		if (slot != null)
			site.slots().markStopping(slot);
	}

	/**
	 * Writes each record of a streamed request to its child until the stream ends or the child stops.
	 *
	 * @param id      the request identifier
	 * @param process the child
	 * @param input   the child's standard input
	 * @return the count in the suspension record, if one was sent to the child
	 * @throws IOException          if a record cannot be written
	 * @throws InterruptedException if the thread is interrupted while it waits for a record
	 */
	private OptionalInt feed(String id, Process process, OutputStream input) throws IOException, InterruptedException
	{
		BlockingQueue<Optional<String>> records;
		synchronized (this)
		{
			Streamed stream = streams.get(id);
			if (stream == null)
				return OptionalInt.empty();
			records = stream.records;
		}
		process.onExit().thenRun(() -> records.add(Optional.empty()));
		while (true)
		{
			Optional<String> record = records.take();
			if (record.isEmpty())
				return OptionalInt.empty();
			input.write((record.get() + "\n").getBytes(StandardCharsets.UTF_8));
			input.flush();
			if (StreamedRequest.endsStream(json, record.get()))
			{
				JsonNode message = json.readTree(record.get());
				if (message.path("type").asString().equals("suspend"))
					return OptionalInt.of(message.path("completed_turns").intValue());
				return OptionalInt.empty();
			}
		}
	}

	/**
	 * Checks that the runner's private transcript acknowledges the exact suspension and that the slot is still
	 * retained.
	 *
	 * @param slot the slot the broker reserved for the runner
	 * @param directory the broker's private response directory
	 * @param expected the completed turn count sent to the runner
	 * @throws IOException if the runner did not publish the matching terminal record or retain the session
	 */
	private void verifySuspension(SessionSlots.Slot slot, Path directory, int expected) throws IOException
	{
		Path transcript = directory.resolve("transcript.jsonl");
		if (!SessionSlots.isRetained(slot) || !Files.isRegularFile(transcript, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Requested runner did not retain its suspended session");
		String last;
		try (BufferedReader lines = Files.newBufferedReader(transcript, StandardCharsets.UTF_8))
		{
			last = lines.readLine();
			for (String line = lines.readLine(); line != null; line = lines.readLine())
				last = line;
		}
		if (last == null)
			throw new IOException("Requested runner did not acknowledge the requested suspension");
		try
		{
			JsonNode record = json.readTree(last);
			JsonNode turns = record.path("completed_turns");
			if (record.path("type").asString("").equals(SUSPENDED) && turns.isInt() && turns.intValue() == expected)
				return;
		}
		catch (JacksonException e)
		{
			throw new IOException("Requested runner wrote a malformed suspension acknowledgement", e);
		}
		throw new IOException("Requested runner did not acknowledge the requested suspension");
	}

	/**
	 * Reads the requested runner's diagnostic from the broker's private response directory.
	 *
	 * @param directory the private response directory, which holds the runner's diagnostics
	 * @param fallback  the reason to give if the diagnostic is unavailable
	 * @return the runner's reason or the fallback
	 */
	private static String runnerDiagnostic(Path directory, String fallback)
	{
		Path file = directory.resolve(RequestedSessionStarter.ERROR_FILE);
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
			return fallback;

		try
		{
			String reason = BoundedInput.readUtf8(Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS), file,
				MAXIMUM_RUNNER_DIAGNOSTIC_BYTES, "requested runner diagnostic").strip();
			if (!reason.isEmpty())
				return reason;
		}
		catch (IOException _)
		{
			// The runner left no readable reason
		}
		return fallback;
	}

	/**
	 * Publishes a child's result after copying transcripts from its retained nested session and the nested session
	 * itself, without its home, into the response directory's {@link #RESULTS_COPY}. A nested session that cannot be
	 * copied in full makes a completed child fail, with the reason that names the file.
	 *
	 * @param id the request id
	 * @param request the verified launch request
	 * @param status the terminal classification
	 * @param reason the diagnostic
	 * @param directory the private response directory
	 * @throws IOException if evidence cannot be read or copied
	 */
	private void finish(String id, LaunchRequest request, String status, String reason, Path directory)
		throws IOException
	{
		synchronized (this)
		{
			// The child has ended, so the end of its request's sender no longer stops it
			streams.remove(id);
			held.remove(id);
			if (stopped || cancelled.contains(id))
				return;
			String childSlotId = copyChildEvidence(id, directory);
			boolean succeeded = status.equals("completed") || status.equals(SUSPENDED);
			if (succeeded && !Files.isRegularFile(directory.resolve("receipt.json")))
				throw new IOException("Requested runner completed without a retained-evidence receipt");
			String outcome = status;
			String diagnostic = reason;
			String sessionId = "";
			if (!childSlotId.isEmpty())
				sessionId = NestedSessionManager.harnessSessionId(childRoots.get(id).resolve(childSlotId)).orElse("");
			// A nested session whose session could not continue stays retained, so it has no copy. Before the harness reports
			// the session's identifier, nothing names a copy.
			if (!sessionId.isEmpty() && !status.equals(UNRESUMABLE))
			{
				// The requester cannot read the requested nested sessions, whose homes hold their logins, so it reads a copy
				Path child = childRoots.get(id).resolve(childSlotId);
				Path results = directory.resolve(RESULTS_COPY);
				Optional<String> incomplete = ResultsCopy.copy(child, results).incomplete();
				if (incomplete.isPresent())
				{
					if (diagnostic.isEmpty())
						diagnostic = incomplete.get();
					else
						diagnostic += "\n" + incomplete.get();
				if (succeeded)
						outcome = "failed";
				}
			else if (succeeded)
				{
					NestedSessionReceipt.read(json, directory.resolve("receipt.json"), results.resolve("project"));
					Optional<Path> links = request.resultProjectLinks();
					if (request.resume().isPresent())
						links = NestedSessionLaunch.resultProjectLinks(json, child);
					if (links.isPresent())
						ResultProjectLinks.capture(child, results, links.get());
				}
			}
			respond(id, request.harness(), outcome, diagnostic, sessionId, directory);
		}
	}

	/**
	 * Copies native transcripts from the child's private results folder, including when no success receipt was written.
	 *
	 * @param id the request id
	 * @param directory the private response directory
	 * @return the child's slot ID, or empty if capacity or startup prevented creation
	 * @throws IOException if retained evidence cannot be inspected or copied
	 */
	private String copyChildEvidence(String id, Path directory) throws IOException
	{
		Path root = childRoots.get(id);
		if (root == null || !Files.isDirectory(root))
			return "";
		try (DirectoryStream<Path> children = Files.newDirectoryStream(root))
		{
			for (Path child : children)
			{
				if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS))
					continue;
				Path transcripts = child.resolve("artifacts/session-transcripts");
				Path copies = directory.resolve("session-transcripts");
				if (Files.isDirectory(transcripts) && !Files.exists(copies))
					copyTranscripts(transcripts, copies);
				return child.getFileName().toString();
			}
		}
		return "";
	}

	/**
	 * Copies transcripts from the child-owned nested session tree, which the requesting session cannot modify.
	 *
	 * @param source      the child's retained transcripts
	 * @param destination the response's transcript copies
	 * @throws IOException if copying fails or the child tree contains a link or special file
	 */
	private static void copyTranscripts(Path source, Path destination) throws IOException
	{
		try (Stream<Path> paths = Files.walk(source))
		{
			for (Path path : (Iterable<Path>) paths::iterator)
			{
				Path target = destination.resolve(source.relativize(path));
				if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
					Files.createDirectories(target);
				else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
					Files.copy(path, target, LinkOption.NOFOLLOW_LINKS);
				else
					throw new IOException("Child transcript is a link or special file: " + path);
			}
		}
	}

	/**
	 * Records a child failure, retaining publication failures for the bridge to report on close.
	 *
	 * @param id the request id
	 * @param harness the requested harness
	 * @param status the terminal classification
	 * @param reason the diagnostic
	 * @param directory the private response directory
	 */
	private void publishFailure(String id, String harness, String status, String reason, Path directory)
	{
		synchronized (this)
		{
			streams.remove(id);
			held.remove(id);
			if (stopped || cancelled.contains(id))
				return;
			try
			{
				respond(id, harness, status, reason, "", directory);
			}
			catch (IOException e)
			{
				recordFailure(e);
			}
		}
	}

	/**
	 * Atomically publishes a terminal response that contains paths rather than evidence content, and the authentication
	 * that the child's harness used, as the child's receipt records it, which names no secret.
	 *
	 * @param id the request id
	 * @param harness the requested harness, empty for an unparsed request
	 * @param status the terminal classification
	 * @param reason the diagnostic
	 * @param sessionId the identifier that the harness gave the child's session, empty if unavailable
	 * @param directory the private response directory
	 * @throws IOException if publication fails
	 */
	private void respond(String id, String harness, String status, String reason, String sessionId, Path directory)
		throws IOException
	{
		ObjectNode response = json.createObjectNode().put("format_version", 1).put("id", id).put("harness", harness).
			put("status", status).put("reason", reason).put(SESSION_ID, sessionId);
		for (String name : List.of("transcript.jsonl", "receipt.json"))
			if (Files.isRegularFile(directory.resolve(name)))
				response.put(name.substring(0, name.indexOf('.')), directory.resolve(name).toString());
		Optional<ObjectNode> authentication = authentication(directory.resolve("receipt.json"));
		authentication.ifPresent(evidence -> response.set(AuthenticationEvidence.FIELD, evidence));
		if (Files.isDirectory(directory.resolve("session-transcripts")))
			response.put("session_transcripts", directory.resolve("session-transcripts").toString());
		if (Files.isDirectory(directory.resolve(RESULTS_COPY), LinkOption.NOFOLLOW_LINKS))
			response.put(RESULTS_COPY_FIELD, directory.resolve(RESULTS_COPY).toString());
		SessionRequestCli.write(json, directory.resolve("response.json"), response);
	}

	/**
	 * Returns the authentication that a child's receipt records.
	 *
	 * @param receipt the child's receipt
	 * @return the receipt's authentication record, or empty if the child wrote no receipt or the receipt records none
	 *   that can be read
	 */
	private Optional<ObjectNode> authentication(Path receipt)
	{
		if (!Files.isRegularFile(receipt, LinkOption.NOFOLLOW_LINKS))
			return Optional.empty();
		try
		{
			JsonNode recorded = json.readTree(BoundedInput.readUtf8(receipt, MAXIMUM_RECEIPT_BYTES, "receipt")).
				path(AuthenticationEvidence.FIELD);
			if (recorded.isMissingNode())
				return Optional.empty();
			return Optional.of(AuthenticationEvidence.fromJson(recorded).toJson(json));
		}
		catch (IOException | JacksonException _)
		{
			// The response's receipt path still leads the requester to whatever the child wrote
			return Optional.empty();
		}
	}

	/**
	 * Answers {@code ended} each request whose response directory holds no response, once the nested session that made
	 * the requests resumes its harness session: those requests were interrupted with the nested session, and the sessions
	 * that they started keep their retained slots for the resumed session to resume. Call it before {@link
	 * #start(HeartbeatTicker)}.
	 *
	 * @throws IOException if the responses cannot be listed or a response cannot be published
	 */
	public void endUnanswered() throws IOException
	{
		Path responses = site.responses();
		if (!Files.isDirectory(responses, LinkOption.NOFOLLOW_LINKS))
			return;
		List<Path> directories;
		try (Stream<Path> entries = Files.list(responses))
		{
			directories = entries.filter(entry -> Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)).sorted().
				toList();
		}
		for (Path directory : directories)
		{
			if (Files.exists(directory.resolve("response.json"), LinkOption.NOFOLLOW_LINKS))
				continue;
			String id = directory.getFileName().toString();
			if (id.startsWith(WorktreeRequest.PREFIX) || id.startsWith(DiscardRequest.PREFIX) ||
				id.startsWith(RetainedTrialRequest.PREFIX) || id.startsWith(HookComparisonRequest.PREFIX))
				WorktreeResponse.failed(ENDED).write(json, id, directory);
			else
				respond(id, "", "failed", ENDED, "", directory);
		}
	}

	/**
	 * Stops children and pending requests on a turn interruption. Later turns may submit new requests against the
	 * nested session's remaining count.
	 *
	 * @throws IOException if termination or response publication fails
	 */
	public void stopChildren() throws IOException
	{
		synchronized (this)
		{
			for (String id : processes.keySet())
				HarnessProcessTermination.stopRunner(processes.get(id));
			for (Map.Entry<String, String> entry : harnesses.entrySet())
			{
				Path directory = site.responses().resolve(entry.getKey());
				if (!Files.exists(directory.resolve("response.json")))
				{
					cancel(entry.getKey());
					respond(entry.getKey(), entry.getValue(), "stopped", "The requesting turn stopped",
						copyChildEvidence(entry.getKey(), directory), directory);
				}
			}
			boolean wasStopped = stopped;
			stopped = true;
			try
			{
				scan();
			}
			finally
			{
				stopped = wasStopped;
			}
		}
	}

	@Override
	public void close() throws IOException
	{
		synchronized (this)
		{
			if (stopped)
				return;
			stopped = true;
			try
			{
				if (monitor != null)
					monitor.close();
			}
			catch (IOException e)
			{
				recordFailure(e);
			}
			for (String id : processes.keySet())
			{
				try
				{
					HarnessProcessTermination.stopRunner(processes.get(id));
				}
				catch (IOException e)
				{
					recordFailure(e);
				}
			}
			for (Map.Entry<String, String> entry : harnesses.entrySet())
			{
				Path directory = site.responses().resolve(entry.getKey());
				try
				{
					if (awaitsAnswer(directory))
						respond(entry.getKey(), entry.getValue(), site.closedStatus(), site.closedReason(),
							copyChildEvidence(entry.getKey(), directory), directory);
				}
				catch (IOException e)
				{
					recordFailure(e);
				}
			}
			try
			{
				scan();
			}
			catch (IOException e)
			{
				recordFailure(e);
			}
			children.shutdownNow();
			worktrees.shutdown();
		}
		children.close();
		// A worktree request that has started nested sessions to its end, so that Git never stops halfway through an
		// operation
		worktrees.close();
		synchronized (this)
		{
			if (failure != null)
				throw failure;
		}
	}

	/**
	 * Indicates whether a request still waits for its answer. Its requester removes the request's response directory
	 * once it has read the answer, and a requester that has gone needs no answer.
	 *
	 * @param directory the request's response directory
	 * @return {@code true} if the directory exists and holds no answer
	 */
	private static boolean awaitsAnswer(Path directory)
	{
		return Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) &&
			!Files.exists(directory.resolve("response.json"), LinkOption.NOFOLLOW_LINKS);
	}

	/**
	 * Retains cleanup failures while allowing every remaining child to be stopped.
	 *
	 * @param exception the failure to report after cleanup
	 */
	private void recordFailure(IOException exception)
	{
		if (failure == null || failure == exception)
			failure = exception;
		else
			failure.addSuppressed(exception);
	}

	/**
	 * The records of a streamed request that the broker took and its child has not received yet.
	 * <p>
	 * <b>Thread Safety:</b> This class is thread-safe; the broker takes records while holding its own lock.
	 */
	private final class Streamed
	{
		/**
		 * The records in order; an empty element means that no record follows.
		 */
		private final BlockingQueue<Optional<String>> records = new LinkedBlockingQueue<>();
		/**
		 * The request's private response directory.
		 */
		private final Path directory;
		/**
		 * The number of the next record to take.
		 */
		private int next = 1;
		/**
		 * Indicates whether the broker took the terminal record.
		 */
		private boolean complete;

		/**
		 * Creates the records of a request.
		 *
		 * @param directory the request's private response directory
		 */
		Streamed(Path directory)
		{
			this.directory = directory;
		}

		/**
		 * Takes each record that the sender has published since the last call, in order.
		 *
		 * @param id       the request identifier
		 * @param requests the nested session's requests directory
		 * @throws IOException if a record is invalid or cannot be read
		 */
		void take(String id, Path requests) throws IOException
		{
			while (!complete)
			{
				Optional<String> record = StreamedRequest.take(json, requests, id, next, directory, site.limits());
				if (record.isEmpty())
					return;
				next += 1;
				complete = StreamedRequest.endsStream(json, record.get());
				records.add(record);
			}
		}
	}

	/**
	 * Where a broker's requests arrive, and what the children that it starts for them receive.
	 * <p>
	 * <b>Thread Safety:</b> Implementations must be thread-safe.
	 */
	interface Site
	{
		/**
		 * Returns the directory into which requesters write their requests.
		 *
		 * @return the directory
		 */
		Path requests();

		/**
		 * Returns the directory that holds one response directory per request.
		 *
		 * @return the directory
		 */
		Path responses();

		/**
		 * Returns the bounds that each request's archive must stay within.
		 *
		 * @return the bounds
		 */
		RequestLimits limits();

		/**
		 * Reads the manifest of an extracted request.
		 *
		 * @param json   the manifest mapper
		 * @param inputs the private directory that holds the extracted request
		 * @return the request
		 * @throws IOException              if the manifest is malformed or chooses a field that the site refuses
		 * @throws IllegalArgumentException if the request names no model or no effort
		 */
		LaunchRequest read(JsonMapper json, Path inputs) throws IOException;

		/**
		 * Returns why the site refuses the next request that does not resume a session.
		 *
		 * @param accepted how many requests the site's requester has accepted
		 * @return the reason, or empty if the site accepts the request
		 */
		Optional<String> refusal(int accepted);

		/**
		 * Returns how many requests the site's requester accepted before the broker started, which the broker
		 * continues to count from.
		 *
		 * @return the number of requests
		 * @throws IOException if the count cannot be read
		 */
		int acceptedBefore() throws IOException;

		/**
		 * Records how many requests the site's requester has accepted, once when the broker starts and again after
		 * each acceptance.
		 *
		 * @param json     writes the record
		 * @param accepted how many requests the site's requester has accepted
		 * @throws IOException if the record cannot be written
		 */
		void accepted(JsonMapper json, int accepted) throws IOException;

		/**
		 * Returns the slots in which the site's requested sessions run, one session at a time in each.
		 *
		 * @return the slots
		 */
		SessionSlots slots();

		/**
		 * Returns the process-wide file lock coordinator used by the site's slots.
		 *
		 * @return the shared coordinator
		 */
		FileLocks fileLocks();

		/**
		 * Prepares a launch with the site's policy and runner, on the request's own thread before the child starts.
		 *
		 * @param request       the request
		 * @param resultsFolder the request's results folder
		 * @return the prepared launch
		 * @throws SessionRefusedException if the site refuses the request; the message says why
		 * @throws IOException             if the options cannot be determined
		 */
		RequestedRunner.Start prepare(LaunchRequest request, Path resultsFolder) throws IOException;

		/**
		 * Returns the status of the requests that remain unanswered when the broker closes.
		 *
		 * @return the status
		 */
		String closedStatus();

		/**
		 * Returns the reason of the requests that remain unanswered when the broker closes.
		 *
		 * @return the reason
		 */
		String closedReason();

		/**
		 * Returns why a request that resumes a harness session is refused when none of the site's retained nested sessions
		 * records the session.
		 *
		 * @param sessionId the harness session
		 * @return the reason
		 * @throws IOException if the places where another requester's nested sessions are retained cannot be read
		 */
		String unknownSession(String sessionId) throws IOException;

		/**
		 * Carries out a worktree request, on the broker's worktree thread.
		 *
		 * @param request the request
		 * @return the response
		 */
		WorktreeResponse worktree(WorktreeRequest request);

		/**
		 * Returns the site's authenticated Codex comparison service, if it has one.
		 *
		 * @return the service, or empty when execution is unsupported
		 */
		default Optional<HookComparisonService> hookComparison()
		{
			return Optional.empty();
		}

		/**
		 * Releases a request's archive once the broker has copied it, or answered the request without reading it.
		 *
		 * @param file the request archive
		 * @throws IOException if the archive cannot be released
		 */
		void taken(Path file) throws IOException;
	}

	/**
	 * Runs one request using the broker's authenticated native installation and owner policy.
	 */
	@FunctionalInterface
	public interface HookComparisonService
	{
		/**
		 * Performs the comparison and returns observations only.
		 *
		 * @param request the captured workspace copies and plugins
		 * @param scratch the broker-owned response directory
		 * @return the native comparison result
		 * @throws IOException if the comparison cannot run under the owner's policy
		 * @throws InterruptedException if the broker interrupts the comparison
		 */
		HookComparisonResult compare(HookComparisonRequest request, Path scratch)
			throws IOException, InterruptedException;
	}

	/**
	 * The site of a nested session whose runner serves the requests of the nested session's commands.
	 * <p>
	 * <b>Thread Safety:</b> This class is thread-safe.
	 *
	 * @param nestedSession the requesting nested session
	 * @param manager       records the nested session's lifecycle in its manifest
	 * @param options       the requesting runner's options
	 * @param catPlugin     the directory of the CAT plugin that the nested session's commands run, which the nested
	 *                      session's requested sessions may read, or {@code null} if the nested session has none
	 * @param limits        the bounds that each request's archive must stay within
	 * @param slots         the slots, each a results folder, that the nested session's requested sessions take
	 * @param starter       resolves and starts child runners
	 */
	private record NestedSessionSite(NestedSession nestedSession, NestedSessionManager manager,
		HarnessRunnerOptions options, Path catPlugin, RequestLimits limits, SessionSlots slots,
		RequestedSessionStarter starter) implements Site
	{
		/**
		 * Carries out the nested session's worktree requests.
		 */
		private static final WorktreeService WORKTREES = new WorktreeService(new GitWorktreeRegistrar());

		/**
		 * Creates the site of a nested session.
		 *
		 * @param nestedSession the requesting nested session
		 * @param manager       records the nested session's lifecycle in its manifest
		 * @param options       the requesting runner's options
		 * @param catPlugin     the directory of the CAT plugin that the nested session's commands run, which the nested
		 *                      session's requested sessions may read, or {@code null} if the nested session has none
		 * @param limits        the bounds that each request's archive must stay within
		 * @param slots         the slots, each a results folder, that the nested session's requested sessions take
		 * @param starter       resolves and starts child runners
		 * @throws NullPointerException if any of the arguments other than {@code catPlugin} are null
		 */
		private NestedSessionSite
		{
			requireThat(nestedSession, "nestedSession").isNotNull();
			requireThat(manager, "manager").isNotNull();
			requireThat(options, "options").isNotNull();
			requireThat(limits, "limits").isNotNull();
			requireThat(slots, "slots").isNotNull();
			requireThat(starter, "starter").isNotNull();
		}

		@Override
		public Path requests()
		{
			return SessionRequestAccess.requestsDirectory(nestedSession);
		}

		@Override
		public Path responses()
		{
			return SessionRequestAccess.responsesDirectory(nestedSession);
		}

		@Override
		public LaunchRequest read(JsonMapper json, Path inputs) throws IOException
		{
			return RequestArchive.readLaunch(json, inputs);
		}

		@Override
		public Optional<String> refusal(int accepted)
		{
			if (options.sessionRequests() == 0)
				return Optional.of(SessionRequestAccess.NO_REQUESTS_REASON);
			if (accepted >= options.sessionRequests())
				return Optional.of(SessionRequestAccess.limitReason(options.sessionRequests()));
			return Optional.empty();
		}

		/**
		 * Returns how many requests the nested session accepted before the broker started, as its manifest records them, so
		 * that a nested session that a runner resumes keeps the count of its earlier runners.
		 *
		 * @return the number of requests
		 * @throws IOException if the nested session's manifest cannot be read
		 */
		@Override
		public int acceptedBefore() throws IOException
		{
			return manager.requestsUsed(nestedSession);
		}

		/**
		 * Records in the nested session's manifest how many requests the nested session has accepted, then atomically
		 * states, in the nested session's {@link SessionRequestAccess#LIMITS_FILE}, how many sessions the nested session
		 * allows, how many it has accepted and how many levels below its requested sessions may request sessions in turn.
		 *
		 * @param json     writes the file
		 * @param accepted how many requests the nested session has accepted
		 * @throws IOException if the manifest or the file cannot be written
		 */
		@Override
		public void accepted(JsonMapper json, int accepted) throws IOException
		{
			manager.recordRequestsUsed(nestedSession, accepted);
			ObjectNode limits = json.createObjectNode().put("format_version", 1).
				put("requests_allowed", options.sessionRequests()).put("requests_used", accepted).
				put("depth_remaining", options.requestDepth());
			SessionRequestCli.write(json, responses().resolve(SessionRequestAccess.LIMITS_FILE), limits);
		}

		@Override
		public SessionSlots slots()
		{
			return slots;
		}

		@Override
		public FileLocks fileLocks()
		{
			return manager.fileLocks();
		}

		/**
		 * Retains the parent's denied reads and capacity ceiling, denies the child the parent's results folder except
		 * for the CAT plugin that the parent's commands run, as {@link #resultsDenials(Path, Path)} does, and, while the
		 * parent's request depth leaves a level below the child, lets the child request as many sessions as the
		 * parent, one level less deep; otherwise it may request none. A resume adds the parent's current denials to
		 * those fixed at the child's start. The child, of either harness, takes its credential from the source home and
		 * gateway login class of the parent's own launch, never from the nested session's isolated home, as passed on
		 * from the parent runner rather than chosen by the requesting session.
		 *
		 * @param request       the request
		 * @param resultsFolder the request's results folder
		 * @return the prepared launch
		 * @throws IOException if a directory of the parent's results folder cannot be listed
		 */
		@Override
		public RequestedRunner.Start prepare(LaunchRequest request, Path resultsFolder) throws IOException
		{
			List<String> arguments = new ArrayList<>(List.of("--capacity-wait", "600", "--max-concurrency",
				String.valueOf(options.maximumConcurrency())));
			String denialOption = "--deny-read";
			if (request.resume().isPresent())
				denialOption = RunnerArguments.INHERITED_DENY_READ_OPTION;
			for (Path denied : options.deniedReads())
				arguments.addAll(List.of(denialOption, denied.toString()));
			for (Path denied : resultsDenials(options.resultsFolder(), catPlugin))
				arguments.addAll(List.of(denialOption, denied.toString()));
			if (options.requestDepth() > 0)
			{
				arguments.addAll(List.of("--session-requests", String.valueOf(options.sessionRequests())));
				int childDepth = options.requestDepth() - 1;
				if (childDepth > 0)
					arguments.addAll(List.of("--request-depth", String.valueOf(childDepth)));
			}
			arguments.addAll(options.authentication().inheritedArguments());
			return (base, directory) ->
			{
				List<String> child = new ArrayList<>(base);
				child.addAll(arguments);
				return starter.start(request.harness(), child, directory);
			};
		}

		/**
		 * Carries out a worktree request in the nested session's project, which the nested session's one session can write
		 * entirely, so the campaign is found by its identifier among the {@code optimizer} directories of the project's CAT
		 * session directories. The nested session's own sandbox keeps the session from writing the repository's
		 * configuration and hooks.
		 *
		 * @param request the request
		 * @return the response
		 */
		@Override
		public WorktreeResponse worktree(WorktreeRequest request)
		{
			if (options.sessionRequests() == 0)
				return WorktreeResponse.refused(SessionRequestAccess.NO_REQUESTS_REASON);
			Path project = nestedSession.directory().resolve("project");
			List<Path> campaigns = new ArrayList<>();
			Path sessions = project.resolve(SessionDirectory.ROOT);
			if (Files.isDirectory(sessions, LinkOption.NOFOLLOW_LINKS))
			{
				try (DirectoryStream<Path> entries = Files.newDirectoryStream(sessions))
				{
					for (Path session : entries)
					{
						Path campaign = session.resolve(SessionDirectory.CAMPAIGNS).resolve(request.campaign());
						if (Files.isDirectory(campaign, LinkOption.NOFOLLOW_LINKS))
							campaigns.add(campaign);
					}
				}
				catch (IOException e)
				{
					return WorktreeResponse.failed(String.valueOf(e.getMessage()));
				}
			}
			if (campaigns.isEmpty())
				return WorktreeResponse.refused(WorktreeService.foreignCampaign(request.campaign()));
			if (campaigns.size() > 1)
			{
				return WorktreeResponse.refused("Campaign " + request.campaign() + " lies in more than one CAT " +
					"session directory of the nested session's project, so CAT cannot tell which campaign the request names.");
			}
			return WORKTREES.serve(request, project, campaigns.getFirst().getParent(), Optional.empty());
		}

		@Override
		public String closedStatus()
		{
			return "stopped";
		}

		@Override
		public String closedReason()
		{
			return "The requesting nested session stopped";
		}

		@Override
		public String unknownSession(String sessionId)
		{
			return "No interrupted nested session that this nested session requested records the session " + sessionId +
				", so no launch of this nested session can resume it";
		}

		@Override
		public void taken(Path file)
		{
			// The request stays in the nested session, which the nested session's runner removes with it
		}
	}
}
