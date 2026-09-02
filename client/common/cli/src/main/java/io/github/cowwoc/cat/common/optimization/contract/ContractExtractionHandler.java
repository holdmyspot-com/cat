package io.github.cowwoc.cat.common.optimization.contract;

import io.github.cowwoc.cat.common.Fingerprints;
import io.github.cowwoc.cat.common.RecordStore;
import io.github.cowwoc.cat.common.optimization.OperationKind;
import io.github.cowwoc.cat.common.optimization.OperationResultKind;
import io.github.cowwoc.cat.common.optimization.OptimizationContext;
import io.github.cowwoc.cat.common.optimization.OptimizationHandler;
import io.github.cowwoc.cat.common.optimization.OptimizationResult;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Runs the fixed contract-extraction actor graph and retains its registered terminal transition evidence. */
public final class ContractExtractionHandler implements OptimizationHandler<ContractExtractionInput>
{
	private final ContractExtractionWorkflow.ProposalAgent primary;
	private final ContractExtractionWorkflow.ProposalAgent challenger;
	private final ContractExtractionWorkflow.StagedAgent familyDesigner;
	private final ContractExtractionWorkflow.StagedAgent interactionDesigner;
	private final ContractExtractionWorkflow.StagedAgent evaluatorDesigner;
	private final ContractExtractionWorkflow.StagedAgent reviewer;
	private final ContractExtractionWorkflow.Resolver resolver;

	/**
	 * Creates one handler with every fixed actor required by the extraction workflow.
	 *
	 * @param primary the primary requirement extractor
	 * @param challenger the independent requirement challenger
	 * @param familyDesigner the prompt-test family designer
	 * @param interactionDesigner the interaction designer
	 * @param evaluatorDesigner the evaluator-protocol designer
	 * @param reviewer the monotonic reviewer
	 * @param resolver the bounded disagreement resolver
	 */
	public ContractExtractionHandler(ContractExtractionWorkflow.ProposalAgent primary,
		ContractExtractionWorkflow.ProposalAgent challenger, ContractExtractionWorkflow.StagedAgent familyDesigner,
		ContractExtractionWorkflow.StagedAgent interactionDesigner,
		ContractExtractionWorkflow.StagedAgent evaluatorDesigner, ContractExtractionWorkflow.StagedAgent reviewer,
		ContractExtractionWorkflow.Resolver resolver)
	{
		this.primary = Objects.requireNonNull(primary, "primary must not be null");
		this.challenger = Objects.requireNonNull(challenger, "challenger must not be null");
		this.familyDesigner = Objects.requireNonNull(familyDesigner, "familyDesigner must not be null");
		this.interactionDesigner = Objects.requireNonNull(interactionDesigner, "interactionDesigner must not be null");
		this.evaluatorDesigner = Objects.requireNonNull(evaluatorDesigner, "evaluatorDesigner must not be null");
		this.reviewer = Objects.requireNonNull(reviewer, "reviewer must not be null");
		this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
	}

	@Override
	public OperationKind operationKind()
	{
		return OperationKind.EXTRACT_CONTRACT;
	}

	@Override
	public Class<ContractExtractionInput> inputType()
	{
		return ContractExtractionInput.class;
	}

	@Override
	public OptimizationResult execute(OptimizationContext context, ContractExtractionInput input) throws IOException
	{
		Objects.requireNonNull(context, "context must not be null");
		Objects.requireNonNull(input, "input must not be null");
		if (context.operationKind() != operationKind())
			throw new IOException("Contract extraction handler received another operation: " + context.operationKind());
		Path inputPath = context.operationDirectory().resolve("input/inventory.json");
		CertificationContractDocument.writeDraft(context.json(), inputPath, input.inventory());
		if (!Fingerprints.ofFile(inputPath).equals(input.inventoryFingerprint()))
			throw new IOException("Contract extraction inventory fingerprint changed before actor invocation");

		ContractExtractionWorkflow.Result extracted = ContractExtractionWorkflow.extract(input.inventory(), primary,
			challenger, familyDesigner, interactionDesigner, evaluatorDesigner, reviewer, resolver);
		Path evidence = context.operationDirectory().resolve("contract-extraction-result.json");
		ObjectNode document = context.json().createObjectNode();
		document.put("schema_version", 1);
		document.put("status", extracted.status().name().toLowerCase(java.util.Locale.ROOT));
		ArrayNode stages = document.putArray("completed_stages");
		for (ContractExtractionWorkflow.Stage stage : extracted.completedStages())
			stages.add(stage.name().toLowerCase(java.util.Locale.ROOT));
		if (extracted.blockedStage().isPresent())
			document.put("blocked_stage", extracted.blockedStage().orElseThrow().name().toLowerCase(java.util.Locale.ROOT));
		else
			document.putNull("blocked_stage");
		OperationResultKind resultKind;
		Optional<OperationKind> successor;
		if (extracted.status() == ContractExtractionWorkflow.Status.READY)
		{
			Path contract = context.operationDirectory().resolve("contract.json");
			CertificationContractDocument.write(context.json(), contract, extracted.contract().orElseThrow());
			document.put("contract_sha256", Fingerprints.ofFile(contract));
			resultKind = OperationResultKind.READY;
			successor = Optional.of(OperationKind.VALIDATE_EVALUATOR_PROTOCOLS);
		}
		else
		{
			document.putNull("contract_sha256");
			resultKind = OperationResultKind.BLOCKED;
			successor = Optional.of(OperationKind.FINISH_PREPARATION);
		}
		RecordStore.write(context.json(), evidence, document);
		return new OptimizationResult(operationKind(), resultKind, evidence, Fingerprints.ofFile(evidence), successor);
	}
}
