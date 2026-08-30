package io.github.cowwoc.cat.common.evaluation;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import io.github.cowwoc.cat.common.PromptEvaluation;
import io.github.cowwoc.cat.common.runner.RunnerRuntimeManifest;
import io.github.cowwoc.cat.common.secrets.EvaluatorSecrets;
import java.io.IOException;
import java.nio.file.Path;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies the deterministic evaluator lifecycle for one packaged runner artifact.
 */
public final class RuntimeVerification
{
	/**
	 * Supplies the coordinator-owned build-plan artifact admission operation.
	 */
	@FunctionalInterface
	public interface Access
	{
		/**
		 * Resolves and verifies the runner artifact declared by one build plan.
		 *
		 * @param temporaryRoot the CAT work temporary root that owns runner-query output
		 * @param buildPlanPath the selected version-controlled build plan
		 * @return the verified packaged runner artifact
		 * @throws IOException if the build plan or its declared artifact is invalid
		 */
		Path verifiedArtifact(Path temporaryRoot, Path buildPlanPath) throws IOException;
	}

	private static volatile Access access;

	/**
	 * Prevents construction of this stateless runtime-verification owner.
	 */
	private RuntimeVerification()
	{
	}

	/**
	 * Registers coordinator-owned build-plan artifact admission.
	 *
	 * @param access the coordinator operation used to resolve a verified artifact
	 */
	public static void configure(Access access)
	{
		RuntimeVerification.access = java.util.Objects.requireNonNull(access, "access may not be null");
	}

	/**
	 * Runs lifecycle verification for the packaged runner declared by a build plan.
	 *
	 * @param temporaryRoot the CAT work temporary root for lifecycle receipts
	 * @param buildPlanPath the plan declaring the packaged runner
	 * @return the completed lifecycle receipt
	 * @throws IOException if the plan or declared runner is invalid
	 */
	public static ObjectNode lifecycleSoakFromPlan(Path temporaryRoot, Path buildPlanPath) throws IOException
	{
		requireThat(temporaryRoot, "temporaryRoot").isNotNull();
		requireThat(buildPlanPath, "buildPlanPath").isNotNull();
		return lifecycleSoakForArtifact(temporaryRoot, access().verifiedArtifact(temporaryRoot, buildPlanPath));
	}

	/**
	 * Runs lifecycle verification for the artifact named by an assembly receipt.
	 *
	 * @param temporaryRoot the CAT work temporary root for lifecycle receipts
	 * @param assembly the verified assembly receipt
	 * @return the completed lifecycle receipt
	 * @throws IOException if the receipt lacks a valid artifact path
	 */
	public static ObjectNode lifecycleSoak(Path temporaryRoot, ObjectNode assembly) throws IOException
	{
		requireThat(temporaryRoot, "temporaryRoot").isNotNull();
		requireThat(assembly, "assembly").isNotNull();
		return lifecycleSoakForArtifact(temporaryRoot,
			PromptEvaluation.path(PromptEvaluation.text(assembly, "runner_artifact", Path.of("assembly"))));
	}

	/**
	 * Runs deterministic lifecycle scenarios for an already verified runner artifact.
	 *
	 * @param temporaryRoot the CAT work temporary root for lifecycle receipts
	 * @param runnerArtifact the packaged runner artifact
	 * @return the completed lifecycle receipt
	 * @throws IOException if the artifact does not have a stable packaged fingerprint
	 */
	public static ObjectNode lifecycleSoakForArtifact(Path temporaryRoot, Path runnerArtifact) throws IOException
	{
		requireThat(temporaryRoot, "temporaryRoot").isNotNull();
		requireThat(runnerArtifact, "runnerArtifact").isNotNull();
		return LifecycleSoak.run(temporaryRoot, RunnerRuntimeManifest.packagedFingerprint(runnerArtifact));
	}

	/**
	 * Verifies that one packaged artifact has a matching completed lifecycle receipt.
	 *
	 * @param temporaryRoot the CAT work temporary root for lifecycle receipts
	 * @param runnerArtifact the packaged runner artifact
	 * @throws IOException if the artifact does not match a completed lifecycle receipt
	 */
	public static void verifyLifecycleSoak(Path temporaryRoot, Path runnerArtifact) throws IOException
	{
		requireThat(temporaryRoot, "temporaryRoot").isNotNull();
		requireThat(runnerArtifact, "runnerArtifact").isNotNull();
		LifecycleSoak.verify(temporaryRoot, RunnerRuntimeManifest.packagedFingerprint(runnerArtifact));
	}

	/**
	 * Verifies the lifecycle receipt for the artifact named by an assembly receipt.
	 *
	 * @param temporaryRoot the CAT work temporary root for lifecycle receipts
	 * @param assembly the verified assembly receipt
	 * @throws IOException if the receipt lacks a valid artifact or its lifecycle check is missing
	 */
	public static void verifyLifecycleSoak(Path temporaryRoot, ObjectNode assembly) throws IOException
	{
		requireThat(temporaryRoot, "temporaryRoot").isNotNull();
		requireThat(assembly, "assembly").isNotNull();
		verifyLifecycleSoak(temporaryRoot,
			PromptEvaluation.path(PromptEvaluation.text(assembly, "runner_artifact", Path.of("assembly"))));
	}

	/**
	 * Creates the passed receipt for one verified packaged runner fingerprint.
	 *
	 * @param json the mapper that creates the lifecycle receipt
	 * @param packagedRunnerFingerprint the packaged runner fingerprint
	 * @return the complete model-free lifecycle receipt
	 */
	public static ObjectNode passedReceipt(JsonMapper json, String packagedRunnerFingerprint)
	{
		return LifecycleSoakReceipt.passed(json, packagedRunnerFingerprint);
	}

	/**
	 * Returns coordinator-owned build-plan artifact admission.
	 *
	 * @return the registered artifact admission operation
	 */
	private static Access access()
	{
		EvaluatorSecrets.access().initialize();
		Access result = access;
		if (result == null)
			throw new AssertionError("PromptEvaluation did not configure runtime-verification access");
		return result;
	}
}
