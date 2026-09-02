package io.github.cowwoc.cat.common.optimization;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;
import tools.jackson.databind.json.JsonMapper;

/**
 * Immutable context shared with one operation handler.
 *
 * @param json the operation-owned JSON codec
 * @param clock the operation clock
 * @param certificationProcessDirectory the owning process directory
 * @param operationDirectory the operation directory
 * @param operationKind the selected operation
 * @param contractFingerprint the frozen contract fingerprint
 * @param environmentFingerprint the frozen environment fingerprint
 */
public record OptimizationContext(JsonMapper json, Clock clock, Path certificationProcessDirectory,
	Path operationDirectory, OperationKind operationKind, String contractFingerprint, String environmentFingerprint)
{
	/**
	 * Validates, normalizes, and binds the operation directory to its process.
	 *
	 * @param json the operation-owned JSON codec
	 * @param clock the operation clock
	 * @param certificationProcessDirectory the owning process directory
	 * @param operationDirectory the operation directory
	 * @param operationKind the selected operation
	 * @param contractFingerprint the frozen contract fingerprint
	 * @param environmentFingerprint the frozen environment fingerprint
	 */
	public OptimizationContext
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(clock, "clock must not be null");
		Objects.requireNonNull(certificationProcessDirectory, "certificationProcessDirectory must not be null");
		Objects.requireNonNull(operationDirectory, "operationDirectory must not be null");
		Objects.requireNonNull(operationKind, "operationKind must not be null");
		certificationProcessDirectory = certificationProcessDirectory.toAbsolutePath().normalize();
		operationDirectory = operationDirectory.toAbsolutePath().normalize();
		Path operationsRoot = certificationProcessDirectory.resolve("operations").normalize();
		if (!operationDirectory.startsWith(operationsRoot) || operationDirectory.equals(operationsRoot))
			throw new IllegalArgumentException("operationDirectory must be below certification-process operations");
		contractFingerprint = requireSha256(contractFingerprint, "contractFingerprint");
		environmentFingerprint = requireSha256(environmentFingerprint, "environmentFingerprint");
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
