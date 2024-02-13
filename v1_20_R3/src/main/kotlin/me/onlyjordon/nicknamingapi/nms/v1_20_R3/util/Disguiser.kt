package me.onlyjordon.nicknamingapi.nms.v1_20_R3.util

import PlayerExtensions.Companion.refresh
import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListener
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.player.TextureProperty
import com.github.retrooper.packetevents.protocol.player.UserProfile
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientSettings
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import com.mojang.authlib.properties.Property
import io.netty.buffer.Unpooled
import me.onlyjordon.nicknamingapi.INicknamer
import me.onlyjordon.nicknamingapi.NickData
import me.onlyjordon.nicknamingapi.events.PlayerSkinLayerChangeEvent
import me.onlyjordon.nicknamingapi.utils.Skin
import me.onlyjordon.nicknamingapi.utils.SkinLayers
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.serializer.json.JSONComponentSerializer
import net.minecraft.ChatFormatting
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.*
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket.Parameters
import net.minecraft.server.level.ServerPlayer
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Location
import org.bukkit.craftbukkit.v1_20_R3.CraftServer
import org.bukkit.craftbukkit.v1_20_R3.entity.CraftPlayer
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import org.jetbrains.annotations.ApiStatus.Internal
import java.lang.reflect.Constructor
import java.util.*

@Internal
class Disguiser: Listener,PacketListener, INicknamer() {
    private val prefixSuffix = WeakHashMap<Player, ClientboundSetPlayerTeamPacket>()

    private var setPlayerTeamPacketConstructor: Constructor<ClientboundSetPlayerTeamPacket>? = null

    private fun setFields() {
        if (setPlayerTeamPacketConstructor == null) {
            setPlayerTeamPacketConstructor = ClientboundSetPlayerTeamPacket::class.java.getDeclaredConstructor(
                String::class.java,
                Integer.TYPE,
                Optional::class.java,
                Collection::class.java
            )
            (setPlayerTeamPacketConstructor as Constructor<ClientboundSetPlayerTeamPacket>).isAccessible = true
        }
    }

    private fun getFakeUUID(player: Player): UUID {
        val d = data[player.uniqueId] ?: return UUID.randomUUID()
        val id = d.fakeUUID ?: UUID.randomUUID()
        d.fakeUUID = id
        return id
    }
    private fun getFakeUUID(uuid: UUID): UUID {
        val d = data[uuid] ?: return UUID.randomUUID()
        val id = d.fakeUUID ?: UUID.randomUUID()
        d.fakeUUID = id
        return id
    }


    override fun disable() {
        PacketEvents.getAPI().terminate()
    }

    override fun setup() {
        PacketEvents.getAPI().eventManager.registerListener(this, PacketListenerPriority.LOW)
        PacketEvents.getAPI().init()
        setFields()
    }

    override fun getSkin(player: Player): Skin {
        val d = data[player.uniqueId] ?: return Skin(null, null)
        d.currentSkin = d.currentSkin ?: d.originalSkin ?: Skin(
            null,
            null
        )
        return d.currentSkin
    }

    override fun getSkin(skinName: String): Skin {
        return Skin.getSkin(skinName)
    }

