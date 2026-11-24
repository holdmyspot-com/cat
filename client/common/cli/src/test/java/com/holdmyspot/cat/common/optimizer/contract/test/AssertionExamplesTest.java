package com.holdmyspot.cat.common.optimizer.contract.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static org.testng.Assert.expectThrows;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.contract.Assertion;
import com.holdmyspot.cat.common.optimizer.contract.AssertionExamples;
import com.holdmyspot.cat.common.optimizer.contract.AssertionKind;
import com.holdmyspot.cat.common.optimizer.contract.BuiltInTool;
import com.holdmyspot.cat.common.optimizer.contract.PromptTest;
import com.holdmyspot.cat.common.optimizer.contract.PromptTestFiles;
import com.holdmyspot.cat.common.optimizer.contract.Scenario;
import com.holdmyspot.cat.common.optimizer.contract.TestCase;
import com.holdmyspot.cat.common.optimizer.contract.TestValue;
import com.holdmyspot.cat.common.optimizer.contract.TestWorkspaces;
import com.holdmyspot.cat.common.optimizer.contract.TrialRecord;
import com.holdmyspot.cat.common.optimizer.contract.UndecidableToolException;
import com.holdmyspot.cat.common.optimizer.contract.VariableDeclaration;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests how {@link AssertionExamples} renders the test author's examples as records for the assertion tools.
 */
public final class AssertionExamplesTest
{
	/**
	 * Creates the test.
	 */
	public AssertionExamplesTest()
	{
	}

	/**
	 * Verifies that passing examples use the first case of their class and failing examples use the first written
	 * case. Reordering the values changes the selections.
	 */
	@Test
	public void selectsCasesInWrittenOrder()
	{
		// Requirement: R-L15
		TestCase first = testCase("River", "same_day");
		TestCase second = testCase("Lake", "next_day");
		TestCase third = testCase("Hill", "next_day");
		List<TestCase> written = List.of(first, second, third);
		List<TestCase> reordered = List.of(third, second, first);

		requireThat(AssertionExamples.selectCase(written, "next_day"), "passing").isEqualTo(second);
		requireThat(AssertionExamples.selectCase(written, null), "failing").isEqualTo(first);
		requireThat(AssertionExamples.selectCase(reordered, "next_day"), "passingReordered").isEqualTo(third);
		requireThat(AssertionExamples.selectCase(reordered, null), "failingReordered").isEqualTo(third);
	}

