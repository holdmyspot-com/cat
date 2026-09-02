package io.github.cowwoc.cat.common.optimization.certification;

import io.github.cowwoc.cat.common.ToolScope;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exposes the durable family-sequential certification operations as one packaged command.
 */
public final class CertificationCommand
{
	/**
	 * Runs one certification operation using the system clock.
	 *
	 * @param args the operation and its arguments
	 * @throws IOException if durable evidence cannot be read, verified, or written
	 */
	public static void main(String[] args) throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			run(scope.json(), Clock.systemUTC(), args, System.out);
		}
	}

	/**
	 * Parses and runs one closed certification operation.
	 *
	 * @param json the ledger-owned JSON mapper
	 * @param clock the operation clock
	 * @param args the operation and its arguments
	 * @param report the operation report destination
	 * @throws IOException if durable evidence or report output cannot be read, verified, or written
	 */
	public static void run(JsonMapper json, Clock clock, String[] args, Appendable report) throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(clock, "clock must not be null");
		Objects.requireNonNull(args, "args must not be null");
		Objects.requireNonNull(report, "report must not be null");
		if (args.length == 0)
			throw new IllegalArgumentException(usage());

		Command command = Command.fromExternalValue(args[0]);
		switch (command)
		{
			case INITIALIZE_LINEAGE -> initializeLineage(json, args, report);
			case ASSIGN -> assign(json, clock.instant(), args, report);
			case INITIALIZE_CANDIDATE -> initializeCandidate(json, args, report);
			case DECIDE -> decide(json, args, report);
		}
	}

	/**
	 * Creates one lineage-wide allowance ledger using the fixed 5% summable schedule.
	 *
	 * @param json the ledger-owned JSON mapper
	 * @param args the command arguments
	 * @param report the report destination
	 * @throws IOException if the ledger cannot be initialized or the report cannot be written
	 */
	private static void initializeLineage(JsonMapper json, String[] args, Appendable report) throws IOException
	{
		requireArgumentCount(args, 4,
			"initialize-lineage <allowance-manifest.json> <lineage-id> <statistical-policy.json>");
		FamilySequentialPolicyDocument.Loaded loaded = FamilySequentialPolicyDocument.read(json, Path.of(args[3]));
		FamilySequentialPolicy policy = loaded.policy();
		CandidateAllowanceLedger.initialize(json, Path.of(args[1]), args[2], policy.lineageErrorBudget(),
			policy.candidateAllowanceSchedule());
		report.append("status=initialized\n");
		report.append("lineage=").append(args[2]).append('\n');
		report.append("error-budget=").append(policy.lineageErrorBudget().toPlainString()).append('\n');
		report.append("statistical-policy-sha256=").append(loaded.fingerprint()).append('\n');
	}

	/**
	 * Assigns the next immutable candidate allowance.
	 *
	 * @param json the ledger-owned JSON mapper
	 * @param assignedAt the assignment time
	 * @param args the command arguments
	 * @param report the report destination
	 * @throws IOException if the assignment cannot be retained or the report cannot be written
	 */
	private static void assign(JsonMapper json, Instant assignedAt, String[] args, Appendable report) throws IOException
	{
		requireArgumentCount(args, 3, "assign <allowance-manifest.json> <candidate-id>");
		CandidateAllowanceLedger.Assignment assignment = CandidateAllowanceLedger.assign(json, Path.of(args[1]),
			args[2], assignedAt);
		report.append("status=assigned\n");
		report.append("candidate=").append(assignment.candidateId()).append('\n');
		report.append("ordinal=").append(Integer.toString(assignment.ordinal())).append('\n');
		report.append("allowance=").append(assignment.allowance().toPlainString()).append('\n');
		report.append("allowance-entry-sha256=").append(assignment.entryFingerprint()).append('\n');
	}

	/**
	 * Creates a formal outcome ledger bound to an existing candidate assignment.
	 *
	 * @param json the ledger-owned JSON mapper
	 * @param args the command arguments
	 * @param report the report destination
	 * @throws IOException if the assignment is absent or the ledger cannot be initialized
	 */
	private static void initializeCandidate(JsonMapper json, String[] args, Appendable report) throws IOException
	{
		if (args.length < 10)
		{
			throw new IllegalArgumentException("Usage: initialize-candidate <trial-manifest.json> " +
				"<allowance-manifest.json> <candidate-id> <candidate-sha256> <contract-sha256> <environment-sha256> " +
				"<evaluator-policy-sha256> <statistical-policy.json> <family-id>...");
		}
		Path allowanceManifest = Path.of(args[2]);
		CandidateAllowanceLedger.Assignment assignment = CandidateAllowanceLedger.find(json, allowanceManifest,
			args[3]).orElseThrow(() -> new IOException("Candidate has no assigned lineage allowance: " + args[3]));
		FamilySequentialPolicyDocument.Loaded policy = FamilySequentialPolicyDocument.read(json, Path.of(args[8]));
		FormalTrialLedger.Identity identity = new FormalTrialLedger.Identity(args[3], args[4], args[5], args[6], args[7],
			policy.fingerprint(), assignment.entryFingerprint());
		FormalTrialLedger.initialize(json, Path.of(args[1]), identity,
			List.of(args).subList(9, args.length));
		report.append("status=initialized\n");
		report.append("candidate=").append(args[3]).append('\n');
		report.append("family-count=").append(Integer.toString(args.length - 9)).append('\n');
	}

	/**
	 * Projects the next proof-covered action from the verified ledgers.
	 *
	 * @param json the ledger-owned JSON mapper
	 * @param args the command arguments
	 * @param report the report destination
	 * @throws IOException if evidence is invalid or the report cannot be written
	 */
	private static void decide(JsonMapper json, String[] args, Appendable report) throws IOException
	{
		if (args.length != 4)
		{
			throw new IllegalArgumentException("Usage: decide <allowance-manifest.json> <trial-manifest.json> " +
				"<statistical-policy.json>");
		}
		FamilySequentialPolicyDocument.Loaded policy = FamilySequentialPolicyDocument.read(json, Path.of(args[3]));
		FamilySequentialDecision.Result result = FamilySequentialEngine.decide(json, Path.of(args[1]), Path.of(args[2]),
			policy);
		report.append("action=").append(ActionValue.from(result.action()).externalValue).append('\n');
		FamilyCertification.Result certification = result.certification();
		if (Double.isNaN(certification.currentLowerBound()))
			report.append("Current lower bound: unavailable\n");
		else
		{
			report.append("Current lower bound: ").append(String.format(Locale.ROOT, "%.1f%%",
				certification.currentLowerBound() * 100)).append('\n');
		}
		report.append("Target: above ").append(String.format(Locale.ROOT, "%.0f%%",
			policy.policy().decisionPolicy().target() * 100)).append('\n');
		if (result.familyId().isPresent())
			report.append("family=").append(result.familyId().orElseThrow()).append('\n');
	}

	/**
	 * Requires one exact command argument count.
	 *
	 * @param args the raw arguments
	 * @param expected the required count including the command
	 * @param commandUsage the operation-specific usage
	 */
	private static void requireArgumentCount(String[] args, int expected, String commandUsage)
	{
		if (args.length != expected)
			throw new IllegalArgumentException("Usage: " + commandUsage);
	}

	/**
	 * Returns the complete top-level usage.
	 *
	 * @return the command usage
	 */
	private static String usage()
	{
		return "Usage: certification <initialize-lineage|assign|initialize-candidate|decide> ...";
	}

	/**
	 * Names the supported certification commands.
	 */
	private enum Command
	{
		INITIALIZE_LINEAGE("initialize-lineage"),
		ASSIGN("assign"),
		INITIALIZE_CANDIDATE("initialize-candidate"),
		DECIDE("decide");

		private final String externalValue;

		/**
		 * Creates one command value.
		 *
		 * @param externalValue the exact CLI value
		 */
		Command(String externalValue)
		{
			this.externalValue = externalValue;
		}

		/**
		 * Parses one exact CLI value.
		 *
		 * @param value the untyped value
		 * @return the closed command
		 */
		private static Command fromExternalValue(String value)
		{
			for (Command candidate : values())
			{
				if (candidate.externalValue.equals(value))
					return candidate;
			}
			throw new IllegalArgumentException("Unsupported certification command: " + value);
		}
	}

	/**
	 * Maps proof-covered actions to stable report values.
	 */
	private enum ActionValue
	{
		SAMPLE_FAMILY("sample-family"),
		CERTIFIED("certified"),
		FUTILE("futile");

		private final String externalValue;

		/**
		 * Creates one report action.
		 *
		 * @param externalValue the stable report value
		 */
		ActionValue(String externalValue)
		{
			this.externalValue = externalValue;
		}

		/**
		 * Converts the engine action without accepting an untyped intermediate value.
		 *
		 * @param action the engine action
		 * @return the report action
		 */
		private static ActionValue from(FamilySequentialDecision.Action action)
		{
			return switch (action)
			{
				case SAMPLE_FAMILY -> SAMPLE_FAMILY;
				case CERTIFIED -> CERTIFIED;
				case FUTILE -> FUTILE;
			};
		}
	}

	/**
	 * Prevents construction of this static command owner.
	 */
	private CertificationCommand()
	{
	}
}
