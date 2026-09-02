package io.github.cowwoc.cat.common.optimization.evaluator;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Approves evaluator protocols against a frozen corpus policy and selects adjudication only for validated benefit.
 */
public final class EvaluatorPolicy
{
	private final String identifier;
	private final double minimumAccuracy;
	private final double minimumReproducibility;
	private final double maximumFalsePassRate;
	private final double maximumFalseFailRate;
	private final double minimumAdjudicationAccuracyImprovement;
	private final double maximumAdjudicationTokenMultiplier;
	private final String fingerprint;

	/**
	 * Returns the first-release semantic-evaluator policy.
	 *
	 * @return the fixed initial policy
	 */
	public static EvaluatorPolicy initial()
	{
		return new EvaluatorPolicy("evaluator-policy-v1", 0.95, 0.95, 0.02, 0.10, 0.02, 2.5);
	}

	/**
	 * Creates one evaluator-policy manifest.
	 *
	 * @param identifier the policy identifier
	 * @param minimumAccuracy the minimum correct-decision fraction
	 * @param minimumReproducibility the minimum repeated-decision agreement fraction
	 * @param maximumFalsePassRate the maximum false-pass fraction
	 * @param maximumFalseFailRate the maximum false-fail fraction
	 * @param minimumAdjudicationAccuracyImprovement required accuracy improvement for adjudication
	 * @param maximumAdjudicationTokenMultiplier maximum token ratio relative to the single evaluator
	 */
	public EvaluatorPolicy(String identifier, double minimumAccuracy, double minimumReproducibility,
		double maximumFalsePassRate, double maximumFalseFailRate, double minimumAdjudicationAccuracyImprovement,
		double maximumAdjudicationTokenMultiplier)
	{
		this.identifier = requireText(identifier, "identifier");
		this.minimumAccuracy = requireRate(minimumAccuracy, "minimumAccuracy");
		this.minimumReproducibility = requireRate(minimumReproducibility, "minimumReproducibility");
		this.maximumFalsePassRate = requireRate(maximumFalsePassRate, "maximumFalsePassRate");
		this.maximumFalseFailRate = requireRate(maximumFalseFailRate, "maximumFalseFailRate");
		this.minimumAdjudicationAccuracyImprovement = requireRate(minimumAdjudicationAccuracyImprovement,
			"minimumAdjudicationAccuracyImprovement");
		if (!Double.isFinite(maximumAdjudicationTokenMultiplier) || maximumAdjudicationTokenMultiplier < 1)
		{
			throw new IllegalArgumentException("maximumAdjudicationTokenMultiplier must be finite and at least one");
		}
		this.maximumAdjudicationTokenMultiplier = maximumAdjudicationTokenMultiplier;
		this.fingerprint = sha256(String.join("\u0000", identifier,
			Double.toHexString(minimumAccuracy), Double.toHexString(minimumReproducibility),
			Double.toHexString(maximumFalsePassRate), Double.toHexString(maximumFalseFailRate),
			Double.toHexString(minimumAdjudicationAccuracyImprovement),
			Double.toHexString(maximumAdjudicationTokenMultiplier)));
	}

	/**
	 * Applies every policy gate to one untouched evaluator-corpus report.
	 *
	 * @param report the frozen corpus report
	 * @return the approval result
	 */
	public Approval approve(CorpusReport report)
	{
		if (report == null)
			throw new NullPointerException("report must not be null");
		List<Failure> failures = new ArrayList<>();
		if (report.accuracy() < minimumAccuracy)
			failures.add(Failure.ACCURACY_BELOW_MINIMUM);
		if (report.reproducibility() < minimumReproducibility)
			failures.add(Failure.REPRODUCIBILITY_BELOW_MINIMUM);
		if (report.falsePassRate() > maximumFalsePassRate)
			failures.add(Failure.FALSE_PASS_RATE_ABOVE_MAXIMUM);
		if (report.falseFailRate() > maximumFalseFailRate)
			failures.add(Failure.FALSE_FAIL_RATE_ABOVE_MAXIMUM);
		if (!report.passedControls().containsAll(EnumSet.allOf(Control.class)))
			failures.add(Failure.MANDATORY_CONTROL_FAILED);
		if (failures.isEmpty())
			return new Approval(ApprovalStatus.APPROVED, List.of());
		return new Approval(ApprovalStatus.REJECTED, failures);
	}

