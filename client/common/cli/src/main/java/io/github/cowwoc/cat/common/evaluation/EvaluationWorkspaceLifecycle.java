package io.github.cowwoc.cat.common.evaluation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import tools.jackson.databind.node.ObjectNode;

/**
 * Owns the live workspace lifecycle for one evaluator record.
 *
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe. Concurrent callers may own distinct workspaces, while the
 * operating-system claim prevents concurrent ownership of the same workspace.
 */
public final class EvaluationWorkspaceLifecycle
{
	/** The workspace receipt that identifies its owning evaluation record. */
	public static final String EVIDENCE_MARKER = ".cat-prompt-evaluation-evidence";
	private static final String ACTIVE_MARKER = ".cat-prompt-evaluation-active";

	private final Access access;
	private final Set<Path> activeRuns = ConcurrentHashMap.newKeySet();

	/**
	 * Creates a lifecycle owner with the collaborators that persist evaluator state.
	 *
	 * @param access the record, status, and process collaborators
	 */
	public EvaluationWorkspaceLifecycle(Access access)
	{
		this.access = access;
	}

	/**
	 * Prepares an invocation-local workspace without discarding existing evidence.
	 *
	 * @param workspace the requested workspace
	 * @param operation the operation that owns the workspace
	 * @throws IOException if the path is unsafe or already contains data
	 */
	public static void prepareEmptyWorkspace(Path workspace, String operation) throws IOException
	{
		if (Files.notExists(workspace))
		{
			Files.createDirectories(workspace);
			return;
		}
		if (Files.isSymbolicLink(workspace) || !Files.isDirectory(workspace))
			throw new IOException(operation + " workspace is not a real directory: " + workspace);
		try (Stream<Path> entries = Files.list(workspace))
		{
			if (entries.findAny().isPresent())
				throw new IOException(operation + " workspace is not empty: " + workspace);
		}
	}

	/**
	 * Prepares a terminal evaluator workspace while preserving its workflow ownership marker.
	 *
	 * @param workspace the evaluator-owned invocation directory
	 * @throws IOException if the workspace contains unrelated data
	 */
	public static void prepareTerminalWorkspace(Path workspace) throws IOException
	{
		if (Files.notExists(workspace))
		{
			Files.createDirectories(workspace);
			return;
		}
		if (Files.isSymbolicLink(workspace) || !Files.isDirectory(workspace))
			throw new IOException("Terminal evaluation workspace is not a real directory: " + workspace);
		try (Stream<Path> entries = Files.list(workspace))
		{
			List<Path> contents = entries.toList();
			if (contents.size() == 1 && contents.getFirst().getFileName().toString().equals(".cat-workflow-temp"))
				return;
			if (!contents.isEmpty())
				throw new IOException("Terminal evaluation workspace is not empty: " + workspace);
		}
	}

	/**
	 * Makes an initially empty workspace belong to exactly one evaluation record.
	 *
	 * @param runPath the record that will own the workspace
	 * @param workspaceRoot the initially empty or previously prepared workspace
	 * @throws IOException if the workspace contains unrelated data or belongs to another record
	 */
	public void prepare(Path runPath, Path workspaceRoot) throws IOException
	{
		Path normalizedRunPath = runPath.toAbsolutePath().normalize();
		Path normalizedWorkspaceRoot = workspaceRoot.toAbsolutePath().normalize();
		Path evidenceMarker = evidenceMarker(normalizedWorkspaceRoot);
		if (Files.isRegularFile(evidenceMarker))
		{
			String owner = Files.readString(evidenceMarker, StandardCharsets.UTF_8).strip();
			if (!owner.equals(normalizedRunPath.toString()))
			{
				throw new IOException("Evaluation workspace belongs to a different run: " + normalizedWorkspaceRoot);
			}
			return;
		}

		access.prepareEmptyWorkspace(normalizedWorkspaceRoot);
		Files.writeString(evidenceMarker, normalizedRunPath + System.lineSeparator(), StandardCharsets.UTF_8);
	}

