package io.github.cowwoc.cat.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * Tests {@link PromptEvidenceCheck}.
 */
public final class PromptEvidenceCheckTest
{
	/**
	 * Creates the test fixture.
	 */
	public PromptEvidenceCheckTest()
	{
	}

	/**
	 * Verifies a complete evidence record is accepted.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void acceptsCompleteRecord() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path record = cleanup.add(Files.createTempFile("evidence", ".txt"));
			Files.writeString(record, passedRecord());
			PromptEvidenceCheck.main(new String[]{record.toString()});
		}
	}

	/**
	 * Verifies a missing required field is rejected.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class)
	public void rejectsMissingField() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path record = cleanup.add(Files.createTempFile("evidence", ".txt"));
			Files.writeString(record, "status=passed\n");
			PromptEvidenceCheck.main(new String[]{record.toString()});
		}
	}

	/**
	 * Verifies a failed record without its required diagnosis is rejected.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*failure_class.*")
	public void rejectsIncompleteFailureRecord() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path record = cleanup.add(Files.createTempFile("evidence", ".txt"));
			Files.writeString(record, passedRecord().replace("status=passed", "status=failed"));
			PromptEvidenceCheck.main(new String[]{record.toString()});
		}
	}

	/**
	 * Verifies a malformed field is rejected instead of being mistaken for evidence.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*malformed field.*")
	public void rejectsMalformedField() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path record = cleanup.add(Files.createTempFile("evidence", ".txt"));
			Files.writeString(record, passedRecord() + "not-a-field\n");
			PromptEvidenceCheck.main(new String[]{record.toString()});
		}
	}

	/**
	 * Returns one complete passing evidence record.
	 *
	 * @return a complete passing evidence record
	 */
	private static String passedRecord()
	{
		return """
			prompt_revision=x
			task_input=x
			harness=x
			profile=x
			allowed_context=x
			tool_events=x
			raw_result=x
			validator_result=x
			status=passed
			""";
	}
}
