package com.holdmyspot.cat.common.optimizer.cli;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.optimizer.cli.PeerTrialProtocol.Launch;
import com.holdmyspot.cat.common.optimizer.port.SessionHandle;
import com.holdmyspot.cat.common.optimizer.port.SessionPort;
import com.holdmyspot.cat.common.optimizer.port.SessionRequest;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;
import com.holdmyspot.cat.common.optimizer.port.TurnObserver;
import com.holdmyspot.cat.common.runner.RetainedTrialUnresumableException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Runs one tracked trial from the installed evaluator's own adapter.
 */
final class EvaluatorTrial
{
	static final String COMMAND = "evaluator-trial";

	/**
	 * Serves the owner's trial through stdin and stdout, with all platform behavior in the installed evaluator.
	 *
	 * @param scope  the installed evaluator's scope
	 * @param input  the owner control pipe
	 * @param output the peer event pipe
	 * @return 0 if the exchange delivered its typed result, or 1 if the result pipe failed
	 */
	static int run(MainOptimizerCommandScope scope, InputStream input, OutputStream output)
	{
		PeerTrialProtocol protocol = new PeerTrialProtocol(scope.json());
		BufferedReader fromOwner = new BufferedReader(new InputStreamReader(input, UTF_8));
		Writer toOwner = new OutputStreamWriter(output, UTF_8);
		try
		{
			JsonNode first = protocol.receive(fromOwner);
			if (first == null)
				throw new IOException("The owner sent no trial");
			Launch launch = protocol.start(first);
			if (launch.harness() != scope.harness())
				throw new IOException("The trial targeted another evaluator");
			SessionPort sessions = scope.evaluatorTrialSessions(launch);
			serve(new Trial(scope, sessions, protocol, fromOwner, toOwner), launch);
			return 0;
		}
		catch (RetainedTrialUnresumableException e)
		{
			return failure(protocol, toOwner, e.getMessage(), true);
		}
		catch (IOException | RuntimeException e)
		{
			return failure(protocol, toOwner, e.getMessage(), false);
		}
	}

	/**
	 * Reports a trial or control failure distinctly from a guarded continuation that cannot resume.
	 *
	 * @param protocol    the trial protocol
	 * @param output      the owner event pipe
	 * @param reason      describes the failure, or null when no explanation is available
	 * @param unresumable whether the broker rejected a retained continuation
	 * @return 0 if the owner received a failure record, or 1 if the pipe failed
	 */
	private static int failure(PeerTrialProtocol protocol, Writer output, String reason, boolean unresumable)
	{
		if (reason == null || reason.isBlank())
			reason = "The evaluator trial failed";
		try
		{
			protocol.send(output, protocol.record("failure").put("unresumable", unresumable).put("reason", reason));
			return 0;
		}
		catch (IOException _)
		{
			return 1;
		}
	}

	/**
	 * Starts and awaits the peer trial while a separate reader accepts owner decisions and interrupts.
	 *
	 * @param trial  holds the exchange and observer
	 * @param launch the original assignment and optional continuation
	 * @throws IOException if the peer or the exchange fails
	 */
	private static void serve(Trial trial, Launch launch) throws IOException
	{
		SessionRequest request = launch.request();
		if (launch.progress() != null)
		{
			trial.progress = launch.progress();
			request = request.withTurnObserver(trial);
		}
		SessionHandle handle = trial.sessions.startTrial(request, launch.requestId(), Optional.ofNullable(launch.claim()));
		Thread control = Thread.ofVirtual().name("evaluator-trial-owner").start(() -> trial.readControl(handle));
		try
		{
			SessionResult result = handle.completion().toCompletableFuture().get();
			IOException problem = trial.controlFailure.get();
			if (problem != null)
				throw problem;
			if (result instanceof SessionResult.Completed completed)
				trial.retain(completed);
			ObjectNode message = trial.protocol.record("result");
			message.set("session", trial.protocol.result(result));
			trial.protocol.send(trial.output, message);
		}
		catch (ExecutionException e)
		{
			if (e.getCause() instanceof IOException problem)
				throw problem;
			throw new IOException("The evaluator could not complete the trial", e.getCause());
		}
		catch (InterruptedException e)
		{
			handle.interrupt();
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while awaiting the evaluator trial", e);
		}
		finally
		{
			control.interrupt();
		}
	}

