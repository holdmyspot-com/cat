package io.github.cowwoc.cat.common.optimization;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Result and retained evidence produced by one optimization operation.
 *
 * @param operationKind the operation that produced the result
 * @param resultKind the registered result kind
 * @param evidencePath the retained result evidence
 * @param evidenceFingerprint the evidence SHA-256 fingerprint
 * @param successorKind the registered successor, if the result advances the workflow
 */
public record OptimizationResult(OperationKind operationKind, OperationResultKind resultKind, Path evidencePath,
	String evidenceFingerprint, Optional<OperationKind> successorKind)
{
	/**
	 * Validates and normalizes the operation result.
	 *
	 * @param operationKind the operation that produced the result
	 * @param resultKind the registered result kind
	 * @param evidencePath the retained result evidence
	 * @param evidenceFingerprint the evidence SHA-256 fingerprint
	 * @param successorKind the registered successor, if the result advances the workflow
	 */
	public OptimizationResult
	{
		Objects.requireNonNull(operationKind, "operationKind must not be null");
		Objects.requireNonNull(resultKind, "resultKind must not be null");
		Objects.requireNonNull(evidencePath, "evidencePath must not be null");
		evidencePath = evidencePath.toAbsolutePath().normalize();
		evidenceFingerprint = requireSha256(evidenceFingerprint, "evidenceFingerprint");
		Objects.requireNonNull(successorKind, "successorKind must not be null");
	}

	/**
	 * Validates one lowercase SHA-256 fingerprint.
	 *
	 * @param value the fingerprint
	 * @param name the parameter name
	 * @return the validated fingerprint
	 */
	private static String requireSha256(String value, String name)
	{
		Objects.requireNonNull(value, name + " must not be null");
		if (!value.matches("[0-9a-f]{64}"))
			throw new IllegalArgumentException(name + " must be a lowercase SHA-256 fingerprint");
		return value;
	}
}
