package io.github.cowwoc.cat.common.optimizer.loading;

import java.io.IOException;

/** Reads immutable exact source bytes by content evidence identity. */
@FunctionalInterface
public interface PromptContentEvidenceReader
{
	/**
	 * Reads the retained source bytes.
	 *
	 * @param contentEvidenceId immutable content identity
	 * @return exact delivered bytes
	 * @throws IOException if retained content cannot be read
	 */
	byte[] read(String contentEvidenceId) throws IOException;
}
