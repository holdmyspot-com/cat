# Marks the move from one installation that every harness shares to one installation per harness. The move changes
# only the updater's installation, which the release's setup replaces, so the plugin data needs no change.
#
# Its registry line makes the updater of the shared installation refuse every later release as a routine update and
# direct the user to the release's setup.
#
# Environment: CAT_MIGRATION_DATA_DIR, CAT_MIGRATION_FROM_VERSION and CAT_MIGRATION_TO_VERSION, which it does not use.
exit 0
