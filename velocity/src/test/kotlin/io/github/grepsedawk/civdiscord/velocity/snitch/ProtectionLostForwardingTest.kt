package io.github.grepsedawk.civdiscord.velocity.snitch

import io.github.grepsedawk.civdiscord.core.bridge.BridgeSigner
import io.github.grepsedawk.civdiscord.core.db.Binding
import io.github.grepsedawk.civdiscord.core.db.BindingDao
import io.github.grepsedawk.civdiscord.core.db.CivDiscordDb
import io.github.grepsedawk.civdiscord.core.db.GuildDao
import io.github.grepsedawk.civdiscord.core.db.RelayDao
import io.github.grepsedawk.civdiscord.core.relay.SnitchPing
import io.github.grepsedawk.civdiscord.velocity.bridge.BridgeServer
import io.github.grepsedawk.civdiscord.velocity.bridge.ServerInboundHandlers
import io.github.grepsedawk.civdiscord.velocity.discord.NameLayerPermService
import io.github.grepsedawk.civdiscord.velocity.discord.PermCheck
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID

/**
 * The Velocity half of the protection-loss path, driven from the exact bytes the Paper half
 * produces (`ProtectionLostBridgeTest` pins the same fixture). Entry is [BridgeServer.handleIncoming],
 * the real ingress a `PluginMessageEvent` calls, so this covers HMAC verification, JSON decode,
 * payload routing and [SnitchRelay] gating — everything between the wire and the Discord send.
 */
class ProtectionLostForwardingTest {

    private data class Sent(val channelId: Long, val body: String, val ping: SnitchPing?)

    private val binderUuid: UUID = UUID.fromString("0111b95d-110c-4ea1-b4b2-59afeff296f4")
    private val secret: ByteArray = ByteArray(32) { it.toByte() }

    private fun permissiveBindings(): BindingDao {
        val b = mockk<BindingDao>(relaxed = true)
        every { b.findByDiscordId(any()) } answers {
            Binding(discordId = firstArg(), mcUuid = binderUuid, mcName = "mc", linkedAt = 0L)
        }
        return b
    }

    private class PermissivePermService : NameLayerPermService(lookup = { _, _, _ -> PermCheck.DENIED }) {
        override fun hasPerm(mcUuid: UUID, group: String, perm: String) = perm != NameLayerPermService.SNITCH_IMMUNE
    }

    private class ImmunePermService : NameLayerPermService(lookup = { _, _, _ -> PermCheck.DENIED }) {
        override fun hasPerm(mcUuid: UUID, group: String, perm: String) = true
    }

    /** Wires the real ingress to a real relay over an in-memory DB, and captures Discord sends. */
    private fun harness(
        permService: NameLayerPermService = PermissivePermService(),
        signed: Boolean = true,
    ): Triple<BridgeServer, RelayDao, MutableList<Sent>> {
        val db = CivDiscordDb.inMemory()
        GuildDao(db).ensure(100L)
        val relays = RelayDao(db)
        val sent = mutableListOf<Sent>()
        val relay = SnitchRelay(
            relays = relays,
            bindings = permissiveBindings(),
            permService = permService,
            sendToDiscord = { ch, txt, ping -> sent.add(Sent(ch, txt, ping)) },
        )
        val bridge = BridgeServer(
            signer = if (signed) BridgeSigner(secret) else null,
            handlersFactory = { ServerInboundHandlers.noop().copy(onSnitchHit = { relay.dispatch(it) }) },
        )
        return Triple(bridge, relays, sent)
    }

    private fun bindChannel(relays: RelayDao, channel: Long = 1001L, showSnitches: Boolean = true) {
        relays.bind(100L, channel, "townhall", isWriter = true, showSnitches = false, createdBy = 1L)
        if (showSnitches) relays.setShowSnitches(channel, "townhall", true)
    }

    private fun wireBytes(): ByteArray = FIXTURE.readText(Charsets.UTF_8).trim().toByteArray(Charsets.UTF_8)

