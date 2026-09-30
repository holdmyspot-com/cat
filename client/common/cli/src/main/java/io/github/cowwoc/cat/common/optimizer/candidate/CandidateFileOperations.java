package io.github.cowwoc.cat.common.optimizer.candidate;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Filesystem operations used by candidate delivery transactions.
 */
interface CandidateFileOperations
{
	/**
	 * Creates and forces a fresh private stage.
	 *
	 * @param privateStage absent CAT-owned path
	 * @param bytes exact bytes to stage
	 * @throws IOException if creation, writing, or forcing fails
	 */
	void writeAndForceNew(Path privateStage, byte[] bytes) throws IOException;

	/**
	 * Atomically moves an observed target to an absent private sibling and inspects its bytes.
	 *
	 * @param observedTarget visible path to capture
	 * @param absentPrivateSibling absent CAT-owned sibling
	 * @return inspection of the moved file
	 * @throws IOException if capture or inspection fails
	 */
	PathInspection captureAtomically(Path observedTarget, Path absentPrivateSibling) throws IOException;

	/**
	 * Hard-links a verified private source into an absent target.
	 *
	 * @param privateSource CAT-owned source
	 * @param absentTarget absent visible target
	 * @param expectedSha256 expected source hash
	 * @return publication identity
	 * @throws IOException if verification or linking fails
	 */
	LinkPublication linkNew(Path privateSource, Path absentTarget, String expectedSha256) throws IOException;

	/**
	 * Checks whether distinct live regular pathnames identify the same file.
	 *
	 * @param first first path
	 * @param second second path
	 * @return true only for distinct live aliases
	 * @throws IOException if either path cannot be inspected
	 */
	boolean sameFile(Path first, Path second) throws IOException;

	/**
	 * Inspects a path without following a final symbolic link.
	 *
	 * @param path path to inspect
	 * @return absent or regular-file inspection
	 * @throws IOException if inspection fails or the kind is unsupported
	 */
	PathInspection inspect(Path path) throws IOException;

	/**
	 * Removes a caller-validated private alias.
	 *
	 * @param privatePath CAT-owned path
	 * @throws IOException if unlinking fails
	 */
	void unlinkPrivate(Path privatePath) throws IOException;

	/**
	 * Deletes a caller-validated private file.
	 *
	 * @param privatePath CAT-owned path
	 * @throws IOException if deletion fails
	 */
	void deletePrivate(Path privatePath) throws IOException;

	/**
	 * Creates an absent directory.
	 *
	 * @param absentDirectory CAT-owned directory path
	 * @throws IOException if creation fails
	 */
	void createDirectoryNew(Path absentDirectory) throws IOException;

	/**
	 * Deletes an empty caller-owned directory.
	 *
	 * @param directory CAT-owned directory
	 * @throws IOException if deletion fails
	 */
	void deleteEmptyOwnedDirectory(Path directory) throws IOException;

	/**
	 * Forces directory metadata to storage or reports unsupported behavior.
	 *
	 * @param directory directory to force
	 * @throws IOException if forcing fails
	 */
	void forceDirectory(Path directory) throws IOException;
}
