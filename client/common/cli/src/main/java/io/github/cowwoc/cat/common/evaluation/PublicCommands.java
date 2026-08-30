package io.github.cowwoc.cat.common.evaluation;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * Owns public command recognition and validation before evaluation workflow composition begins.
 *
 * <p>
 * This boundary accepts only complete caller-facing operations. It delegates validated commands to package-private
 * workflow composition, which may use partial steps without exposing them as CLI commands.
 */
public final class PublicCommands
{
	/**
	 * Names a complete user-facing evaluation operation.
	 */
	enum PublicCommand
	{
		LIFECYCLE_SOAK("lifecycle-soak", "lifecycle-soak <build-plan.json>",
			"Checks that the packaged runner can start, finish, recover, and clean up without sending a model request."),
		PRECHECK("precheck", "precheck <scenario-suite-directory>",
			"Checks a version-controlled test suite and its deterministic known-good and known-bad controls before model " +
				"work begins."),
		CANDIDATE_STATUS("candidate-status", "candidate-status <candidate-state.json> [goal.md]",
			"Reports the candidate’s retained evidence state and its next complete public operation."),
		CREATE_DIRECT_DIAGNOSTIC("create-direct-diagnostic", "create-direct-diagnostic <source-run.json> " +
			"<case-id> <trial> <diagnostic-scenario-suite-directory>",
			"Creates a diagnostic that tests prompt application after a recorded failure, without retesting routing."),
		ROUTING_DIAGNOSTIC("routing-diagnostic", "routing-diagnostic <source-run.json> <case-id> <trial> " +
			"<prompt-artifact> <harness> <execution-context> <runner> <build-plan.json> " +
			"<retained-directory-under-cat-work-temp> [--max-concurrency <count>]",
			"Creates and runs a routing-only diagnostic for one recorded routing failure; its result is not certification " +
				"evidence."),
		AUDIT_ROUTING("audit-routing", "audit-routing <rules-root> <router-rule> <certification-scenario-suite-directory>",
			"Verifies that a named router can select every route asserted by a certification suite."),
		AUDIT_GENERALIZATION("audit-generalization", "audit-generalization <scenario-suite-directory> <prompt-artifact>",
			"Rejects reader guidance that repeats a fixture-specific test detail without an external authority."),
		PROMOTE_ROUTER("promote-router", "promote-router <source-run.json> <routing-matrix-run.json> <rules-root> " +
			"<router-promotion.json>", "Records that a router may be used after its recorded routing-only diagnostic " +
				"passes."),
		VERIFY_REVIEWER_CONTROLS("verify-reviewer-controls", "verify-reviewer-controls <scenario-suite-directory> " +
			"<prompt-artifact> <harness> <execution-context> <runner> <build-plan.json> " +
			"<retained-directory-under-cat-work-temp>", "Uses the independent reviewer to check its required pass and fail " +
				"control responses before candidate trials begin."),
		REGRESSION_CHECK("regression-check", "regression-check <smoke-run.json> <candidate-state.json> " +
			"<scenario-suite-directory> " +
			"<prompt-artifact> " +
			"<harness> <execution-context> <runner> <build-plan.json> <retained-directory-under-cat-work-temp> " +
			"[--max-concurrency <count>]", "Runs every durable certification case once and records whether this " +
			"candidate can be promoted."),
		REVISION_REVIEW("revision-review", "revision-review <candidate-state.json> <proposed-run.json> " +
			"<review-bundle-directory-under-cat-work-temp>",
			"Runs the required independent review for a changed candidate and records its retained runner receipt."),
		REVALIDATE_RUNTIME("revalidate-runtime", "revalidate-runtime <candidate-state.json> <passed-smoke-run.json>",
			"Records that a passed smoke run authorizes one replacement execution identity before regression resumes."),
		BENCHMARK("benchmark", "benchmark <scenario-suite-directory> <candidate-bundle> <harness> " +
			"<execution-context> <runner> " +
			"<build-plan.json> <retained-directory-under-cat-work-temp> [--max-concurrency <count>]",
			"Measures one fixed copy of selected prompt files without changing which revision is eligible for " +
				"certification."),
		MATERIALIZE_CANDIDATE("materialize-candidate", "materialize-candidate <source-rules-directory> " +
			"<bundle-directory-under-cat-work> <primary-rule> [<additional-rule>...] " +
			"[--route-replacement <ordinary-route> <candidate-route>]...",
			"Copies selected rule files into a fixed candidate prompt package for testing."),
		MATERIALIZE_CANDIDATE_GIT("materialize-candidate-git", "materialize-candidate-git <repository> <revision> " +
			"<repository-relative-rules-root> <bundle-directory-under-cat-work> <primary-rule> " +
			"[<additional-rule>...] [--route-replacement <ordinary-route> <candidate-route>]...",
			"Copies selected rule files from one Git revision into a fixed candidate prompt package for testing."),
		BENCHMARK_PAIR("benchmark-pair", "benchmark-pair <scenario-suite-directory> <baseline-candidate-bundle> " +
			"<candidate-bundle> <harness> <execution-context> <runner> <build-plan.json> " +
			"<retained-directory-under-cat-work-temp> <whole-suite-trial-count> [--max-concurrency <count>]",
			"Runs the same fixed number of interleaved trials for two fixed copies of selected prompt files. It does not " +
				"change which revision is eligible for certification."),
		CERTIFY_PROMOTED("certify-promoted", "certify-promoted <candidate-state.json> " +
			"<scenario-suite-directory> <prompt-artifact> " +
			"<harness> <execution-context> <runner> <build-plan.json> <retained-directory-under-cat-work-temp> " +
			"[--max-concurrency <count>]", "Measures the prompt revision marked eligible in the candidate-state record " +
			"until the configured compliance decision passes, fails, or reaches its limit."),
		SMOKE_EVALUATE("smoke-evaluate", "smoke-evaluate <scenario-suite-directory> <prompt-artifact> <harness> " +
			"<execution-context> <runner> <build-plan.json> <case-id> <retained-directory-under-cat-work-temp> " +
			"[--max-concurrency <count>]", "Runs one named case for diagnosis; its result is not certification evidence."),
		CAPABILITY_DIAGNOSTIC("capability-diagnostic", "capability-diagnostic <source-run.json> <case-id> <trial> " +
			"<diagnostic-scenario-suite-directory> <prompt-artifact> <harness> <execution-context> " +
			"<runner> <build-plan.json> " +
			"<executor-model> <executor-effort> <retained-directory-under-cat-work-temp> [--max-concurrency <count>]",
			"Runs a matched executor model/profile diagnostic; its result is not certification evidence."),
		STATUS("status", "status <run.json> [workspace-root] [--summary]",
			"Reports whether a recorded workflow is active, complete, or abandoned and gives its safe next action."),
		RECOVER("recover", "recover <run.json> [workspace-root]",
			"Records the terminal outcome for an abandoned workflow so it can be safely retried or cleaned up."),
		REPORT("report", "report <run.json> [--performance]",
			"Prints one retained result, optionally with measured worker performance."),
		COMPARE("compare", "compare <baseline-run.json> <candidate-run.json> [--diagnostic]",
			"Compares measurements only when both runs used the same suite, prompt, runner, model profile, and sandbox " +
				"policy; diagnostic mode lists the differences instead."),
		CLASSIFY_FAILURE("classify-failure", "classify-failure <run.json> <case-id> <trial>",
			"Classifies one recorded failed attempt and cites the evidence for that classification."),
		FINALIZE("finalize", "finalize <run.json> <workspace-root> <retain|delete> " +
			"[retained-evidence-directory-under-cat-work]",
			"Keeps or deletes a completed workflow's workspace and verifies the selected evidence action. Retain uses a " +
				"deterministic CAT work destination when none is supplied."),
		LINK_CORRECTION("link-correction", "link-correction <failed-workflow-journal.json> " +
			"<correction-workflow-journal.json>",
			"Links evidence from a failed workflow to the later correction that replaces it."),
		REVIEW_BUNDLE("review-bundle", "review-bundle <question.md> <retained-bundle-directory-under-cat-work-temp> " +
			"<result-path-relative-to-bundle> <evidence>...",
			"Packages a review question and named evidence for an independent " +
				"reviewer.");

