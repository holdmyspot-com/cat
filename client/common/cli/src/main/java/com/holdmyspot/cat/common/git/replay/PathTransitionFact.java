package com.holdmyspot.cat.common.git.replay;

/**
 * One raw path transition reported by Git for a source commit.
 *
 * @param id the manifest-local transition identifier
 * @param source the source commit containing the transition
 * @param output the source's declared logical output, or {@code null} when semantic review has not assigned one
 * @param status Git's addition, deletion, or rename status
 * @param path the added/deleted path, or the old path for a rename
 * @param targetPath the new path for a Git-reported rename, or {@code null} otherwise
 */
public record PathTransitionFact(String id, String source, String output, String status, String path, String targetPath)
{
}
