package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.contract.AddedTestCase;
import com.holdmyspot.cat.common.optimizer.contract.Contract;
import com.holdmyspot.cat.common.optimizer.execution.PromptAuthorConflict;
import com.holdmyspot.cat.common.optimizer.inventory.AuthorityWarning;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import com.holdmyspot.cat.common.optimizer.inventory.FileDelta;
import com.holdmyspot.cat.common.optimizer.port.RateLimitWindow;
import com.holdmyspot.cat.common.optimizer.port.SessionRole;
import com.holdmyspot.cat.common.optimizer.port.TokenUsage;
import com.holdmyspot.cat.common.optimizer.statistics.CapCalibration;

import java.time.Instant;
import java.util.List;

/**
 * An input to {@link Transitions#apply(Campaign, CampaignEvent)} that requests a campaign state change.
 */
public sealed interface CampaignEvent
{
	/**
	 * Records token counts reported by a finished nested session.
	 *
	 * @param role the session's role
	 * @param usage the reported counts
	 */
	record ObserveTokenUsage(SessionRole role, TokenUsage usage) implements CampaignEvent
	{
		/**
		 * Creates the observation.
		 *
		 * @param role the session's role
		 * @param usage the reported counts
		 * @throws NullPointerException if {@code role} or {@code usage} are null
		 */
		public ObserveTokenUsage
		{
			requireThat(role, "role").isNotNull();
			requireThat(usage, "usage").isNotNull();
		}
	}

	/**
	 * A revision that waits for confirmation moved the campaign onto a fork that holds the files the revised contract
	 * needs. The incumbent and the working candidate keep their numbers and edits; their snapshots on the larger fork
	 * replace their earlier ones, which stay in the earlier lineages' evidence.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param incumbent the incumbent's snapshot on the larger fork
	 * @param working the working candidate's snapshot on the larger fork, which equals {@code incumbent} while the
	 *   incumbent is the working candidate
	 */
	record ExtendFork(Sha256 incumbent, Sha256 working) implements CampaignEvent
	{
		/**
		 * Creates the event.
		 *
		 * @param incumbent the incumbent's snapshot on the larger fork
		 * @param working the working candidate's snapshot on the larger fork, which equals {@code incumbent} while the
		 *   incumbent is the working candidate
		 * @throws NullPointerException if any of the arguments are null
		 */
		public ExtendFork
		{
			requireThat(incumbent, "incumbent").isNotNull();
			requireThat(working, "working").isNotNull();
		}
	}

	/**
	 * The user confirmed a contract, which starts a new evidence history called a lineage.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param contract the confirmed contract, with the certification scope that the user chose
	 * @param contractHash the contract's identity
	 * @param seed the seed of the lineage's development and baseline draw streams
	 */
	record Confirm(Contract contract, Sha256 contractHash, long seed) implements CampaignEvent
	{
		/**
		 * Creates the event.
		 *
		 * @param contract the confirmed contract, with the certification scope that the user chose
		 * @param contractHash the contract's identity
		 * @param seed the seed of the lineage's development and baseline draw streams
		 * @throws NullPointerException if {@code contract} or {@code contractHash} are null
		 * @throws IllegalArgumentException if {@code contract} has no certification scope
		 */
		public Confirm
		{
			requireThat(contract, "contract").isNotNull();
			requireThat(contractHash, "contractHash").isNotNull();
			if (contract.profiles().scope() == null)
				throw new IllegalArgumentException("A confirmed contract needs a certification scope");
		}
	}

	/**
	 * The user asked to revise the contract. A running certification attempt stops and never resumes.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 */
	record Revise() implements CampaignEvent
	{
	}

	/**
	 * Requests assignment and recording of a trial before its session starts.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param admission the admission that the scheduler planned
	 */
	record Assign(Admission.Assign admission) implements CampaignEvent
	{
		/**
		 * Creates the event.
		 *
		 * @param admission the admission that the scheduler planned
		 * @throws NullPointerException if {@code admission} is null
		 */
		public Assign
		{
			requireThat(admission, "admission").isNotNull();
		}
	}

	/**
	 * A session started running an assigned logical trial.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param key the trial
	 */
	record Start(TrialKey key) implements CampaignEvent
	{
		/**
		 * Creates the event.
		 *
		 * @param key the trial
		 * @throws NullPointerException if {@code key} is null
		 */
		public Start
		{
			requireThat(key, "key").isNotNull();
		}
	}

