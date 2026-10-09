package dev.skycraft.world;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.skycraft.SkyCraft;
import dev.skycraft.combat.FusRoDah;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.phys.Vec3;

/**
 * The SkyCraft merchant: {@code /marchand} (or {@code /merchant}) puts a Minecraft wandering trader
 * in front of the player. It never moves, can't be hurt, never leaves, and its stock never runs
 * out. It sells the Fus Ro Dah sword and mob spawn eggs for emeralds (dug out of Skyrim's rock),
 * and buys raw ores and diamonds for emeralds. {@code /marchand retirer} removes merchants near you.
 */
public final class SkyMerchant {
	private static final String TAG = "skycraft_merchant";

	private SkyMerchant() {
	}

	public static void init() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> {
			register(dispatcher, "marchand", "retirer");
			register(dispatcher, "merchant", "remove");
		});
	}

	private static void register(CommandDispatcher<CommandSourceStack> dispatcher, String name, String remove) {
		LiteralArgumentBuilder<CommandSourceStack> command = Commands.literal(name)
			.executes(c -> spawn(c.getSource().getPlayerOrException()))
			.then(Commands.literal(remove).executes(c -> remove(c.getSource().getPlayerOrException())));
		dispatcher.register(command);
	}

	private static int spawn(ServerPlayer player) {
		ServerLevel level = player.level();
		Entity entity = EntityType.WANDERING_TRADER.create(level, EntitySpawnReason.COMMAND);
		if (!(entity instanceof Mob trader) || !(entity instanceof Merchant merchant)) {
			player.sendSystemMessage(Component.literal("Impossible de faire apparaître le marchand.").withStyle(ChatFormatting.RED));
			return 0;
		}
		// Two blocks in front of the player, on their level, facing them.
		Vec3 look = player.getLookAngle();
		Vec3 flat = new Vec3(look.x, 0.0, look.z);
		flat = flat.lengthSqr() < 1.0E-4 ? new Vec3(0.0, 0.0, 1.0) : flat.normalize();
		float yaw = player.getYRot() + 180.0F;
		trader.snapTo(player.getX() + flat.x * 2.0, player.getY(), player.getZ() + flat.z * 2.0, yaw, 0.0F);
		trader.setYHeadRot(yaw);
		trader.setYBodyRot(yaw);
		trader.setNoAi(true);
		trader.setInvulnerable(true);
		trader.setPersistenceRequired();
		trader.setCustomName(Component.literal("Marchand").withStyle(ChatFormatting.GOLD));
		trader.setCustomNameVisible(true);
		trader.addTag(TAG);
		stock(level, merchant.getOffers());
		if (!level.addFreshEntity(trader)) {
			player.sendSystemMessage(Component.literal("Impossible de faire apparaître le marchand ici.").withStyle(ChatFormatting.RED));
			return 0;
		}
		player.sendSystemMessage(Component.literal("Un marchand est apparu. Clic droit dessus pour commercer.").withStyle(ChatFormatting.GOLD));
		SkyCraft.LOG.info("SkyCraft: {} summoned a merchant at {}", player.getName().getString(), trader.blockPosition());
		return 1;
	}

	private static int remove(ServerPlayer player) {
		List<LivingEntity> near = player.level().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(8.0), e -> e.entityTags().contains(TAG));
		near.forEach(Entity::discard);
		player.sendSystemMessage(Component.literal(near.isEmpty() ? "Aucun marchand à moins de 8 blocs." : near.size() + " marchand(s) retiré(s).")
			.withStyle(ChatFormatting.GOLD));
		return near.size();
	}

	/** Replaces the trader's random wares with SkyCraft's. */
	private static void stock(ServerLevel level, MerchantOffers offers) {
		offers.clear();
		offers.add(sell(Items.EMERALD, 12, FusRoDah.create(level)));
		offers.add(sell(Items.EMERALD, 2, new ItemStack(Items.ZOMBIE_SPAWN_EGG)));
		offers.add(sell(Items.EMERALD, 2, new ItemStack(Items.SKELETON_SPAWN_EGG)));
		offers.add(sell(Items.EMERALD, 3, new ItemStack(Items.SPIDER_SPAWN_EGG)));
		offers.add(sell(Items.EMERALD, 4, new ItemStack(Items.CREEPER_SPAWN_EGG)));
		offers.add(sell(Items.RAW_IRON, 8, new ItemStack(Items.EMERALD)));
		offers.add(sell(Items.RAW_GOLD, 4, new ItemStack(Items.EMERALD)));
		offers.add(sell(Items.DIAMOND, 1, new ItemStack(Items.EMERALD, 2)));
	}

	/** {@code count} of {@code cost} for {@code result}, as often as you like. */
	private static MerchantOffer sell(ItemLike cost, int count, ItemStack result) {
		return new MerchantOffer(new ItemCost(cost, count), Optional.empty(), result, 0, Integer.MAX_VALUE, 0, 0.0F);
	}
}