		private final String name;
		private final String synopsis;
		private final String result;

		/**
		 * Creates a public command description.
		 *
		 * @param name the exact command-line name
		 * @param synopsis the accepted caller-facing input form
		 * @param result the observable result of a successful invocation
		 */
		PublicCommand(String name, String synopsis, String result)
		{
			this.name = name;
			this.synopsis = synopsis;
			this.result = result;
		}

		/**
		 * Finds the public command with an exact command-line name.
		 *
		 * @param name the command-line name
		 * @return the matching command, or {@code null} if no public command has that name
		 */
		static PublicCommand find(String name)
		{
			for (PublicCommand command: values())
			{
				if (command.name.equals(name))
					return command;
			}
			return null;
		}
	}


	/**
	 * Returns whether the caller supplied one of a public command's supported argument counts.
	 *
	 * @param command the selected public command
	 * @param argumentCount the number of supplied arguments, including the command name
	 * @return {@code true} if the count can be interpreted by the selected command
	 */
	static boolean commandAccepts(PublicCommand command, int argumentCount)
	{
		return switch (command)
		{
			case LIFECYCLE_SOAK -> argumentCount == 2;
			case PRECHECK -> argumentCount == 2;
			case REPORT -> argumentCount == 2 || argumentCount == 3;
			case VERIFY_REVIEWER_CONTROLS -> argumentCount == 8;
			case CREATE_DIRECT_DIAGNOSTIC -> argumentCount == 5;
			case ROUTING_DIAGNOSTIC -> argumentCount == 10 || argumentCount == 12;
			case AUDIT_ROUTING -> argumentCount == 4;
			case AUDIT_GENERALIZATION -> argumentCount == 3;
			case PROMOTE_ROUTER -> argumentCount == 5;
			case REGRESSION_CHECK -> argumentCount == 10 || argumentCount == 12;
			case REVISION_REVIEW -> argumentCount == 4;
			case REVALIDATE_RUNTIME -> argumentCount == 3;
			case CERTIFY_PROMOTED, SMOKE_EVALUATE -> argumentCount == 9 || argumentCount == 11;
			case BENCHMARK -> argumentCount == 8 || argumentCount == 10;
			case MATERIALIZE_CANDIDATE -> argumentCount >= 4;
			case MATERIALIZE_CANDIDATE_GIT -> argumentCount >= 6;
			case BENCHMARK_PAIR -> argumentCount == 10 || argumentCount == 12;
			case CAPABILITY_DIAGNOSTIC -> argumentCount == 13 || argumentCount == 15;
			case CANDIDATE_STATUS -> argumentCount == 2 || argumentCount == 3;
			case STATUS -> argumentCount >= 2 && argumentCount <= 4;
			case RECOVER -> argumentCount == 2 || argumentCount == 3;
			case COMPARE -> argumentCount == 3 || argumentCount == 4;
			case CLASSIFY_FAILURE -> argumentCount == 4;
			case FINALIZE -> argumentCount == 4 || argumentCount == 5;
			case LINK_CORRECTION -> argumentCount == 3;
			case REVIEW_BUNDLE -> argumentCount >= 5;
		};
	}


