package io.github.cowwoc.cat.common.optimizer.adapter;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.ToolCall;
import io.github.cowwoc.cat.common.optimizer.evidence.SessionEvidence;
import io.github.cowwoc.cat.common.optimizer.port.RateLimitWindow;
import io.github.cowwoc.cat.common.optimizer.port.TokenUsage;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads what a session's harness events say about the model, token usage, rate limits, errors, root tool calls,
 * and whether the model has answered the turn in progress.
 * <p>
 * A subclass reads one harness's event format and records each observation through the protected setters.
 * Unrecognized lines are ignored.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe. {@link #read(JsonNode)} runs while the parser holds its lock.
 */
public abstract class HarnessEvents
{
	private final JsonMapper json;
	private final Object lock = new Object();
	private String model;
	private String reasoning;
	private TokenUsage usage;
	private List<RateLimitWindow> windows = List.of();
	private Rejection rejection;
	private String error;
	private List<ToolCall> toolCalls = List.of();
	private SessionEvidence evidence = new SessionEvidence(List.of());
	private boolean outputProduced;
	private final Set<String> runningHooks = new HashSet<>();

	/**
	 * Creates a parser that has seen no events.
	 *
	 * @param json the JSON mapper
	 * @throws NullPointerException if {@code json} is null
	 */
	protected HarnessEvents(JsonMapper json)
	{
		requireThat(json, "json").isNotNull();
		this.json = json;
	}

	/**
	 * Absorbs one harness event line.
	 *
	 * @param line the line. A line that is not a JSON object is ignored.
	 * @throws NullPointerException if {@code line} is null
	 */
	public final void accept(String line)
	{
		JsonNode event = EventLine.parse(json, line);
		if (event == null)
			return;
		synchronized (lock)
		{
			read(event);
		}
	}

	/**
	 * Reads one harness event.
	 *
	 * @param event a JSON object
	 */
	protected abstract void read(JsonNode event);

	/**
	 * Returns the model that the session started with.
	 *
	 * @return the model, or {@code null} if no event named one
	 */
	public final String model()
	{
		synchronized (lock)
		{
			return model;
		}
	}

	/**
	 * Records the model that the session started with.
	 *
	 * @param model the model, or {@code null} if the events did not name one
	 */
	protected final void setModel(String model)
	{
		this.model = model;
	}

	/**
	 * Returns the reasoning effort that the session started with.
	 *
	 * @return the effort, or {@code null} if no event named one
	 */
	public final String reasoning()
	{
		synchronized (lock)
		{
			return reasoning;
		}
	}

	/**
	 * Records the reasoning effort that the session started with.
	 *
	 * @param reasoning the effort, or {@code null} if the events did not name one
	 */
	protected final void setReasoning(String reasoning)
	{
		this.reasoning = reasoning;
	}

	/**
	 * Returns the session's cumulative token usage.
	 *
	 * @return the usage, or {@code null} if no event reported any
	 */
	public final TokenUsage usage()
	{
		synchronized (lock)
		{
			return usage;
		}
	}

	/**
	 * Records the session's cumulative token usage.
	 *
	 * @param usage the usage
	 * @throws NullPointerException if {@code usage} is null
	 */
	protected final void setUsage(TokenUsage usage)
	{
		requireThat(usage, "usage").isNotNull();
		this.usage = usage;
	}

	/**
	 * Returns the latest rate-limit windows.
	 *
	 * @return the windows, in the order that the harness's format defines
	 */
	public final List<RateLimitWindow> windows()
	{
		synchronized (lock)
		{
			return windows;
		}
	}

	/**
	 * Records the latest rate-limit windows.
	 *
	 * @param windows the windows
	 * @throws NullPointerException if {@code windows} is null
	 */
	protected final void setWindows(List<RateLimitWindow> windows)
	{
		requireThat(windows, "windows").isNotNull();
		this.windows = List.copyOf(windows);
	}

	/**
	 * Returns the root agent's tool calls.
	 *
	 * @return the calls in invocation order, with unreported commands and exit statuses left null
	 */
	public final List<ToolCall> toolCalls()
	{
		synchronized (lock)
		{
			return toolCalls;
		}
	}

	/**
	 * Records the root agent's tool calls.
	 *
	 * @param toolCalls the calls in invocation order
	 * @throws NullPointerException if {@code toolCalls} or any element is null
	 */
	protected final void setToolCalls(List<ToolCall> toolCalls)
	{
		this.toolCalls = List.copyOf(toolCalls);
	}

	/**
	 * Returns the recorded tools, hooks and assistant positions, including child activity.
	 *
	 * @return the activity in invocation order, without streaming fragments or session instructions
	 */
	public final SessionEvidence evidence()
	{
		synchronized (lock)
		{
			return evidence;
		}
	}

	/**
	 * Records the tools, hooks and assistant positions observed so far.
	 *
	 * @param evidence the activity in invocation order
	 * @throws NullPointerException if {@code evidence} is null
	 */
	protected final void setEvidence(SessionEvidence evidence)
	{
		requireThat(evidence, "evidence").isNotNull();
		this.evidence = evidence;
	}

	/**
	 * Returns the rate-limit rejection that the events reported.
	 *
	 * @return the rejection, or empty if none was reported
	 */
	public final Optional<Rejection> rejection()
	{
		synchronized (lock)
		{
			return Optional.ofNullable(rejection);
		}
	}

	/**
	 * Records a rate-limit rejection.
	 *
	 * @param rejection the rejection
	 * @throws NullPointerException if {@code rejection} is null
	 */
	protected final void setRejection(Rejection rejection)
	{
		requireThat(rejection, "rejection").isNotNull();
		this.rejection = rejection;
	}

	/**
	 * Returns the error that the events reported, other than a rate-limit rejection.
	 *
	 * @return a description of the error that omits the harness's error text, or {@code null} if no event reported
	 *   an error
	 */
	public final String error()
	{
		synchronized (lock)
		{
			return error;
		}
	}

	/**
	 * Records an error other than a rate-limit rejection.
	 *
	 * @param error a description of the error that omits the harness's error text
	 * @throws NullPointerException if {@code error} is null
	 */
	protected final void setError(String error)
	{
		requireThat(error, "error").isNotNull();
		this.error = error;
	}

	/**
	 * Starts a new turn: the model has produced no output in it yet. A hook that is still running stays running.
	 */
	public final void startTurn()
	{
		synchronized (lock)
		{
			outputProduced = false;
		}
	}

	/**
	 * Indicates whether the model produced any output since the latest {@link #startTurn()}. Output is a reasoning
	 * item, a reply or a part of one, a tool or command item, or reported token usage. A user message, a thread or
	 * turn status and a rate-limit update are not output.
	 *
	 * @return {@code true} if the model produced output
	 */
	public final boolean outputProduced()
	{
		synchronized (lock)
		{
			return outputProduced;
		}
	}

	/**
	 * Records that the model produced output in the turn in progress.
	 */
	protected final void setOutputProduced()
	{
		this.outputProduced = true;
	}

	/**
	 * Indicates whether a hook has started and not yet completed.
	 *
	 * @return {@code true} if a hook is running
	 */
	public final boolean hookRunning()
	{
		synchronized (lock)
		{
			return !runningHooks.isEmpty();
		}
	}

	/**
	 * Records that a hook started.
	 *
	 * @param id the hook's identifier, which its completion repeats
	 * @throws NullPointerException if {@code id} is null
	 */
	protected final void setHookStarted(String id)
	{
		requireThat(id, "id").isNotNull();
		runningHooks.add(id);
	}

	/**
	 * Records that a hook completed.
	 *
	 * @param id the identifier that the hook's start reported
	 * @throws NullPointerException if {@code id} is null
	 */
	protected final void setHookCompleted(String id)
	{
		requireThat(id, "id").isNotNull();
		runningHooks.remove(id);
	}

	/**
	 * A rate-limit rejection.
	 *
	 * @param resetsAt when the provider accepts requests again, or {@code null} if the events did not say
	 */
	public record Rejection(Instant resetsAt)
	{
	}
}
