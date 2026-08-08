# File Links

## Design Goals

- Let readers open each cited local file directly while retaining a concise, unambiguous description of its location.

## Guidance

When a reader-visible response refers to a local file, use a Markdown link: `[Description](target)`. The description
must contain the target's filename and, when available, append a colon followed by its decimal line number, such as
`ReceiptDownloadEndpoint.java:7`. When the response refers to different targets with the same filename, include the
smallest subset of each target's full path that lets the reader distinguish them; otherwise do not include a directory
in the description. The target may use the full available path. Before finalizing, reject a bracketed local-file label
such as `[ReceiptDownloadEndpoint.java:7]` unless it has the Markdown link target required by this rule; brackets alone
are not a link. Write the target immediately after `(` and before `)`, without leading or trailing whitespace.
