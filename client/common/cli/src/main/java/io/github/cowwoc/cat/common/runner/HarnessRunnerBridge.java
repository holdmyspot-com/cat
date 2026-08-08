package io.github.cowwoc.cat.common.runner;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.node.ObjectNode;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;


/**
 * Forwards line-delimited JSON requests to one persistent harness process and writes each response.
 */
public final class HarnessRunnerBridge
{
	/**
	 * Starts one session and serves turn and completion messages until completion. Native events are written once as
	 * {@code native-event} responses; the terminal response records only the final status and error.
	 *
	 * @param starter starts the isolated persistent session
	 *
	 * @param input the JSONL client input
	 *
	 * @param output the JSONL client output
	 *
	 * @return the run that completed the protocol
	 *
	 * @throws IOException if the bridge protocol, harness, or stream fails
	 */
	public static HarnessRun serve(HarnessRunStarter starter, Reader input, Writer output) throws IOException
	{
		return serve(starter, null, true, input, output);
	}

	/**
	 * Starts one session and retains the complete JSONL bridge transcript at the requested path.
	 *
	 * @param starter starts the isolated persistent session
	 *
	 * @param input the JSONL client input
	 *
	 * @param output the durable JSONL client-output destination
	 *
	 * @return the run that completed the protocol
	 *
	 * @throws IOException if the bridge protocol, harness, or transcript I/O fails
	 */
	public static HarnessRun serve(HarnessRunStarter starter, Reader input, Path output) throws IOException
	{
		return serve(starter, null, true, input, output);
	}

	/**
	 * Starts one session and serves JSONL messages while applying owned-run lifecycle transitions.
	 *
	 * @param starter starts the isolated persistent session
	 *
	 * @param manager the run manager, or {@code null} when the caller owns lifecycle transitions
	 *
	 * @param keepRun whether a completed run should be retained
	 *
	 * @param input the JSONL client input
	 *
	 * @param output the JSONL client output
	 *
	 * @return the run that completed the protocol
	 *
	 * @throws IOException if the bridge protocol, harness, lifecycle, or stream fails
	 */
	@SuppressWarnings("PMD.CloseResource")
	public static HarnessRun serve(HarnessRunStarter starter, HarnessRunManager manager, boolean keepRun, Reader input,
		Writer output) throws IOException
	{
		requireThat(starter, "starter").isNotNull();
		requireThat(input, "input").isNotNull();
		requireThat(output, "output").isNotNull();
		HarnessRun run = null;
		boolean completed = false;
		int turnNumber = 0;
		Future<?> activeTurn = null;
		CountDownLatch activeTurnStarted = null;
		ExecutorService turnExecutor = Executors.newSingleThreadExecutor();
		try
		{
			try (HarnessRunSession session = starter.start())
			{
				run = session.run();
				if (manager != null)
					manager.markIdle(run);
				write(session.scope(), output, event(session.scope(), "started").put("runId", session.run().id()));
				BufferedReader lines = new BufferedReader(input);
				String line = lines.readLine();
				while (line != null)
				{
					JsonNode request = read(session.scope(), line);
					String type = requiredText(request, "type");
					if (type.equals("turn"))
					{
						awaitActiveTurn(activeTurn);
						String prompt = requiredText(request, "prompt");
						int currentTurn = turnNumber + 1;
						CountDownLatch turnStarted = new CountDownLatch(1);
						activeTurn = turnExecutor.submit(() ->
						{
							turnStarted.countDown();
							if (manager != null)
								manager.markWorking(session.run());
							HarnessProcessResult result = session.sendTurn(prompt, nativeEvent ->
							{
								if (manager != null)
									manager.appendTurnEvent(session.run(), currentTurn, nativeEvent);
								forwardNativeEvent(session, output, nativeEvent);
							});
							if (manager != null)
								manager.writeTurnOutput(session.run(), currentTurn, result);
							if (!result.completed())
								throw nativeTurnFailure(result);
							if (manager != null)
								manager.markIdle(session.run());
							ObjectNode response = event(session.scope(), "turn-completed");
							response.put("runId", session.run().id());
							response.put("exitCode", result.exitCode());
							response.put("error", result.standardError());
							response.put("assistant_response", result.assistantResponse());
							write(session.scope(), output, response);
							return null;
						});
						activeTurnStarted = turnStarted;
						turnNumber = currentTurn;
					}
					else if (type.equals("steer"))
					{
						if (activeTurn == null || activeTurn.isDone())
							throw new IOException("Bridge has no active turn to steer");
						awaitRequestedTurnStart(request, activeTurnStarted);
						session.steer(requiredText(request, "prompt"));
						write(session.scope(), output, event(session.scope(), "steered").put("runId", session.run().
				id()));
					}
					else if (type.equals("interrupt"))
					{
						if (activeTurn == null || activeTurn.isDone())
							throw new IOException("Bridge has no active turn to interrupt");
						awaitRequestedTurnStart(request, activeTurnStarted);
						session.interrupt();
						write(session.scope(), output, event(session.scope(), "interrupted").put("runId", session.run().
				id()));
					}
					else if (type.equals("complete"))
					{
						awaitActiveTurn(activeTurn);
						write(session.scope(), output, event(session.scope(), "completed").put("runId", session.run().
				id()));
						completed = true;
						break;
					}
					else
						throw new IOException("Unsupported bridge message type: " + type);
					line = lines.readLine();
				}
				if (!completed)
				{
					awaitActiveTurn(activeTurn);
					throw new IOException("Bridge input ended without a complete message");
				}
			}
			if (manager != null)
			{
				manager.markCompleted(run);
				if (!keepRun)
					manager.deleteRun(run);
			}
			return run;
		}
		catch (IOException e)
		{
			if (manager != null && run != null)
			{
				String diagnostic = e.getMessage();
				if (diagnostic == null)
					diagnostic = e.getClass().getSimpleName();
				manager.fail(run, diagnostic);
			}
			throw e;
		}
		finally
		{
			turnExecutor.shutdownNow();
		}
	}

