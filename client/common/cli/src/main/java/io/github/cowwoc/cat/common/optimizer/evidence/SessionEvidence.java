package io.github.cowwoc.cat.common.optimizer.evidence;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The complete ordered activity of a trial, with root assistant text supplied separately to the judge.
 *
 * @param entries the tool calls, hook outcomes and assistant positions recorded by the harness
 */
public record SessionEvidence(List<Entry> entries)
{
	/**
	 * The actor name of the trial's main agent, whose response text is supplied separately.
	 */
	public static final String ROOT_AGENT = "root";

	/**
	 * Creates a trial's activity.
	 *
	 * @param entries the recorded entries, in event order within each turn
	 * @throws NullPointerException if {@code entries} or any entry is null
	 */
	public SessionEvidence
	{
		requireThat(entries, "entries").isNotNull();
		entries = List.copyOf(entries);
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
	 * A tool call with its entire recorded input and output.
	 *
	 * @param turn the one-based turn
	 * @param agent the root name or child identity
	 * @param tool the tool name
	 * @param input the command or tool arguments
	 * @param exitStatus the reported exit status, or {@code null} when absent
	 * @param output the complete output
	 */
	public record Tool(int turn, String agent, String tool, String input, Integer exitStatus, String output)
		implements Entry
	{
		/**
		 * Creates a tool call.
		 *
		 * @param turn the one-based turn
		 * @param agent the root name or child identity
		 * @param tool the tool name
		 * @param input the command or tool arguments
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
	 * Renders every entry by turn without limiting tool output size or repeating root response text.
	 *
	 * @return the complete trial activity
	 */
	public String render()
	{
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
					case Tool tool -> appendTool(result, tool);
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
	 * Appends the complete information of a tool call.
	 *
	 * @param result the destination
	 * @param tool the call
	 */
	private static void appendTool(StringBuilder result, Tool tool)
	{
		result.append("Tool: ").append(tool.tool()).append("\nInput:\n");
		appendText(result, tool.input());
		result.append("Exit status: ");
		if (tool.exitStatus() == null)
			result.append("not reported");
		else
			result.append(tool.exitStatus());
		result.append("\nOutput:\n");
		appendText(result, tool.output());
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
