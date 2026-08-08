package io.github.cowwoc.cat.common.git.replay;

import io.github.cowwoc.cat.common.git.Git;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.json.JsonMapper;

/**
 * Records one complete reviewer-authored source-review transaction in an unfinalized replay manifest.
 */
final class SourceReviewRecording
{
	private final Git git;
	private final JsonMapper json;
	private final SourceReviewValidation validation;
	private final ManifestStore<BatchManifest> manifests;

	/**
	 * Creates the source-review recorder for one repository and manifest store.
	 *
	 * @param git the repository Git boundary
	 * @param json the process JSON mapper
	 * @param validation the semantic source-review validator
	 * @param manifests the owner of unfinalized manifest persistence
	 */
	SourceReviewRecording(Git git, JsonMapper json, SourceReviewValidation validation,
		ManifestStore<BatchManifest> manifests)
	{
		requireThat(git, "git").isNotNull();
		requireThat(json, "json").isNotNull();
		requireThat(validation, "validation").isNotNull();
		requireThat(manifests, "manifests").isNotNull();
		this.git = git;
		this.json = json;
		this.validation = validation;
		this.manifests = manifests;
	}

	/**
	 * Validates and atomically records every reviewer-authored source review for the captured replay range.
	 *
	 * @param manifestFile the manifest whose source review is being recorded
	 * @param reviewFile the reviewer-authored JSON array below the manifest directory
	 * @throws IOException if the manifest or review file is invalid, stale, incomplete, or cannot be written
	 */
	void record(Path manifestFile, Path reviewFile) throws IOException
	{
		requireThat(manifestFile, "manifestFile").isRegularFile();
		requireThat(reviewFile, "reviewFile").isNotNull();
		BatchManifest manifest = manifests.readUnfinalized(manifestFile);
		Path manifestDirectory = manifestFile.toAbsolutePath().normalize().getParent();
		Path resolvedReviewFile = reviewFile.toAbsolutePath().normalize();
		if (!resolvedReviewFile.startsWith(manifestDirectory) || !Files.isRegularFile(resolvedReviewFile))
		{
			throw new IOException("Topic-replay source-review file must be a regular file below the manifest directory: " +
				reviewFile);
		}
		if (!git.lines("rev-parse", "HEAD^{tree}").getFirst().equals(manifest.tipTree()))
			throw new IOException("Topic-replay source-review manifest does not match the current tip tree; prepare a new " +
				"manifest before recording reviews");
		SourceReview[] parsed = json.readValue(resolvedReviewFile.toFile(), SourceReview[].class);
		List<SourceReview> reviews = List.of(parsed);
		validation.validateInput(manifest, reviews, true);
		manifests.write(manifestFile, manifest.withSourceReviews(reviews));
	}

	/**
	 * Merges independently prepared partial source-review files into an unfinalized replay manifest.
	 *
	 * @param manifestFile the manifest whose source reviews are extended
	 * @param reviewFiles non-empty manifest-local review files
	 * @throws IOException if a file is unsafe, a review is invalid, or the combined state cannot be published
	 */
	void recordBatches(Path manifestFile, List<Path> reviewFiles) throws IOException
	{
		requireThat(manifestFile, "manifestFile").isRegularFile();
		requireThat(reviewFiles, "reviewFiles").isNotEmpty();
		BatchManifest manifest = manifests.readUnfinalized(manifestFile);
		Path directory = manifestFile.toAbsolutePath().normalize().getParent();
		List<SourceReview> combined = new ArrayList<>(sourceReviews(manifest));
		for (Path reviewFile : reviewFiles)
		{
			requireThat(reviewFile, "reviewFile").isRegularFile();
			Path resolvedReviewFile = reviewFile.toAbsolutePath().normalize();
			if (!resolvedReviewFile.startsWith(directory))
			{
				throw new IOException("Topic-replay source-review file must be below the manifest directory: " +
					reviewFile);
			}
			combined.addAll(List.of(json.readValue(resolvedReviewFile.toFile(), SourceReview[].class)));
		}
		validation.validateInput(manifest, combined, false);
		manifests.write(manifestFile, manifest.withSourceReviews(combined));
	}

	/**
	 * Returns source reviews, treating an omitted field as empty.
	 *
	 * @param manifest the replay manifest
	 * @return the declared or implied source reviews
	 */
	private List<SourceReview> sourceReviews(BatchManifest manifest)
	{
		if (manifest.sourceReviews() == null)
			return List.of();
		return manifest.sourceReviews();
	}
}
