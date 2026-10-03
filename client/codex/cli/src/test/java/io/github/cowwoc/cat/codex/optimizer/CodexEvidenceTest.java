package io.github.cowwoc.cat.codex.optimizer;

import static io.github.cowwoc.cat.codex.optimizer.RecordedAppServer.recorded;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.optimizer.evidence.SessionEvidence;
import io.github.cowwoc.cat.common.optimizer.evidence.SessionEvidence.Assistant;
import io.github.cowwoc.cat.common.optimizer.evidence.SessionEvidence.Hook;
import io.github.cowwoc.cat.common.optimizer.evidence.SessionEvidence.Tool;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests {@link AppServerEvents}' evidence over complete recorded app-server streams without starting a process.
 */
public final class CodexEvidenceTest
{
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Creates the test.
	 */
	public CodexEvidenceTest()
	{
	}

	/**
	 * Provides complete role recordings and their independently counted command and response totals.
	 *
	 * @return the recording name, command count and response count
	 */
	@DataProvider
	public Object[][] roleRecordings()
	{
		return new Object[][]
		{
			{"trial-turn-1.jsonl", 1, 1},
			{"judge-turn-1.jsonl", 4, 2},
			{"author-turn-1.jsonl", 11, 3},
			{"deriver-turn-1.jsonl", 12, 2}
		};
	}

	/**
	 * Retains each recorded command, its numeric exit status and its entire completed output, once and in order.
	 * Root responses are markers and a session-start hook contributes only its outcome.
	 *
	 * @param recording the complete recording
	 * @param commandCount the number of completed shell calls
	 * @param responseCount the number of root responses
	 * @throws IOException if the recording cannot be read
	 */
	@Test(dataProvider = "roleRecordings")
	public void rendersRecordedRoleEvidence(String recording, int commandCount, int responseCount) throws IOException
	{
		// Requirement: R-E21
		List<String> lines = recorded(recording);
		SessionEvidence evidence = parse(lines);
		List<Tool> commands = evidence.entries().stream().filter(Tool.class::isInstance).map(Tool.class::cast).
			filter(tool -> tool.tool().equals("commandExecution")).toList();
		requireThat(commands.size(), "commands.size").isEqualTo(commandCount);
		List<JsonNode> completed = lines.stream().map(JSON::readTree).
			filter(event -> event.path("method").asString("").equals("item/completed")).
			map(event -> event.path("params").path("item")).
			filter(item -> item.path("type").asString("").equals("commandExecution")).toList();
		String rendered = evidence.render();
		for (int i = 0; i < commands.size(); ++i)
		{
			Tool tool = commands.get(i);
			JsonNode item = completed.get(i);
			requireThat(tool.turn(), "turn").isEqualTo(1);
			requireThat(tool.agent(), "agent").isEqualTo("root");
			requireThat(tool.input(), "input").isEqualTo(item.path("command").stringValue());
			requireThat(tool.exitStatus(), "exitStatus").isEqualTo(item.path("exitCode").intValue());
			requireThat(tool.output(), "output").isEqualTo(item.path("aggregatedOutput").asString(""));
			requireThat(rendered, "rendered").contains(tool.input());
			if (!tool.output().isEmpty())
				requireThat(rendered, "rendered").contains(tool.output());
		}
		List<Assistant> responses = evidence.entries().stream().filter(Assistant.class::isInstance).
			map(Assistant.class::cast).toList();
		requireThat(responses.size(), "responses.size").isEqualTo(responseCount);
		for (int i = 0; i < responses.size(); ++i)
		{
			requireThat(responses.get(i), "response").isEqualTo(new Assistant(1, "root", i + 1, ""));
		}
		List<Hook> hooks = evidence.entries().stream().filter(Hook.class::isInstance).map(Hook.class::cast).toList();
		requireThat(hooks, "hooks").isEqualTo(List.of(new Hook(1, "root", "sessionStart", "completed")));
	}

	/**
	 * Child calls retain their own thread attribution and appear at their invocation position among root calls.
	 *
	 * @throws IOException if the recording cannot be read
	 */
	@Test
	public void attributesRecordedChildCommands() throws IOException
	{
		// Requirement: R-E21
		SessionEvidence evidence = parse(recorded("child-work.jsonl"));
		List<Tool> tools = evidence.entries().stream().filter(Tool.class::isInstance).map(Tool.class::cast).toList();
		requireThat(tools.size(), "tools.size").isEqualTo(4);
		requireThat(tools.stream().map(Tool::agent).toList(), "agents").
			isEqualTo(List.of("root", "root", "root", "child-thread"));
		requireThat(tools.getLast(), "childCall").isEqualTo(new Tool(1, "child-thread", "commandExecution",
			"/bin/bash -lc true", 1,
			"error building bubblewrap command: app-server socket directory must be a user-owned directory " +
				"with mode 0700\n"));
		requireThat(evidence.render(), "rendered").contains("child-thread");
	}

