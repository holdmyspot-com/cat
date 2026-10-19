package com.holdmyspot.cat.common.git.replay;

/**
 * One reviewer-authored batch declaration in an atomic output batch file.
 *
 * @param id the manifest-local batch identifier
 * @param source the full hash of the source commit reconstructed by this batch
 * @param patch the patch path relative to the manifest directory
 */
public record BatchRequest(String id, String source, String patch)
{
}