	/**
	 * A running logical trial was labelled by its built-in tools or by its judge.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param key the trial
	 * @param verdict the trial's label
	 * @param rationale the judge's explanation of the label or, when no judge ran, the record of the trial's
	 *   built-in tools
	 * @param failedAssertions the assertions that a failed trial failed, each with its behavior: those of its
	 *   failed built-in tools or, when its judge failed it, those that the judge named; empty for a pass
	 * @param triage the impact of a failed development trial, or {@code null} for any other trial
	 * @param observed the conditions that the trial session and, if one ran, the judge session observed
	 * @param inputTokens the trial session's input tokens, or {@code null} if unavailable
	 * @param responses each completed turn's assistant messages in order, ending with its final response
	 * @param files where the trial session's harness events and file changes were retained
	 */
	record Complete(TrialKey key, Verdict verdict, String rationale, List<FailedAssertion> failedAssertions,
		Triage triage, MeasurementIdentity observed, Long inputTokens, List<List<String>> responses, TrialFiles files)
		implements CampaignEvent
	{
		/**
		 * Creates the event.
		 *
		 * @param key the trial
		 * @param verdict the trial's label
		 * @param rationale the judge's explanation of the label or, when no judge ran, the record of the trial's
		 *   built-in tools
		 * @param failedAssertions the assertions that a failed trial failed, each with its behavior: those of its
		 *   failed built-in tools or, when its judge failed it, those that the judge named; empty for a pass
		 * @param triage the impact of a failed development trial, or {@code null} for any other trial
		 * @param observed the conditions that the trial session and, if one ran, the judge session observed
		 * @param inputTokens the trial session's input tokens, or {@code null} if unavailable
		 * @param responses each completed turn's assistant messages in order, ending with its final response
		 * @param files where the trial session's harness events and file changes were retained
		 * @throws NullPointerException if any of {@code key}, {@code verdict}, {@code rationale},
		 *   {@code failedAssertions}, {@code observed}, {@code responses}, {@code files}, a failed assertion, a
		 *   turn's messages or an assistant message are null
		 * @throws IllegalArgumentException if {@code inputTokens} is negative, a pass has failed assertions, or a
		 *   {@code triage} exists for a trial that is not a failed {@link Purpose#DEVELOPMENT development} trial
		 */
		public Complete
		{
			requireThat(key, "key").isNotNull();
			requireThat(verdict, "verdict").isNotNull();
			requireThat(rationale, "rationale").isNotNull();
			requireThat(failedAssertions, "failedAssertions").isNotNull();
			if (verdict == Verdict.PASS)
				requireThat(failedAssertions, "failedAssertions").isEmpty();
			requireThat(observed, "observed").isNotNull();
			requireThat(responses, "responses").isNotNull();
			requireThat(files, "files").isNotNull();
			if (inputTokens != null)
				requireThat(inputTokens.longValue(), "inputTokens").isNotNegative();
			if (triage != null)
			{
				requireThat(verdict, "verdict").isEqualTo(Verdict.FAIL);
				requireThat(key.purpose(), "key.purpose()").isEqualTo(Purpose.DEVELOPMENT);
			}

			failedAssertions = List.copyOf(failedAssertions);
			responses = responses.stream().map(List::copyOf).toList();
		}
	}

	/**
	 * A run of a logical trial ended without an outcome. The trial records it as an infrastructure attempt.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param key the trial
	 * @param failure why the run ended
	 * @param detail what the harness or CAT reported about the failure
	 * @param at when the run ended
	 */
	record Fail(TrialKey key, RunFailure failure, String detail, Instant at) implements CampaignEvent
	{
		/**
		 * Creates the event.
		 *
		 * @param key the trial
		 * @param failure why the run ended
		 * @param detail what the harness or CAT reported about the failure
		 * @param at when the run ended
		 * @throws NullPointerException if {@code key}, {@code failure}, {@code detail} or {@code at} are null
		 * @throws IllegalArgumentException if {@code detail} is blank
		 */
		public Fail
		{
			requireThat(key, "key").isNotNull();
			requireThat(failure, "failure").isNotNull();
			requireThat(detail, "detail").isNotBlank();
			requireThat(at, "at").isNotNull();
		}
	}

