# Attachment Sync Implementation Plan

## Required outcome

The sync service copies each user attachment to the team's object store and keeps the attachment's tags with it, so
that a search for a tag in the object store finds every attachment that carries it. Attachments can carry up to 200
tags, totalling up to 6 KiB.

## Work

1. Attachment scanner: lists attachments changed since the last sync.
2. Upload worker: uploads each attachment and its tags to the object store.
3. Tag storage: stores the tags where the object store's search can find them.
4. Sync status page: shows the last sync time and the number of attachments copied, from the existing status table.
5. Acceptance: sync a sample folder whose attachments carry 1, 50, and 200 tags, then search the object store for
	tags from each one.

## Open question

{{capability_question}} If it can, tag storage writes the tags with the object itself. If it cannot, tag storage needs
a separate index that the upload worker updates and the search reads, which changes the upload worker's design.
