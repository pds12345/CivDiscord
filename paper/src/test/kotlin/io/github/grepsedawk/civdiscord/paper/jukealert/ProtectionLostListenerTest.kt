package io.github.grepsedawk.civdiscord.paper.jukealert

import be.seeseemelk.mockbukkit.MockBukkit
import com.untamedears.jukealert.events.SnitchProtectionLostEvent
import com.untamedears.jukealert.model.Snitch
import io.github.grepsedawk.civdiscord.core.bridge.Payload
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.helpers.MessageFormatter
import vg.civcraft.mc.namelayer.group.Group
import java.util.UUID

class ProtectionLostListenerTest {

    private val intruder: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val owner: UUID = UUID.fromString("00000000-0000-0000-0000-000000000002")

    @BeforeEach fun setup() {
        MockBukkit.mock()
    }

    @AfterEach fun teardown() {
        MockBukkit.unmock()
    }

    private class CapturingLogger : Logger by LoggerFactory.getLogger("capture") {
        val warns = mutableListOf<String>()
        override fun warn(msg: String) {
            warns += msg
        }
        override fun warn(msg: String, arg: Any?) {
            warns += MessageFormatter.format(msg, arg).message
        }
        override fun warn(msg: String, arg1: Any?, arg2: Any?) {
            warns += MessageFormatter.format(msg, arg1, arg2).message
        }
        override fun warn(msg: String, vararg args: Any?) {
            warns += MessageFormatter.arrayFormat(msg, args).message
        }
    }

    private fun mockPlayer(): Player {
        val p = mockk<Player>()
        every { p.uniqueId } returns intruder
        every { p.name } returns "Alice"
        return p
    }

    /** The snitch sits at 10/64/-3 so any test asserting the lost chunk can tell the two apart. */
    private fun mockSnitch(): Snitch {
        val s = mockk<Snitch>()
        val g = mockk<Group>()
        every { g.name } returns "townhall"
        every { g.owner } returns owner
        every { s.id } returns 42
        every { s.name } returns "TownNorth"
        every { s.group } returns g
        every { s.placer } returns owner
        val loc = mockk<Location>()
        every { loc.blockX } returns 10
        every { loc.blockY } returns 64
        every { loc.blockZ } returns -3
        every { s.location } returns loc
        return s
    }

    private fun mockLocation(x: Int, y: Int, z: Int): Location {
        val loc = mockk<Location>()
        every { loc.blockX } returns x
        every { loc.blockY } returns y
        every { loc.blockZ } returns z
        return loc
    }

    private fun mockEvent(lostChunk: Location? = mockLocation(328, 71, -1544)): SnitchProtectionLostEvent {
        val e = mockk<SnitchProtectionLostEvent>()
        every { e.snitch } returns mockSnitch()
        every { e.player } returns mockPlayer()
        every { e.lostChunk } returns lostChunk
        return e
    }

    private fun fixture(
        logger: Logger = LoggerFactory.getLogger("test"),
    ): Pair<ProtectionLostListener, MutableList<Payload>> {
        val sent = mutableListOf<Payload>()
        val snitches = SnitchListener(serverName = "citadel", send = sent::add)
        return ProtectionLostListener(snitches, logger) to sent
    }

    @Test
    fun `emits a SnitchHit tagged PROTECTION_LOST`() {
        val (listener, sent) = fixture()

        listener.onProtectionLost(mockEvent())

        sent shouldHaveSize 1
        val hit = sent.single() as Payload.SnitchHit
        hit.kind shouldBe "PROTECTION_LOST"
        hit.server shouldBe "citadel"
        hit.intruderUuid shouldBe intruder.toString()
        hit.intruderName shouldBe "Alice"
        hit.snitchName shouldBe "TownNorth"
        hit.namelayerGroup shouldBe "townhall"
    }

    @Test
    fun `reports the lost chunk rather than the snitch's own location`() {
        val (listener, sent) = fixture()

        listener.onProtectionLost(mockEvent(lostChunk = mockLocation(328, 71, -1544)))

        val hit = sent.single() as Payload.SnitchHit
        hit.x shouldBe 328
        hit.y shouldBe 71
        hit.z shouldBe -1544
    }

    @Test
    fun `falls back to the snitch location when the event carries no lost chunk`() {
        val (listener, sent) = fixture()

        listener.onProtectionLost(mockEvent(lostChunk = null))

        val hit = sent.single() as Payload.SnitchHit
        hit.x shouldBe 10
        hit.y shouldBe 64
        hit.z shouldBe -3
    }

    @Test
    fun `drops the alert when the snitch has no NameLayer group`() {
        val (listener, sent) = fixture()
        val orphan = mockk<Snitch>()
        every { orphan.id } returns 7
        every { orphan.name } returns "OrphanSnitch"
        every { orphan.group } returns null
        every { orphan.placer } returns owner
        every { orphan.location } returns mockLocation(1, 2, 3)
        val e = mockk<SnitchProtectionLostEvent>()
        every { e.snitch } returns orphan
        every { e.player } returns mockPlayer()
        every { e.lostChunk } returns mockLocation(328, 71, -1544)

        listener.onProtectionLost(e)

        sent shouldBe emptyList()
    }

    @Test
    fun `catches exceptions so Bukkit does not swallow them`() {
        val log = CapturingLogger()
        val (listener, sent) = fixture(logger = log)
        val e = mockk<SnitchProtectionLostEvent>()
        every { e.snitch } returns mockSnitch()
        every { e.player } returns mockPlayer()
        every { e.lostChunk } throws RuntimeException("kaboom")

        listener.onProtectionLost(e)

        sent shouldBe emptyList()
        log.warns shouldHaveSize 1
        log.warns.first() shouldContain "Bukkit would have swallowed this"
    }

    @Test
    fun `onProtectionLost is annotated EventHandler so Bukkit dispatches the event`() {
        val m = ProtectionLostListener::class.java.getDeclaredMethod(
            "onProtectionLost",
            SnitchProtectionLostEvent::class.java,
        )
        (m.getAnnotation(EventHandler::class.java) != null) shouldBe true
    }

    @Test
    fun `SnitchListener carries no handler for the event so a missing class cannot break it`() {
        // The whole reason this listener is a separate class: Bukkit resolves handler event types
        // at registration, so one @EventHandler for a possibly-absent class here would take
        // ENTER/LOGIN/LOGOUT down with it.
        SnitchListener::class.java.declaredMethods.none { m ->
            m.parameterTypes.any { it == SnitchProtectionLostEvent::class.java }
        } shouldBe true
    }
}