	/**
	 * The harness's shared hold, set by a rate-limit rejection or a provider refusal, stops admissions until it ends
	 * and drops the concurrency window to 1.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param until when the hold ends
	 * @param backoffs the shared hold's number of doubled holds since a turn last completed
	 */
	record HoldAdmissions(Instant until, int backoffs) implements CampaignEvent
	{
		/**
		 * Creates the event.
		 *
		 * @param until when the hold ends
		 * @param backoffs the shared hold's number of doubled holds since a turn last completed
		 * @throws NullPointerException if {@code until} is null
		 * @throws IllegalArgumentException if {@code backoffs} is negative
		 */
		public HoldAdmissions
		{
			requireThat(until, "until").isNotNull();
			requireThat(backoffs, "backoffs").isNotNegative();
		}
	}

	/**
	 * The harness reported its rate-limit windows.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param windows the reported windows
	 * @param at when they were reported
	 */
	record ObserveRateLimit(List<RateLimitWindow> windows, Instant at) implements CampaignEvent
	{
		/**
		 * Creates the event.
		 *
		 * @param windows the reported windows
		 * @param at when they were reported
		 * @throws NullPointerException if any argument is null
		 */
		public ObserveRateLimit
		{
			requireThat(windows, "windows").isNotNull();
			requireThat(at, "at").isNotNull();
			windows = List.copyOf(windows);
		}
	}

	/**
	 * Reserves a prompt author session's stable number before it starts.
	 *
	 * @param cell the optimization round's addressed cell
	 */
	record StartPromptAuthorAttempt(String cell) implements CampaignEvent
	{
		/**
		 * Creates a reservation request.
		 *
		 * @param cell the addressed cell
		 * @throws NullPointerException if test is null
		 * @throws IllegalArgumentException if test is blank
		 */
		public StartPromptAuthorAttempt
		{
			requireThat(cell, "cell").isNotBlank();
		}
	}

	/**
	 * Freezes a cell's approach order before the round reserves its attempts.
	 *
	 * @param cell  the addressed cell
	 * @param order the ranked or fallback order
	 */
	record RankApproaches(String cell, ApproachOrder order) implements CampaignEvent
	{
		/**
		 * Creates an order event.
		 *
		 * @param cell  the addressed cell
		 * @param order the frozen order
		 * @throws NullPointerException     if an argument is null
		 * @throws IllegalArgumentException if {@code cell} is blank
		 */
		public RankApproaches
		{
			requireThat(cell, "cell").isNotBlank();
			requireThat(order, "order").isNotNull();
		}
	}

	/**
	 * A prompt author's proposed file changes passed inventory validation, so checks that decide whether to promote
	 * the snapshot can start.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param number the reserved attempt number
	 * @param cell the optimization round's cell identifier that the attempt addresses
	 * @param snapshot the attempt's snapshot
	 * @param changes the captured original file changes
	 */
	record SubmitPromptAuthorAttempt(int number, String cell, Sha256 snapshot, List<FileDelta> changes)
		implements CampaignEvent
	{
		/**
		 * Creates the event.
		 *
		 * @param number the reserved attempt number
		 * @param cell the optimization round's cell identifier that the attempt addresses
		 * @param snapshot the attempt's snapshot
		 * @param changes the captured original file changes
		 * @throws NullPointerException if any argument is null
		 * @throws IllegalArgumentException if number is not positive or test is blank
		 */
		public SubmitPromptAuthorAttempt
		{
			requireThat(number, "number").isPositive();
			requireThat(cell, "cell").isNotBlank();
			requireThat(snapshot, "snapshot").isNotNull();
			changes = List.copyOf(changes);
		}
	}

	/**
	 * Advances a completed promotion decision to the next reduction or promotes its minimal passing snapshot.
	 *
	 * @param snapshot the saved overlay for the next reduction, ignored when the search has finished
	 */
	record AdvanceMinimality(Sha256 snapshot) implements CampaignEvent
	{
		/**
		 * Creates the event.
		 *
		 * @param snapshot the saved next reduction
		 * @throws NullPointerException if snapshot is null
		 */
		public AdvanceMinimality
		{
			requireThat(snapshot, "snapshot").isNotNull();
		}
	}