    override fun onPacketSend(event: PacketSendEvent) {
        if (event.packetType == PacketType.Play.Server.PLAYER_INFO_UPDATE) {
            val packet = WrapperPlayServerPlayerInfoUpdate(event)
            packet.entries.forEach {
                changeProfile(it.gameProfile, !(event.player is Player && it.gameProfile.uuid == (event.player as Player).uniqueId))
            }
        }
        if (event.packetType == PacketType.Play.Server.PLAYER_INFO_REMOVE) {
            val packet = WrapperPlayServerPlayerInfoRemove(event)
            val toRemove = HashSet<UUID>()
            val toAdd = HashSet<UUID>()
            packet.profileIds.forEach {
                if (event.player is Player && it == (event.player as Player).uniqueId) return@forEach
                toRemove.add(it)
                toAdd.add(getFakeUUID(it))
            }
            toRemove.forEach { packet.profileIds.remove(it) }
            toAdd.forEach { packet.profileIds.add(it) }
        }
        if (event.packetType == PacketType.Play.Server.SPAWN_ENTITY) {
            val packet = WrapperPlayServerSpawnEntity(event)
            packet.uuid.ifPresent {
                val player = Bukkit.getPlayer(it) ?: return@ifPresent
                if (event.player is Player && it == (event.player as Player).uniqueId) return@ifPresent
                val id = getFakeUUID(player)
                packet.uuid = Optional.of(id)
            }
        }
        if (event.packetType == PacketType.Play.Server.ENTITY_METADATA) {
            val packet = WrapperPlayServerEntityMetadata(event)
            var metadataPlayer: Player? = null
            Bukkit.getOnlinePlayers().forEach {
                if (packet.entityId == it.entityId) {
                    metadataPlayer = it
                }
            }
            if (metadataPlayer == null) return
            packet.entityMetadata.forEach {
                if (it.index == 17) { // skin layers
                    it.value = data[metadataPlayer!!.uniqueId]?.skinLayers?.rawSkinLayers ?: it.value
                }
            }
        }
    }

    override fun onPacketReceive(event: PacketReceiveEvent) {
        if (event.player !is Player) return
        val player = event.player as Player
        if (event.packetType == PacketType.Play.Client.CLIENT_SETTINGS) {
            val packet = WrapperPlayClientSettings(event)
            val e = PlayerSkinLayerChangeEvent(player, data[player.uniqueId]?.skinLayers, SkinLayers.getFromRaw(packet.visibleSkinSectionMask), PlayerSkinLayerChangeEvent.Reason.PLAYER)
            Bukkit.getPluginManager().callEvent(e)
            if (e.isCancelled) {
                packet.visibleSkinSectionMask = data[player.uniqueId]?.skinLayers?.rawSkinLayers ?: 0b0000000
            } else {
                packet.visibleSkinSectionMask = e.newLayers?.rawSkinLayers ?: 0b0000000
            }
            data[player.uniqueId]?.skinLayers = SkinLayers.getFromRaw(packet.visibleSkinSectionMask)
        }
    }

