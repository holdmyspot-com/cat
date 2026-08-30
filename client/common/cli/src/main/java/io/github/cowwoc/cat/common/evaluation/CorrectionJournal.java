package io.github.cowwoc.cat.common.evaluation;

import io.github.cowwoc.cat.common.secrets.EvaluatorSecrets;
import java.io.IOException;
import java.nio.file.Path;
import tools.jackson.databind.node.ObjectNode;

/**
 * Links a failed prompt-evaluation journal to its terminal correction.
 */
public final class CorrectionJournal
{
	/**
	 * Supplies coordinator-owned journal persistence and field validation.
	 */
	public interface Access
	{
		/**
		 * Reads a journal as an object.
		 *
		 * @param journalPath the journal path
		 * @param description the journal role in malformed-content diagnostics
		 * @return the parsed journal object
		 * @throws IOException if the journal cannot be read or is not an object
		 */
		ObjectNode read(Path journalPath, String description) throws IOException;

		/**
		 * Returns a required text field from a journal.
		 *
		 * @param journal the parsed journal
		 * @param field the required field name
		 * @param journalPath the journal path used in malformed-content diagnostics
		 * @return the non-empty field value
		 * @throws IOException if the field is missing or malformed
		 */
		String text(ObjectNode journal, String field, Path journalPath) throws IOException;

		/**
		 * Persists a changed journal.
		 *
		 * @param journalPath the journal path
		 * @param journal the replacement content
		 * @throws IOException if the journal cannot be persisted
		 */
		void write(Path journalPath, ObjectNode journal) throws IOException;
	}

	private static volatile Access access;

	/**
	 * Prevents construction of this static journal owner.
	 */
	private CorrectionJournal()
	{
	}

	/**
	 * Registers the coordinator-owned journal operations.
	 *
	 * @param access the journal operations
	 */
	public static void configure(Access access)
	{
		CorrectionJournal.access = java.util.Objects.requireNonNull(access, "access may not be null");
	}

	/**
	 * Marks a current failed journal as superseded by a current terminal PASS journal.
	 *
	 * @param failedJournal the failed journal to supersede
	 * @param correctionJournal the terminal PASS journal that corrects it
	 * @throws IOException if either journal is invalid or the correction cannot supersede the failure
	 */
	public static void link(Path failedJournal, Path correctionJournal) throws IOException
	{
		Path normalizedFailure = failedJournal.toAbsolutePath().normalize();
		Path normalizedCorrection = correctionJournal.toAbsolutePath().normalize();
		if (normalizedFailure.equals(normalizedCorrection))
			throw new IOException("A failed workflow journal cannot correct itself: " + normalizedFailure);
		ObjectNode failure = access().read(normalizedFailure, "failed workflow journal");
		ObjectNode correction = access().read(normalizedCorrection, "correction workflow journal");
		validate(failure, normalizedFailure);
		validate(correction, normalizedCorrection);
		if (!access().text(failure, "status", normalizedFailure).equals("current"))
			throw new IOException("Failed workflow journal is not current: " + normalizedFailure);
		if (access().text(failure, "terminal_decision", normalizedFailure).equals("PASS"))
			throw new IOException("Only a failed workflow journal can be superseded: " + normalizedFailure);
		if (!access().text(correction, "status", normalizedCorrection).equals("current"))
			throw new IOException("Correction workflow journal is not current: " + normalizedCorrection);
		if (!access().text(correction, "terminal_decision", normalizedCorrection).equals("PASS"))
			throw new IOException("Correction workflow must have a terminal PASS: " + normalizedCorrection);

		failure.put("status", "superseded");
		failure.put("superseded_by", normalizedCorrection.toString());
		access().write(normalizedFailure, failure);
	}

	/**
	 * Validates the minimum durable fields that identify one prompt-evaluation journal.
	 *
	 * @param journal the parsed journal
	 * @param journalPath the journal location used in diagnostics
	 * @throws IOException if the journal does not identify a prompt evaluation
	 */
	private static void validate(ObjectNode journal, Path journalPath) throws IOException
	{
		if (!access().text(journal, "purpose", journalPath).equals("prompt-evaluation"))
			throw new IOException("Journal does not describe a prompt evaluation: " + journalPath);
		access().text(journal, "terminal_decision", journalPath);
		access().text(journal, "failure_class", journalPath);
		access().text(journal, "status", journalPath);
	}

	/**
	 * Returns the configured coordinator journal operations.
	 *
	 * @return the coordinator operations
	 */
	private static Access access()
	{
		EvaluatorSecrets.access().initialize();
		Access result = access;
		if (result == null)
			throw new AssertionError("PromptEvaluation did not configure correction-journal access");
		return result;
	}
}