	/**
	 * Runs one prepared evaluator operation while publishing its live and terminal workspace state.
	 *
	 * @param runPath the owning evaluation record
	 * @param workspaceRoot the prepared workspace
	 * @param initialPhase the first externally visible evaluator phase
	 * @param operation the evaluator work
	 * @param terminalResult identifies results that complete the workspace lifecycle
	 * @param failureResult handles an operation failure after its interruption marker is retained
	 * @return the operation result or the failure handler's recovery result
	 * @throws IOException if the workspace cannot be owned, published, or run
	 */
	public ObjectNode run(Path runPath, Path workspaceRoot, String initialPhase, Operation operation,
		TerminalResult terminalResult, FailureResult failureResult) throws IOException
	{
		Path normalizedRunPath = runPath.toAbsolutePath().normalize();
		Path normalizedWorkspaceRoot = workspaceRoot.toAbsolutePath().normalize();
		verifyOwnership(normalizedRunPath, normalizedWorkspaceRoot);

		Path activeMarker = activeMarker(normalizedWorkspaceRoot);
		Path interruptionMarker = interruptionMarker(normalizedRunPath);
		Files.deleteIfExists(interruptionMarker);
		try (Lock activeLock = Lock.claim(activeMarker))
		{
			activeLock.verifyHeld();
			access.recordActiveWorkspace(normalizedRunPath, normalizedWorkspaceRoot);
			activeRuns.add(normalizedRunPath);
			ObjectNode result = null;
			try
			{
				try (Heartbeat heartbeat = access.startHeartbeat(normalizedWorkspaceRoot, activeLock))
				{
					Context context = new Context()
					{
						@Override
						public void publishPhase(String phase) throws IOException
						{
							access.publishActiveStatus(normalizedWorkspaceRoot, phase);
						}

						@Override
						public void verifyHeartbeat() throws IOException
						{
							heartbeat.verify();
						}
					};
					context.publishPhase(initialPhase);
					result = operation.run(context);
					heartbeat.verify();
					return result;
				}
			}
			catch (IOException e)
			{
				access.writeInterruption(normalizedRunPath, e.getMessage() + System.lineSeparator());
				result = failureResult.handle(e);
				return result;
			}
			finally
			{
				activeRuns.remove(normalizedRunPath);
				if (result != null && terminalResult.isTerminal(result))
				{
					access.publishTerminalStatus(normalizedWorkspaceRoot);
					Files.deleteIfExists(activeMarker);
					access.clearActiveWorkspace(normalizedRunPath, normalizedWorkspaceRoot);
				}
			}
		}
	}

	/**
	 * Retains interruption evidence for live evaluators, then stops their child processes.
	 */
	public void interruptActive()
	{
		for (Path runPath : activeRuns)
		{
			try
			{
				access.writeInterruption(runPath,
					"The evaluator process ended before it recorded a terminal decision.\n");
			}
			catch (IOException _)
			{
				// Process termination remains necessary when an unavailable record cannot retain the diagnostic.
			}
		}
		access.interruptActiveProcesses();
	}

	/**
	 * Returns the active ownership-marker path for a workspace.
	 *
	 * @param workspaceRoot the evaluator workspace
	 * @return the exclusive workspace-claim marker
	 */
	public static Path activeMarker(Path workspaceRoot)
	{
		return workspaceRoot.resolve(ACTIVE_MARKER);
	}

	/**
	 * Returns the durable ownership-receipt path for a workspace.
	 *
	 * @param workspaceRoot the evaluator workspace
	 * @return the workspace receipt
	 */
	public static Path evidenceMarker(Path workspaceRoot)
	{
		return workspaceRoot.resolve(EVIDENCE_MARKER);
	}

	/**
	 * Returns the interruption-marker path for an evaluation record.
	 *
	 * @param runPath the evaluation record
	 * @return the adjacent interruption marker
	 */
	public static Path interruptionMarker(Path runPath)
	{
		return runPath.resolveSibling(runPath.getFileName() + ".interrupted");
	}

	/**
	 * Verifies the workspace receipt identifies the caller's record.
	 *
	 * @param runPath the evaluation record
	 * @param workspaceRoot the prepared workspace
	 * @throws IOException if the receipt is absent or names another record
	 */
	private static void verifyOwnership(Path runPath, Path workspaceRoot) throws IOException
	{
		Path evidenceMarker = evidenceMarker(workspaceRoot);
		if (!Files.isRegularFile(evidenceMarker))
			throw new IOException("Workspace is not owned by this prompt evaluation: " + workspaceRoot);
		String owner = Files.readString(evidenceMarker, StandardCharsets.UTF_8).strip();
		if (!owner.equals(runPath.toString()))
			throw new IOException("Workspace is not owned by this prompt evaluation: " + workspaceRoot);
	}