	/**
	 * Returns a public command's accepted input form and result.
	 *
	 * @param command the command to describe
	 * @return the detailed help text
	 */
	static String commandHelp(PublicCommand command)
	{
		return "Usage: prompt-evaluation " + command.synopsis + "\n\n" + command.result;
	}

	/**
	 * Returns the compact public command index.
	 *
	 * @return the help text for the public CLI surface
	 */
	static String usage()
	{
		return """
			Usage: prompt-evaluation <command> [arguments]

			Evaluate a prompt revision:
			  precheck                  check suite structure and known pass/fail controls without a model
			  lifecycle-soak            check runner recovery and cleanup without a model
			  verify-reviewer-controls  check the reviewer's known pass and fail responses
			  regression-check          run every durable case once before promotion
			  revision-review           review a changed candidate before its next check
			  revalidate-runtime        authorize a passed smoke under replacement runtime conditions
			  benchmark                 measure a candidate without changing promotion eligibility
			  certify-promoted          measure an eligible candidate for certification
			  smoke-evaluate            run one case for diagnosis without certification

			Investigate a failed result:
			  classify-failure             identify why a recorded attempt failed
			  routing-diagnostic           test rule selection and delivery after a routing failure
			  create-direct-diagnostic     test prompt application after a recorded failure
			  capability-diagnostic        compare executor profiles without certification
			  audit-routing                check whether a router selects every suite route
			  audit-generalization         check whether reader guidance depends on fixture-only details
			  promote-router               record that a routing diagnostic passed

			Manage recorded results:
			  candidate-status             inspect a candidate's retained evidence and next operation
			  status, recover              inspect a workflow or recover ownerless work
			  report, compare               read retained evaluation results
			  finalize, link-correction     keep, delete, or connect evidence
			  review-bundle                 prepare evidence for an independent review

			Use `prompt-evaluation help <command>` or `prompt-evaluation <command> --help`
			for that command's inputs and result.
			""";
	}


