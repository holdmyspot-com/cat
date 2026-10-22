package com.holdmyspot.cat.common.optimizer.port;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.contract.ToolCall;
import com.holdmyspot.cat.common.runner.HarnessCapacity.Hold;

import java.nio.file.Path;
import java.util.List;

/**
 * How a nested session ended.
 * <p>
 * Only {@link Completed} carries evidence. Every other result is an infrastructure attempt that yields no outcome.
 */
public sealed interface SessionResult
{
	/**
	 * Returns the rate-limit windows that the harness reported during the session.
	 *
	 * @return the windows, empty if the harness reported none
	 */
	List<RateLimitWindow> windows();

	/**
	 * The session ran to the end of its turns, or to its time limit.
	 * <p>
	 * A trial that the time limit cut off in a turn with model output or a running hook is completed with the replies
	 * it gave so far, because CAT judges the timeout evidence instead of retrying the trial. {@code responses} then
	 * holds fewer entries than the request had turns.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param transcript the retained harness event archive
	 * @param responses each turn's assistant messages in order, ending with its final response
	 * @param effects the directory that holds the session's project effects
	 * @param initialProject a copy of the session's project as it was before the first turn, with the files that the
	 *   request added to the source
	 * @param model the model observed in harness events, or {@code null} if the events did not name one
	 * @param reasoning the reasoning configuration observed in harness events, or {@code null} if the events did not
	 *   name one
	 * @param usage the reported token usage, or {@code null} if the harness did not report it
	 * @param windows the rate-limit windows that the harness reported during the session
	 * @param promptSources the {@code PROMPT_SOURCES} measurement value that the harness was observed loading,
	 *   normalized so that staged paths do not appear in it, or {@code none} if the events showed nothing
	 *   loading
	 * @param toolCalls the root agent's calls in invocation order, with absent commands and exit statuses left null
	 * @param hostTools the programs that a trial's agents ran by name, each its name followed by its version or by
	 *   why its version is unknown, in name order; {@code null} for a session that is not a trial
	 * @param outsideSources the MCP servers, web hosts and web search that a trial's agents used, in name order;
	 *   {@code null} for a session that is not a trial
	 */
	record Completed(Path transcript, List<List<String>> responses, Path effects, Path initialProject, String model,
		String reasoning, TokenUsage usage, List<RateLimitWindow> windows, String promptSources, List<ToolCall> toolCalls,
		List<String> hostTools, List<String> outsideSources)
		implements SessionResult
	{
		/**
		 * Creates a completed result.
		 *
		 * @param transcript the retained harness event archive
		 * @param responses each turn's assistant messages in order, ending with its final response
		 * @param effects the directory that holds the session's project effects
		 * @param initialProject a copy of the session's project as it was before the first turn, with the files that
		 *   the request added to the source
		 * @param model the model observed in harness events, or {@code null} if the events did not name one
		 * @param reasoning the reasoning configuration observed in harness events, or {@code null} if the events did
		 *   not name one
		 * @param usage the reported token usage, or {@code null} if the harness did not report it
		 * @param windows the rate-limit windows that the harness reported during the session
		 * @param promptSources the {@code PROMPT_SOURCES} measurement value that the harness was observed loading
		 * @param toolCalls the root agent's calls in invocation order, with absent commands and exit statuses left null
		 * @param hostTools the programs that a trial's agents ran by name, each its name followed by its version or
		 *   by why its version is unknown, in name order; {@code null} for a session that is not a trial
		 * @param outsideSources the MCP servers, web hosts and web search that a trial's agents used, in name order;
		 *   {@code null} for a session that is not a trial
		 * @throws NullPointerException if {@code transcript}, {@code responses}, {@code effects},
		 *   {@code initialProject}, {@code windows}, {@code promptSources} or {@code toolCalls} are null, or a response
		 *   list, message or element of {@code toolCalls}, {@code hostTools} or {@code outsideSources} is null
		 * @throws IllegalArgumentException if {@code promptSources} or an element of {@code hostTools} or
		 *   {@code outsideSources} is blank or spans lines, or {@code hostTools} or {@code outsideSources} is null
		 *   while the other is not
		 */
		public Completed
		{
			requireThat(transcript, "transcript").isNotNull();
			requireThat(responses, "responses").isNotNull();
			requireThat(effects, "effects").isNotNull();
			requireThat(initialProject, "initialProject").isNotNull();
			requireThat(windows, "windows").isNotNull();
			requireThat(promptSources, "promptSources").isNotBlank();
			requireThat(outsideSources == null, "outsideSourcesIsNull").isEqualTo(hostTools == null, "hostToolsIsNull");
			responses = responses.stream().map(List::copyOf).toList();
			windows = List.copyOf(windows);
			toolCalls = List.copyOf(toolCalls);
			if (hostTools != null)
			{
				hostTools = entries(hostTools, "hostTools");
				outsideSources = entries(outsideSources, "outsideSources");
			}
		}

		/**
		 * Creates a completed result of a session that is not a trial.
		 *
		 * @param transcript the retained harness event archive
		 * @param responses each turn's assistant messages in order, ending with its final response
		 * @param effects the directory that holds the session's project effects
		 * @param initialProject a copy of the session's project as it was before the first turn, with the files that
		 *   the request added to the source
		 * @param model the model observed in harness events, or {@code null} if the events did not name one
		 * @param reasoning the reasoning configuration observed in harness events, or {@code null} if the events did
		 *   not name one
		 * @param usage the reported token usage, or {@code null} if the harness did not report it
		 * @param windows the rate-limit windows that the harness reported during the session
		 * @param promptSources the {@code PROMPT_SOURCES} measurement value that the harness was observed loading
		 * @param toolCalls the root agent's calls in invocation order, with absent commands and exit statuses left null
		 * @throws NullPointerException if {@code transcript}, {@code responses}, {@code effects},
		 *   {@code initialProject}, {@code windows}, {@code promptSources} or {@code toolCalls} are null, or a response
		 *   list, message or {@code toolCalls} element is null
		 * @throws IllegalArgumentException if {@code promptSources} is blank
		 */
		public Completed(Path transcript, List<List<String>> responses, Path effects, Path initialProject,
			String model, String reasoning, TokenUsage usage, List<RateLimitWindow> windows, String promptSources,
			List<ToolCall> toolCalls)
		{
			this(transcript, responses, effects, initialProject, model, reasoning, usage, windows, promptSources,
				toolCalls, null, null);
		}

		/**
		 * Returns a copy of a list of entries, each a line of a measurement value.
		 *
		 * @param entries the entries
		 * @param name    the list's name
		 * @return the copy
		 * @throws NullPointerException     if an entry is null
		 * @throws IllegalArgumentException if an entry is blank or spans lines
		 */
		private static List<String> entries(List<String> entries, String name)
		{
			List<String> copy = List.copyOf(entries);
			for (String entry : copy)
			{
				requireThat(entry, name).isNotBlank();
				requireThat(entry.lines().count(), name + ".lines().count()").isEqualTo(1L);
			}
			return copy;
		}
	}