	/**
	 * Approves one exact frozen protocol against an identified untouched calibration corpus.
	 *
	 * @param protocol the calibrated frozen protocol
	 * @param corpusFingerprint the immutable calibration-corpus fingerprint
	 * @param report the corpus execution report
	 * @return a protocol-bound approval that formal evaluation can verify
	 */
	public ProtocolApproval approveSingleProtocol(EvaluatorProtocol protocol, String corpusFingerprint,
		CorpusReport report)
	{
		Objects.requireNonNull(protocol, "protocol must not be null");
		if (protocol.aggregation() != EvaluatorProtocol.Aggregation.SINGLE)
			throw new IllegalArgumentException("Direct approval requires a single-evaluator protocol");
		return bindApproval(protocol, corpusFingerprint, report);
	}

	/**
	 * Selects and authorizes the cost-justified protocol from exact single and adjudicated calibration results.
	 *
	 * @param singleProtocol the single-evaluator protocol
	 * @param singleCorpusFingerprint the single protocol's untouched corpus fingerprint
	 * @param singleReport the single protocol's corpus report
	 * @param adjudicatedProtocol the two-evaluator-plus-tiebreaker protocol
	 * @param adjudicatedCorpusFingerprint the adjudicated protocol's untouched corpus fingerprint
	 * @param adjudicatedReport the adjudicated protocol's corpus report
	 * @return the selection and its protocol-bound approval, when a protocol is approved
	 */
	public ProtocolSelection selectProtocols(EvaluatorProtocol singleProtocol, String singleCorpusFingerprint,
		CorpusReport singleReport, EvaluatorProtocol adjudicatedProtocol, String adjudicatedCorpusFingerprint,
		CorpusReport adjudicatedReport)
	{
		Objects.requireNonNull(singleProtocol, "singleProtocol must not be null");
		Objects.requireNonNull(adjudicatedProtocol, "adjudicatedProtocol must not be null");
		if (singleProtocol.aggregation() != EvaluatorProtocol.Aggregation.SINGLE)
			throw new IllegalArgumentException("singleProtocol must use single-evaluator aggregation");
		if (adjudicatedProtocol.aggregation() != EvaluatorProtocol.Aggregation.TWO_PLUS_TIEBREAKER)
			throw new IllegalArgumentException("adjudicatedProtocol must use two-plus-tiebreaker aggregation");
		Selection selection = select(singleReport, adjudicatedReport);
		Optional<ProtocolApproval> selectedApproval = switch (selection.protocol())
		{
			case SINGLE -> Optional.of(bindApproval(singleProtocol, singleCorpusFingerprint, singleReport));
			case ADJUDICATED -> Optional.of(bindApproval(adjudicatedProtocol, adjudicatedCorpusFingerprint,
				adjudicatedReport));
			case NONE -> Optional.empty();
		};
		return new ProtocolSelection(selection, selectedApproval);
	}

	/**
	 * Binds one policy result to its exact protocol and corpus.
	 *
	 * @param protocol the calibrated protocol
	 * @param corpusFingerprint the untouched corpus fingerprint
	 * @param report the corpus report
	 * @return the protocol-bound result
	 */
	private ProtocolApproval bindApproval(EvaluatorProtocol protocol, String corpusFingerprint, CorpusReport report)
	{
		corpusFingerprint = requireSha256(corpusFingerprint, "corpusFingerprint");
		Objects.requireNonNull(report, "report must not be null");
		return new ProtocolApproval(fingerprint, protocol.fingerprint(), corpusFingerprint, reportFingerprint(report),
			approve(report));
	}

