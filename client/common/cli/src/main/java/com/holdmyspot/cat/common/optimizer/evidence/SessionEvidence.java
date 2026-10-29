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
 * @param entries  the tool calls, hook outcomes, assistant positions and child events recorded by the harness
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
	 * <p>
	 * A command can still be running when its agent receives a result from it. Each such result is retained whole, in
	 * the order that the agent received them, and the command's end is a separate {@link CommandEnd} where it ended.
	 *
	 * @param turn the one-based turn
	 * @param agent the root name or child identity
	 * @param tool the tool name
	 * @param input the command or tool arguments
	 * @param command {@code true} if the call ran {@code input} as a shell command
	 * @param exitStatus the reported exit status, or {@code null} when absent
	 * @param output the complete output
	 * @param running each result that the agent received while the command was still running, in the order that it
	 *                received them; empty if the agent received none
	 */
	public record Tool(int turn, String agent, String tool, String input, boolean command, Integer exitStatus,
		String output, List<String> running) implements Entry
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
		 * @param running each result that the agent received while the command was still running, in the order that
		 *                it received them; empty if the agent received none
		 * @throws NullPointerException if {@code agent}, {@code tool}, {@code input}, {@code output} or
		 *                              {@code running} are null, or {@code running} contains null
		 * @throws IllegalArgumentException if {@code turn} is less than 1, or {@code agent} or {@code tool} are blank
		 */
		public Tool
		{
			requireThat(turn, "turn").isGreaterThanOrEqualTo(1);
			requireThat(agent, "agent").isNotBlank();
			requireThat(tool, "tool").isNotBlank();
			requireThat(input, "input").isNotNull();
			requireThat(output, "output").isNotNull();
			requireThat(running, "running").isNotNull();
			running = List.copyOf(running);
		}

		/**
		 * Creates a tool call whose agent received no result from it while it was still running.
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
		public Tool(int turn, String agent, String tool, String input, boolean command, Integer exitStatus,
			String output)
		{
			this(turn, agent, tool, input, command, exitStatus, output, List.of());
		}
	}

	/**
	 * The end of a command whose agent received a result from it while it was still running.
	 *
	 * @param turn the one-based turn
	 * @param agent the root name or child identity
	 * @param input the command
	 * @param exitStatus the reported exit status, or {@code null} when absent
	 * @param output the complete output
	 */
	public record CommandEnd(int turn, String agent, String input, Integer exitStatus, String output) implements Entry
	{
		/**
		 * Creates a command's end.
		 *
		 * @param turn the one-based turn
		 * @param agent the root name or child identity
		 * @param input the command
		 * @param exitStatus the reported exit status, or {@code null} when absent
		 * @param output the complete output
		 * @throws NullPointerException if {@code agent}, {@code input} or {@code output} are null
		 * @throws IllegalArgumentException if {@code turn} is less than 1, or {@code agent} is blank
		 */
		public CommandEnd
		{
			requireThat(turn, "turn").isGreaterThanOrEqualTo(1);
			requireThat(agent, "agent").isNotBlank();
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
	 * An assistant message's position, with child text retained because it is not supplied among root responses, and
	 * the questions of a message that asked the user for input.
	 *
	 * @param turn      the one-based turn
	 * @param agent     the root name or child identity
	 * @param position  the one-based response position within the actor's turn
	 * @param text      the child text, or an empty string for a root response marker
	 * @param questions the questions that the message asked the user, in the order asked; empty if it asked none
	 */
	public record Assistant(int turn, String agent, int position, String text, List<Question> questions)
		implements Entry
	{
		/**
		 * Creates an assistant position.
		 *
		 * @param turn      the one-based turn
		 * @param agent     the root name or child identity
		 * @param position  the one-based response position within the actor's turn
		 * @param text      the child text, or an empty string for a root response marker
		 * @param questions the questions that the message asked the user, in the order asked; empty if it asked none
		 * @throws NullPointerException     if {@code agent}, {@code text} or {@code questions} are null, or
		 *                                  {@code questions} contains null
		 * @throws IllegalArgumentException if {@code turn} or {@code position} are less than 1, or {@code agent} is blank
		 */
		public Assistant
		{
			requireThat(turn, "turn").isGreaterThanOrEqualTo(1);
			requireThat(agent, "agent").isNotBlank();
			requireThat(position, "position").isGreaterThanOrEqualTo(1);
			requireThat(text, "text").isNotNull();
			requireThat(questions, "questions").isNotNull();
			questions = List.copyOf(questions);
		}

		/**
		 * Creates the position of a message that asked the user nothing.
		 *
		 * @param turn     the one-based turn
		 * @param agent    the root name or child identity
		 * @param position the one-based response position within the actor's turn
		 * @param text     the child text, or an empty string for a root response marker
		 * @throws NullPointerException     if {@code agent} or {@code text} are null
		 * @throws IllegalArgumentException if {@code turn} or {@code position} are less than 1, or {@code agent} is blank
		 */
		public Assistant(int turn, String agent, int position, String text)
		{
			this(turn, agent, position, text, List.of());
		}
	}

	/**
	 * A question that an assistant message asked the user, as the agent wrote it.
	 *
	 * @param title   the question's text, which can be empty
	 * @param options the answers that the agent offered, in order; empty if it offered none
	 */
	public record Question(String title, List<String> options)
	{
		/**
		 * Creates a question.
		 *
		 * @param title   the question's text, which can be empty
		 * @param options the answers that the agent offered, in order; empty if it offered none
		 * @throws NullPointerException if any of the arguments are null, or {@code options} contains null
		 */
		public Question
		{
			requireThat(title, "title").isNotNull();
			requireThat(options, "options").isNotNull();
			options = List.copyOf(options);
		}
	}

	/**
	 * A child agent's start, a message that an agent sent it or received from it, or its end, as the harness recorded
	 * it.
	 *
	 * @param turn  the one-based turn
	 * @param agent the root name or child identity of the agent that started the child, sent it a message or received
	 *              its message
	 * @param child the child's identity, which names the child's own entries when the harness records them
	 * @param name  the name that the agent gave the child, or an empty string when the harness recorded none
	 * @param kind  the kind of event
	 * @param text  the assignment of a start, the message of a sending or a receipt, or what the harness reported of
	 *              an end; an empty string when the harness recorded no readable text
	 */
	public record ChildEvent(int turn, String agent, String child, String name, Kind kind, String text)
		implements Entry
	{
		/**
		 * Creates a child event.
		 *
		 * @param turn  the one-based turn
		 * @param agent the root name or child identity of the agent that started the child, sent it a message or
		 *              received its message
		 * @param child the child's identity, which names the child's own entries when the harness records them
		 * @param name  the name that the agent gave the child, or an empty string when the harness recorded none
		 * @param kind  the kind of event
		 * @param text  the assignment of a start, the message of a sending or a receipt, or what the harness reported
		 *              of an end; an empty string when the harness recorded no readable text
		 * @throws NullPointerException     if {@code agent}, {@code child}, {@code name}, {@code kind} or {@code text}
		 *                                  are null
		 * @throws IllegalArgumentException if {@code turn} is less than 1, or {@code agent} or {@code child} are blank
		 */
		public ChildEvent
		{
			requireThat(turn, "turn").isGreaterThanOrEqualTo(1);
			requireThat(agent, "agent").isNotBlank();
			requireThat(child, "child").isNotBlank();
			requireThat(name, "name").isNotNull();
			requireThat(kind, "kind").isNotNull();
			requireThat(text, "text").isNotNull();
		}

		/**
		 * The kinds of child events.
		 */
		public enum Kind
		{
			/**
			 * The agent started the child; the text is the assignment that the agent gave it.
			 */
			STARTED,
			/**
			 * The agent sent the child a message; the text is the message.
			 */
			SENT,
			/**
			 * The agent received a message from the child; the text is the message.
			 */
			RECEIVED,
			/**
			 * The harness reported that the child ended; the text is what the harness reported.
			 */
			ENDED
		}
	}

	/**
	 * Renders every entry by turn without repeating root response text, but with the questions of each message that
	 * asked the user for input. Each tool output is described by its size
	 * rather than copied, so that the activity stays short however much a trial prints; for a command, the description
	 * also says whether the agent received the whole output. A command whose agent received a result from it while it
	 * was still running is shown instead with each such result whole, and its end with its exit status and the size of
	 * its output where it ended. The text of a child event is kept whole, as a child's
	 * response is, because it is a message that an agent wrote or a report that the harness made rather than the
	 * output of a tool.
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
					case CommandEnd end -> appendCommandEnd(result, end);
					case Hook hook -> result.append("Hook: ").append(hook.hook()).append("; status: ").
						append(hook.status()).append('\n');
					case Assistant assistant ->
					{
						result.append("Assistant response position: ").append(assistant.position()).append('\n');
						if (!assistant.agent().equals(ROOT_AGENT) && !assistant.text().isEmpty())
							appendText(result, assistant.text());
						appendQuestions(result, assistant.questions());
					}
					case ChildEvent event -> appendChildEvent(result, event);
				}
				result.append('\n');
			}
		}
		return result.toString();
	}

	/**
	 * Appends the questions that a message asked the user, each with the answers that the agent offered, saying so
	 * when a question's text is empty or it offered no answers.
	 *
	 * @param result    the destination
	 * @param questions the questions; nothing is appended if it is empty
	 */
	private static void appendQuestions(StringBuilder result, List<Question> questions)
	{
		if (questions.isEmpty())
			return;
		result.append("Asked the user for input:\n");
		for (Question question : questions)
		{
			if (question.title().isEmpty())
				result.append("Question: empty\n");
			else
			{
				result.append("Question:\n");
				appendText(result, question.title());
			}
			if (question.options().isEmpty())
				result.append("Options: none\n");
			else
			{
				result.append("Options:\n");
				for (String option : question.options())
					appendText(result, "- " + option);
			}
		}
	}

	/**
	 * Appends a tool call's name and input, followed either by what its agent received while it was still running or
	 * by its exit status and the size of its output.
	 *
	 * @param result        the destination
	 * @param tool          the call
	 * @param receivedLines every line of the received tool results, without trailing whitespace
	 */
	private static void appendTool(StringBuilder result, Tool tool, Set<String> receivedLines)
	{
		result.append("Tool: ").append(tool.tool()).append("\nInput:\n");
		appendText(result, tool.input());
		if (!tool.running().isEmpty())
		{
			result.append("Received while the command was still running:\n");
			for (String text : tool.running())
			{
				if (text.isEmpty())
					result.append("(an empty result)\n");
				else
					appendText(result, text);
			}
			return;
		}
		appendExitStatus(result, tool.exitStatus());
		String output = tool.output();
		if (output.isBlank())
		{
			result.append("Output: none\n");
			return;
		}
		result.append("Output: ");
		appendSize(result, output);
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
	 * Appends the end of a command whose agent received a result from it while it was still running.
	 *
	 * @param result the destination
	 * @param end    the command's end
	 */
	private static void appendCommandEnd(StringBuilder result, CommandEnd end)
	{
		result.append("Command ended\nInput:\n");
		appendText(result, end.input());
		appendExitStatus(result, end.exitStatus());
		result.append("Output: ");
		if (end.output().isBlank())
			result.append("none");
		else
			appendSize(result, end.output());
		result.append('\n');
	}

	/**
	 * Appends a reported exit status.
	 *
	 * @param result     the destination
	 * @param exitStatus the reported exit status, or {@code null} when absent
	 */
	private static void appendExitStatus(StringBuilder result, Integer exitStatus)
	{
		result.append("Exit status: ");
		if (exitStatus == null)
			result.append("not reported");
		else
			result.append(exitStatus);
		result.append('\n');
	}

	/**
	 * Appends the number of characters of an output.
	 *
	 * @param result the destination
	 * @param output the output
	 */
	private static void appendSize(StringBuilder result, String output)
	{
		result.append(output.codePointCount(0, output.length())).append(" characters");
	}

	/**
	 * Appends a child event, naming the child by its identity and by the name that it was given.
	 *
	 * @param result the destination
	 * @param event  the event
	 */
	private static void appendChildEvent(StringBuilder result, ChildEvent event)
	{
		String child = event.child();
		if (!event.name().isEmpty())
			child += " (" + event.name() + ")";
		switch (event.kind())
		{
			case STARTED ->
			{
				result.append("Started child ").append(child).append('\n');
				if (event.text().isEmpty())
					result.append("Assignment: not recorded in readable form\n");
				else
				{
					result.append("Assignment:\n");
					appendText(result, event.text());
				}
			}
			case SENT ->
			{
				result.append("Sent a message to child ").append(child).append('\n');
				if (event.text().isEmpty())
					result.append("Message: not recorded in readable form\n");
				else
				{
					result.append("Message:\n");
					appendText(result, event.text());
				}
			}
			case RECEIVED ->
			{
				result.append("Received from child ").append(child).append(":\n");
				if (event.text().isEmpty())
					result.append("(not recorded in readable form)\n");
				else
					appendText(result, event.text());
			}
			case ENDED ->
			{
				result.append("Child ").append(child).append(" finished\n");
				if (!event.text().isEmpty())
					appendText(result, event.text());
			}
		}
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