	/**
	 * Validates and executes one public command.
	 *
	 * @param args the command and arguments supplied at the CLI boundary
	 * @param executor the workflow composition that runs a validated complete operation
	 * @throws IOException if execution cannot complete its documented operation
	 */
	public static void execute(String[] args, CommandExecutor executor) throws IOException
	{
		execute(args, executor, System.out::println);
	}

	/**
	 * Validates and executes one public command with an owned output destination.
	 *
	 * @param args the command and arguments supplied at the CLI boundary
	 * @param executor the workflow composition that runs a validated complete operation
	 * @param output the destination for caller-visible command output
	 * @throws IOException if execution cannot complete its documented operation
	 */
	static void execute(String[] args, CommandExecutor executor, Consumer<String> output) throws IOException
	{
		requireThat(args, "args").isNotNull();
		requireThat(executor, "executor").isNotNull();
		requireThat(output, "output").isNotNull();
		if (args.length == 1 && (args[0].equals("--help") || args[0].equals("help")))
		{
			output.accept(usage());
			return;
		}
		if (args.length == 2 && args[0].equals("help"))
		{
			PublicCommand command = PublicCommand.find(args[1]);
			if (command == null)
				throw new IllegalArgumentException("Unknown public command: " + args[1] + ". Use " +
					"`prompt-evaluation help` to list public commands.");
			output.accept(commandHelp(command));
			return;
		}
		if (args.length == 0)
			throw new IllegalArgumentException(usage());

		PublicCommand command = PublicCommand.find(args[0]);
		if (command == null)
			throw new IllegalArgumentException("Unknown prompt-evaluation command: " + args[0] + ". Use " +
				"`prompt-evaluation --help` to list public commands.");
		if (args.length == 2 && args[1].equals("--help"))
		{
			output.accept(commandHelp(command));
			return;
		}
		if (!commandAccepts(command, args.length))
			throw new IllegalArgumentException("Invalid invocation.\n" + commandHelp(command));

		executor.execute(args);
	}

	/**
	 * Executes one validated public command through the evaluator's workflow composition.
	 */
	@FunctionalInterface
	public interface CommandExecutor
	{
		/**
		 * Runs one validated command.
		 *
		 * @param args the complete public command and its arguments
		 * @throws IOException if execution cannot complete its documented operation
		 */
		void execute(String[] args) throws IOException;
	}

	/**
	 * Prevents utility-class construction.
	 */
	private PublicCommands()
	{
	}
}