	/**
	 * A complete trial recording omits injected context and root response text from the rendered evidence.
	 *
	 * @throws IOException if the recording cannot be read
	 */
	@Test
	public void filtersRecordedTrialContext() throws IOException
	{
		// Requirement: R-E21
		String rendered = parse(recorded("trial-turn-1.jsonl")).render();
		for (String omitted : List.of("# Design Quality", "\"emittedAtMs\"", "\"delta\"", "tokenUsage",
			"rateLimits", "The capital of Japan is Tokyo."))
		{
			requireThat(rendered.contains(omitted), "containsOmittedText").isFalse();
		}
		requireThat(rendered, "rendered").contains("sessionStart");
		requireThat(rendered, "rendered").contains("completed");
	}

	/**
	 * Turn archives without thread initialization still identify the root actor from the first started turn.
	 *
	 * @throws IOException if the recording cannot be read
	 */
	@Test
	public void rendersWithoutThreadInitialization() throws IOException
	{
		// Requirement: R-E21
		List<String> lines = recorded("trial-turn-1.jsonl");
		List<String> turnArchive = lines.stream().filter(line ->
			!JSON.readTree(line).path("method").asString("").equals("thread/started")).toList();
		requireThat(parse(turnArchive), "turnEvidence").isEqualTo(parse(lines));
		requireThat(parse(turnArchive).entries().size(), "entries.size").isEqualTo(3);
	}

	/**
	 * The next recorded root turn resets response positions and does not duplicate its thousands of deltas.
	 *
	 * @throws IOException if either recording cannot be read
	 */
	@Test
	public void ordersRecordedResponsesByTurn() throws IOException
	{
		// Requirement: R-E21
		List<String> lines = new ArrayList<>(recorded("deriver-turn-1.jsonl"));
		lines.addAll(recorded("deriver-turn-2.jsonl"));
		SessionEvidence evidence = parse(lines);
		List<Assistant> responses = evidence.entries().stream().filter(Assistant.class::isInstance).
			map(Assistant.class::cast).toList();
		requireThat(responses, "responses").isEqualTo(List.of(new Assistant(1, "root", 1, ""),
			new Assistant(1, "root", 2, ""), new Assistant(2, "root", 1, "")));
		requireThat(evidence.entries().size(), "entries.size").isEqualTo(16);
		requireThat(evidence.render().contains("\"requirements\": ["), "repeatedResponse").isFalse();
	}

	/**
	 * Session-start context, prompts, deltas and telemetry are absent while complete tool output has no size cap.
	 */
	@Test
	public void omitsContextDeltasAndTelemetry()
	{
		// Requirement: R-E21
		String output = "complete-output-" + "x".repeat(200_001) + "-last-byte";
		List<String> lines = List.of("""
			{"method":"thread/started","params":{"thread":{"id":"root-thread"}}}
			""", """
			{"method":"turn/started","params":{"threadId":"root-thread","turn":{"id":"t"}}}
			""", """
			{"method":"hook/completed","params":{"threadId":"root-thread","turnId":"t","run":{
			 "id":"h","eventName":"sessionStart","status":"completed",
			 "entries":[{"kind":"context","text":"injected-session-text"}],"statusMessage":"hook-detail"}}}
			""", """
			{"method":"item/completed","params":{"threadId":"root-thread","turnId":"t","item":{
			 "id":"u","type":"userMessage","content":[{"text":"candidate-prompt-text"}]}}}
			""", """
			{"method":"item/agentMessage/delta","params":{"delta":"streaming-delta-fragment"}}
			""", """
			{"method":"thread/tokenUsage/updated","params":{"threadId":"root-thread",
			 "tokenUsage":{"total":{"inputTokens":12345}}}}
			""", """
			{"method":"account/rateLimits/updated","params":{"rateLimits":{"limitName":"telemetry"}}}
			""", """
			{"method":"item/completed","params":{"threadId":"root-thread","turnId":"t","item":{
			 "id":"a","type":"agentMessage","text":"already-in-responses"}}}
			""", """
			{"method":"item/completed","params":{"threadId":"root-thread","turnId":"t","item":{
			 "id":"c","type":"commandExecution","command":"run-command","exitCode":7,"aggregatedOutput":%s}}}
			""".formatted(JSON.writeValueAsString(output)));
		SessionEvidence evidence = parse(lines);
		requireThat(evidence.entries(), "entries").isEqualTo(List.of(new Hook(1, "root", "sessionStart", "completed"),
			new Assistant(1, "root", 1, ""), new Tool(1, "root", "commandExecution", "run-command", 7, output)));
		String rendered = evidence.render();
		requireThat(rendered, "rendered").contains(output);
		requireThat(rendered, "rendered").contains("7");
		for (String omitted : List.of("injected-session-text", "hook-detail", "candidate-prompt-text",
			"streaming-delta-fragment", "inputTokens", "rateLimits", "already-in-responses"))
		{
			requireThat(rendered.contains(omitted), "containsOmittedText").isFalse();
		}
	}

