package io.github.cowwoc.cat.common.optimizer.state;

/**
 * Committed campaign state and embedded receipt identity returned by a transition.
 *
 * @param resultingCounter committed transition counter
 * @param phase committed campaign phase
 * @param lineageId active lineage identity, if confirmed
 * @param candidateId active candidate identity, if selected
 * @param receiptId embedded transition receipt identity
 */
public record TransitionResult(long resultingCounter, CampaignStatus phase, String lineageId,
	String candidateId, String receiptId)
{
	/**
	 * Validates committed result metadata.
	 */
	public TransitionResult
	{
		if (resultingCounter < 0 || phase == null || receiptId == null || receiptId.isBlank())
			throw new IllegalArgumentException("Transition result is incomplete");
	}
}