	/**
	 * Supplies the workspace lifecycle's persistence, status, and process collaborators.
	 */
	public interface Access
	{
		/**
		 * Verifies and initializes an empty workspace before the owner receipt is written.
		 *
		 * @param workspaceRoot the workspace to initialize
		 * @throws IOException if the workspace is not empty or cannot be initialized
		 */
		void prepareEmptyWorkspace(Path workspaceRoot) throws IOException;

		/**
		 * Publishes the workspace currently owned by one active record.
		 *
		 * @param runPath the evaluation record
		 * @param workspaceRoot the claimed workspace
		 * @throws IOException if the record cannot be updated
		 */
		void recordActiveWorkspace(Path runPath, Path workspaceRoot) throws IOException;

		/**
		 * Clears an active workspace pointer when it still names the completed workspace.
		 *
		 * @param runPath the evaluation record
		 * @param workspaceRoot the terminal workspace
		 * @throws IOException if the record cannot be updated
		 */
		void clearActiveWorkspace(Path runPath, Path workspaceRoot) throws IOException;

		/**
		 * Starts live-status refresh for an exclusively owned workspace.
		 *
		 * @param workspaceRoot the claimed workspace
		 * @param activeLock the held exclusive claim
		 * @return the refresher that must close before the claim is released
		 */
		Heartbeat startHeartbeat(Path workspaceRoot, Lock activeLock);

		/**
		 * Publishes an active evaluator phase.
		 *
		 * @param workspaceRoot the claimed workspace
		 * @param phase the active phase
		 * @throws IOException if the status cannot be retained
		 */
		void publishActiveStatus(Path workspaceRoot, String phase) throws IOException;

		/**
		 * Publishes terminal workspace status.
		 *
		 * @param workspaceRoot the completed workspace
		 * @throws IOException if the terminal status cannot be retained
		 */
		void publishTerminalStatus(Path workspaceRoot) throws IOException;

		/**
		 * Retains an actionable interruption diagnostic for an evaluation record.
		 *
		 * @param runPath the evaluation record
		 * @param diagnostic the interruption diagnostic
		 * @throws IOException if the diagnostic cannot be retained
		 */
		void writeInterruption(Path runPath, String diagnostic) throws IOException;

		/**
		 * Interrupts child runner processes that belong to live evaluators.
		 */
		void interruptActiveProcesses();
	}

	/**
	 * Performs evaluator work while its workspace is exclusively owned.
	 */
	@FunctionalInterface
	public interface Operation
	{
		/**
		 * Runs evaluator work with live-status control.
		 *
		 * @param context the owned workspace context
		 * @return the retained evaluator result
		 * @throws IOException if evaluator work cannot retain its result
		 */
		ObjectNode run(Context context) throws IOException;
	}

	/**
	 * Classifies whether an evaluator result has finished its workspace lifecycle.
	 */
	@FunctionalInterface
	public interface TerminalResult
	{
		/**
		 * Determines whether the result closes its workspace lifecycle.
		 *
		 * @param result the retained evaluator result
		 * @return whether the workspace is terminal
		 */
		boolean isTerminal(ObjectNode result);
	}

	/**
	 * Recovers or rethrows an evaluator failure after its interruption marker is durable.
	 */
	@FunctionalInterface
	public interface FailureResult
	{
		/**
		 * Handles a retained evaluator failure.
		 *
		 * @param failure the evaluator failure
		 * @return the recovery result
		 * @throws IOException if the failure remains terminal for the caller
		 */
		ObjectNode handle(IOException failure) throws IOException;
	}

	/**
	 * Publishes phases and verifies live-status refresh while evaluator work executes.
	 */
	public interface Context
	{
		/**
		 * Publishes the current evaluator phase.
		 *
		 * @param phase the active phase
		 * @throws IOException if the status cannot be retained
		 */
		void publishPhase(String phase) throws IOException;

		/**
		 * Fails foreground work when live-status refresh failed.
		 *
		 * @throws IOException if the refresher failed
		 */
		void verifyHeartbeat() throws IOException;
	}

	/**
	 * Refreshes owned workspace state until closed.
	 */
	public interface Heartbeat extends AutoCloseable
	{
		/**
		 * Verifies a background status refresh has not failed.
		 *
		 * @throws IOException if the refresher failed
		 */
		void verify() throws IOException;

		/**
		 * Stops background status refresh before the workspace claim is released.
		 */
		@Override
		void close();
	}
}
