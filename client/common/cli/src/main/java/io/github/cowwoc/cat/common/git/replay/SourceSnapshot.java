package io.github.cowwoc.cat.common.git.replay;

/**
 * Git facts captured for one source commit before reviewer-authored batches are added.
 *
 * @param hash the full source commit hash
 * @param parentTree the tree of the source commit's parent
 * @param tree the source commit's tree
 */
record SourceSnapshot(String hash, String parentTree, String tree)
{
}