    private fun changeProfile(profile: UserProfile, hideUUID: Boolean = true) {
        val uuid = profile.uuid
        val other = Bukkit.getOfflinePlayer(uuid)
        if (!other.isOnline) return
        if (other !is Player) return
        val data = data[uuid] ?: return
        val id = getFakeUUID(other)
        profile.name = data.nickname ?: other.name
        if (hideUUID) profile.uuid = id // fake uuid
        val skin = data.currentSkin ?: data.originalSkin ?: Skin(
            null,
            null
        )
        profile.textureProperties = listOf(TextureProperty("textures", skin.value, skin.signature))
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onJoin(e: PlayerJoinEvent) {
        val player = e.player
        val metadata = (player as CraftPlayer).handle.entityData
        var rawLayers = 0.toByte()
        metadata.nonDefaultValues?.forEach {
            if (it.id == 17)
                if (it.value is Byte) rawLayers = it.value as Byte
        }
        data[player.uniqueId] = NickData(
            player.profile.properties.get("textures").firstOrNull()?.let {
                Skin(
                    it.value,
                    it.signature
                )
            },
            player.name,
            net.kyori.adventure.text.Component.text(""),
            net.kyori.adventure.text.Component.text(""),
            SkinLayers.getFromRaw(rawLayers),
            UUID.randomUUID()
        )
        prefixSuffix.values.forEach {
            player.handle.connection.send(it)
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onQuit(e: PlayerQuitEvent) {
        val player = e.player
        val plugin = JavaPlugin.getProvidingPlugin(this.javaClass)
        if (plugin.isEnabled) {
            // to allow for info remove packet to be altered
            Bukkit.getServer().scheduler.runTaskLater(plugin, Runnable {
                if (!player.isOnline)
                    data.remove(player.uniqueId)
            }, 2L)
        }
        Bukkit.getOnlinePlayers().forEach {
            sendScoreboardRemovePacket(player, it)
        }
        prefixSuffix.remove(player)
    }

    private fun sendScoreboardRemovePacket(toRemove: Player, toSend: Player) {
        getTeamPacket(toRemove, net.kyori.adventure.text.Component.text(""), net.kyori.adventure.text.Component.text(""), ChatColor.WHITE, 1, 0).let { packet ->
            (toSend as CraftPlayer).handle.connection.send(packet)
        }
    }

    @EventHandler
    fun onWorldChange(e: PlayerChangedWorldEvent) {
        e.player.refresh()
    }

    override fun refreshPlayer(player: Player) {
        val plugin = JavaPlugin.getProvidingPlugin(this.javaClass)
        player.location.world?.players?.forEach {
            if (it != player) {
                if (!Bukkit.isPrimaryThread()) Bukkit.getScheduler().runTask(plugin, Runnable {
                    it.hidePlayer(player)
                })
                else {
                    it.hidePlayer(player)
                }
            }
        }
        if (!player.isOnline) return
        for (other in player.location.world?.players ?: emptyList()) {
            val otherNMSEntity = (other as CraftPlayer).handle
            otherNMSEntity.sendPlayerInfoRemovePacket(player)
            otherNMSEntity.sendPlayerInfoUpdatePacket(player, Action.ADD_PLAYER)
            otherNMSEntity.sendPlayerInfoUpdatePacket(player, Action.UPDATE_LISTED)
            otherNMSEntity.sendSetEntityMetadataPacket(player)
            prefixSuffix[player]?.let { other.handle.connection.send(it) }
        }
        player.location.world?.players?.forEach {
            if (it != player) {
                if (!Bukkit.isPrimaryThread()) Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                    it.showPlayer(player)
                }, 1)
                else {
                    it.showPlayer(player)
                }
            }
        }
        val entityPlayer = (player as CraftPlayer).handle

        entityPlayer.sendRespawnPacket()
        entityPlayer.sendTeleportPacket(player.location)
        entityPlayer.sendLevelInfo()
        entityPlayer.sendAllPlayerInfo()
    }

    override fun setSkin(player: Player, skin: Skin): Boolean { // maybe replace with Paper's API for it?
        val data = data[player.uniqueId] ?: return false
        val craftPlayer = player as CraftPlayer
        craftPlayer.profile.properties.removeAll("textures")
        craftPlayer.profile.properties.put("textures", Property("textures", skin.value, skin.signature))
        data.currentSkin = skin
        return true
    }

    override fun setSkinLayerVisible(player: Player, layer: SkinLayers.SkinLayer, visible: Boolean) {
        val layers = data[player.uniqueId]?.skinLayers ?: return
        layers.setLayerVisible(layer, visible)
        data[player.uniqueId]?.skinLayers = layers
    }

    override fun setSkinLayers(player: Player, layers: SkinLayers) {
        data[player.uniqueId]?.skinLayers = layers
    }

    override fun isSkinLayerVisible(player: Player, layer: SkinLayers.SkinLayer): Boolean {
        return data[player.uniqueId]?.skinLayers?.isLayerVisible(layer) ?: true
    }

    override fun getSkinLayers(player: Player): SkinLayers {
        return data[player.uniqueId]?.skinLayers ?: SkinLayers.getFromRaw(0b0111111)
    }

    override fun setNick(player: Player, nick: String) {
        val data = data[player.uniqueId] ?: return
        println("Setting nick to $nick")
        data.nickname = nick
    }

    override fun resetNick(player: Player) {
        val data = data[player.uniqueId] ?: return
        data.nickname = null
    }

    override fun resetDisguise(player: Player) {
        resetSkin(player)
        resetNick(player)
        prefixSuffix.remove(player)
    }

    private fun getParameters(player: Player, prefix: TextComponent, suffix: TextComponent, color: ChatFormatting): Parameters {
        // better way of doing this?
        val byteBuf = FriendlyByteBuf(Unpooled.buffer()).
            writeComponent(Component.literal(player.uniqueId.toString().replace('-', Character.MIN_VALUE).subSequence(0,16).toString())).
            writeByte(0b01).
            writeUtf("always").
            writeUtf("never").
            writeEnum(color).
            writeComponent(Component.Serializer.fromJson((JSONComponentSerializer.json().serialize(prefix.asComponent())))).
            writeComponent(Component.Serializer.fromJson((JSONComponentSerializer.json().serialize(suffix.asComponent()))))
        return Parameters(byteBuf)
    }

    private fun getTeamPacket(player: Player, prefix: TextComponent, suffix: TextComponent, textColor: ChatColor, method: Int, priority: Int): ClientboundSetPlayerTeamPacket {
        return setPlayerTeamPacketConstructor!!.newInstance(
            (priority.toChar()+player.uniqueId.toString().replace('-', Character.MIN_VALUE)).substring(0, 16),
            method,
            Optional.of(getParameters(player, prefix, suffix, ChatFormatting.getByCode(textColor.char)!!)),
            listOf(getNick(player))
        )
    }

    override fun setPrefixSuffix(player: Player, prefix: TextComponent, suffix: TextComponent, textColor: ChatColor, priority: Int) {
        setFields()
        prefixSuffix[player] = getTeamPacket(player, prefix, suffix, textColor, 0, priority)
        data[player.uniqueId]?.prefix = prefix
        data[player.uniqueId]?.suffix = suffix
    }

    override fun getPrefix(player: Player?): TextComponent {
        return data[player?.uniqueId]?.prefix ?: net.kyori.adventure.text.Component.text("")
    }

    override fun getSuffix(player: Player?): TextComponent {
        return data[player?.uniqueId]?.suffix ?: net.kyori.adventure.text.Component.text("")
    }

    override fun updatePrefixSuffix(player: Player) {
        Bukkit.getOnlinePlayers().forEach {
            sendScoreboardRemovePacket(player, it)
            prefixSuffix[player]?.let { packet ->
                (it as CraftPlayer).handle.connection.send(packet)
            }
        }
    }

    override fun getNick(player: Player): String {
        return data[player.uniqueId]?.nickname ?: player.name
    }

    override fun getPlayerWithNick(nick: String): Player? {
        return data.entries.firstOrNull { it.value.nickname?.lowercase() == nick.lowercase() }?.key?.let { Bukkit.getPlayer(it) }
    }


    private fun ServerPlayer.sendAllPlayerInfo() {
        (Bukkit.getServer() as CraftServer).handle.sendAllPlayerInfo(this)
    }

    private fun ServerPlayer.sendLevelInfo() {
        (Bukkit.getServer() as CraftServer).handle.sendLevelInfo(this, this.serverLevel())
    }

    private fun ServerPlayer.sendRespawnPacket() {
        connection.send(ClientboundRespawnPacket(
                CommonPlayerSpawnInfo(
                    this.serverLevel().dimensionTypeId(),
                    this.serverLevel().dimension(),
                    0L, // seed
                    this.gameMode.gameModeForPlayer,
                    this.gameMode.gameModeForPlayer,
                    false, //isDebug
                    false, //isFlat
                    Optional.empty(),
                    0 // portal cooldown
                ),
                0x02.toByte()
            )
        )
    }

    private fun ServerPlayer.sendTeleportPacket(loc: Location) {
        connection.send(ClientboundPlayerPositionPacket(loc.x, loc.y, loc.z, loc.yaw, loc.pitch, Collections.emptySet(), 0))
    }

    private fun ServerPlayer.sendPlayerInfoRemovePacket(updatingPlayer: Player) {
        connection.send(ClientboundPlayerInfoRemovePacket(listOf(updatingPlayer.uniqueId))) // fake UUID is applied in packet listener
    }

    private fun ServerPlayer.sendPlayerInfoUpdatePacket(updatingPlayer: Player, action: Action) {
        connection.send(ClientboundPlayerInfoUpdatePacket(action, (updatingPlayer as CraftPlayer).handle))
    }

    private fun ServerPlayer.sendSetEntityMetadataPacket(updatingPlayer: Player) {
        connection.send(ClientboundSetEntityDataPacket(
            updatingPlayer.entityId,
            (updatingPlayer as CraftPlayer).handle.entityData.nonDefaultValues
        ))
    }

}
