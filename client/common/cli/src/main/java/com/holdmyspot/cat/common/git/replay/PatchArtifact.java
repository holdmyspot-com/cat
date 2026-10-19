package com.holdmyspot.cat.common.git.replay;

/**
 * An immutable patch artifact stored beside the manifest.
 *
 * @param path the patch path relative to the manifest directory
 * @param sha256 the lowercase SHA-256 hash of the patch bytes
 */
public record PatchArtifact(String path, String sha256)
{
}