	/**
	 * Recorded edits retain their file paths and full diffs, and other tools retain their arguments and results.
	 *
	 * @throws IOException if the recordings cannot be read
	 */
	@Test
	public void retainsNonCommandInputsAndResults() throws IOException
	{
		// Requirement: R-E21
		SessionEvidence author = parse(recorded("author-turn-1.jsonl"));
		Tool edit = author.entries().stream().filter(Tool.class::isInstance).map(Tool.class::cast).
			filter(tool -> tool.tool().equals("fileChange")).findFirst().orElseThrow();
		requireThat(edit.input(), "edit.input").contains("/project/AGENTS.md");
		requireThat(edit.input(), "edit.input").contains("+- Answer in one or two sentences total");
		requireThat(edit.exitStatus(), "edit.exitStatus").isNull();
		requireThat(edit.output(), "edit.output").contains("completed");

		SessionEvidence dynamic = parse(resource("codex-dynamic-tool.jsonl"));
		Tool call = (Tool) dynamic.entries().getFirst();
		requireThat(call.tool(), "tool").isEqualTo("recording_echo");
		requireThat(call.input(), "arguments").isEqualTo("{}");
		requireThat(call.output(), "result").contains("done");
		requireThat(call.exitStatus(), "exitStatus").isNull();

		SessionEvidence web = parse(resource("codex-web-search.jsonl"));
		Tool search = (Tool) web.entries().getFirst();
		requireThat(search.input(), "search.input").contains("https://www.apache.org/licenses/LICENSE-2.0");
		requireThat(search.output(), "search.output").contains("Apache License, Version 2.0");
	}

	/**
	 * Child tool arguments, results and assistant text remain available, while a root message contributes one marker.
	 */
	@Test
	public void retainsChildResultsAndMessageOrder()
	{
		// Requirement: R-E21
		SessionEvidence evidence = parse(List.of("""
			{"method":"thread/started","params":{"thread":{"id":"root-thread"}}}
			""", """
			{"method":"turn/started","params":{"threadId":"root-thread","turn":{"id":"t"}}}
			""", """
			{"method":"item/started","params":{"threadId":"root-thread","turnId":"t","item":{
			 "id":"a","type":"agentMessage","text":"root-text"}}}
			""", """
			{"method":"hook/completed","params":{"threadId":"child","turnId":"child-t","run":{
			 "id":"h","eventName":"subagentStart","status":"completed","entries":[{"text":"child-context"}]}}}
			""", """
			{"method":"item/completed","params":{"threadId":"child","turnId":"child-t","item":{
			 "id":"c","type":"mcpToolCall","tool":"edit","arguments":{"path":"file.txt","edit":"before -> after"},
			 "result":{"content":[{"type":"text","text":"tool-result"}]},"error":null}}}
			""", """
			{"method":"item/completed","params":{"threadId":"child","turnId":"child-t","item":{
			 "id":"b","type":"agentMessage","text":"child-result"}}}
			""", """
			{"method":"item/completed","params":{"threadId":"root-thread","turnId":"t","item":{
			 "id":"a","type":"agentMessage","text":"root-text"}}}
			"""));
		requireThat(evidence.entries().size(), "entries.size").isEqualTo(4);
		requireThat(evidence.entries().getFirst(), "first").isEqualTo(new Assistant(1, "root", 1, ""));
		requireThat(evidence.entries().getLast(), "last").isEqualTo(new Assistant(1, "child", 1, "child-result"));
		Tool tool = (Tool) evidence.entries().get(2);
		requireThat(tool.agent(), "agent").isEqualTo("child");
		requireThat(tool.input(), "input").contains("file.txt");
		requireThat(tool.input(), "input").contains("before -> after");
		requireThat(tool.output(), "output").contains("tool-result");
		requireThat(tool.exitStatus(), "exitStatus").isNull();
		String rendered = evidence.render();
		requireThat(rendered, "rendered").contains("child-result");
		requireThat(rendered.contains("child-context"), "injectedChildContext").isFalse();
		requireThat(rendered.contains("root-text"), "repeatedRootText").isFalse();
	}

	/**
	 * Parses a recording with the production app-server parser.
	 *
	 * @param lines the complete event stream
	 * @return the evidence
	 */
	private static SessionEvidence parse(List<String> lines)
	{
		AppServerEvents events = new AppServerEvents(JSON);
		for (String line : lines)
			events.accept(line);
		return events.evidence();
	}

	/**
	 * Loads a recorded tool stream outside the session-recording directory.
	 *
	 * @param name the resource name
	 * @return its lines
	 * @throws IOException if the recording cannot be read
	 */
	private static List<String> resource(String name) throws IOException
	{
		try (InputStream input = CodexEvidenceTest.class.getResourceAsStream("/optimizer/" + name))
		{
			requireThat(input, "input").isNotNull();
			return new String(input.readAllBytes(), UTF_8).lines().toList();
		}
	}
}
