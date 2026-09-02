package com.yugahashimoto.andcode.runtime.local

/**
 * Appended to package-installation failures, where the log alone cannot say whose fault it was.
 *
 * A user on a slow or flaky connection reads `1 error; 1435.7 MiB in 257 packages` as a broken
 * build, deletes the app and moves on, when retrying on a stable network very often succeeds
 * (issue #290). The installers share one wording so the advice reads the same whichever agent's
 * card showed the failure.
 */
internal const val PACKAGE_INSTALL_RETRY_HINT =
    "This is often just a network timeout - retrying the installation on a stable connection usually succeeds."