	/**
	 * Verifies that a reply tool reads the requested turn, values containing JSON delimiters stay in one message,
	 * and a turn that the example omits stays empty.
	 *
	 * @throws IOException if the example cannot be prepared or read
	 * @throws UndecidableToolException if the tool cannot read the rendered record
	 */
	@Test
	public void fillsRepliesAndPadsMissingTurns() throws IOException, UndecidableToolException
	{
		// Requirement: R-L15
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path source = cleanup.add(Files.createTempDirectory("assertion-example-source"));
			Path scratch = cleanup.add(Files.createTempDirectory("assertion-example-record"));
			Path replies = Files.writeString(source.resolve("replies.json"), """
				[["interim", "First"], ["{{label}}"]]
				""");
			TestCase testCase = testCase("River \"Express\"\nline 2", "same_day");
			TrialRecord rendered = AssertionExamples.render(JsonMapper.builder().build(), 3, testCase,
				Map.of("replies.json", replies), Set.of(), source, scratch);

			requireThat(rendered.replies(), "replies").isEqualTo(List.of(List.of("interim", "First"),
				List.of("River \"Express\"\nline 2"), List.of()));
			requireThat(BuiltInTool.parse("exact-text turn=2 {{label}}").
				decide(rendered, PromptTest.textByName(testCase)).passed(), "secondTurn").isTrue();
		}
	}

	/**
	 * Verifies that an example's activity and changed project become observable to the corresponding built-in
	 * tools. The original project defaults to the staged base and the workspace files of the selected case.
	 *
	 * @throws IOException if the example cannot be prepared or read
	 * @throws UndecidableToolException if a tool cannot read the rendered record
	 */
	@Test
	public void readsActivityAndProjectChanges() throws IOException, UndecidableToolException
	{
		// Requirement: R-L15
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path source = cleanup.add(Files.createTempDirectory("assertion-example-source"));
			Path scratch = cleanup.add(Files.createTempDirectory("assertion-example-record"));
			Path latest = Files.writeString(source.resolve("latest.txt"), "Updated {{label}}");
			Path activity = Files.writeString(source.resolve("activity.json"), """
				{
				  "format_version": 1,
				  "entries": [{
				    "type": "tool",
				    "entry": {
				      "turn": 1, "agent": "root", "tool": "Read", "input": "{{label}}", "command": false,
				      "exit_status": null, "output": "read file", "running": []
				    }
				  }],
				  "received": ["read {{label}}"]
				}
				""");
			TestCase testCase = testCase("River", "same_day");
			Path initial = stageWorkspace(source, testCase);
			Path base = Files.writeString(source.resolve("base.txt"), "Base project");
			TrialRecord rendered = AssertionExamples.render(JsonMapper.builder().build(), 3, testCase,
				Map.of("activity.json", activity, "project/{{label}}.txt", latest, "project/base.txt", base),
				Set.of("project"), initial, scratch);

			requireThat(rendered.initial("River.txt"), "initial").isEqualTo(Optional.of("Initially River"));
			requireThat(rendered.initial("base.txt"), "initialBase").isEqualTo(Optional.of("Base project"));
			requireThat(rendered.changedPaths(), "changes").containsExactly(List.of("River.txt"));
			requireThat(BuiltInTool.parse("file-content {{label}}.txt \"Updated {{label}}\"").
				decide(rendered, PromptTest.textByName(testCase)).passed(), "fileContent").isTrue();
			requireThat(BuiltInTool.parse("tool-used Read").decide(rendered, Map.of()).passed(), "toolUsed").isTrue();
			requireThat(rendered.tools().getFirst().input(), "toolInput").isEqualTo("River");
			requireThat(rendered.received(), "received").containsExactly(List.of("read River"));
		}
	}

	/**
	 * Verifies that omitting the project, activity and replies produces an unchanged project, no tool calls and
	 * three empty turns. Explicitly empty initial and final projects override their respective defaults.
	 *
	 * @throws IOException if the example cannot be prepared or read
	 */
	@Test
	public void distinguishesMissingAndEmptyProject() throws IOException
	{
		// Requirement: R-L15
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path source = cleanup.add(Files.createTempDirectory("assertion-example-source"));
			Path implicitScratch = cleanup.add(Files.createTempDirectory("implicit-project"));
			Path explicitScratch = cleanup.add(Files.createTempDirectory("empty-project"));
			Path emptyInitialScratch = cleanup.add(Files.createTempDirectory("empty-initial-project"));
			TestCase testCase = testCase("River", "same_day");
			Path initial = stageWorkspace(source, testCase);
			JsonMapper json = JsonMapper.builder().build();

			TrialRecord implicit = AssertionExamples.render(json, 3, testCase, Map.of(), Set.of(), initial,
				implicitScratch);
			TrialRecord emptyProject = AssertionExamples.render(json, 3, testCase, Map.of(), Set.of("project"),
				initial, explicitScratch);
			TrialRecord emptyInitial = AssertionExamples.render(json, 3, testCase, Map.of(), Set.of("initial"),
				null, emptyInitialScratch);

			requireThat(implicit.replies(), "replies").isEqualTo(List.of(List.of(), List.of(), List.of()));
			requireThat(implicit.tools(), "tools").isEmpty();
			requireThat(implicit.received(), "received").isEmpty();
			requireThat(implicit.changedPaths(), "implicitChanges").isEmpty();
			requireThat(implicit.content("River.txt"), "implicitContent").isEqualTo(Optional.of("Initially River"));
			requireThat(Set.copyOf(emptyProject.changedPaths()), "explicitChanges").
				isEqualTo(Set.of("River.txt", "base.txt"));
			requireThat(emptyProject.content("River.txt"), "explicitContent").isEmpty();
			requireThat(emptyInitial.initial("River.txt"), "emptyInitial").isEmpty();
			requireThat(emptyInitial.changedPaths(), "emptyInitialChanges").isEmpty();
		}
	}

	/**
	 * Verifies that a substituted filename cannot escape its project snapshot.
	 *
	 * @throws IOException if the example cannot be prepared
	 */
	@Test
	public void refusesAnEscapingFilename() throws IOException
	{
		// Requirement: R-L15
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path source = cleanup.add(Files.createTempDirectory("assertion-example-source"));
			Path scratch = cleanup.add(Files.createTempDirectory("assertion-example-record"));
			Path file = Files.writeString(source.resolve("payload.txt"), "Example");
			TestCase testCase = testCase("../outside", "same_day");

			expectThrows(IOException.class, () -> AssertionExamples.render(JsonMapper.builder().build(), 3,
				testCase, Map.of("project/{{label}}.txt", file), Set.of("project"), source, scratch));
			requireThat(Files.exists(scratch.resolve("outside.txt")), "escaped").isFalse();
		}
	}

	/**
	 * Returns a test case with the given label and boundary class.
	 *
	 * @param label         the input's label
	 * @param boundaryClass the kind of boundary the input exercises
	 * @return the test case
	 */
	private static TestCase testCase(String label, String boundaryClass)
	{
		return new TestCase(Map.of("label", new TestValue.Text(label)), "Arrives " + label, boundaryClass);
	}

	/**
	 * Stages a base file and overlays the selected test case's rendered workspace.
	 *
	 * @param source   the fixture directory
	 * @param testCase the case that the trial would run
	 * @return the initial project
	 * @throws IOException if the initial project cannot be staged
	 */
	private static Path stageWorkspace(Path source, TestCase testCase) throws IOException
	{
		Path initial = Files.createDirectory(source.resolve("staged"));
		Files.writeString(initial.resolve("base.txt"), "Base project");
		Path workspaceFile = Files.writeString(source.resolve("workspace.txt"), "Initially {{label}}");
		Scenario scenario = new Scenario(List.of(new VariableDeclaration("label", "The line's label")),
			List.of("When does {{label}} arrive?", "Confirm the answer.", "Repeat the result."),
			List.of(new Assertion("gives_result", AssertionKind.EFFECT, "exact-text {{label}}",
				"The answer states {{label}}.")));
		String path = "{{label}}.txt";
		PromptTestFiles.WorkspaceEntry entry = new PromptTestFiles.WorkspaceEntry(path, true, Set.of("label"));
		PromptTestFiles files = new PromptTestFiles(source, source, scenario, List.of(entry), Map.of(path, workspaceFile),
			Map.of(), List.of());
		TestWorkspaces.copy(files, testCase, initial);
		return initial;
	}
}
