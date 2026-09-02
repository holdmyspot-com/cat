package io.github.cowwoc.cat.common.optimization;

import io.github.cowwoc.cat.common.ToolScope;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.json.JsonMapper;

/**
 * Initializes, advances, and reports one durable local certification process.
 */
public final class CertificationProcessCommand
{
	/**
	 * Runs one certification process command.
	 *
	 * @param args the action-specific certification process arguments
	 * @throws IOException if durable certification process evidence is malformed or cannot be updated
	 */
	public static void main(String[] args) throws IOException
	{
		Objects.requireNonNull(args, "args must not be null");
		try (ToolScope scope = new ToolScope())
		{
			if (args.length == 5 && args[0].equals("initialize"))
			{
				CertificationProcessLedger.initialize(scope.json(), Path.of(args[1]), args[2], args[3], args[4]);
				status(scope.json(), Path.of(args[1]), System.out);
				return;
			}
			if (args.length == 2 && args[0].equals("status"))
			{
				status(scope.json(), Path.of(args[1]), System.out);
				return;
			}
			if (args.length == 2 && args[0].equals("next"))
			{
				next(scope.json(), Path.of(args[1]), System.out);
				return;
			}
			if (args.length == 2 && args[0].equals("report-round"))
			{
				reportRound(scope.json(), Path.of(args[1]), System.out);
				return;
			}
			if (args.length == 3 && args[0].equals("record-evidence"))
			{
				CertificationProcessLedger.Entry entry =
					recordEvidence(scope.json(), Path.of(args[1]), Path.of(args[2]));
				System.out.println("recorded_sequence=" + entry.sequence());
				status(scope.json(), Path.of(args[1]), System.out);
				return;
			}
		}
		throw new IllegalArgumentException("Usage: certification-process initialize <manifest> " +
			"<certification-process-id> <contract-sha256> <environment-sha256> | " +
			"certification-process status <manifest> | " +
			"certification-process next <manifest> | " +
			"certification-process report-round <round-report> | certification-process record-evidence <manifest> " +
			"<operation-evidence>");
	}

	/**
	 * Reports the exact replayed state needed to resume one certification process.
	 *
	 * @param json the JSON codec
	 * @param manifestPath the certification process manifest
	 * @param report the report destination
	 * @throws IOException if certification process evidence is malformed or report writing fails
	 */
	public static void status(JsonMapper json, Path manifestPath, Appendable report) throws IOException
	{
		Objects.requireNonNull(report, "report must not be null");
		CertificationProcessLedger.Snapshot snapshot = CertificationProcessLedger.read(json, manifestPath);
		report.append("certification_process=").append(snapshot.certificationProcessIdentifier()).append('\n');
		report.append("sequence=").append(Long.toString(snapshot.state().sequence())).append('\n');
		report.append("terminal=").append(Boolean.toString(snapshot.state().terminal())).append('\n');
		if (snapshot.state().terminal())
		{
			report.append("terminal_result=").
				append(snapshot.state().terminalResult().orElseThrow().externalValue()).append('\n');
		}
		else
		{
			report.append("current_operation=").
				append(snapshot.state().currentOperation().orElseThrow().externalValue()).append('\n');
		}
		report.append("ledger_sha256=").append(snapshot.ledgerFingerprint()).append('\n');
	}

	/**
	 * Reports the registry-owned constraints and possible results for the certification process's next operation.
	 *
	 * @param json the JSON codec
	 * @param manifestPath the certification process manifest
	 * @param report the report destination
	 * @throws IOException if certification process evidence is malformed or report writing fails
	 */
	public static void next(JsonMapper json, Path manifestPath, Appendable report) throws IOException
	{
		Objects.requireNonNull(report, "report must not be null");
		CertificationProcessLedger.Snapshot snapshot = CertificationProcessLedger.read(json, manifestPath);
		report.append("certification_process=").append(snapshot.certificationProcessIdentifier()).append('\n');
		report.append("sequence=").append(Long.toString(snapshot.state().sequence())).append('\n');
		if (snapshot.state().terminal())
		{
			report.append("terminal_result=").
				append(snapshot.state().terminalResult().orElseThrow().externalValue()).append('\n');
			return;
		}

		OperationKind operation = snapshot.state().currentOperation().orElseThrow();
		OptimizationOperationRegistry.Definition definition = OptimizationOperationRegistry.definitions().get(operation);
		report.append("operation=").append(operation.externalValue()).append('\n');
		report.append("owner=").append(externalName(definition.owner())).append('\n');
		boolean modelConsuming =
			definition.modelCostBehavior() == OptimizationOperationRegistry.ModelCostBehavior.MAY_CONSUME_MODEL;
		report.append("model_consuming=").append(Boolean.toString(modelConsuming)).append('\n');
		report.append("recovery=").append(externalName(definition.recoveryBoundary())).append('\n');
		report.append("input_schema=").append(definition.inputSchemaId()).append('\n');
		report.append("evidence_schema=").append(definition.evidenceSchemaId()).append('\n');
		for (Map.Entry<OperationResultKind,
			java.util.Set<OperationKind>> transition : definition.transitions().entrySet())
		{
			String successors = transition.getValue().stream().map(OperationKind::externalValue).
				sorted().reduce((left, right) -> left + "," + right).orElse("-");
			report.append("result=").append(transition.getKey().externalValue()).append(" successors=").
				append(successors).append('\n');
		}
	}

	/**
	 * Converts a closed internal enum name to its stable CLI spelling.
	 *
	 * @param value the closed domain value
	 * @return the lowercase underscore-separated spelling
	 */
	private static String externalName(Enum<?> value)
	{
		return value.name().toLowerCase(Locale.ROOT);
	}

	/**
	 * Reports one retained optimization round.
	 *
	 * @param json the JSON codec
	 * @param reportPath the strict round-report document
	 * @param report the report destination
	 * @throws IOException if the round evidence is malformed or report writing fails
	 */
	public static void reportRound(JsonMapper json, Path reportPath, Appendable report) throws IOException
	{
		Objects.requireNonNull(report, "report must not be null");
		OptimizationRoundReport.Snapshot snapshot = OptimizationRoundReportDocument.read(json, reportPath);
		report.append(OptimizationRoundReport.render(snapshot));
	}

	/**
	 * Verifies operation-owned terminal evidence and records its certification process transition.
	 *
	 * @param json the JSON codec
	 * @param manifestPath the certification process manifest
	 * @param evidencePath the strict operation evidence envelope
	 * @return the retained or newly recorded transition
	 * @throws IOException if the certification process or operation evidence is invalid
	 */
	public static CertificationProcessLedger.Entry recordEvidence(JsonMapper json, Path manifestPath, Path evidencePath)
		throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(manifestPath, "manifestPath must not be null");
		Objects.requireNonNull(evidencePath, "evidencePath must not be null");
		CertificationProcessLedger.Snapshot certificationProcess = CertificationProcessLedger.read(json, manifestPath);
		OptimizationOperationEvidenceDocument.VerifiedEvidence evidence =
			OptimizationOperationEvidenceDocument.readAndVerify(json, evidencePath, certificationProcess);
		return CertificationProcessLedger.record(json, manifestPath, evidence.predecessorSequence(), evidence.result(),
			evidence.successor(), evidence.evidenceFingerprint(), evidence.completedAt());
	}

	/**
	 * Prevents construction of this static command owner.
	 */
	private CertificationProcessCommand()
	{
	}
}
