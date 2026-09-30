package io.github.cowwoc.cat.common.optimizer.candidate;

import java.nio.file.Path;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

/**
 * One native generation edit request with exact expected source and destination states.
 *
 * @param operationId call-local idempotency key
 * @param kind requested edit kind
 * @param sourcePath project-relative source or creation target
 * @param destinationPath destination only for a rename
 * @param expectedSourceState required source prestate
 * @param expectedDestinationState absent destination prestate only for a rename
 * @param contentBase64 bounded exact bytes only for create or replace
 */
public record GenerationEditOperation(String operationId, Kind kind, Path sourcePath,
	Optional<Path> destinationPath, GenerationExpectedState expectedSourceState,
	Optional<GenerationExpectedState> expectedDestinationState, Optional<String> contentBase64)
{
	/** Maximum edit content accepted from one native call. */
	public static final int MAX_CONTENT_BYTES = 16 * 1024 * 1024;

	/** Validates the operation without trusting its expected hashes as observations. */
	public GenerationEditOperation
	{
		if (operationId == null || operationId.isBlank())
			throw new IllegalArgumentException("Generation operation id must be non-blank");
		Objects.requireNonNull(kind, "kind");
		validatePath(sourcePath);
		Objects.requireNonNull(destinationPath, "destinationPath");
		Objects.requireNonNull(expectedSourceState, "expectedSourceState");
		Objects.requireNonNull(expectedDestinationState, "expectedDestinationState");
		Objects.requireNonNull(contentBase64, "contentBase64");
		if (kind == Kind.RENAME)
		{
			if (destinationPath.isEmpty() || expectedDestinationState.isEmpty())
				throw new IllegalArgumentException("RENAME requires destination path and expected destination state");
			validatePath(destinationPath.orElseThrow());
			if (sourcePath.equals(destinationPath.orElseThrow()) ||
				expectedDestinationState.orElseThrow().presence() != GenerationExpectedState.Presence.ABSENT)
				throw new IllegalArgumentException("RENAME destination must be distinct and expected absent");
		}
		else if (destinationPath.isPresent() || expectedDestinationState.isPresent())
			throw new IllegalArgumentException("Only RENAME may have a destination");
		if (kind == Kind.CREATE && expectedSourceState.presence() != GenerationExpectedState.Presence.ABSENT ||
			kind != Kind.CREATE && expectedSourceState.presence() != GenerationExpectedState.Presence.REGULAR)
			throw new IllegalArgumentException("Generation edit source expected state disagrees with operation kind");
		if (kind == Kind.CREATE || kind == Kind.REPLACE)
		{
			if (contentBase64.isEmpty())
				throw new IllegalArgumentException("CREATE and REPLACE require content");
			decode(contentBase64.orElseThrow());
		}
		else if (contentBase64.isPresent())
			throw new IllegalArgumentException("DELETE and RENAME forbid content");
	}

	/**
	 * Returns bounded decoded bytes after constructor validation.
	 *
	 * @return exact bytes for CREATE/REPLACE
	 */
	public byte[] decodedContent()
	{
		if (contentBase64.isEmpty())
			throw new IllegalStateException("This operation does not contain content");
		return decode(contentBase64.orElseThrow());
	}

	/**
	 * Rejects an absolute, dot, or traversal component before root resolution.
	 *
	 * @param path raw native path
	 */
	private static void validatePath(Path path)
	{
		Objects.requireNonNull(path, "path");
		if (path.isAbsolute() || path.toString().isEmpty())
			throw new IllegalArgumentException("Generation edit path must be nonempty and project-relative: " + path);
		for (Path component: path)
		{
			if (component.toString().equals(".") || component.toString().equals(".."))
				throw new IllegalArgumentException("Generation edit path contains a dot component: " + path);
		}
	}

	/**
	 * Decodes bounded native bytes.
	 *
	 * @param value strict Base64 text
	 * @return decoded bytes
	 */
	private static byte[] decode(String value)
	{
		if (value.length() > 4L * ((MAX_CONTENT_BYTES + 2L) / 3L))
			throw new IllegalArgumentException("Generation edit content exceeds maximum bytes");
		byte[] bytes;
		try
		{
			bytes = Base64.getDecoder().decode(value);
		}
		catch (IllegalArgumentException e)
		{
			throw new IllegalArgumentException("Generation edit content is not valid Base64", e);
		}
		if (bytes.length > MAX_CONTENT_BYTES)
			throw new IllegalArgumentException("Generation edit content exceeds maximum bytes");
		return bytes;
	}

	/** Native edit kinds. */
	public enum Kind
	{
		/** Create an absent file. */
		CREATE,
		/** Replace a regular file. */
		REPLACE,
		/** Delete a regular file. */
		DELETE,
		/** Move a regular file to an absent pathname. */
		RENAME
	}
}