	/**
	 * The session stopped because CAT interrupted it.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param windows the rate-limit windows that the harness reported during the session
	 */
	record Interrupted(List<RateLimitWindow> windows) implements SessionResult
	{
		/**
		 * Creates an interrupted result.
		 *
		 * @param windows the rate-limit windows that the harness reported during the session
		 * @throws NullPointerException if {@code windows} is null
		 */
		public Interrupted
		{
			requireThat(windows, "windows").isNotNull();
			windows = List.copyOf(windows);
		}
	}

	/**
	 * The provider rejected the session because of a rate limit.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param hold the harness's shared hold after the session recorded the rejection
	 * @param windows the rate-limit windows that the harness reported during the session
	 */
	record RateLimited(Hold hold, List<RateLimitWindow> windows) implements SessionResult
	{
		/**
		 * Creates a rate-limited result.
		 *
		 * @param hold the harness's shared hold after the session recorded the rejection
		 * @param windows the rate-limit windows that the harness reported during the session
		 * @throws NullPointerException if any argument is null
		 */
		public RateLimited
		{
			requireThat(hold, "hold").isNotNull();
			requireThat(windows, "windows").isNotNull();
			windows = List.copyOf(windows);
		}
	}

	/**
	 * The provider refused the session's request with HTTP status 403.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param hold the harness's shared hold after the session recorded the refusal
	 * @param afterLongestHold {@code true} if the refusal followed the end of a hold of the longest length
	 * @param windows the rate-limit windows that the harness reported during the session
	 */
	record Refused(Hold hold, boolean afterLongestHold, List<RateLimitWindow> windows) implements SessionResult
	{
		/**
		 * Creates a refused result.
		 *
		 * @param hold the harness's shared hold after the session recorded the refusal
		 * @param afterLongestHold {@code true} if the refusal followed the end of a hold of the longest length
		 * @param windows the rate-limit windows that the harness reported during the session
		 * @throws NullPointerException if {@code hold} or {@code windows} are null
		 */
		public Refused
		{
			requireThat(hold, "hold").isNotNull();
			requireThat(windows, "windows").isNotNull();
			windows = List.copyOf(windows);
		}
	}

	/**
	 * The session ended without replying to every turn for a reason other than an interrupt, rate limit or refusal.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param reason what went wrong, for the infrastructure-failure record
	 * @param windows the rate-limit windows that the harness reported during the session
	 */
	record Failed(String reason, List<RateLimitWindow> windows) implements SessionResult
	{
		/**
		 * Creates a failed result.
		 *
		 * @param reason what went wrong, for the infrastructure-failure record
		 * @param windows the rate-limit windows that the harness reported during the session
		 * @throws NullPointerException if any argument is null
		 * @throws IllegalArgumentException if {@code reason} is blank
		 */
		public Failed
		{
			requireThat(reason, "reason").isNotBlank();
			requireThat(windows, "windows").isNotNull();
			windows = List.copyOf(windows);
		}
	}
}
