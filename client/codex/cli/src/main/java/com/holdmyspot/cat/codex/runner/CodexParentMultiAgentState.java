package com.holdmyspot.cat.codex.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import com.holdmyspot.cat.codex.rollout.RolloutLineage;
import com.holdmyspot.cat.common.history.TranscriptLines.Line;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The multi-agent state that the Codex thread which starts a nested Codex process actually runs with, as its rollout
 * records it, for {@link CodexConnectionConfiguration} to give the nested process.
 * <p>
 * Codex records the thread's multi-agent version ({@code disabled}, {@code v1} or {@code v2}) in each
 * {@code turn_context} record. It records the mode that the model was told, whether it may start subagents only when
 * asked ({@code explicitRequestOnly}), on its own initiative ({@code proactive}), or under a configured text
 * ({@code custom}), in the {@code multi_agent_mode} section of its {@code world_state} records: a full record replaces
 * the whole state and a later record that is not full patches it. Earlier Codex releases recorded the mode in the
 * {@code turn_context} record instead, which this class also reads. The latest record of each wins.
 * <p>
 * The recorded state includes what the thread's configuration does not show: features enabled on the command line,
 * the version that the model's catalog entry selects, and the proactive mode that an {@code ultra} reasoning effort
 * selects.
 * <p>
 * A thread that was forked from another has a rollout that holds only what the fork recorded itself. Its state is the
 * latest that the records of its history record: those of the rollouts that it inherited its history from, up to
 * where it inherited them, then its own, as {@link RolloutLineage} finds them.
 * <p>
 * A supplied parent must have a complete, readable rollout lineage, which this class reconstructs from the beginning.
 * A thread with a valid rollout that records no multi-agent state has no recorded state. A blank thread identifier
 * means that no Codex thread starts the nested process and therefore needs no rollout.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
public final class CodexParentMultiAgentState
{
	/**
	 * The state of a process that no Codex thread starts, or whose thread recorded none: the nested process gets only
	 * the parent's configuration.
	 */
	public static final CodexParentMultiAgentState NONE = new CodexParentMultiAgentState(null, Mode.UNKNOWN, "");
	/**
	 * The JSON strings that each record this class reads holds, one of which every such rollout line contains whatever
	 * spacing its JSON has.
	 */
	private static final List<String> RECORD_MARKERS = List.of("\"turn_context\"", "\"world_state\"");
	/**
	 * The text that Codex gives the model in the proactive mode, from Codex's bundled model messages
	 * ({@code codex-rs/prompts/src/model_messages/multi_agent.rs}, {@code PROACTIVE_MULTI_AGENT_MODE_TEXT}, in
	 * codex-cli 0.160.0).
	 */
	private static final String PROACTIVE_TEXT = "Proactive multi-agent delegation is active. Any earlier developer " +
		"instruction requiring an explicit user request before spawning sub-agents no longer applies. This mode " +
		"remains active until a later multi-agent mode developer message changes it. User requests override this " +
		"hint.\n" +
		"\n" +
		"If at any point you can parallelize work by delegating tasks to another agent (no matter if you are root or " +
		"subagent), you should do so using collaboration tools if it could save time or improve quality.";
	private static final String MODE_HINT = "multi_agent_mode_hint_text";
	private static final String VERSION_2 = "multi_agent_v2";

	/**
	 * A thread's multi-agent version.
	 */
	private enum Version
	{
		/**
		 * The thread has no multi-agent tools.
		 */
		DISABLED,
		/**
		 * The thread has the first generation of multi-agent tools, which offer no mode.
		 */
		V1,
		/**
		 * The thread has the second generation of multi-agent tools, whose mode Codex tells the model.
		 */
		V2
	}

	/**
	 * A thread's recorded multi-agent mode.
	 */
	private enum Mode
	{
		/**
		 * No mode is recorded, so the nested process's mode follows its own configuration.
		 */
		UNKNOWN,
		/**
		 * The model may start subagents only when asked.
		 */
		EXPLICIT_REQUEST_ONLY,
		/**
		 * The model starts subagents on its own initiative.
		 */
		PROACTIVE,
		/**
		 * The model follows a configured text.
		 */
		CUSTOM
	}

	private final Version version;
	private final Mode mode;
	private final String customText;

	/**
	 * Creates a state.
	 *
	 * @param version    the recorded version, or {@code null} if none is recorded
	 * @param mode       the recorded mode
	 * @param customText the text of a {@link Mode#CUSTOM} mode; otherwise empty
	 */
	private CodexParentMultiAgentState(Version version, Mode mode, String customText)
	{
		assert mode != null;
		assert customText != null;
		this.version = version;
		this.mode = mode;
		this.customText = customText;
	}

