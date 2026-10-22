package com.hazia.forgetmenot.domain

// Port of www/js/lib/merge.js: merging two copies of a friend (SPEC §3 "Resolving a record conflict").
//
// contacts, notes, dates and gifts only grow by one entry at a time, each with its own id: a
// reach-out logged on one phone and a note written on another are both worth keeping, whichever
// copy wins the other fields.

/**
 * [winner]'s fields, plus every entry of [other]'s logs that winner lacks (matched by id, and
 * only entries that have one). An entry removed on one side and still present on the other
 * comes back: there is no record of removals, and keeping a logged reach-out beats losing one.
 */
fun mergeFriendLogs(winner: Friend, other: Friend?): Friend {
    if (other == null) return winner
    return winner.copy(
        contacts = union(winner.contacts, other.contacts) { it.id },
        notes = union(winner.notes, other.notes) { it.id },
        dates = union(winner.dates, other.dates) { it.id },
        gifts = union(winner.gifts, other.gifts) { it.id },
    )
}

private inline fun <T> union(mine: List<T>, theirs: List<T>, id: (T) -> String): List<T> {
    val have = mine.mapTo(HashSet(), id)
    val extra = theirs.filter { id(it).isNotEmpty() && id(it) !in have }
    return if (extra.isEmpty()) mine else mine + extra
}

/** True when merging added something, so the merged copy has to be written. */
fun logsDiffer(a: Friend, b: Friend): Boolean =
    a.contacts.size != b.contacts.size || a.notes.size != b.notes.size ||
        a.dates.size != b.dates.size || a.gifts.size != b.gifts.size
