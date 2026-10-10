package io.github.cowwoc.cat.common.git.replay;

/**
 * One plan batch with its captured immutable patch artifact.
 *
 * @param id the manifest-local batch identifier
 * @param source the source commit reconstructed by this batch
 * @param patch the captured patch artifact
 */
public record PlannedBatch(String id, String source, PatchArtifact patch)
{
}
