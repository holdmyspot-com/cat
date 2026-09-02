package io.github.cowwoc.cat.common.optimization.contract;

import io.github.cowwoc.cat.common.ToolScope;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Validates a certification-contract document and optionally writes its canonical form.
 */
public final class CertificationContractCommand
{
	/**
	 * Runs the certification-contract command.
	 *
	 * @param args the input document and optional canonical output document
	 * @throws IOException if input is malformed, output fails, or deterministic validation blocks the contract
	 */
	public static void main(String[] args) throws IOException
	{
		Objects.requireNonNull(args, "args must not be null");
		if (args.length < 1 || args.length > 2)
			throw new IllegalArgumentException("Usage: certification-contract <input.json> [canonical-output.json]");
		Optional<Path> output = Optional.empty();
		if (args.length == 2)
			output = Optional.of(Path.of(args[1]));
		try (ToolScope scope = new ToolScope())
		{
			CertificationContract.Status status = validate(scope.json(), Path.of(args[0]), output, System.out);
			if (status == CertificationContract.Status.BLOCKED)
				throw new IOException("Certification contract is blocked; correct the reported issues and retry");
		}
	}

	/**
	 * Validates one document and projects its deterministic result.
	 *
	 * @param json the JSON codec
	 * @param input the proposed contract document
	 * @param canonicalOutput the canonical output destination when requested
	 * @param report the report destination
	 * @return the ready or blocked result
	 * @throws IOException if input, output, or report writing fails
	 */
	public static CertificationContract.Status validate(JsonMapper json, Path input, Optional<Path> canonicalOutput,
		Appendable report) throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(input, "input must not be null");
		Objects.requireNonNull(canonicalOutput, "canonicalOutput must not be null");
		Objects.requireNonNull(report, "report must not be null");
		CertificationContract.Result result = CertificationContractDocument.read(json, input);
		if (result.status() == CertificationContract.Status.BLOCKED)
		{
			report.append("status=blocked\n");
			for (CertificationContract.Issue issue : result.issues())
			{
				report.append("issue=").append(issue.code().name()).append(" subject=").
					append(oneLine(issue.subject())).append(" detail=").append(oneLine(issue.detail())).append('\n');
			}
			return result.status();
		}
		CertificationContract contract = result.contract().orElseThrow();
		if (canonicalOutput.isPresent())
			CertificationContractDocument.write(json, canonicalOutput.orElseThrow(), contract);
		report.append("status=ready\n");
		report.append("fingerprint=").append(contract.fingerprint()).append('\n');
		return result.status();
	}

	/**
	 * Escapes line breaks in open-text diagnostics.
	 *
	 * @param value the diagnostic value
	 * @return a single-line representation
	 */
	private static String oneLine(String value)
	{
		return value.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n");
	}

	/**
	 * Prevents construction of this static command owner.
	 */
	private CertificationContractCommand()
	{
	}
}
