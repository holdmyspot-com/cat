package io.github.cowwoc.cat.common.optimization.contract;

import io.github.cowwoc.cat.common.optimization.OperationKind;
import io.github.cowwoc.cat.common.optimization.OptimizationInput;
import java.util.Objects;

/**
 * Immutable input for the durable {@code extract-contract} operation.
 *
 * @param inventory the source-traced immutable contract inventory
 * @param inventoryFingerprint the SHA-256 hash of the persisted inventory document
 */
public record ContractExtractionInput(CertificationContract.Draft inventory, String inventoryFingerprint)
	implements OptimizationInput
{
	/**
	 * Validates one extraction input.
	 *
	 * @param inventory the source-traced immutable contract inventory
	 * @param inventoryFingerprint the SHA-256 hash of the persisted inventory document
	 */
	public ContractExtractionInput
	{
		Objects.requireNonNull(inventory, "inventory must not be null");
		Objects.requireNonNull(inventoryFingerprint, "inventoryFingerprint must not be null");
		if (!inventoryFingerprint.matches("[0-9a-f]{64}"))
			throw new IllegalArgumentException("inventoryFingerprint must be a lowercase SHA-256 value");
	}

	/**
	 * Returns the only operation that may consume this input.
	 *
	 * @return {@link OperationKind#EXTRACT_CONTRACT}
	 */
	@Override
	public OperationKind operationKind()
	{
		return OperationKind.EXTRACT_CONTRACT;
	}

	/**
	 * Returns the fingerprint of the exact canonical inventory document.
	 *
	 * @return the input fingerprint
	 */
	@Override
	public String inputFingerprint()
	{
		return inventoryFingerprint;
	}
}