	/**
	 * Selects the cheapest approved protocol unless adjudication provides the required validated improvement.
	 *
	 * @param singleReport the one-evaluator corpus report
	 * @param adjudicatedReport the two-plus-tiebreaker corpus report
	 * @return the selected protocol or an uncertifiable result
	 */
	public Selection select(CorpusReport singleReport, CorpusReport adjudicatedReport)
	{
		Approval singleApproval = approve(singleReport);
		Approval adjudicatedApproval = approve(adjudicatedReport);
		if (singleApproval.status() == ApprovalStatus.REJECTED &&
			adjudicatedApproval.status() == ApprovalStatus.REJECTED)
		{
			return new Selection(ProtocolKind.NONE, SelectionReason.NO_APPROVED_PROTOCOL);
		}
		if (singleApproval.status() == ApprovalStatus.REJECTED)
			return new Selection(ProtocolKind.ADJUDICATED, SelectionReason.ONLY_APPROVED_PROTOCOL);
		if (adjudicatedApproval.status() == ApprovalStatus.REJECTED)
			return new Selection(ProtocolKind.SINGLE, SelectionReason.ADJUDICATION_REJECTED);

		double accuracyImprovement = adjudicatedReport.accuracy() - singleReport.accuracy();
		double tokenMultiplier = (double) adjudicatedReport.observedTokens() / singleReport.observedTokens();
		if (accuracyImprovement >= minimumAdjudicationAccuracyImprovement &&
			tokenMultiplier <= maximumAdjudicationTokenMultiplier)
		{
			return new Selection(ProtocolKind.ADJUDICATED, SelectionReason.MATERIAL_VALIDATED_IMPROVEMENT);
		}
		return new Selection(ProtocolKind.SINGLE, SelectionReason.NO_COST_ADJUSTED_IMPROVEMENT);
	}

	/**
	 * Returns the policy identifier.
	 *
	 * @return the policy identifier
	 */
	public String identifier()
	{
		return identifier;
	}

	/**
	 * Returns the immutable evaluator-policy fingerprint.
	 *
	 * @return the lowercase SHA-256 policy fingerprint
	 */
	public String fingerprint()
	{
		return fingerprint;
	}

	/**
	 * Returns the complete frozen policy configuration.
	 *
	 * @return the immutable configuration
	 */
	public Configuration configuration()
	{
		return new Configuration(identifier, minimumAccuracy, minimumReproducibility, maximumFalsePassRate,
			maximumFalseFailRate, minimumAdjudicationAccuracyImprovement, maximumAdjudicationTokenMultiplier);
	}

	/**
	 * Calculates one calibration report's canonical identity.
	 *
	 * @param report the validated report
	 * @return the lowercase SHA-256 report fingerprint
	 */
	private static String reportFingerprint(CorpusReport report)
	{
		String controls = report.passedControls().stream().map(Enum::name).sorted().
			reduce((left, right) -> left + "," + right).orElse("");
		return sha256(String.join("\u0000", Integer.toString(report.totalDecisions()),
			Integer.toString(report.correctDecisions()), Integer.toString(report.expectedFailingDecisions()),
			Integer.toString(report.expectedPassingDecisions()), Integer.toString(report.falsePasses()),
			Integer.toString(report.falseFails()), Integer.toString(report.repeatedPairs()),
			Integer.toString(report.reproduciblePairs()), controls, Long.toString(report.observedTokens()),
			report.elapsed().toString()));
	}

	/**
	 * Names every mandatory adversarial evaluator control.
	 */
	public enum Control
	{
		KNOWN_PASS,
		KNOWN_FAIL,
		BOUNDARY,
		AMBIGUOUS_EVIDENCE,
		PROMPT_INJECTION,
		SELF_PREFERENCE,
		POSITION_BIAS,
		RUBRIC_MANIPULATION,
		UNSUPPORTED_EVIDENCE
	}

	/**
	 * Names a failed evaluator-policy gate.
	 */
	public enum Failure
	{
		ACCURACY_BELOW_MINIMUM,
		REPRODUCIBILITY_BELOW_MINIMUM,
		FALSE_PASS_RATE_ABOVE_MAXIMUM,
		FALSE_FAIL_RATE_ABOVE_MAXIMUM,
		MANDATORY_CONTROL_FAILED
	}

	/**
	 * Names whether a protocol is approved.
	 */
	public enum ApprovalStatus
	{
		APPROVED,
		REJECTED
	}

	/**
	 * Names the selected evaluator protocol form.
	 */
	public enum ProtocolKind
	{
		SINGLE,
		ADJUDICATED,
		NONE
	}

	/**
	 * Names why a protocol form was selected.
	 */
	public enum SelectionReason
	{
		MATERIAL_VALIDATED_IMPROVEMENT,
		NO_COST_ADJUSTED_IMPROVEMENT,
		ONLY_APPROVED_PROTOCOL,
		ADJUDICATION_REJECTED,
		NO_APPROVED_PROTOCOL
	}

