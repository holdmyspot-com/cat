package io.github.cowwoc.cat.common.git.replay;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.json.JsonMapper;

/**
 * Records reviewer decisions for immutable Git path-transition facts.
 */
final class PathTransitionRecording
{
	private final JsonMapper json;
	private final ReviewEvidence evidence;
	private final PathTransitionValidation validation;
	private final SourceReviewValidation sourceReviewValidation;

	/**
	 * Creates the path-transition decision recorder.
	 *
	 * @param json the process JSON mapper
	 * @param evidence the owner of path-transition evidence files
	 * @param validation the path-transition decision validator
	 * @param sourceReviewValidation the source-review validator used by transition validation
	 */
	PathTransitionRecording(JsonMapper json, ReviewEvidence evidence, PathTransitionValidation validation,
		SourceReviewValidation sourceReviewValidation)
	{
		requireThat(json, "json").isNotNull();
		requireThat(evidence, "evidence").isNotNull();
		requireThat(validation, "validation").isNotNull();
		requireThat(sourceReviewValidation, "sourceReviewValidation").isNotNull();
		this.json = json;
		this.evidence = evidence;
		this.validation = validation;
		this.sourceReviewValidation = sourceReviewValidation;
	}

	/**
	 * Atomically merges decisions from manifest-local reviewer files.
	 *
	 * @param manifestFile the manifest that owns the decision evidence
	 * @param manifest the unfinalized replay manifest
	 * @param facts immutable Git path-transition facts
	 * @param decisionFiles non-empty manifest-local reviewer files
	 * @throws IOException if a file, decision, or merged evidence is invalid
	 */
	void record(Path manifestFile, BatchManifest manifest, List<PathTransitionFact> facts,
		List<Path> decisionFiles) throws IOException
	{
		requireThat(manifestFile, "manifestFile").isRegularFile();
		requireThat(manifest, "manifest").isNotNull();
		requireThat(facts, "facts").isNotNull();
		requireThat(decisionFiles, "decisionFiles").isNotEmpty();
		Path directory = manifestFile.toAbsolutePath().normalize().getParent();
		List<PathTransitionDecision> combined = new ArrayList<>(evidence.readPathTransitionDecisions(manifestFile));
		for (Path decisionFile : decisionFiles)
		{
			requireThat(decisionFile, "decisionFile").isRegularFile();
			if (!decisionFile.toAbsolutePath().normalize().startsWith(directory))
			{
				throw new IOException("Topic-replay path-transition decision file must be below the manifest directory: " +
					decisionFile);
			}
			combined.addAll(List.of(json.readValue(decisionFile.toFile(), PathTransitionDecision[].class)));
		}
		validation.validate(manifest, facts, combined, false, sourceReviewValidation);
		evidence.writePathTransitionDecisions(manifestFile, combined);
	}
}
