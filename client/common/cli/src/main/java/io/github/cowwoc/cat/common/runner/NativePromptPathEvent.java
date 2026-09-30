package io.github.cowwoc.cat.common.runner;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Records one native path signal without claiming which source bytes the host consumed.
 *
 * @param conversationId the native conversation identity
 * @param nativeEventOrdinal the retained native event ordinal
 * @param normalizedRelativePath the observed path relative to the run project
 * @param nativeReason the native load reason when supplied
 * @param triggerPath the native trigger path when supplied
 * @param parentPath the native parent path when supplied
 * @param retainedNativeEventRef the immutable raw event reference
 */
public record NativePromptPathEvent(String conversationId,
	long nativeEventOrdinal,
	Path normalizedRelativePath,
	Optional<String> nativeReason,
	Optional<Path> triggerPath,
	Optional<Path> parentPath,
	EvidenceRef retainedNativeEventRef)
{
	/**
	 * Validates one path observation.
	 */
	public NativePromptPathEvent
	{
		Objects.requireNonNull(conversationId, "conversationId");
		if (conversationId.isBlank())
			throw new IllegalArgumentException("conversationId must not be blank");
		if (nativeEventOrdinal < 1)
			throw new IllegalArgumentException("nativeEventOrdinal must be positive");
		checkRelative(normalizedRelativePath);
		Objects.requireNonNull(nativeReason, "nativeReason");
		nativeReason.ifPresent(value ->
		{
			if (value.isBlank())
				throw new IllegalArgumentException("nativeReason must not be blank");
		});
		Objects.requireNonNull(triggerPath, "triggerPath");
		triggerPath.ifPresent(NativePromptPathEvent::checkRelative);
		Objects.requireNonNull(parentPath, "parentPath");
		parentPath.ifPresent(NativePromptPathEvent::checkRelative);
		Objects.requireNonNull(retainedNativeEventRef, "retainedNativeEventRef");
	}

	/**
	 * Requires a normalized project-relative path.
	 *
	 * @param path the observed path
	 */
	private static void checkRelative(Path path)
	{
		Objects.requireNonNull(path, "path");
		if (path.isAbsolute() || path.toString().isEmpty() || !path.equals(path.normalize()))
			throw new IllegalArgumentException("path must be normalized and relative");
	}
}