	/**
	 * Records a snapshot's observed prompt-file sizes.
	 *
	 * @param snapshot the measured snapshot
	 * @param size the measurements or the reason they are unavailable
	 */
	record MeasurePrompt(Sha256 snapshot, PromptSize size) implements CampaignEvent
	{
		/**
		 * Creates the event.
		 *
		 * @param snapshot the measured snapshot
		 * @param size the measurements
		 * @throws NullPointerException if either argument is null
		 */
		public MeasurePrompt
		{
			requireThat(snapshot, "snapshot").isNotNull();
			requireThat(size, "size").isNotNull();
		}
	}

	/**
	 * Retains a completed prompt author session's diagnosis independently of whether it contributes a candidate.
	 *
	 * @param diagnosis the prompt author's retained evidence
	 */
	record RetainPromptAuthorDiagnosis(PromptAuthorDiagnosis diagnosis) implements CampaignEvent
	{
		/**
		 * Creates the event.
		 *
		 * @param diagnosis the retained evidence
		 * @throws NullPointerException if the diagnosis is null
		 */
		public RetainPromptAuthorDiagnosis
		{
			requireThat(diagnosis, "diagnosis").isNotNull();
		}
	}

	/**
	 * Starts the working candidate's next certification attempt by freezing its procedure and opening its trial stream.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param calibration the cap calibrated for the attempt's certification level
	 * @param formalSeed the seed of the attempt's formal draw stream
	 * @param control the snapshot that the attempt's control trials run: the incumbent with every target prompt file
	 *   emptied
	 */
	record Commit(CapCalibration calibration, long formalSeed, Sha256 control) implements CampaignEvent
	{
		/**
		 * Creates the event.
		 *
		 * @param calibration the cap calibrated for the attempt's certification level
		 * @param formalSeed the seed of the attempt's formal draw stream
		 * @param control the snapshot that the attempt's control trials run: the incumbent with every target prompt
		 *   file emptied
		 * @throws NullPointerException if {@code calibration} or {@code control} are null
		 */
		public Commit
		{
			requireThat(calibration, "calibration").isNotNull();
			requireThat(control, "control").isNotNull();
		}
	}

	/**
	 * A contract author session wrote the test cases of a formal failure group's current round.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param attempt the index of the attempt whose failure group the test cases address
	 * @param group the group's 1-based position in the attempt's groups
	 * @param testCases the new test cases, each recording {@code attempt}
	 */
	record ReproductionValues(int attempt, int group, List<AddedTestCase> testCases) implements CampaignEvent
	{
		/**
		 * Creates the event.
		 *
		 * @param attempt the index of the attempt whose failure group the test cases address
		 * @param group the group's 1-based position in the attempt's groups
		 * @param testCases the new test cases, each recording {@code attempt}
		 * @throws NullPointerException if {@code testCases} is null or contains null
		 * @throws IllegalArgumentException if {@code group} is not positive, {@code testCases} is empty or a test case
		 *   records another attempt
		 */
		public ReproductionValues
		{
			requireThat(group, "group").isPositive();
			requireThat(testCases, "testCases").isNotEmpty();
			testCases = List.copyOf(testCases);
			for (AddedTestCase testCase : testCases)
				requireThat(testCase.attempt(), "testCase.attempt()").isEqualTo(attempt, "attempt");
		}
	}

	/**
	 * Cap calibration for the next certification attempt failed.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param detail why calibration failed
	 */
	record CalibrationFailed(String detail) implements CampaignEvent
	{
		/**
		 * Creates the event.
		 *
		 * @param detail why calibration failed
		 * @throws NullPointerException if {@code detail} is null
		 * @throws IllegalArgumentException if {@code detail} is blank
		 */
		public CalibrationFailed
		{
			requireThat(detail, "detail").isNotBlank();
		}
	}

	/**
	 * A valid prompt author report found conflicts in confirmed requirements.
	 *
	 * @param conflicts the conflicts, in report order
	 */
	record FindConflict(List<PromptAuthorConflict> conflicts) implements CampaignEvent
	{
		/**
		 * Creates the event.
		 *
		 * @param conflicts the ordered conflicts
		 * @throws NullPointerException if the list or an element is null
		 * @throws IllegalArgumentException if {@code conflicts} is empty
		 */
		public FindConflict
		{
			conflicts = List.copyOf(conflicts);
			requireThat(conflicts, "conflicts").isNotEmpty();
		}
	}

