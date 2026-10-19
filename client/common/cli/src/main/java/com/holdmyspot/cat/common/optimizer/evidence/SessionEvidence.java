package com.holdmyspot.cat.common.optimizer.evidence;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The complete ordered activity of a trial, with root assistant text supplied separately to the judge.
 * <p>
 * A harness can shorten a tool's output before its model receives it, so the evidence also holds every tool result as
 * the trial's agents received it. A line of a tool's output that appears in no received result never reached an agent.
 *
 * @param entries  the tool calls, hook outcomes and assistant positions recorded by the harness
 * @param received every tool result as the trial's agents received it, in no particular order. A harness that wraps
 *                 or encodes a result supplies it both as received and decoded.
 */
public record SessionEvidence(List<Entry> entries, List<String> received)
{
	/**
	 * The actor name of the trial's main agent, whose response text is supplied separately.
	 */
	public static final String ROOT_AGENT = "root";

	/**
	 * Creates a trial's activity.
	 *
	 * @param entries  the recorded entries, in event order within each turn
	 * @param received every tool result as the trial's agents received it, in no particular order
	 * @throws NullPointerException if any of the arguments, or any of their elements, are null
	 */
	public SessionEvidence
	{
		requireThat(entries, "entries").isNotNull();
		requireThat(received, "received").isNotNull();
		entries = List.copyOf(entries);
		received = List.copyOf(received);
	}

	/**
	 * Creates the activity of a trial whose agents received every recorded tool output whole.
	 *
	 * @param entries the recorded entries, in event order within each turn
	 * @throws NullPointerException if {@code entries} or any entry is null
	 */
	public SessionEvidence(List<Entry> entries)
	{
		this(entries, outputs(entries));
	}

	/**
	 * Returns the output of every tool call.
	 *
	 * @param entries the recorded entries
	 * @return the outputs, in entry order
	 * @throws NullPointerException if {@code entries} or any entry is null
	 */
	private static List<String> outputs(List<Entry> entries)
	{
		requireThat(entries, "entries").isNotNull();
		List<String> outputs = new ArrayList<>();
		for (Entry entry : entries)
		{
			if (entry instanceof Tool tool)
				outputs.add(tool.output());
		}
		return outputs;
	}

	/**
	 * Returns every line of the received tool results, without trailing whitespace.
	 *
	 * @return the lines
	 */
	public Set<String> receivedLines()
	{
		Set<String> lines = new HashSet<>();
		for (String result : received)
		{
			for (String line : result.split("\n", -1))
				lines.add(line.stripTrailing());
		}
		return lines;
	}

	/**
	 * An activity entry with its turn and actor. The root actor is named {@code root}; other names identify children.
	 */
	public sealed interface Entry
	{
		/**
		 * Returns the one-based trial turn.
		 *
		 * @return the turn
		 */
		int turn();

		/**
		 * Returns the root name or child identity.
		 *
		 * @return the actor
		 */
		String agent();
	}

	/**
	 * A tool call with its entire recorded input and output. The output is the harness's record of what the tool
	 * printed, which can be longer than the result that the agent received.
	 *
	 * @param turn the one-based turn
	 * @param agent the root name or child identity
	 * @param tool the tool name
	 * @param input the command or tool arguments
	 * @param command {@code true} if the call ran {@code input} as a shell command
	 * @param exitStatus the reported exit status, or {@code null} when absent
	 * @param output the complete output
	 */
	public record Tool(int turn, String agent, String tool, String input, boolean command, Integer exitStatus,
		String output) implements Entry
	{
		/**
		 * Creates a tool call.
		 *
		 * @param turn the one-based turn
		 * @param agent the root name or child identity
		 * @param tool the tool name
		 * @param input the command or tool arguments
		 * @param command {@code true} if the call ran {@code input} as a shell command
		 * @param exitStatus the reported exit status, or {@code null} when absent
		 * @param output the complete output
		 * @throws NullPointerException if {@code agent}, {@code tool}, {@code input} or {@code output} are null
		 * @throws IllegalArgumentException if {@code turn} is less than 1, or {@code agent} or {@code tool} are blank
		 */
		public Tool
		{
			requireThat(turn, "turn").isGreaterThanOrEqualTo(1);
			requireThat(agent, "agent").isNotBlank();
			requireThat(tool, "tool").isNotBlank();
			requireThat(input, "input").isNotNull();
			requireThat(output, "output").isNotNull();
		}
	}

	/**
	 * A hook's reported outcome, with no hook output or injected prompt text.
	 *
	 * @param turn the one-based turn
	 * @param agent the root name or child identity
	 * @param hook the hook name
	 * @param status its recorded outcome
	 */
	public record Hook(int turn, String agent, String hook, String status) implements Entry
	{
		/**
		 * Creates a hook outcome.
		 *
		 * @param turn the one-based turn
		 * @param agent the root name or child identity
		 * @param hook the hook name
		 * @param status its recorded outcome
		 * @throws NullPointerException if {@code agent}, {@code hook} or {@code status} are null
		 * @throws IllegalArgumentException if {@code turn} is less than 1, or any string argument is blank
		 */
		public Hook
		{
			requireThat(turn, "turn").isGreaterThanOrEqualTo(1);
			requireThat(agent, "agent").isNotBlank();
			requireThat(hook, "hook").isNotBlank();
			requireThat(status, "status").isNotBlank();
		}
	}

