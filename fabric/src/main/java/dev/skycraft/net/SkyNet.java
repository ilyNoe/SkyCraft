package dev.skycraft.net;

import dev.skycraft.SkyCraft;
import dev.skycraft.combat.SkyCombat;
import dev.skycraft.world.SkyDig;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * Multiplayer: every player has their own Skyrim, talking to their own Minecraft client. The host's
 * Skyrim reaches the host's integrated server through shared memory; a guest's Skyrim reaches the
 * host's server through these packets instead.
 */
public final class SkyNet {
	private SkyNet() {
	}

	/** Guest -> server: the guest's Skyrim hit them (as proto::InputEvent kInHurt). */
	public record Hurt(int kind, float skyrimDamage, int attackerFormId, int flags) implements CustomPacketPayload {
		public static final Type<Hurt> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "hurt"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Hurt> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, Hurt::kind,
			ByteBufCodecs.FLOAT, Hurt::skyrimDamage,
			ByteBufCodecs.INT, Hurt::attackerFormId,
			ByteBufCodecs.VAR_INT, Hurt::flags,
			Hurt::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Server -> guest: the guest died in Minecraft, so their Skyrim player dies too. */
	public record Died(int attackerFormId) implements CustomPacketPayload {
		public static final Type<Died> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "died"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Died> CODEC = StreamCodec.composite(ByteBufCodecs.INT, Died::attackerFormId, Died::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Client -> server: the player hit Skyrim's geometry in this cell (SkyDig.open). */
	public record DigOpen(int world, BlockPos pos, int material) implements CustomPacketPayload {
		public static final Type<DigOpen> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "dig_open"));
		public static final StreamCodec<RegistryFriendlyByteBuf, DigOpen> CODEC = StreamCodec.composite(
			ByteBufCodecs.INT, DigOpen::world,
			BlockPos.STREAM_CODEC, DigOpen::pos,
			ByteBufCodecs.VAR_INT, DigOpen::material,
			DigOpen::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Client -> server: cells around a broken dug block that are inside Skyrim's geometry (SkyDig.reveal). */
	public record DigReveal(int world, List<BlockPos> cells, List<Integer> materials) implements CustomPacketPayload {
		public static final Type<DigReveal> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "dig_reveal"));
		public static final StreamCodec<RegistryFriendlyByteBuf, DigReveal> CODEC = StreamCodec.composite(
			ByteBufCodecs.INT, DigReveal::world,
			BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list(64)), DigReveal::cells,
			ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(64)), DigReveal::materials,
			DigReveal::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * Server -> guest: someone hit a Skyrim actor the guest's Skyrim has too (same form id), so it
	 * takes the hit there as well. {@code flagsAndWeapon} is Proto.HIT_* | Proto.WEAPON_* << 16.
	 */
	public record ActorHit(int formId, float damage, float pushX, float pushZ, float pushStrength, int flagsAndWeapon) implements CustomPacketPayload {
		public static final Type<ActorHit> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "actor_hit"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ActorHit> CODEC = StreamCodec.composite(
			ByteBufCodecs.INT, ActorHit::formId,
			ByteBufCodecs.FLOAT, ActorHit::damage,
			ByteBufCodecs.FLOAT, ActorHit::pushX,
			ByteBufCodecs.FLOAT, ActorHit::pushZ,
			ByteBufCodecs.FLOAT, ActorHit::pushStrength,
			ByteBufCodecs.INT, ActorHit::flagsAndWeapon,
			ActorHit::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * Server -> guest, ten times a second: where the host's Skyrim has the actors near the guest.
	 * The guest's Skyrim moves its own copies there, so both players see them in the same place.
	 * {@code where} holds x, y, z, yaw for each form id, in order (Minecraft coordinates), and
	 * {@code world} is the Skyrim world the host is in: the same coordinates mean somewhere else in
	 * another one (an interior, say), so a guest who isn't there ignores it.
	 */
	public record ActorSync(int world, List<Integer> formIds, List<Integer> flags, List<Float> where) implements CustomPacketPayload {
		public static final int MAX_ACTORS = 96;
		public static final Type<ActorSync> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "actor_sync"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ActorSync> CODEC = StreamCodec.composite(
			ByteBufCodecs.INT, ActorSync::world,
			ByteBufCodecs.INT.apply(ByteBufCodecs.list(MAX_ACTORS)), ActorSync::formIds,
			ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(MAX_ACTORS)), ActorSync::flags,
			ByteBufCodecs.FLOAT.apply(ByteBufCodecs.list(MAX_ACTORS * 4)), ActorSync::where,
			ActorSync::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Guest -> server: the guest's Skyrim completed a Daedric quest (as proto::InputEvent kInQuestDone). */
	public record QuestDone(int quest) implements CustomPacketPayload {
		public static final Type<QuestDone> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "quest_done"));
		public static final StreamCodec<RegistryFriendlyByteBuf, QuestDone> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, QuestDone::quest, QuestDone::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public static void init() {
		PayloadTypeRegistry.serverboundPlay().register(QuestDone.TYPE, QuestDone.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(QuestDone.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> dev.skycraft.world.SkyRewards.questDone(player, payload.quest()));
		});
		PayloadTypeRegistry.serverboundPlay().register(Hurt.TYPE, Hurt.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(DigOpen.TYPE, DigOpen.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(DigReveal.TYPE, DigReveal.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(DigOpen.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> SkyDig.open(player, payload.world(), payload.pos(), payload.material()));
		});
		ServerPlayNetworking.registerGlobalReceiver(DigReveal.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			int[] materials = payload.materials().stream().mapToInt(Integer::intValue).toArray();
			context.server().execute(() -> SkyDig.reveal(player, payload.world(), payload.cells(), materials));
		});
		PayloadTypeRegistry.clientboundPlay().register(Died.TYPE, Died.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ActorHit.TYPE, ActorHit.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ActorSync.TYPE, ActorSync.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(Hurt.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			// A hit's worth of damage, whatever the guest's client claims (friends only, but still).
			float damage = Math.max(0.0F, Math.min(payload.skyrimDamage(), 10000.0F));
			context.server().execute(() -> SkyCombat.hurtPlayer(player, payload.kind(), damage, payload.attackerFormId(), payload.flags()));
		});
	}

	/** True if this player plays on this machine (their Skyrim is on the shared-memory link). */
	public static boolean isHost(ServerPlayer player) {
		var server = player.level().getServer();
		return server != null && server.isSingleplayerOwner(player.nameAndId());
	}
}