	/**
	 * A prompt test needs a test revision before new trials start.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param request the request
	 */
	record RequestTestRevision(TestRevisionRequest request) implements CampaignEvent
	{
		/**
		 * Creates the event.
		 *
		 * @param request the request
		 * @throws NullPointerException if {@code request} is null
		 */
		public RequestTestRevision
		{
			requireThat(request, "request").isNotNull();
		}
	}

	/**
	 * A test revision changed prompt tests. A running attempt stops, and the revised contract waits for confirmation.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param revisions the changes, one per revised test
	 */
	record ReviseTests(List<TestRevision> revisions) implements CampaignEvent
	{
		/**
		 * Creates the event.
		 *
		 * @param revisions the changes, one per revised test
		 * @throws NullPointerException if the list or an element is null
		 * @throws IllegalArgumentException if {@code revisions} is empty
		 */
		public ReviseTests
		{
			revisions = List.copyOf(revisions);
			requireThat(revisions, "revisions").isNotEmpty();
		}
	}

	/**
	 * A test revision found that a prompt test needs no change.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param test the identifier of the test
	 * @param reasons why the test needs no change
	 */
	record ReportTestSound(String test, String reasons) implements CampaignEvent
	{
		/**
		 * Creates the event.
		 *
		 * @param test the identifier of the test
		 * @param reasons why the test needs no change
		 * @throws NullPointerException if any argument is null
		 * @throws IllegalArgumentException if any argument is blank
		 */
		public ReportTestSound
		{
			requireThat(test, "test").isNotBlank();
			requireThat(reasons, "reasons").isNotBlank();
		}
	}

	/**
	 * A test revision found that it cannot proceed within the confirmed requirements.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param test the identifier of the test that the revision examined
	 * @param reason why the revision cannot proceed
	 * @param reasons the test revision's explanation
	 * @param proposedChange the requirement change that the test revision proposes, or empty if it proposes none
	 */
	record BlockTestRevision(String test, PendingDecision.TestRevisionBlocked.Reason reason, String reasons,
		String proposedChange) implements CampaignEvent
	{
		/**
		 * Creates the event.
		 *
		 * @param test the identifier of the test that the revision examined
		 * @param reason why the revision cannot proceed
		 * @param reasons the test revision's explanation
		 * @param proposedChange the requirement change that the test revision proposes, or empty if it proposes none
		 * @throws NullPointerException if any argument is null
		 * @throws IllegalArgumentException if {@code test} or {@code reasons} are blank
		 */
		public BlockTestRevision
		{
			requireThat(test, "test").isNotBlank();
			requireThat(reason, "reason").isNotNull();
			requireThat(reasons, "reasons").isNotBlank();
			requireThat(proposedChange, "proposedChange").isNotNull();
		}
	}

	/**
	 * The user selects a resolution of the first unanswered conflict.
	 *
	 * @param resolution the 1-based resolution position
	 */
	record ResolveConflict(int resolution) implements CampaignEvent
	{
		/**
		 * Creates the event.
		 *
		 * @param resolution the 1-based resolution position
		 * @throws IllegalArgumentException if {@code resolution} is not positive
		 */
		public ResolveConflict
		{
			requireThat(resolution, "resolution").isPositive();
		}
	}

	/**
	 * The user answered a pending decision.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param choice the answer
	 * @param attempt the certification index of the attempt to keep for {@link Choice#KEEP_IMPROVEMENT}, otherwise
	 *   0
	 * @param reviewPoint the next review point for {@link Choice#MORE_EVIDENCE}, otherwise 0
	 */
	record Choose(Choice choice, int attempt, int reviewPoint) implements CampaignEvent
	{
		/**
		 * Creates the event.
		 *
		 * @param choice the answer
		 * @param attempt the certification index of the attempt to keep for {@link Choice#KEEP_IMPROVEMENT},
		 *   otherwise 0
		 * @param reviewPoint the next review point for {@link Choice#MORE_EVIDENCE}, otherwise 0
		 * @throws NullPointerException if {@code choice} is null
		 * @throws IllegalArgumentException if {@code attempt} is not positive for {@link Choice#KEEP_IMPROVEMENT}
		 *   or is not zero for another choice, or {@code reviewPoint} is not positive for {@link Choice#MORE_EVIDENCE}
		 *   or is not zero for another choice
		 */
		public Choose
		{
			requireThat(choice, "choice").isNotNull();
			if (choice == Choice.KEEP_IMPROVEMENT)
				requireThat(attempt, "attempt").isPositive();
			else
				requireThat(attempt, "attempt").isZero();
			if (choice == Choice.MORE_EVIDENCE)
				requireThat(reviewPoint, "reviewPoint").isPositive();
			else
				requireThat(reviewPoint, "reviewPoint").isZero();
		}

