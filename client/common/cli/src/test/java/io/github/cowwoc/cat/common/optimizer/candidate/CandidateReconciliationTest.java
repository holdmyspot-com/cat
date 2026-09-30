package io.github.cowwoc.cat.common.optimizer.candidate;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

/** Tests complete-state candidate reconciliation. */
public final class CandidateReconciliationTest
{
	/** Creates the test fixture. */
	public CandidateReconciliationTest()
	{
	}

	/**
	 * The first complete predecessor application reports APPLIED, while a repeated selected state is idempotent.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void appliesThenRecognizesSelectedState() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("reconcile-project-"));
			write(project, "prompts/rule.md", "baseline");
			PromptSnapshot baseline = PromptSnapshot.capture(project, List.of(Path.of("prompts")));
			PromptSnapshot selected = snapshot(cleanup, "selected");
			try (CandidateDeliveryFixture fixture = new CandidateDeliveryFixture(project,
				Map.of(baseline.id(), baseline, selected.id(), selected), List.of(Path.of("prompts"))))
			{
				CandidateApplication first = fixture.delivery().reconcile(baseline.id(), null, selected.id());
				CandidateApplication repeated = fixture.delivery().reconcile(baseline.id(), null, selected.id());

			requireThat(first.disposition(), "firstDisposition").isEqualTo(ApplyDisposition.APPLIED);
			requireThat(repeated.disposition(), "repeatedDisposition").isEqualTo(ApplyDisposition.ALREADY_APPLIED);
			requireThat(Files.readString(project.resolve("prompts/rule.md")), "selectedContent").isEqualTo("selected");
			}
		}
	}

	/**
	 * A mixed predecessor is rejected before any selected byte is written and unrelated dirty bytes remain unchanged.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void rejectsMixedStateWithoutPartialMutation() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("reconcile-mixed-project-"));
			write(project, "prompts/first.md", "baseline first");
			write(project, "prompts/second.md", "baseline second");
			write(project, "notes.bin", "unrelated");
			PromptSnapshot baseline = PromptSnapshot.capture(project, List.of(Path.of("prompts")));
			Path selectedProject = cleanup.add(Files.createTempDirectory("reconcile-mixed-selected-"));
			write(selectedProject, "prompts/first.md", "selected first");
			write(selectedProject, "prompts/second.md", "selected second");
			PromptSnapshot selected = PromptSnapshot.capture(selectedProject, List.of(Path.of("prompts")));
			write(project, "prompts/first.md", "mixed first");
			try (CandidateDeliveryFixture fixture = new CandidateDeliveryFixture(project,
				Map.of(baseline.id(), baseline, selected.id(), selected), List.of(Path.of("prompts"))))
			{
				try
				{
					fixture.delivery().reconcile(baseline.id(), null, selected.id());
					throw new AssertionError("Expected mixed-state rejection");
				}
				catch (CandidateDeliveryException expected)
				{
					requireThat(expected.getMessage(), "mixedStateMessage").contains("mixed");
					requireThat(expected.reason(), "mixedStateReason").isEqualTo(
						CandidateDeliveryException.Reason.CONCURRENT_MODIFICATION);
				}
			}
			requireThat(Files.readString(project.resolve("prompts/first.md")), "firstPrompt").isEqualTo("mixed first");
			requireThat(Files.readString(project.resolve("prompts/second.md")), "secondPrompt").isEqualTo("baseline second");
			requireThat(Files.readAllBytes(project.resolve("notes.bin")), "unrelatedBytes").isEqualTo(
				"unrelated".getBytes(StandardCharsets.UTF_8));
		}
	}

	/**
	 * Captures one selected revision.
	 *
	 * @param cleanup owner of the source directory
	 * @param content selected prompt text
	 * @return captured snapshot
	 * @throws IOException if fixture I/O fails
	 */
	private static PromptSnapshot snapshot(TemporaryDirectories cleanup, String content) throws IOException
	{
		Path source = cleanup.add(Files.createTempDirectory("reconcile-source-"));
		write(source, "prompts/rule.md", content);
		return PromptSnapshot.capture(source, List.of(Path.of("prompts")));
	}

	/**
	 * Writes one UTF-8 fixture file.
	 *
	 * @param root fixture root
	 * @param relative fixture-relative path
	 * @param content text content
	 * @throws IOException if fixture I/O fails
	 */
	private static void write(Path root, String relative, String content) throws IOException
	{
		Path file = root.resolve(relative);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content, StandardCharsets.UTF_8);
	}
}