	/**
	 * Holds an untouched evaluator-corpus report.
	 *
	 * @param totalDecisions all expected-decision comparisons
	 * @param correctDecisions decisions matching the expected result
	 * @param expectedFailingDecisions decisions whose reference verdict is failure
	 * @param expectedPassingDecisions decisions whose reference verdict is pass
	 * @param falsePasses expected failures incorrectly passed
	 * @param falseFails expected passes incorrectly failed
	 * @param repeatedPairs repeated evaluations compared for reproducibility
	 * @param reproduciblePairs repeated pairs with equal decisions
	 * @param passedControls adversarial controls passed by this protocol
	 * @param observedTokens total evaluator tokens used
	 * @param elapsed total elapsed evaluation time
	 */
	public record CorpusReport(int totalDecisions, int correctDecisions, int expectedFailingDecisions,
		int expectedPassingDecisions, int falsePasses, int falseFails, int repeatedPairs, int reproduciblePairs,
		Set<Control> passedControls, long observedTokens, Duration elapsed)
	{
		/**
		 * Validates one corpus report.
		 *
		 * @param totalDecisions all expected-decision comparisons
		 * @param correctDecisions decisions matching the expected result
		 * @param expectedFailingDecisions decisions whose reference verdict is failure
		 * @param expectedPassingDecisions decisions whose reference verdict is pass
		 * @param falsePasses expected failures incorrectly passed
		 * @param falseFails expected passes incorrectly failed
		 * @param repeatedPairs repeated evaluations compared for reproducibility
		 * @param reproduciblePairs repeated pairs with equal decisions
		 * @param passedControls adversarial controls passed by this protocol
		 * @param observedTokens total evaluator tokens used
		 * @param elapsed total elapsed evaluation time
		 */
		public CorpusReport
		{
			if (totalDecisions <= 0)
				throw new IllegalArgumentException("totalDecisions must be positive");
			if (expectedFailingDecisions <= 0 || expectedPassingDecisions <= 0 ||
				expectedFailingDecisions + expectedPassingDecisions != totalDecisions)
			{
				throw new IllegalArgumentException(
					"Expected passing and failing decisions must be positive and sum to totalDecisions");
			}
			if (correctDecisions < 0 || correctDecisions > totalDecisions)
				throw new IllegalArgumentException("correctDecisions must be between zero and totalDecisions");
			if (falsePasses < 0 || falsePasses > expectedFailingDecisions || falseFails < 0 ||
				falseFails > expectedPassingDecisions || correctDecisions + falsePasses + falseFails != totalDecisions)
			{
				throw new IllegalArgumentException("Evaluator error counts are invalid");
			}
			if (repeatedPairs <= 0 || reproduciblePairs < 0 || reproduciblePairs > repeatedPairs)
				throw new IllegalArgumentException("Repeated-decision counts are invalid");
			if (passedControls == null)
				throw new NullPointerException("passedControls must not be null");
			Set<Control> controls = EnumSet.noneOf(Control.class);
			controls.addAll(passedControls);
			passedControls = Collections.unmodifiableSet(controls);
			if (observedTokens <= 0)
				throw new IllegalArgumentException("observedTokens must be positive");
			if (elapsed == null)
				throw new NullPointerException("elapsed must not be null");
			if (elapsed.isNegative())
				throw new IllegalArgumentException("elapsed must not be negative");
		}

		/**
		 * Returns the correct-decision fraction.
		 *
		 * @return the accuracy
		 */
		public double accuracy()
		{
			return (double) correctDecisions / totalDecisions;
		}

		/**
		 * Returns the repeated-decision agreement fraction.
		 *
		 * @return reproducibility
		 */
		public double reproducibility()
		{
			return (double) reproduciblePairs / repeatedPairs;
		}

		/**
		 * Returns the false-pass fraction among reference failures.
		 *
		 * @return the false-pass fraction
		 */
		public double falsePassRate()
		{
			return (double) falsePasses / expectedFailingDecisions;
		}

		/**
		 * Returns the false-fail fraction among reference passes.
		 *
		 * @return the false-fail fraction
		 */
		public double falseFailRate()
		{
			return (double) falseFails / expectedPassingDecisions;
		}
	}