		/**
		 * Returns a choice that needs no argument.
		 *
		 * @param choice the answer
		 * @return the event
		 * @throws NullPointerException if {@code choice} is null
		 * @throws IllegalArgumentException if the choice is {@link Choice#KEEP_IMPROVEMENT} or
		 *   {@link Choice#MORE_EVIDENCE}
		 */
		public static Choose of(Choice choice)
		{
			return new Choose(choice, 0, 0);
		}

		/**
		 * Returns a {@link Choice#KEEP_IMPROVEMENT} choice.
		 *
		 * @param attempt the certification index of the attempt to keep
		 * @return the event
		 * @throws IllegalArgumentException if {@code attempt} is not positive
		 */
		public static Choose keep(int attempt)
		{
			return new Choose(Choice.KEEP_IMPROVEMENT, attempt, 0);
		}

		/**
		 * Returns a {@link Choice#MORE_EVIDENCE} choice.
		 *
		 * @param reviewPoint the next review point
		 * @return the event
		 * @throws IllegalArgumentException if {@code reviewPoint} is not positive
		 */
		public static Choose moreEvidence(int reviewPoint)
		{
			return new Choose(Choice.MORE_EVIDENCE, 0, reviewPoint);
		}
	}

	/**
	 * Reports required edits outside the prompt author's permitted files and asks the user how to continue. Only the
	 * driver applies this event.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param warnings the warnings, in report order
	 */
	record WarnAuthority(List<AuthorityWarning> warnings) implements CampaignEvent
	{
		/**
		 * Creates the event.
		 *
		 * @param warnings the warnings, in report order
		 * @throws NullPointerException if {@code warnings} is null or contains null
		 * @throws IllegalArgumentException if {@code warnings} is empty
		 */
		public WarnAuthority
		{
			requireThat(warnings, "warnings").isNotEmpty();
			warnings = List.copyOf(warnings);
		}
	}

	/**
	 * The user cancelled the campaign.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 */
	record Cancel() implements CampaignEvent
	{
	}

	/**
	 * The user closed the campaign, for example to start a new one with a fresh budget.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 */
	record Close() implements CampaignEvent
	{
	}

	/**
	 * CAT observed measurement conditions outside a trial's completion, before the next model call.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param observed the observed conditions
	 */
	record ChangeMeasurement(MeasurementIdentity observed) implements CampaignEvent
	{
		/**
		 * Creates the event.
		 *
		 * @param observed the observed conditions
		 * @throws NullPointerException if {@code observed} is null
		 */
		public ChangeMeasurement
		{
			requireThat(observed, "observed").isNotNull();
		}
	}

	/**
	 * Inventory validation found a change in the optimization worktree that CAT did not make.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param description what changed
	 */
	record DetectUnownedChange(String description) implements CampaignEvent
	{
		/**
		 * Creates the event.
		 *
		 * @param description what changed
		 * @throws NullPointerException if {@code description} is null
		 * @throws IllegalArgumentException if {@code description} is blank
		 */
		public DetectUnownedChange
		{
			requireThat(description, "description").isNotBlank();
		}
	}

	/**
	 * CAT found a condition that makes certification unavailable under the current lineage.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param outcome the matching {@code certification unavailable} outcome
	 */
	record ReportUnavailable(Outcome outcome) implements CampaignEvent
	{
		/**
		 * Creates the event.
		 *
		 * @param outcome the matching {@code certification unavailable} outcome
		 * @throws NullPointerException if {@code outcome} is null
		 * @throws IllegalArgumentException if {@code outcome} does not report unavailability
		 */
		public ReportUnavailable
		{
			requireThat(outcome, "outcome").isNotNull();
			requireThat(outcome.isUnavailable(), "outcome.isUnavailable()").isTrue();
		}
	}
}