	/**
	 * An assistant message's position, with child text retained because it is not supplied among root responses.
	 *
	 * @param turn the one-based turn
	 * @param agent the root name or child identity
	 * @param position the one-based response position within the actor's turn
	 * @param text the child text, or an empty string for a root response marker
	 */
	public record Assistant(int turn, String agent, int position, String text) implements Entry
	{
		/**
		 * Creates an assistant position.
		 *
		 * @param turn the one-based turn
		 * @param agent the root name or child identity
		 * @param position the one-based response position within the actor's turn
		 * @param text the child text, or an empty string for a root response marker
		 * @throws NullPointerException if {@code agent} or {@code text} are null
		 * @throws IllegalArgumentException if {@code turn} or {@code position} are less than 1, or {@code agent} is blank
		 */
		public Assistant
		{
			requireThat(turn, "turn").isGreaterThanOrEqualTo(1);
			requireThat(agent, "agent").isNotBlank();
			requireThat(position, "position").isGreaterThanOrEqualTo(1);
			requireThat(text, "text").isNotNull();
		}
	}

	/**
	 * Renders every entry by turn without repeating root response text. Each tool output is described by its size
	 * rather than copied, so that the activity stays short however much a trial prints; for a command, the description
	 * also says whether the agent received the whole output.
	 *
	 * @return the trial activity
	 */
	public String render()
	{
		Set<String> receivedLines = receivedLines();
		StringBuilder result = new StringBuilder(128);
		if (entries.stream().noneMatch(entry -> entry instanceof Tool || entry instanceof Hook))
			result.append("No tool or hook activity was recorded.\n");
		Map<Integer, List<Entry>> turns = new TreeMap<>();
		for (Entry entry : entries)
			turns.computeIfAbsent(entry.turn(), _ -> new ArrayList<>()).add(entry);
		for (Map.Entry<Integer, List<Entry>> turn : turns.entrySet())
		{
			result.append("Turn ").append(turn.getKey()).append('\n');
			for (Entry entry : turn.getValue())
			{
				result.append("Actor: ");
				if (!entry.agent().equals(ROOT_AGENT))
					result.append("child ");
				result.append(entry.agent()).append('\n');
				switch (entry)
				{
					case Tool tool -> appendTool(result, tool, receivedLines);
					case Hook hook -> result.append("Hook: ").append(hook.hook()).append("; status: ").
						append(hook.status()).append('\n');
					case Assistant assistant ->
					{
						result.append("Assistant response position: ").append(assistant.position()).append('\n');
						if (!assistant.agent().equals(ROOT_AGENT) && !assistant.text().isEmpty())
							appendText(result, assistant.text());
					}
				}
				result.append('\n');
			}
		}
		return result.toString();
	}

	/**
	 * Appends a tool call's name, input and exit status, and the size of its output.
	 *
	 * @param result        the destination
	 * @param tool          the call
	 * @param receivedLines every line of the received tool results, without trailing whitespace
	 */
	private static void appendTool(StringBuilder result, Tool tool, Set<String> receivedLines)
	{
		result.append("Tool: ").append(tool.tool()).append("\nInput:\n");
		appendText(result, tool.input());
		result.append("Exit status: ");
		if (tool.exitStatus() == null)
			result.append("not reported");
		else
			result.append(tool.exitStatus());
		result.append("\nOutput: ");
		String output = tool.output();
		if (output.isBlank())
		{
			result.append("none\n");
			return;
		}
		result.append(output.codePointCount(0, output.length())).append(" characters");
		if (tool.command())
		{
			result.append("; the agent received ");
			if (isReceived(output, receivedLines))
				result.append("all of it");
			else
				result.append("only part of it");
		}
		result.append('\n');
	}

	/**
	 * Indicates whether every non-blank line of an output appears among the received lines.
	 *
	 * @param output        a tool's output
	 * @param receivedLines every line of the received tool results, without trailing whitespace
	 * @return {@code true} if the agent received the whole output
	 */
	private static boolean isReceived(String output, Set<String> receivedLines)
	{
		for (String line : output.split("\n", -1))
		{
			String stripped = line.stripTrailing();
			if (!stripped.isEmpty() && !receivedLines.contains(stripped))
				return false;
		}
		return true;
	}

	/**
	 * Appends text without modifying it, adding a separator newline when it has none.
	 *
	 * @param result the destination
	 * @param text the exact recorded text
	 */
	private static void appendText(StringBuilder result, String text)
	{
		result.append(text);
		if (!text.endsWith("\n"))
			result.append('\n');
	}
}
