# Search Long Record Preserved

## Design Goals

- A selective lookup returns the complete identifier even when a matching record is longer than a search preview.
- The lookup does not expand into reading almost the entire source document.

## Variables

- **notes**: The catalog filename.
- **label**: The parcel label whose archive identifier is requested.
- **archive_id**: A short opaque archive identifier located after the parcel metadata, beyond a 500-byte search preview.
- **before**: Generated operational reference sections unrelated to parcel labels.

## Scenario

Look up the archive identifier of `Parcel {{label}}` in `cat_contract_catalog/{{notes}}`. Return the complete identifier only. Do not edit files.

## Expected Behavior

- **description**: Locate the parcel record and return the entire {{archive_id}}, including its final suffix. If a search preview shortens the record, recover that record without reading almost all of the document. Do not load unrelated task guides or update files.
- **time_limit_seconds**: 120

## Assertions

### identifier_not_shortened

- **kind**: guard
- **description**: The final answer gives the complete short archive identifier {{archive_id}}. The identifier follows the parcel metadata beyond the first 500 bytes of the matching record; recover the requested field if the search preview omits it.

### selective_record_lookup

- **kind**: guard
- **tool**: `read-at-most cat_contract_catalog/{{notes}} 90`
- **description**: At most 90% of the catalog's nonblank lines reach the agent; recovering the matching record does not require a whole-document read.
