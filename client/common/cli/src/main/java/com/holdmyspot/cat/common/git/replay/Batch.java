package com.holdmyspot.cat.common.git.replay;

/**
 * One opaque patch batch selected by the reviewer.
 *
 * @param id the unique manifest-local batch identifier
 * @param source the full hash of the source commit that this batch reconstructs
 * @param destination the logical output identifier that receives this batch
 * @param patch the immutable patch artifact
 * @param destinationWitness the index states before and after applying this batch
 */
public record Batch(String id, String source, String destination, PatchArtifact patch,
	DestinationWitness destinationWitness)
{
}
