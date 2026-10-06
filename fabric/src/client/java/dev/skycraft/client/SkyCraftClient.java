package dev.skycraft.client;

import dev.skycraft.combat.SkyCombat;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.renderer.entity.NoopRenderer;

public final class SkyCraftClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		dev.skycraft.link.SkyLink.announceRunning();
		DiscordPresence.start();
		DestructionToggle.register();
		// Multiplayer without editing files: the host opens their world to LAN (O, Open to LAN) and
		// e4mc gives them a link; friends type /join <link> in chat, and /leave to come back.
		net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
			dispatcher.register(net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("join")
				.then(net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument("link", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
					.executes(c -> {
						String link = com.mojang.brigadier.arguments.StringArgumentType.getString(c, "link");
						c.getSource().sendFeedback(net.minecraft.network.chat.Component.literal("Joining " + link.trim() + "..."));
						// After the chat screen has closed: this leaves the current world.
						net.minecraft.client.Minecraft.getInstance().execute(() -> MirrorWorld.joinFriend(net.minecraft.client.Minecraft.getInstance(), link));
						return 1;
					})));
			dispatcher.register(net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("leave").executes(c -> {
				net.minecraft.client.Minecraft.getInstance().execute(() -> MirrorWorld.leaveFriend(net.minecraft.client.Minecraft.getInstance()));
				return 1;
			}));
		});
		ClientTickEvents.END_CLIENT_TICK.register(SkyClient::clientTick);
		// Multiplayer testing on one PC: SKYCRAFT_LAN_PORT opens the world to LAN on that port as soon
		// as it's loaded, and SKYCRAFT_LAN_OFFLINE lets offline (dev) clients join it.
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register((handler, sender, minecraft) -> {
			String port = System.getenv("SKYCRAFT_LAN_PORT");
			var server = minecraft.getSingleplayerServer();
			if (port == null || port.isBlank() || server == null || server.isPublished()) {
				return;
			}
			minecraft.execute(() -> {
				if (System.getenv("SKYCRAFT_LAN_OFFLINE") != null) {
					server.setUsesAuthentication(false);
				}
				boolean ok = server.publishServer(net.minecraft.server.MinecraftServer.MultiplayerScope.LAN, false, Integer.parseInt(port.trim()));
				dev.skycraft.SkyCraft.LOG.info("SkyCraft: world opened to LAN on port {} ({}{})", port.trim(), ok ? "ok" : "FAILED",
					System.getenv("SKYCRAFT_LAN_OFFLINE") != null ? ", offline logins allowed" : "");
			});
		});
		// A guest in a friend's world: dying there kills this player's own Skyrim character.
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(dev.skycraft.net.SkyNet.Died.TYPE, (payload, context) -> {
			if (dev.skycraft.link.SkyLink.active()) {
				dev.skycraft.link.SkyLink.pushEvent(dev.skycraft.link.Proto.EV_PLAYER_DIED, payload.attackerFormId(), 0, 0, 0, 0, 0);
			}
		});
		// A guest in a friend's world: hits on the host's Skyrim actors land on this Skyrim's copies too,
		// and those copies stand where the host's Skyrim has them.
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(dev.skycraft.net.SkyNet.ActorHit.TYPE, (payload, context) -> {
			if (dev.skycraft.link.SkyLink.active()) {
				dev.skycraft.link.SkyLink.pushEvent(dev.skycraft.link.Proto.EV_HIT_ACTOR, payload.formId(), payload.damage(), payload.pushX(), payload.pushZ(),
					payload.pushStrength(), payload.flagsAndWeapon() & 0xFFFF, payload.flagsAndWeapon() >>> 16);
			}
		});
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(dev.skycraft.net.SkyNet.ActorSync.TYPE, (payload, context) -> {
			var formIds = payload.formIds();
			var flags = payload.flags();
			var where = payload.where();
			if (!dev.skycraft.link.SkyLink.active() || flags.size() != formIds.size() || where.size() != formIds.size() * 4
				|| payload.world() != SkyClient.sky().worldId) {
				return;
			}
			// Positions are replaced by the next packet; never crowd out hits when Skyrim is paused.
			if (dev.skycraft.link.SkyLink.eventRoom() < formIds.size() + 128) {
				return;
			}
			// Only actors around where this player's Skyrim has them (the host's server may not know
			// that yet, right after joining): Skyrim has nothing loaded further out.
			var here = SkyClient.sky();
			for (int i = 0; i < formIds.size(); i++) {
				double dx = where.get(i * 4) - here.x, dy = where.get(i * 4 + 1) - here.y, dz = where.get(i * 4 + 2) - here.z;
				if (dx * dx + dy * dy + dz * dz > 72.0 * 72.0) {
					continue;
				}
				dev.skycraft.link.SkyLink.pushEvent(dev.skycraft.link.Proto.EV_PUPPET_ACTOR, formIds.get(i), where.get(i * 4), where.get(i * 4 + 1),
					where.get(i * 4 + 2), where.get(i * 4 + 3), flags.get(i));
			}
		});
		// Skyrim draws the real NPC; its Minecraft stand-in is only a hitbox.
		EntityRendererRegistry.register(SkyCombat.SKYRIM_ACTOR, NoopRenderer::new);
		// Players (client-side movement AND the integrated server's re-check of it) use the smooth
		// triangle collider, never Skyrim's voxels; otherwise the server sees the smooth position
		// dip into a voxel and teleports the player back every few ticks.
		dev.skycraft.world.SkyCollision.setSmoothCollider(e -> e instanceof net.minecraft.world.entity.player.Player && SkyClient.linked());
	}
}