	/**
	 * Waits for the active turn so a later bridge request observes its terminal outcome.
	 *
	 * @param activeTurn the active turn, or {@code null} when none was started
	 *
	 * @throws IOException if the turn failed or this thread was interrupted
	 */
	private static void awaitActiveTurn(Future<?> activeTurn) throws IOException
	{
		if (activeTurn == null)
			return;
		try
		{
			activeTurn.get();
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while waiting for the active harness turn", e);
		}
		catch (ExecutionException e)
		{
			Throwable cause = e.getCause();
			if (cause instanceof IOException failure)
				throw failure;
			throw new IOException("Active harness turn failed", cause);
		}
	}

	/**
	 * Waits for the currently submitted turn only when a test explicitly requests an active-turn control point.
	 *
	 * @param request the steering or interruption request
	 *
	 * @param turnStarted the current turn's start signal
	 *
	 * @throws IOException if the requested control point is unsupported or the wait is interrupted
	 */
	private static void awaitRequestedTurnStart(JsonNode request, CountDownLatch turnStarted) throws IOException
	{
		assert request != null;
		if (!request.has("after"))
			return;
		if (!"turn-started".equals(requiredText(request, "after")))
			throw new IOException("Unsupported bridge control point: " + requiredText(request, "after"));
		if (turnStarted == null)
			throw new IOException("Bridge has no submitted turn start to await");
		try
		{
			turnStarted.await();
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while waiting for the active harness turn to start", e);
		}
	}