	/**
	 * Keeps the turn gate in the evaluator while the owner records the decisions.
	 */
	private static final class Trial implements TurnObserver
	{
		private final MainOptimizerCommandScope scope;
		private final SessionPort sessions;
		private final PeerTrialProtocol protocol;
		private final BufferedReader input;
		private final Writer output;
		private final AtomicReference<PendingTurn> pending = new AtomicReference<>();
		private final AtomicReference<IOException> controlFailure = new AtomicReference<>();
		private TurnObserver.Progress progress;

		/**
		 * Creates a trial exchange.
		 *
		 * @param scope    the installed evaluator's scope
		 * @param sessions the installed evaluator's adapter
		 * @param protocol the trial protocol
		 * @param input    the owner records
		 * @param output   the evaluator records
		 */
		private Trial(MainOptimizerCommandScope scope, SessionPort sessions, PeerTrialProtocol protocol,
			BufferedReader input, Writer output)
		{
			this.scope = scope;
			this.sessions = sessions;
			this.protocol = protocol;
			this.input = input;
			this.output = output;
		}

		@Override
		public boolean endAfter(int turn, SessionResult.Completed result) throws IOException
		{
			retain(result);
			PendingTurn decision = new PendingTurn(turn, new CompletableFuture<>());
			pending.set(decision);
			try
			{
				IOException problem = controlFailure.get();
				if (problem != null)
					throw problem;
				ObjectNode message = protocol.record("turn").put("turn", turn);
				message.set("session", protocol.result(result));
				protocol.send(output, message);
				progress = decision.completion().get();
				return progress.endedEarly();
			}
			catch (ExecutionException e)
			{
				throw new IOException("The owner did not decide the evaluator turn", e.getCause());
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while awaiting the owner's turn decision", e);
			}
			finally
			{
				pending.set(null);
			}
		}

		@Override
		public TurnObserver.Progress progress()
		{
			return progress;
		}

		@Override
		public Map<String, Integer> settledAssertions()
		{
			return progress.settledAssertions();
		}

		/**
		 * Retains evidence through the latest turn before publishing its result to the owner.
		 *
		 * @param result the evidence paths
		 * @throws IOException if the native activity cannot be parsed or saved
		 */
		private void retain(SessionResult.Completed result) throws IOException
		{
			PeerTrialEvidence.write(scope.json(), scope.fileReplacer(), result.transcript(),
				sessions.readEvidence(result.transcript()));
		}

		/**
		 * Accepts only turn decisions and interruption requests until the owner closes its pipe.
		 *
		 * @param handle the running evaluator trial
		 */
		private void readControl(SessionHandle handle)
		{
			try
			{
				for (JsonNode message = protocol.receive(input); message != null; message = protocol.receive(input))
				{
					switch (PeerTrialProtocol.type(message))
					{
						case "decision" -> decide(message);
						case "interrupt" -> handle.interrupt();
						default -> throw new IOException("Unknown owner trial control record");
					}
				}
				throw new IOException("The owner disconnected from the evaluator trial");
			}
			catch (IOException | RuntimeException e)
			{
				IOException problem = new IOException("The owner stopped sending trial control records", e);
				controlFailure.set(problem);
				PendingTurn decision = pending.get();
				if (decision != null)
					decision.completion().completeExceptionally(problem);
				try
				{
					handle.interrupt();
				}
				catch (IOException failure)
				{
					problem.addSuppressed(failure);
				}
			}
		}

		/**
		 * Delivers the owner's decision only to the current turn.
		 *
		 * @param message the decision record
		 * @throws IOException if it does not name the turn awaiting a decision
		 */
		private void decide(JsonNode message) throws IOException
		{
			PendingTurn decision = pending.get();
			TurnObserver.Progress answer = protocol.progress(message.path("progress"));
			if (decision == null || answer.completedTurn() != decision.turn() ||
				!decision.completion().complete(answer))
			{
				throw new IOException("The owner answered an unexpected evaluator trial turn");
			}
		}
	}

	/**
	 * One completed turn waiting for the owner to persist and return its decision.
	 *
	 * @param turn       the completed turn
	 * @param completion the owner's decision
	 */
	private record PendingTurn(int turn, CompletableFuture<TurnObserver.Progress> completion)
	{
	}

	/**
	 * Prevents construction.
	 */
	private EvaluatorTrial()
	{
	}
}