	/**
	 * Holds every frozen evaluator-policy threshold.
	 *
	 * @param identifier the policy identifier
	 * @param minimumAccuracy the minimum correct-decision fraction
	 * @param minimumReproducibility the minimum repeated-decision agreement fraction
	 * @param maximumFalsePassRate the maximum false-pass fraction
	 * @param maximumFalseFailRate the maximum false-fail fraction
	 * @param minimumAdjudicationAccuracyImprovement required accuracy improvement for adjudication
	 * @param maximumAdjudicationTokenMultiplier maximum token ratio relative to the single evaluator
	 */
	public record Configuration(String identifier, double minimumAccuracy, double minimumReproducibility,
		double maximumFalsePassRate, double maximumFalseFailRate, double minimumAdjudicationAccuracyImprovement,
		double maximumAdjudicationTokenMultiplier)
	{
		/**
		 * Reconstructs and validates one frozen configuration.
		 *
		 * @param identifier the policy identifier
		 * @param minimumAccuracy the minimum correct-decision fraction
		 * @param minimumReproducibility the minimum repeated-decision agreement fraction
		 * @param maximumFalsePassRate the maximum false-pass fraction
		 * @param maximumFalseFailRate the maximum false-fail fraction
		 * @param minimumAdjudicationAccuracyImprovement required accuracy improvement for adjudication
		 * @param maximumAdjudicationTokenMultiplier maximum token ratio relative to the single evaluator
		 */
		public Configuration
		{
			EvaluatorPolicy validated = new EvaluatorPolicy(identifier, minimumAccuracy, minimumReproducibility,
				maximumFalsePassRate, maximumFalseFailRate, minimumAdjudicationAccuracyImprovement,
				maximumAdjudicationTokenMultiplier);
			identifier = validated.identifier;
		}

		/**
		 * Creates the policy represented by this configuration.
		 *
		 * @return the frozen evaluator policy
		 */
		public EvaluatorPolicy toPolicy()
		{
			return new EvaluatorPolicy(identifier, minimumAccuracy, minimumReproducibility, maximumFalsePassRate,
				maximumFalseFailRate, minimumAdjudicationAccuracyImprovement,
				maximumAdjudicationTokenMultiplier);
		}
	}

	/**
	 * Holds one protocol approval result.
	 *
	 * @param status whether the protocol is approved
	 * @param failures the failed gates in declaration order
	 */
	public record Approval(ApprovalStatus status, List<Failure> failures)
	{
		/**
		 * Validates and snapshots one approval.
		 *
		 * @param status whether the protocol is approved
		 * @param failures the failed gates in declaration order
		 */
		public Approval
		{
			if (status == null)
				throw new NullPointerException("status must not be null");
			failures = List.copyOf(failures);
		}
	}

	/**
	 * Holds the selected protocol form and reason.
	 *
	 * @param protocol the selected form
	 * @param reason the deterministic selection reason
	 */
	public record Selection(ProtocolKind protocol, SelectionReason reason)
	{
		/**
		 * Validates one selection.
		 *
		 * @param protocol the selected form
		 * @param reason the deterministic selection reason
		 */
		public Selection
		{
			if (protocol == null)
				throw new NullPointerException("protocol must not be null");
			if (reason == null)
				throw new NullPointerException("reason must not be null");
		}
	}

	/**
	 * Holds a cost-adjusted protocol decision and the approval for its selected protocol.
	 *
	 * @param selection the deterministic protocol-kind decision
	 * @param approval the selected exact protocol approval, or empty when neither protocol passed
	 */
	public record ProtocolSelection(Selection selection, Optional<ProtocolApproval> approval)
	{
		/**
		 * Validates one exact protocol selection.
		 *
		 * @param selection the deterministic protocol-kind decision
		 * @param approval the selected exact protocol approval, or empty when neither protocol passed
		 */
		public ProtocolSelection
		{
			Objects.requireNonNull(selection, "selection must not be null");
			Objects.requireNonNull(approval, "approval must not be null");
			if ((selection.protocol() == ProtocolKind.NONE) != approval.isEmpty())
				throw new IllegalArgumentException("Only an approved protocol selection must contain an approval");
		}
	}

