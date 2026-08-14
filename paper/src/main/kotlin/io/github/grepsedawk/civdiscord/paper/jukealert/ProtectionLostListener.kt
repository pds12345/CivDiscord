package io.github.grepsedawk.civdiscord.paper.jukealert

import com.untamedears.jukealert.events.SnitchProtectionLostEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Relays a snitch losing ground to whoever damaged it out of.
 *
 * [SnitchProtectionLostEvent] fires for snitches whose field can shrink. JukeAlert's own snitch
 * types have fixed fields and never fire it; in practice it comes from civnodes' node snitch, whose
 * field is the chunks its node protects and therefore contracts as the node's bastion is beaten
 * down.
 *
 * This is deliberately a separate [Listener] from [SnitchListener] rather than one more
 * `@EventHandler` on it. Bukkit resolves every handler method's event class when the listener is
 * registered, so a missing [SnitchProtectionLostEvent] would fail registration for the whole class
 * — silently taking ENTER/LOGIN/LOGOUT down with it on any server whose JukeAlert predates the
 * event. Kept apart, the guard in CivDiscordPaperPlugin can skip just this one and leave ordinary
 * snitch alerts working.
 */
class ProtectionLostListener(
    private val snitches: SnitchListener,
    private val logger: Logger = LoggerFactory.getLogger(ProtectionLostListener::class.java),
) : Listener {

    @EventHandler
    fun onProtectionLost(event: SnitchProtectionLostEvent) {
        runCatching {
            // The location reported is the chunk that fell out of the field, not the snitch's own:
            // the snitch has not moved, and where the boundary went is the point of the alert.
            val lost = event.lostChunk
            snitches.handle(
                event.snitch,
                event.player,
                SnitchListener.Kind.PROTECTION_LOST,
                at = lost?.let { SnitchListener.BlockPos(it.blockX, it.blockY, it.blockZ) },
            )
        }.onFailure {
            logger.warn(
                "ProtectionLostListener.{} threw — Bukkit would have swallowed this",
                SnitchListener.Kind.PROTECTION_LOST,
                it,
            )
        }
    }
}
