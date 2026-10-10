package io.github.cowwoc.cat.common.git.replay;

import java.util.List;

/**
 * One source commit reconstructed either from its placement batches or from its immutable original patch.
 *
 * @param source the original full source commit hash
 * @param batchIds every batch from that source in its original order, when those patches also reconstruct the source
 * @param sourcePatch the immutable original patch, when target-adapted placement batches cannot reconstruct it
 * @param beforeTree the source commit parent's tree
 * @param afterTree the source commit's tree
 */
public record SourceReconstruction(String source, List<String> batchIds, PatchArtifact sourcePatch, String beforeTree,
	String afterTree)
{
}
