package com.example.licensing;

import java.time.Instant;
import java.util.Set;

/**
 * A license stored on this machine.
 *
 * @param products the identifiers of the products it covers
 * @param expires  when it expires
 */
public record License(Set<String> products, Instant expires)
{
}