	/**
	 * Starts one managed session and retains the complete JSONL bridge transcript at the requested path.
	 *
	 * @param starter starts the isolated persistent session
	 *
	 * @param manager the run manager, or {@code null} when the caller owns lifecycle transitions
	 *
	 * @param keepRun whether a completed run should be retained
	 *
	 * @param input the JSONL client input
	 *
	 * @param output the durable JSONL client-output destination
	 *
	 * @return the run that completed the protocol
	 *
	 * @throws IOException if the bridge protocol, harness, lifecycle, or transcript I/O fails
	 */
	public static HarnessRun serve(HarnessRunStarter starter, HarnessRunManager manager, boolean keepRun, Reader input,
		Path output) throws IOException
	{
		requireThat(output, "output").isNotNull();
		Path parent = output.toAbsolutePath().normalize().getParent();
		if (parent == null)
			throw new IOException("Bridge transcript has no parent directory: " + output);
		Files.createDirectories(parent);
		try (Writer transcript = Files.newBufferedWriter(output, StandardCharsets.UTF_8))
		{
			return serve(starter, manager, keepRun, input, transcript);
		}
	}

	/**
	 * Prevents construction of utility class instances.
	 */
	private HarnessRunnerBridge()
	{
	}

	/**
	 * Converts a native turn that ended without a terminal result into the bridge's caller-visible failure.
	 *
	 * @param result the completed native turn result
	 *
	 * @return the failure that preserves the native exit status and safe diagnostic
	 */
	private static IOException nativeTurnFailure(HarnessProcessResult result)
	{
		assert result != null;
		String diagnostic = "Native harness ended before completing the requested turn (exit status " +
			result.exitCode() + ")";
		if (!result.standardError().isBlank())
			diagnostic += "; inspect the retained turn stderr artifact for details";
		return new IOException(diagnostic);
	}

	/**
	 * Parses one JSONL request.
	 *
	 * @param scope the live session scope
	 *
	 * @param line the request line
	 *
	 * @return the parsed object
	 *
	 * @throws IOException if the line is not a JSON object
	 */
	private static JsonNode read(HarnessRunnerScope scope, String line) throws IOException
	{
		assert scope != null;
		assert line != null;
		try
		{
			JsonNode result = scope.json().readTree(line);
			if (!result.isObject())
				throw new IOException("Bridge message must be a JSON object");
			return result;
		}
		catch (JacksonException e)
		{
			throw new IOException("Invalid bridge JSON", e);
		}
	}

	/**
	 * Gets a required non-blank string property.
	 *
	 * @param object the request object
	 *
	 * @param name the property name
	 *
	 * @return the property value
	 *
	 * @throws IOException if the property is absent or invalid
	 */
	private static String requiredText(JsonNode object, String name) throws IOException
	{
		assert object != null;
		assert name != null;
		JsonNode value = object.path(name);
		if (!value.isString() || value.stringValue().isBlank())
			throw new IOException("Bridge message requires a non-blank " + name);
		return value.stringValue();
	}

	/**
	 * Creates a bridge response object.
	 *
	 * @param scope the live session scope
	 *
	 * @param type the response type
	 *
	 * @return the response object
	 */
	private static ObjectNode event(HarnessRunnerScope scope, String type)
	{
		assert scope != null;
		assert type != null;
		return scope.json().createObjectNode().put("type", type);
	}

	/**
	 * Writes one native event before its enclosing turn reaches completion.
	 *
	 * @param session the live harness session
	 *
	 * @param output the JSONL client output
	 *
	 * @param nativeEvent the raw native event line
	 *
	 * @throws IOException if client output fails
	 */
	private static void forwardNativeEvent(HarnessRunSession session, Writer output, String nativeEvent)
		throws IOException
	{
		assert session != null;
		assert output != null;
		assert nativeEvent != null;
		ObjectNode response = event(session.scope(), "native-event");
		response.put("runId", session.run().id());
		response.put("event", nativeEvent);
		write(session.scope(), output, response);
	}

	/**
	 * Writes and flushes one JSONL response.
	 *
	 * @param scope the live session scope
	 *
	 * @param output the output stream
	 *
	 * @param event the response object
	 *
	 * @throws IOException if output fails
	 */
	private static void write(HarnessRunnerScope scope, Writer output, ObjectNode event) throws IOException
	{
		assert scope != null;
		assert output != null;
		assert event != null;
		synchronized (output)
		{
			output.write(scope.json().writer().without(SerializationFeature.INDENT_OUTPUT).writeValueAsString(event));
			output.write('\n');
			output.flush();
		}
	}
}
