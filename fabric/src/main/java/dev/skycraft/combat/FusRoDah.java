package dev.skycraft.combat;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import dev.skycraft.net.SkyNet;
import java.util.List;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.Vec3;

/**
 * The Fus Ro Dah sword: a netherite sword that shouts. Right click and Skyrim's player shouts
 * Unrelenting Force (all three words) the way it would in Skyrim: its shockwave, sound and push
 * throw the NPCs in front into the air, with everything that comes with hitting them (crime,
 * aggro). Minecraft's own mobs and players in the cone are blown back too. Five seconds between
 * shouts. Sold by the merchant (SkyMerchant).
 */
public final class FusRoDah {
	private static final String TAG = "skycraft_fus_ro_dah";
	private static final int COOLDOWN_TICKS = 100;
	private static final double RANGE = 12.0;
	private static final double CONE_COS = Math.cos(Math.toRadians(40.0));

	private FusRoDah() {
	}

	public static void init() {
		UseItemCallback.EVENT.register((player, level, hand) -> {
			ItemStack stack = player.getItemInHand(hand);
			if (!is(stack)) {
				return InteractionResult.PASS;
			}
			if (player.getCooldowns().isOnCooldown(stack)) {
				return InteractionResult.FAIL;
			}
			if (player instanceof ServerPlayer serverPlayer) {
				shout(serverPlayer, stack);
			}
			return InteractionResult.SUCCESS;
		});
	}

	/** A new Fus Ro Dah sword. */
	public static ItemStack create(ServerLevel level) {
		ItemStack stack = new ItemStack(Items.NETHERITE_SWORD);
		var enchantments = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
		stack.enchant(enchantments.getOrThrow(Enchantments.SHARPNESS), 3);
		stack.enchant(enchantments.getOrThrow(Enchantments.UNBREAKING), 3);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal("Fus Ro Dah").withStyle(style -> style.withItalic(false).withColor(ChatFormatting.GOLD)));
		stack.set(DataComponents.LORE, new ItemLore(List.of(
			Component.literal("Clic droit : Force implacable").withStyle(style -> style.withItalic(false).withColor(ChatFormatting.YELLOW)),
			Component.literal("Projette les PNJ et les mobs devant toi").withStyle(ChatFormatting.GRAY)
		)));
		CompoundTag tag = new CompoundTag();
		tag.putBoolean(TAG, true);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		return stack;
	}

	public static boolean is(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data != null && data.copyTag().contains(TAG);
	}

	private static void shout(ServerPlayer player, ItemStack stack) {
		ServerLevel level = player.level();
		player.getCooldowns().addCooldown(stack, COOLDOWN_TICKS);

		// Skyrim: the player's own Skyrim shouts (host through shared memory, a guest through the server).
		if (SkyNet.isHost(player)) {
			SkyLink.pushEvent(Proto.EV_SHOUT, 0, 3.0F, 0.0F, 0.0F, 0.0F, 0);
		} else if (ServerPlayNetworking.canSend(player, SkyNet.Shout.TYPE)) {
			ServerPlayNetworking.send(player, new SkyNet.Shout(3));
		}

		// Minecraft: mobs and other players in front are blown away. Skyrim's NPCs are left to
		// Skyrim's shout (their stand-ins don't move by themselves).
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getLookAngle();
		int blown = 0;
		for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(RANGE), e -> e != player && e.isAlive())) {
			if (target instanceof SkyrimActorEntity) {
				continue;
			}
			Vec3 to = target.getBoundingBox().getCenter().subtract(eye);
			double dist = to.length();
			if (dist > RANGE || dist < 1.0E-3 || to.scale(1.0 / dist).dot(look) < CONE_COS) {
				continue;
			}
			double strength = 3.0 * (1.0 - dist / (RANGE * 1.25));
			var source = level.damageSources().playerAttack(player);
			target.knockback(strength, -look.x, -look.z, source, 2.0F);
			target.push(0.0, 0.35 + 0.25 * strength / 3.0, 0.0);
			// Hurting it last also sends the new motion to clients (players included).
			target.hurtServer(level, source, 2.0F);
			blown++;
		}
		SkyCraft.LOG.info("SkyCraft: {} shouted Fus Ro Dah ({} Minecraft mobs/players blown away)", player.getName().getString(), blown);
	}
}