	/**
	 * Reads the state that a Codex thread recorded in its rollout.
	 *
	 * @param json      the JSON mapper
	 * @param codexHome the directory that holds the thread's Codex configuration and rollouts
	 * @param threadId  the thread's identifier, from {@code CODEX_THREAD_ID}, or {@code null} or blank if no Codex
	 *                  thread starts the nested process
	 * @return the state, or {@link #NONE} if there is no thread or its valid rollout records no state
	 * @throws NullPointerException if {@code json} or {@code codexHome} are null
	 * @throws IOException if a supplied thread's rollout or required lineage cannot be reconstructed
	 */
	public static CodexParentMultiAgentState read(JsonMapper json, Path codexHome, String threadId) throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(codexHome, "codexHome").isNotNull();
		if (threadId == null || threadId.isBlank())
			return NONE;
		if (threadId.contains("/") || threadId.contains("\\"))
			throw new IOException("Cannot reconstruct required Codex parent thread '" + threadId + "' under " + codexHome,
				new IllegalArgumentException("thread identifier contains a path separator"));
		String requiredThread = threadId.strip();
		Path rollout;
		try
		{
			rollout = locate(codexHome, requiredThread).orElseThrow(() ->
				new IOException("Required Codex parent rollout for thread '" + requiredThread + "' was not found under " +
					codexHome, new NoSuchFileException(codexHome.resolve("sessions").toString())));
		}
		catch (IOException e)
		{
			throw new IOException("Cannot locate required Codex parent thread '" + requiredThread + "' under " + codexHome,
				e);
		}
		try
		{
			return readRolloutRequired(json, rollout);
		}
		catch (UncheckedIOException e)
		{
			throw new IOException("Cannot reconstruct required Codex parent thread '" + requiredThread + "' from " +
				rollout + ": " + e.getMessage(), e.getCause());
		}
		catch (IOException e)
		{
			throw new IOException("Cannot reconstruct required Codex parent thread '" + requiredThread + "' from " +
				rollout + ": " + e.getMessage(), e);
		}
	}

	/**
	 * Finds the rollout of a thread.
	 *
	 * @param codexHome the directory that holds the thread's rollouts
	 * @param threadId  the thread's identifier
	 * @return the rollout, or empty if none exists. If several do, the one whose name sorts last, which is the most
	 *   recently started.
	 * @throws IOException if the sessions directory cannot be listed
	 */
	private static Optional<Path> locate(Path codexHome, String threadId) throws IOException
	{
		String suffix = "-" + threadId + ".jsonl";
		Path found = null;
		for (Path directory : List.of(codexHome.resolve("sessions"), codexHome.resolve("archived_sessions")))
		{
			if (!Files.isDirectory(directory))
				continue;
			try (Stream<Path> files = Files.walk(directory))
			{
				Path candidate = files.filter(Files::isRegularFile).
					filter(file ->
					{
						String name = file.getFileName().toString();
						return name.startsWith("rollout-") && name.endsWith(suffix);
					}).
					max(Comparator.comparing(file -> file.getFileName().toString())).orElse(null);
				if (candidate != null && (found == null || candidate.getFileName().toString().compareTo(
					found.getFileName().toString()) > 0))
					found = candidate;
			}
		}
		return Optional.ofNullable(found);
	}

	/**
	 * Reads the latest recorded version and mode from the end of a thread's history: the thread's own rollout and, for
	 * a thread that was forked, the rollouts that it inherited its history from.
	 *
	 * @param json    the JSON mapper
	 * @param rollout the thread's own rollout
	 * @return the recorded state
	 * @throws IOException if the thread's own rollout cannot be read
	 */
	private static CodexParentMultiAgentState readRolloutRequired(JsonMapper json, Path rollout) throws IOException
	{
		Latest latest = new Latest();
		try (Stream<Line> lines = RolloutLineage.linesRequired(json, rollout))
		{
			Iterator<Line> iterator = lines.iterator();
			while (iterator.hasNext())
			{
				Line line = iterator.next();
				if (!holdsReadRecord(line.text()))
					continue;
				JsonNode record;
				try
				{
					record = json.readTree(line.text());
				}
				catch (JacksonException e)
				{
					throw new IOException(line.transcript() + ":" + line.lineNumber() +
						": multi-agent record is not valid JSON", e);
				}
				latest.addRequired(record, line);
			}
		}
		catch (UncheckedIOException e)
		{
			throw new IOException(e.getMessage(), e.getCause());
		}
		return latest.state();
	}

	/**
	 * Returns whether a rollout line can hold a record that this class reads.
	 *
	 * @param line the line
	 * @return {@code true} if the line holds one of the substrings of those records
	 */
	private static boolean holdsReadRecord(String line)
	{
		for (String marker : RECORD_MARKERS)
		{
			if (line.contains(marker))
				return true;
		}
		return false;
	}

	/**
	 * Returns a recorded version.
	 *
	 * @param recorded the recorded {@code multi_agent_version}
	 * @return the version, or {@code null} if the record names none that this class knows
	 */
	private static Version version(JsonNode recorded)
	{
		return switch (recorded.asString(""))
		{
			case "disabled" -> Version.DISABLED;
			case "v1" -> Version.V1;
			case "v2" -> Version.V2;
			default -> null;
		};
	}

	/**
	 * Returns a recorded mode.
	 *
	 * @param recorded the recorded mode: {@code "explicitRequestOnly"}, {@code "proactive"}, {@code {"custom": text}},
	 *                 or {@code "none"}, which earlier Codex releases recorded for an empty custom text
	 * @return the mode, or {@link Mode#UNKNOWN} if the record names none that this class knows
	 */
	private static Mode mode(JsonNode recorded)
	{
		if (recorded.isObject())
		{
			if (recorded.path("custom").isString())
				return Mode.CUSTOM;
			return Mode.UNKNOWN;
		}
		return switch (recorded.asString(""))
		{
			case "explicitRequestOnly" -> Mode.EXPLICIT_REQUEST_ONLY;
			case "proactive" -> Mode.PROACTIVE;
			case "none" -> Mode.CUSTOM;
			default -> Mode.UNKNOWN;
		};
	}

	/**
	 * Returns the text of a recorded custom mode.
	 *
	 * @param recorded the recorded mode
	 * @return the text, or empty if the mode is not custom or its text is empty
	 */
	private static String customText(JsonNode recorded)
	{
		return recorded.path("custom").asString("");
	}

	/**
	 * Makes a nested process's multi-agent settings, which hold the parent's configured entries, run the recorded
	 * state. The recorded state wins over a configured entry that contradicts it, because it is what the thread actually
	 * runs with:
	 * <ul>
	 *   <li>{@code v2} enables {@code [features.multi_agent_v2]}. A proactive mode sets its
	 *     {@code multi_agent_mode_hint_text} to the text that Codex gives the model in that mode, and a custom mode sets
	 *     it to the recorded text, so that the nested process's model receives the same text whatever its own reasoning
	 *     effort. A mode that only allows subagents when asked removes a configured text, and the nested process's
	 *     mode then follows its own reasoning effort as the parent's did.</li>
	 *   <li>{@code v1} disables a configured {@code multi_agent_v2} and enables {@code multi_agent}. A model whose
	 *     catalog entry selects {@code v2} still runs {@code v2}, as Codex offers no setting that overrides it.</li>
	 *   <li>{@code disabled} sets {@code [agents] enabled = false}, which disables the multi-agent tools whatever the
	 *     model, and disables {@code multi_agent} and a configured {@code multi_agent_v2}.</li>
	 * </ul>
	 * Without a recorded version, the settings stay as configured.
	 *
	 * @param features the nested process's {@code [features]} entries, which this method changes
	 * @param agents   the nested process's {@code [agents]} entries, which this method changes
	 * @throws NullPointerException if any argument is null
	 */
	public void applyTo(ObjectNode features, ObjectNode agents)
	{
		requireThat(features, "features").isNotNull();
		requireThat(agents, "agents").isNotNull();
		if (version == null)
			return;
		switch (version)
		{
			case V2 ->
			{
				JsonNode configured = features.path(VERSION_2);
				ObjectNode table;
				if (configured.isObject())
					table = (ObjectNode) configured;
				else
					table = features.putObject(VERSION_2);
				table.put("enabled", true);
				switch (mode)
				{
					case EXPLICIT_REQUEST_ONLY -> table.remove(MODE_HINT);
					case PROACTIVE -> table.put(MODE_HINT, PROACTIVE_TEXT);
					case CUSTOM -> table.put(MODE_HINT, customText);
					case UNKNOWN ->
					{
					}
				}
			}
			case V1 ->
			{
				disableVersion2(features);
				features.put("multi_agent", true);
				agents.remove("enabled");
			}
			case DISABLED ->
			{
				disableVersion2(features);
				features.put("multi_agent", false);
				agents.put("enabled", false);
			}
		}
	}

	/**
	 * Disables a configured {@code multi_agent_v2} feature in whichever form it takes.
	 *
	 * @param features the nested process's {@code [features]} entries
	 */
	private static void disableVersion2(ObjectNode features)
	{
		JsonNode configured = features.get(VERSION_2);
		if (configured == null)
			return;
		if (configured.isObject())
			((ObjectNode) configured).put("enabled", false);
		else
			features.put(VERSION_2, false);
	}

	/**
	 * The latest version and mode that the records read so far record.
	 */
	private static final class Latest
	{
		private Version version;
		private Mode mode = Mode.UNKNOWN;
		private String customText = "";

		/**
		 * Applies a validated state record.
		 *
		 * @param record the JSON record
		 * @param line its physical source location
		 * @throws IOException if a present state field is invalid
		 */
		private void addRequired(JsonNode record, Line line) throws IOException
		{
			String type = record.path("type").asString("");
			if (!type.equals("turn_context") && !type.equals("world_state"))
				return;
			JsonNode payload = record.get("payload");
			if (payload == null || !payload.isObject())
				throw invalid(line, "state record payload must be an object", null);
			switch (type)
			{
				case "turn_context" -> readTurnContext(payload, line);
				case "world_state" -> readWorldState(payload, line);
				default ->
				{
				}
			}
		}

		/**
		 * Applies a version or legacy mode from a turn context.
		 *
		 * @param payload the context payload
		 * @param line the physical source line
		 * @throws IOException if a present state field is invalid
		 */
		private void readTurnContext(JsonNode payload, Line line) throws IOException
		{
			if (!payload.has("multi_agent_version"))
				version = null;
			else
			{
				version = version(payload.get("multi_agent_version"));
				if (version == null)
					throw invalid(line, "multi_agent_version is unsupported or has the wrong type", null);
			}
			if (payload.has("multi_agent_mode"))
			{
				JsonNode recorded = payload.get("multi_agent_mode");
				validateMode(line, recorded);
				mode = mode(recorded);
				customText = customText(recorded);
			}
		}

		/**
		 * Applies a mode patch or replacement from a world-state record.
		 *
		 * @param payload the world-state payload
		 * @param line the physical source line
		 * @throws IOException if a present state field is invalid
		 */
		private void readWorldState(JsonNode payload, Line line) throws IOException
		{
			JsonNode fullNode = payload.get("full");
			if (fullNode != null && !fullNode.isBoolean())
				throw invalid(line, "world_state.full must be a boolean when present", null);
			boolean full = fullNode != null && fullNode.asBoolean();
			JsonNode state = payload.get("state");
			if (state != null && !state.isObject())
				throw invalid(line, "world_state.state must be an object when present", null);
			JsonNode section = null;
			if (state != null)
				section = state.get("multi_agent_mode");
			if (section != null && !section.isNull() && !section.isObject())
				throw invalid(line, "world_state.multi_agent_mode must be an object or null", null);
			if (section != null && section.isObject())
			{
				if (!section.has("mode"))
				{
					if (full)
					{
						mode = Mode.UNKNOWN;
						customText = "";
					}
					return;
				}
				JsonNode recorded = section.get("mode");
				validateMode(line, recorded);
				mode = mode(recorded);
				customText = customText(recorded);
				return;
			}
			if (full || section != null)
			{
				// A full record without the section, or a patch that removes it, leaves no mode
				mode = Mode.UNKNOWN;
				customText = "";
			}
		}

		/**
		 * Verifies that a recorded mode uses a supported JSON shape and value.
		 *
		 * @param line the source line
		 * @param recorded the mode
		 * @throws IOException if the mode is unsupported
		 */
		private static void validateMode(Line line, JsonNode recorded) throws IOException
		{
			if (recorded.isString())
			{
				if (mode(recorded) == Mode.UNKNOWN)
					throw invalid(line, "multi-agent mode is unsupported", null);
				return;
			}
			if (recorded.isObject() && recorded.has("custom") && recorded.get("custom").isString())
				return;
			throw invalid(line, "multi-agent mode has an invalid type or shape", null);
		}

		/**
		 * Creates a source-located error for an invalid state field.
		 *
		 * @param line the physical source line
		 * @param reason the reason
		 * @param cause the cause, or {@code null}
		 * @return the failure
		 */
		private static IOException invalid(Line line, String reason, Throwable cause)
		{
			String message = line.transcript() + ":" + line.lineNumber() + ": " + reason;
			if (cause == null)
				return new IOException(message);
			return new IOException(message, cause);
		}

		/**
		 * Returns the state that the records read so far record.
		 *
		 * @return the state, or {@link #NONE} if they record none
		 */
		CodexParentMultiAgentState state()
		{
			if (version == null && mode == Mode.UNKNOWN)
				return NONE;
			return new CodexParentMultiAgentState(version, mode, customText);
		}
	}
}