    private fun frame(): ByteArray = BridgeSigner(secret).sign(wireBytes())

    @Test
    fun `a signed frame from Paper reaches Discord as a protection lost alert`() {
        val (bridge, relays, sent) = harness()
        bindChannel(relays)

        bridge.handleIncoming(frame())

        sent shouldHaveSize 1
        sent.single().channelId shouldBe 1001L
        sent.single().body shouldBe
            "**SNITCH** [protection lost] `Alice` at `328 71 -1544` (`TownNorth`) [`citadel`]"
    }

    @Test
    fun `the alert names the lost chunk rather than the snitch block`() {
        val (bridge, relays, sent) = harness()
        bindChannel(relays)

        bridge.handleIncoming(frame())

        // 10/64/-3 is where the snitch itself sits in the fixture's source event.
        sent.single().body.contains("328 71 -1544") shouldBe true
        sent.single().body.contains("10 64 -3") shouldBe false
    }

    @Test
    fun `fans out to every channel bound to the group with show_snitches on`() {
        val (bridge, relays, sent) = harness()
        bindChannel(relays, channel = 1001L)
        bindChannel(relays, channel = 1002L)
        bindChannel(relays, channel = 1003L, showSnitches = false)

        bridge.handleIncoming(frame())

        sent.map { it.channelId }.toSet() shouldBe setOf(1001L, 1002L)
    }

    @Test
    fun `drops the alert when no channel has show_snitches enabled`() {
        val (bridge, relays, sent) = harness()
        bindChannel(relays, showSnitches = false)

        bridge.handleIncoming(frame())

        sent shouldBe emptyList()
    }

    @Test
    fun `drops the alert when the intruder is SNITCH_IMMUNE on the group`() {
        val (bridge, relays, sent) = harness(permService = ImmunePermService())
        bindChannel(relays)

        bridge.handleIncoming(frame())

        sent shouldBe emptyList()
    }

    @Test
    fun `a frame signed with the wrong key is rejected before it can reach Discord`() {
        val (bridge, relays, sent) = harness()
        bindChannel(relays)

        bridge.handleIncoming(BridgeSigner(ByteArray(32) { 0x7f }).sign(wireBytes()))

        sent shouldBe emptyList()
        bridge.hmacVerifyFailures() shouldBe 1L
    }

    @Test
    fun `an unsigned frame is rejected when a signer is configured`() {
        val (bridge, relays, sent) = harness()
        bindChannel(relays)

        bridge.handleIncoming(wireBytes())

        sent shouldBe emptyList()
    }

    @Test
    fun `an older Velocity without the label still forwards the alert`() {
        // render() falls back to the lowercased kind, so a Paper deployed ahead of Velocity
        // degrades to the raw kind rather than dropping the alert. This pins that, since it is
        // what makes the two jars safe to roll out independently. The underscore comes back
        // escaped because the fallback goes through MarkdownSafe.text — Discord renders "\_" as a
        // plain underscore, so the reader still sees "some_future_kind".
        val (bridge, relays, sent) = harness()
        bindChannel(relays)
        val unknownKind = wireBytes().toString(Charsets.UTF_8)
            .replace("\"kind\":\"PROTECTION_LOST\"", "\"kind\":\"SOME_FUTURE_KIND\"")
            .toByteArray(Charsets.UTF_8)

        bridge.handleIncoming(BridgeSigner(secret).sign(unknownKind))

        sent shouldHaveSize 1
        sent.single().body.contains("""[some\_future\_kind]""") shouldBe true
    }

    @Test
    fun `the ping configured on the relay is applied to protection loss like any other hit`() {
        val (bridge, relays, sent) = harness()
        bindChannel(relays)
        relays.setSnitchPing(1001L, "townhall", "<@&555>")

        bridge.handleIncoming(frame())

        sent.single().ping shouldBe SnitchPing.Role(555L)
        sent.single().body.startsWith("<@&555>") shouldBe true
    }

    companion object {
        val FIXTURE: File = run {
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
