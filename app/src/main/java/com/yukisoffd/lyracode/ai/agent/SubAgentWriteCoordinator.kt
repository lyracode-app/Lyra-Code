package com.yukisoffd.lyracode.ai

internal data class SubAgentWriteOwner(
    val parentConversationId: Long,
    val taskIndex: Int,
)

internal class SubAgentWriteCoordinator {
    private val monitor = Any()
    private val reservations = linkedMapOf<SubAgentWriteOwner, Set<String>>()
    private val activeLeases = mutableListOf<MutationLease>()

    fun reserveBatch(scopes: Map<SubAgentWriteOwner, Collection<String>>): BatchReservation {
        val normalized = linkedMapOf<SubAgentWriteOwner, Set<String>>()
        scopes.entries
            .sortedWith(compareBy({ it.key.parentConversationId }, { it.key.taskIndex }))
            .forEach { (owner, paths) ->
                normalized[owner] = paths.map(::normalizeWorkspacePath).toSortedSet()
            }
        val entries = normalized.entries.toList()
        for (leftIndex in entries.indices) {
            for (rightIndex in leftIndex + 1 until entries.size) {
                val left = entries[leftIndex]
                val right = entries[rightIndex]
                val conflict = left.value.firstNotNullOfOrNull { leftPath ->
                    right.value.firstOrNull { rightPath -> pathsConflict(leftPath, rightPath) }
                        ?.let { rightPath -> leftPath to rightPath }
                }
                require(conflict == null) {
                    "Sub-agent write scopes overlap: task ${left.key.taskIndex + 1} '${conflict?.first}' conflicts with task ${right.key.taskIndex + 1} '${conflict?.second}'. Assign each path to exactly one task."
                }
            }
        }
        synchronized(monitor) {
            normalized.forEach { (owner, paths) ->
                require(owner !in reservations) { "Sub-agent write scope is already active for task ${owner.taskIndex + 1}." }
                reservations.forEach { (existingOwner, existingPaths) ->
                    val conflict = paths.firstNotNullOfOrNull { path ->
                        existingPaths.firstOrNull { existing -> pathsConflict(path, existing) }
                            ?.let { existing -> path to existing }
                    }
                    require(conflict == null) {
                        "Sub-agent write scope '${conflict?.first}' conflicts with active task ${existingOwner.taskIndex + 1} scope '${conflict?.second}'."
                    }
                }
            }
            reservations.putAll(normalized)
        }
        return BatchReservation(normalized.keys.toSet())
    }

    fun acquire(owner: SubAgentWriteOwner?, rawPaths: Collection<String>): MutationLease? {
        if (rawPaths.isEmpty()) return null
        val paths = rawPaths.map(::normalizeWorkspacePath).toSortedSet()
        synchronized(monitor) {
            if (owner != null) {
                require(reservations.containsKey(owner)) { "This sub-agent has no active write assignment." }
                val allowed = reservations.getValue(owner)
                val undeclared = paths.firstOrNull { it !in allowed }
                require(undeclared == null) {
                    "Sub-agent task ${owner.taskIndex + 1} attempted to mutate undeclared path '$undeclared'. Declare every exact workspace path in write_paths before delegation."
                }
            } else {
                val conflict = paths.firstNotNullOfOrNull { path ->
                    reservations.entries.firstNotNullOfOrNull { (reservedOwner, reservedPaths) ->
                        reservedPaths.firstOrNull { reserved -> pathsConflict(path, reserved) }
                            ?.let { reserved -> Triple(path, reserved, reservedOwner) }
                    }
                }
                require(conflict == null) {
                    "Workspace path '${conflict?.first}' is reserved by sub-agent task ${(conflict?.third?.taskIndex ?: 0) + 1} as '${conflict?.second}'. Wait for the sub-agent batch to finish."
                }
            }
            val activeConflict = paths.firstNotNullOfOrNull { path ->
                activeLeases.firstNotNullOfOrNull { lease ->
                    lease.paths.firstOrNull { active -> pathsConflict(path, active) }
                        ?.let { active -> path to active }
                }
            }
            require(activeConflict == null) {
                "Concurrent workspace mutation blocked: '${activeConflict?.first}' conflicts with active path '${activeConflict?.second}'."
            }
            return MutationLease(owner, paths).also { activeLeases += it }
        }
    }

    fun hasReservations(): Boolean = synchronized(monitor) { reservations.isNotEmpty() }

    internal fun normalizeWorkspacePath(raw: String): String {
        val replaced = raw.trim().replace('\\', '/')
        require(replaced.isNotBlank()) { "Workspace mutation path must not be blank." }
        require(!replaced.startsWith('/')) { "Sub-agent write_paths must be workspace-relative: $raw" }
        require(!Regex("^[A-Za-z]:/").containsMatchIn(replaced)) { "Sub-agent write_paths must be workspace-relative: $raw" }
        val segments = replaced.split('/').filter { it.isNotBlank() && it != "." }
        require(segments.isNotEmpty()) { "Workspace mutation path must identify a file or directory." }
        require(segments.none { it == ".." }) { "Sub-agent write_paths cannot escape the workspace: $raw" }
        return segments.joinToString("/")
    }

    internal fun pathsConflict(left: String, right: String): Boolean {
        return left == right || left.startsWith("$right/") || right.startsWith("$left/")
    }

    inner class BatchReservation internal constructor(
        private val owners: Set<SubAgentWriteOwner>,
    ) : AutoCloseable {
        override fun close() {
            synchronized(monitor) {
                owners.forEach(reservations::remove)
            }
        }
    }

    inner class MutationLease internal constructor(
        val owner: SubAgentWriteOwner?,
        val paths: Set<String>,
    ) : AutoCloseable {
        override fun close() {
            synchronized(monitor) {
                activeLeases.remove(this)
            }
        }
    }
}
