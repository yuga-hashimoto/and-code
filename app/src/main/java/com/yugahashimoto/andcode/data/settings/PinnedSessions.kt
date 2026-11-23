package com.yugahashimoto.andcode.data.settings

/** Applies a pin or unpin to a set; ids not already pinned are ignored when unpinning. */
internal fun pinnedAfter(
    pinned: Set<String>,
    ids: Set<String>,
    pin: Boolean,
): Set<String> = if (pin) pinned + ids else pinned - ids