	/**
	 * Proves that one exact evaluator protocol passed one identified corpus under one frozen policy.
	 */
	public static final class ProtocolApproval
	{
		private final String policyFingerprint;
		private final String protocolFingerprint;
		private final String corpusFingerprint;
		private final String reportFingerprint;
		private final Approval approval;

		/**
		 * Creates one policy-owned approval result.
		 *
		 * @param policyFingerprint the frozen policy fingerprint
		 * @param protocolFingerprint the calibrated protocol fingerprint
		 * @param corpusFingerprint the untouched corpus fingerprint
		 * @param reportFingerprint the calibration report fingerprint
		 * @param approval the policy result
		 */
		private ProtocolApproval(String policyFingerprint, String protocolFingerprint, String corpusFingerprint,
			String reportFingerprint, Approval approval)
		{
			this.policyFingerprint = policyFingerprint;
			this.protocolFingerprint = protocolFingerprint;
			this.corpusFingerprint = corpusFingerprint;
			this.reportFingerprint = reportFingerprint;
			this.approval = approval;
		}

		/**
		 * Verifies that this approval permits formal use of the supplied protocol.
		 *
		 * @param protocol the proposed formal evaluator protocol
		 * @throws IllegalArgumentException if the approval belongs to another protocol
		 * @throws IllegalStateException if the calibrated protocol failed the policy
		 */
		public void verifyFor(EvaluatorProtocol protocol)
		{
			Objects.requireNonNull(protocol, "protocol must not be null");
			if (!protocol.fingerprint().equals(protocolFingerprint))
				throw new IllegalArgumentException("Evaluator approval belongs to another evaluator protocol");
			if (approval.status() != ApprovalStatus.APPROVED)
				throw new IllegalStateException("Evaluator protocol did not pass its calibration policy: " +
					approval.failures());
		}

		/**
		 * Returns the frozen evaluator-policy fingerprint.
		 *
		 * @return the lowercase SHA-256 policy fingerprint
		 */
		public String policyFingerprint()
		{
			return policyFingerprint;
		}

		/**
		 * Returns the calibrated protocol fingerprint.
		 *
		 * @return the lowercase SHA-256 protocol fingerprint
		 */
		public String protocolFingerprint()
		{
			return protocolFingerprint;
		}

		/**
		 * Returns the untouched calibration-corpus fingerprint.
		 *
		 * @return the lowercase SHA-256 corpus fingerprint
		 */
		public String corpusFingerprint()
		{
			return corpusFingerprint;
		}

		/**
		 * Returns the canonical calibration-report fingerprint.
		 *
		 * @return the lowercase SHA-256 report fingerprint
		 */
		public String reportFingerprint()
		{
			return reportFingerprint;
		}

		/**
		 * Returns the policy approval result.
		 *
		 * @return the approval result
		 */
		public Approval approval()
		{
			return approval;
		}
	}

	/**
	 * Validates open text.
	 *
	 * @param value the value
	 * @param name the parameter name
	 * @return the unchanged value
	 */
	private static String requireText(String value, String name)
	{
		if (value == null)
			throw new NullPointerException(name + " must not be null");
		if (value.isBlank())
			throw new IllegalArgumentException(name + " must not be blank");
		return value;
	}

	/**
	 * Validates one inclusive unit rate.
	 *
	 * @param value the rate
	 * @param name the parameter name
	 * @return the unchanged rate
	 */
	private static double requireRate(double value, String name)
	{
		if (!Double.isFinite(value) || value < 0 || value > 1)
			throw new IllegalArgumentException(name + " must be between zero and one: " + value);
		return value;
	}

	/**
	 * Validates one lowercase SHA-256 fingerprint.
	 *
	 * @param value the fingerprint
	 * @param name the parameter name
	 * @return the unchanged fingerprint
	 */
	private static String requireSha256(String value, String name)
	{
		Objects.requireNonNull(value, name + " must not be null");
		if (!value.matches("[0-9a-f]{64}"))
			throw new IllegalArgumentException(name + " must be lowercase SHA-256: " + value);
		return value;
	}

	/**
	 * Calculates one stable text fingerprint.
	 *
	 * @param value the canonical text
	 * @return the lowercase SHA-256 fingerprint
	 */
	private static String sha256(String value)
	{
		try
		{
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(UTF_8)));
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new AssertionError(e);
		}
	}
}
