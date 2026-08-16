package io.github.grepsedawk.civdiscord.paper.jukealert

import be.seeseemelk.mockbukkit.MockBukkit
import be.seeseemelk.mockbukkit.ServerMock
import be.seeseemelk.mockbukkit.entity.PlayerMock
import com.untamedears.jukealert.events.SnitchProtectionLostEvent
import com.untamedears.jukealert.model.Snitch
import io.github.grepsedawk.civdiscord.core.bridge.BridgeCodec
import io.github.grepsedawk.civdiscord.core.bridge.BridgeSigner
import io.github.grepsedawk.civdiscord.core.bridge.Payload
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import vg.civcraft.mc.namelayer.group.Group
import java.io.File
import java.util.UUID

/**
 * Drives a protection-loss alert the way the server does: a real [SnitchProtectionLostEvent]
 * object handed to Bukkit's own dispatcher, with [ProtectionLostListener] registered through the
 * real PluginManager. Nothing here calls the handler directly, so it also proves the
 * `@EventHandler` registration actually routes this event type.
 *
 * The bytes it produces are pinned against [WIRE_FIXTURE], the same file
 * `ProtectionLostForwardingTest` on the Velocity side decodes. Paper and Velocity ship as separate
 * jars and can be deployed at different versions, so the wire format between them is a contract;
 * the fixture is what makes a drift on either side fail a build instead of a raid alert.
 */
class ProtectionLostBridgeTest {

    private val intruder: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val owner: UUID = UUID.fromString("00000000-0000-0000-0000-000000000002")

    private lateinit var server: ServerMock

    @BeforeEach fun setup() {
        server = MockBukkit.mock()
    }

    @AfterEach fun teardown() {
        MockBukkit.unmock()
    }

    private fun mockSnitch(): Snitch {
        val s = mockk<Snitch>()
        val g = mockk<Group>()
        every { g.name } returns "townhall"
        every { g.owner } returns owner
        every { s.id } returns 42
        every { s.name } returns "TownNorth"
        every { s.group } returns g
        every { s.placer } returns owner
        // The snitch's own block. The alert must NOT report this.
        val loc = mockk<Location>()
        every { loc.blockX } returns 10
        every { loc.blockY } returns 64
        every { loc.blockZ } returns -3
        every { s.location } returns loc
        return s
    }

    private fun lostChunkAt(x: Int, y: Int, z: Int): Location {
        val loc = mockk<Location>()
        every { loc.blockX } returns x
        every { loc.blockY } returns y
        every { loc.blockZ } returns z
        return loc
    }

    /**
     * Fires the event through Bukkit rather than calling the listener, and returns whatever the
     * listener handed to the bridge.
     */
    private fun fireThroughBukkit(
        lostChunk: Location = lostChunkAt(328, 71, -1544),
        chunkCount: Int = 1,
    ): List<Payload> {
        val sent = mutableListOf<Payload>()
        val plugin = MockBukkit.createMockPlugin()
        val snitches = SnitchListener(serverName = "citadel", send = sent::add)
        server.pluginManager.registerEvents(ProtectionLostListener(snitches), plugin)

        val player = PlayerMock(server, "Alice", intruder)
        val event = SnitchProtectionLostEvent(mockSnitch(), player, lostChunk, chunkCount)
        server.pluginManager.callEvent(event)

        return sent
    }

    @Test
    fun `a real event dispatched by Bukkit reaches the bridge as a PROTECTION_LOST SnitchHit`() {
        val sent = fireThroughBukkit()

        sent shouldHaveSize 1
        val hit = sent.single() as Payload.SnitchHit
        hit.kind shouldBe "PROTECTION_LOST"
        hit.server shouldBe "citadel"
        hit.namelayerGroup shouldBe "townhall"
        hit.snitchName shouldBe "TownNorth"
        hit.intruderUuid shouldBe intruder.toString()
        hit.intruderName shouldBe "Alice"
        hit.snitchOwnerUuid shouldBe owner.toString()
    }

    @Test
    fun `the coordinates on the wire are the lost chunk, not the snitch`() {
        val hit = fireThroughBukkit(lostChunk = lostChunkAt(328, 71, -1544)).single() as Payload.SnitchHit

        hit.x shouldBe 328
        hit.y shouldBe 71
        hit.z shouldBe -1544
    }

    @Test
    fun `losing several chunks at once still emits exactly one alert`() {
        // NodeProtectionWatcher batches a multi-chunk loss into a single event with chunkCount > 1.
        // The payload has no field for the count, so the contract is one alert naming the outermost
        // chunk — not one alert per chunk.
        fireThroughBukkit(chunkCount = 4) shouldHaveSize 1
    }

    @Test
    fun `encoded frame matches the checked-in wire fixture byte for byte`() {
        val hit = fireThroughBukkit().single()

        val actual = BridgeCodec.encode(hit).toString(Charsets.UTF_8)

        actual shouldBe WIRE_FIXTURE.readText(Charsets.UTF_8).trim()
    }

    @Test
    fun `the signed frame the bridge puts on the channel verifies and decodes back`() {
        val hit = fireThroughBukkit().single()
        val signer = BridgeSigner(TEST_SECRET)

        val frame = signer.sign(BridgeCodec.encode(hit))
        val verified = signer.verify(frame)

        verified shouldBe BridgeCodec.encode(hit)
        BridgeCodec.decode(verified!!) shouldBe hit
    }

    @Test
    fun `the event class the registration guard looks for is the one that is dispatched`() {
        // CivDiscordPaperPlugin registers this listener only if the class resolves. If the vendored
        // JukeAlert ever loses the event, this fails here rather than going quiet in production.
        Class.forName("com.untamedears.jukealert.events.SnitchProtectionLostEvent") shouldBe
            SnitchProtectionLostEvent::class.java
    }

    companion object {
        val TEST_SECRET: ByteArray = ByteArray(32) { it.toByte() }

        /** Resolved by walking up, so it works from any module's test working directory. */
        val WIRE_FIXTURE: File = run {
            var dir: File? = File(".").absoluteFile
            while (dir != null) {
                val f = File(dir, "testdata/protection-lost-snitch-hit.json")
                if (f.isFile) return@run f
                dir = dir.parentFile
            }
            error("testdata/protection-lost-snitch-hit.json not found above ${File(".").absolutePath}")
        }
    }
}
