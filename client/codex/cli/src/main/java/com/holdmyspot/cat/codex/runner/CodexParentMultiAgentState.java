package com.holdmyspot.cat.codex.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.codex.rollout.RolloutLineage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
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
 * Only the end of a large rollout is read, so a thread whose latest records lie before it has no recorded state. A
 * missing or unreadable rollout, and a malformed line such as one that Codex is still writing, are not errors: the
 * state stays as the records read before them left it. A rollout that the thread inherited from and that cannot be
 * found or read leaves out its records.
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
	 * The most bytes at the end of a rollout that are read. A thread writes a {@code turn_context} and a full
	 * {@code world_state} record at the start of each turn, and while it waits for the command that starts the nested
	 * process its rollout barely grows, so its latest records lie near the end. Rollouts of long threads reach gigabytes.
	 */
	private static final long MAXIMUM_TAIL_BYTES = 64L * 1024 * 1024;
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
	 * @return the state, or {@link #NONE} if there is no thread, its identifier names no file, or its rollout is missing,
	 *   unreadable or records no state
	 * @throws NullPointerException if {@code json} or {@code codexHome} are null
	 */
	public static CodexParentMultiAgentState read(JsonMapper json, Path codexHome, String threadId)
	{
		requireThat(json, "json").isNotNull();
		requireThat(codexHome, "codexHome").isNotNull();
		if (threadId == null || threadId.isBlank() || threadId.contains("/") || threadId.contains("\\"))
			return NONE;
		try
		{
			Optional<Path> rollout = locate(codexHome, threadId.strip());
			if (rollout.isEmpty())
				return NONE;
			return readRollout(json, rollout.get());
		}
		catch (IOException | UncheckedIOException _)
		{
			// The nested process then gets only the parent's configuration, as it does outside a Codex thread
			return NONE;
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
		Path sessions = codexHome.resolve("sessions");
		if (!Files.isDirectory(sessions))
			return Optional.empty();
		String suffix = "-" + threadId + ".jsonl";
		try (Stream<Path> files = Files.walk(sessions))
		{
			return files.filter(Files::isRegularFile).
				filter(file ->
				{
					String name = file.getFileName().toString();
					return name.startsWith("rollout-") && name.endsWith(suffix);
				}).
				max(Comparator.comparing(file -> file.getFileName().toString()));
		}
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
	private static CodexParentMultiAgentState readRollout(JsonMapper json, Path rollout) throws IOException
	{
		Latest latest = new Latest();
		for (RolloutLineage.Segment segment : RolloutLineage.resolve(json, rollout))
		{
			try
			{
				latest.read(json, segment);
			}
			catch (IOException | UncheckedIOException e)
			{
				if (segment.rollout().equals(rollout))
					throw e;
				// An inherited rollout that cannot be read leaves out its records, as one that cannot be found does
			}
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
		 * Reads the records at the end of one rollout of a thread's history, after those of the rollouts read before it.
		 * Only the end of the part of the rollout that belongs to the history is read, as far as
		 * {@code MAXIMUM_TAIL_BYTES} allows.
		 *
		 * @param json    the JSON mapper
		 * @param segment the rollout, and where the part of it that belongs to the history ends
		 * @throws IOException if the rollout cannot be read
		 */
		void read(JsonMapper json, RolloutLineage.Segment segment) throws IOException
		{
			try (SeekableByteChannel channel = Files.newByteChannel(segment.rollout());
				BufferedReader reader = new BufferedReader(Channels.newReader(channel, UTF_8.newDecoder().
					onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE), -1)))
			{
				long end = Math.min(channel.size(), segment.endByteOffset());
				long start = Math.max(0, end - MAXIMUM_TAIL_BYTES);
				channel.position(start);
				// A read that starts inside a line skips the rest of that line
				if (start > 0)
					reader.readLine();
				for (String line = reader.readLine(); line != null; line = reader.readLine())
				{
					if (!holdsReadRecord(line))
						continue;
					JsonNode record;
					try
					{
						record = json.readTree(line);
					}
					catch (JacksonException _)
					{
						continue;
					}
					// Ordinals only grow, so the first record at or past the end ends the part that the history holds
					if (segment.excludes(record))
						break;
					add(record);
				}
			}
		}

		/**
		 * Applies the version or mode that a record holds.
		 *
		 * @param record the {@code turn_context} or {@code world_state} record
		 */
		private void add(JsonNode record)
		{
			JsonNode payload = record.path("payload");
			switch (record.path("type").asString(""))
			{
				case "turn_context" ->
				{
					version = version(payload.path("multi_agent_version"));
					if (payload.has("multi_agent_mode"))
					{
						JsonNode recorded = payload.get("multi_agent_mode");
						mode = mode(recorded);
						customText = customText(recorded);
					}
				}
				case "world_state" ->
				{
					JsonNode state = payload.path("state");
					boolean full = payload.path("full").asBoolean(false);
					JsonNode section = state.path("multi_agent_mode");
					if (full || section.isNull())
					{
						// A full record without the section, or a patch that removes it, leaves no mode
						mode = Mode.UNKNOWN;
						customText = "";
					}
					if (section.isObject() && section.has("mode"))
					{
						mode = mode(section.get("mode"));
						customText = customText(section.get("mode"));
					}
				}
				default ->
				{
				}
			}
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
