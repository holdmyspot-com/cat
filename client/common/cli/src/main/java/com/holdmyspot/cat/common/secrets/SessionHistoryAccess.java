package com.holdmyspot.cat.common.secrets;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Exposes the operations of the session-history command to the module's tests.
 */
public interface SessionHistoryAccess
{
	/**
	 * Executes one session-history operation.
	 *
	 * @param args the operation, transcript path, and operation-specific arguments
	 * @return the deterministic text result
	 * @throws IOException if the transcript cannot be read or contains invalid JSONL
	 */
	String execute(String[] args) throws IOException;

	/**
	 * Finds records containing a phrase and adjacent records.
	 *
	 * @param transcript the existing JSONL transcript to search
	 * @param phrase the case-insensitive phrase to find
	 * @param context the number of adjacent records retained on each side
	 * @return the matching JSONL records in source order
	 * @throws IOException if the transcript cannot be read or parsed
	 */
	String search(Path transcript, String phrase, int context) throws IOException;
}
