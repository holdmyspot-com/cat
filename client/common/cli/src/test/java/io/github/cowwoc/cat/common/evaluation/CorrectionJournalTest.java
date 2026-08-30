package io.github.cowwoc.cat.common.evaluation;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * Tests {@link CorrectionJournal}.
 */
public final class CorrectionJournalTest
{
	/**
	 * Creates the test fixture.
	 */
	public CorrectionJournalTest()
	{
	}

	/**
	 * Verifies a current failed journal records the terminal PASS journal that supersedes it.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void linksCurrentFailureToTerminalPass() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("correction-journal"));
			Path failedJournal = writeJournal(root.resolve("failed/workflow-journal.json"), "FAIL", "current");
			Path correctionJournal = writeJournal(root.resolve("correction/workflow-journal.json"), "PASS", "current");

			CorrectionJournal.link(failedJournal, correctionJournal);

			String failure = Files.readString(failedJournal);
			requireThat(failure, "failure").contains("\"status\" : \"superseded\"").contains(
				correctionJournal.toAbsolutePath().normalize().toString());
		}
	}

	/**
	 * Verifies a non-PASS correction cannot supersede a failed journal.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*Correction workflow must have a terminal PASS.*")
	public void rejectsNonPassCorrection() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("invalid-correction-journal"));
			Path failedJournal = writeJournal(root.resolve("failed/workflow-journal.json"), "FAIL", "current");
			Path correctionJournal = writeJournal(root.resolve("correction/workflow-journal.json"), "FAIL", "current");

			CorrectionJournal.link(failedJournal, correctionJournal);
		}
	}

	/**
	 * Writes a minimal journal with the declared terminal decision and status.
	 *
	 * @param journalPath the journal to write
	 * @param terminalDecision the terminal decision recorded by the journal
	 * @param status the current journal status
	 * @return the written journal
	 * @throws IOException if the fixture cannot be written
	 */
	private static Path writeJournal(Path journalPath, String terminalDecision, String status) throws IOException
	{
		Files.createDirectories(journalPath.getParent());
		Files.writeString(journalPath, """
			{
			  "purpose": "prompt-evaluation",
			  "terminal_decision": "%s",
			  "failure_class": "prompt-or-certification",
			  "status": "%s"
			}
			""".formatted(terminalDecision, status));
		return journalPath;
	}
}
