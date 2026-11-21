package com.holdmyspot.cat.common.optimizer.cli;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.JvmScope;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.optimizer.cli.PeerTrialProtocol.Launch;
import com.holdmyspot.cat.common.optimizer.port.SessionHandle;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;
import com.holdmyspot.cat.common.optimizer.port.TurnObserver;
import com.holdmyspot.cat.common.runner.HarnessProcessTermination;
import com.holdmyspot.cat.common.runner.PluginDirectory;
import com.holdmyspot.cat.common.runner.RetainedTrialUnresumableException;
import com.holdmyspot.cat.common.runner.SessionRequestAccess;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The owner-side handle for a trial running in the same-release installed evaluator.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
final class PeerTrialProcess implements SessionHandle
{
	private static final Duration EXIT_LIMIT = Duration.ofSeconds(10);
	private final Process process;
	private final PeerTrialProtocol protocol;
	private final Launch launch;
	private final Writer control;
	private final CompletableFuture<SessionResult> completion = new CompletableFuture<>();
	// Both flags are guarded by the control writer.
	private boolean started;
	private boolean interrupted;

	/**
	 * Starts the installed peer's fixed trial command in the owner's sandbox.
	 *
	 * @param scope    the process dependencies
	 * @param artifact the selected same-release installed evaluator
	 * @param launch   the owner's tracked launch
	 * @return the trial handle
	 * @throws IOException if the installed evaluator cannot be started
	 */
	static SessionHandle start(JvmScope scope, Path artifact, Launch launch) throws IOException
	{
		PeerTrialProtocol protocol = new PeerTrialProtocol(scope.json());
		ObjectNode first = protocol.start(launch);
		String executable = "prompt-optimizer";
		if (Platform.current().isWindows())
			executable += ".cmd";
		Path launcher = artifact.resolve(PluginDirectory.BIN).resolve(executable);
		ProcessBuilder builder = new ProcessBuilder(launcher.toString(), EvaluatorTrial.COMMAND).
			directory(launch.project().toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
		builder.environment().remove(SessionRequestAccess.REQUESTS_VARIABLE);
		builder.environment().remove(SessionRequestAccess.LAUNCHER_VARIABLE);
		builder.environment().remove(SessionRequestAccess.SHELL_VARIABLE);
		PeerTrialProcess launched = new PeerTrialProcess(scope.executableFiles().run(builder), protocol, launch);
		Thread.ofVirtual().name("optimizer-peer-trial").start(() -> launched.exchange(first));
		return launched;
	}

	/**
	 * Owns the child process and its pipes.
	 *
	 * @param process  the installed evaluator process
	 * @param protocol the process protocol
	 * @param launch   the owner's assignment
	 */
	private PeerTrialProcess(Process process, PeerTrialProtocol protocol, Launch launch)
	{
		this.process = process;
		this.protocol = protocol;
		this.launch = launch;
		this.control = new OutputStreamWriter(process.getOutputStream(), UTF_8);
	}

	@Override
	public CompletionStage<SessionResult> completion()
	{
		return completion.minimalCompletionStage();
	}

	@Override
	public void interrupt() throws IOException
	{
		synchronized (control)
		{
			if (interrupted || completion.isDone() || !process.isAlive())
				return;
			interrupted = true;
			if (started)
				protocol.send(control, protocol.record("interrupt"));
		}
	}

	/**
	 * Sends the launch, exchanges observer decisions, and reports the terminal result after the child exits.
	 *
	 * @param first the typed launch record
	 */
	private void exchange(ObjectNode first)
	{
		SessionResult result;
		try
		{
			try (Process _ = process;
				BufferedReader output = new BufferedReader(new InputStreamReader(process.getInputStream(), UTF_8));
				Writer _ = control)
			{
				try
				{
					protocol.send(control, first);
					synchronized (control)
					{
						started = true;
						if (interrupted)
							protocol.send(control, protocol.record("interrupt"));
					}
					result = await(output);
					if (!process.waitFor(EXIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS) || process.exitValue() != 0)
						throw new IOException("The installed evaluator did not exit successfully after returning its trial");
				}
				finally
				{
					if (process.isAlive())
						HarnessProcessTermination.terminateImmediately(process);
				}
			}
			completion.complete(result);
		}
		catch (IOException | RuntimeException e)
		{
			completion.completeExceptionally(e);
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			completion.completeExceptionally(new IOException("Interrupted while awaiting the installed evaluator", e));
		}
	}

	/**
	 * Receives the trial's completed turns and terminal result.
	 *
	 * @param output the evaluator's event pipe
	 * @return the trial's terminal result
	 * @throws IOException if the exchange ends without a valid result
	 */
	private SessionResult await(BufferedReader output) throws IOException
	{
		int previousTurn = 0;
		if (launch.claim() != null)
			previousTurn = Math.max(0, launch.claim().completedTurns() - 1);
		if (launch.progress() != null)
			previousTurn = Math.max(previousTurn, launch.progress().completedTurn());
		for (JsonNode message = protocol.receive(output); message != null; message = protocol.receive(output))
		{
			switch (PeerTrialProtocol.type(message))
			{
				case "turn" ->
				{
					int turn = PeerTrialProtocol.number(message, "turn");
					if (turn != previousTurn + 1 || turn >= launch.request().turns().size())
						throw new IOException("The evaluator reported an unexpected completed turn");
					decide(turn, message.path("session"));
					previousTurn = turn;
				}
				case "result" ->
				{
					SessionResult result = protocol.result(message.path("session"));
					if (result instanceof SessionResult.Completed completed)
						validateEvidencePaths(completed);
					return result;
				}
				case "failure" ->
				{
					String reason = PeerTrialProtocol.text(message, "reason");
					if (message.path("unresumable").booleanValue())
						throw new RetainedTrialUnresumableException(reason);
					throw new IOException(reason);
				}
				default -> throw new IOException("Unknown installed evaluator trial record");
			}
		}
		throw new IOException("The installed evaluator disconnected before reporting its trial result");
	}

	/**
	 * Invokes the owner's observer and acknowledges its durable progress before the evaluator can send the next turn.
	 *
	 * @param turn  the completed turn
	 * @param value the cumulative completed result from the evaluator
	 * @throws IOException if the turn evidence or the owner's decision cannot be used
	 */
	private void decide(int turn, JsonNode value) throws IOException
	{
		if (!(protocol.result(value) instanceof SessionResult.Completed completed) || completed.responses().size() != turn)
			throw new IOException("The evaluator reported invalid completed-turn evidence");
		validateEvidencePaths(completed);
		TurnObserver observer = launch.request().turnObserver().orElseThrow(() ->
			new IOException("The evaluator asked for a turn decision without an observer"));
		boolean ended = observer.endAfter(turn, completed);
		TurnObserver.Progress progress = new TurnObserver.Progress(turn, observer.settledAssertions(), ended);
		ObjectNode reply = protocol.record("decision");
		reply.set("progress", protocol.progress(progress));
		protocol.send(control, reply);
	}

	/**
	 * Checks that reported result paths belong to one session in the owner's results folder for this evaluator.
	 *
	 * @param completed the reported result
	 * @throws IOException if the result points outside that session
	 */
	private void validateEvidencePaths(SessionResult.Completed completed) throws IOException
	{
		Path directory = PeerTrialSessions.resultsFor(launch.results(), launch.request().part()).
			resolve(launch.harness().commandName()).toRealPath();
		Path session = completed.effects().getParent();
		if (session == null || !directory.equals(session.getParent()) ||
			!completed.effects().equals(session.resolve("project")) ||
			!completed.initialProject().equals(session.resolve("initial-project")) ||
			!Files.isDirectory(completed.effects(), LinkOption.NOFOLLOW_LINKS) ||
			!Files.isDirectory(completed.initialProject(), LinkOption.NOFOLLOW_LINKS) ||
			!completed.transcript().getParent().equals(session.resolve("artifacts")) ||
			!completed.transcript().toRealPath().startsWith(session.toRealPath()) ||
			!session.toRealPath().startsWith(directory))
		{
			throw new IOException("The evaluator reported evidence outside its retained trial");
		}
	}
}
