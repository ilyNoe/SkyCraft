package dev.skycraft.world;

import dev.skycraft.SkyCraft;
import it.unimi.dsi.fastutil.ints.IntList;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Daedric quest rewards. Skyrim's Daedric artifacts can't be used while Minecraft drives the player,
 * so when a player's Skyrim completes one of the fifteen Daedric quests, a chest appears next to
 * them with a Minecraft counterpart of the artifact: two of it, one for each of two players. Each
 * quest pays out once per world, whoever completes it first (the list is kept in the world's folder).
 */
public final class SkyRewards {
	/** In the order of kDaedric in the Skyrim plugin's Quests.cpp. */
	private static final String[] PRINCES = {
		"Azura", "Boethiah", "Clavicus Vile", "Hermaeus Mora", "Hircine", "Malacath", "Mehrunes Dagon", "Mephala", "Meridia", "Molag Bal", "Namira",
		"Peryite", "Sanguine", "Sheogorath", "Vaermina"
	};
	private static final String GIVEN_FILE = "skycraft_daedric_rewards.txt";

	private SkyRewards() {
	}

	/** Server thread: {@code player}'s Skyrim has just completed Daedric quest number {@code quest}. */
	public static void questDone(ServerPlayer player, int quest) {
		if (quest < 0 || quest >= PRINCES.length) {
			return;
		}
		ServerLevel level = player.level();
		MinecraftServer server = level.getServer();
		Set<Integer> given = load(server);
		if (given.contains(quest)) {
			SkyCraft.LOG.info("SkyCraft: {} completed {}'s quest; its reward was already given in this world", player.getName().getString(), PRINCES[quest]);
			return;
		}
		List<ItemStack> items;
		try {
			items = reward(level, quest);
		} catch (RuntimeException e) {
			SkyCraft.LOG.error("SkyCraft: couldn't make the reward for {}'s quest", PRINCES[quest], e);
			return;
		}
		BlockPos pos = chestSpot(level, player);
		level.setBlockAndUpdate(pos, Blocks.CHEST.defaultBlockState());
		if (level.getBlockEntity(pos) instanceof ChestBlockEntity chest) {
			// Two of everything, side by side in the middle row.
			int slot = 9 + Math.max(0, (9 - items.size() * 2) / 2);
			for (ItemStack stack : items) {
				chest.setItem(Math.min(slot++, 26), stack.copy());
				chest.setItem(Math.min(slot++, 26), stack.copy());
			}
			chest.setChanged();
		} else {
			// No chest after all: hand the items over instead.
			for (ItemStack stack : items) {
				for (int i = 0; i < 2; i++) {
					ItemStack copy = stack.copy();
					if (!player.getInventory().add(copy)) {
						player.drop(copy, false);
					}
				}
			}
		}
		given.add(quest);
		save(server, given);
		String what = items.isEmpty() ? "" : items.getFirst().getHoverName().getString();
		server.getPlayerList().broadcastSystemMessage(
			Component.literal(PRINCES[quest] + " : un coffre est apparu près de " + player.getName().getString() + " (" + what + " x2)").withStyle(ChatFormatting.LIGHT_PURPLE),
			false
		);
		SkyCraft.LOG.info("SkyCraft: {} completed {}'s quest; reward chest at {}", player.getName().getString(), PRINCES[quest], pos);
	}

	/** In front of the player if there's room, else beside or behind them, else where they stand. */
	private static BlockPos chestSpot(ServerLevel level, ServerPlayer player) {
		BlockPos feet = player.blockPosition();
		Direction facing = player.getDirection();
		for (Direction side : new Direction[] { facing, facing.getClockWise(), facing.getCounterClockWise(), facing.getOpposite() }) {
			BlockPos pos = feet.relative(side);
			if (level.getBlockState(pos).isAir()) {
				return pos;
			}
		}
		return feet.above(2);
	}

	// ---- the fifteen rewards ----------------------------------------------------------------

	private static List<ItemStack> reward(ServerLevel level, int quest) {
		List<ItemStack> out = new ArrayList<>();
		switch (quest) {
			case 0 -> out.add(named(new ItemStack(Items.TOTEM_OF_UNDYING), "Étoile d'Azura"));
			case 1 -> out.add(gear(level, Items.NETHERITE_CHESTPLATE, "Cotte d'ébonite", Enchantments.PROTECTION, 4, Enchantments.THORNS, 3));
			case 2 -> out.add(gear(level, Items.NETHERITE_HELMET, "Masque de Clavicus Vile", Enchantments.PROTECTION, 4, Enchantments.RESPIRATION, 3));
			case 3 -> {
				ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
				enchant(level, book, Enchantments.MENDING, 1);
				out.add(named(book, "Oghma Infinium"));
				out.add(new ItemStack(Items.EXPERIENCE_BOTTLE, 64));
			}
			case 4 -> {
				out.add(gear(level, Items.BOW, "Arc d'Hircine", Enchantments.POWER, 5, Enchantments.INFINITY, 1));
				out.add(new ItemStack(Items.ARROW, 1));
			}
			case 5 -> out.add(gear(level, Items.NETHERITE_AXE, "Volendrung", Enchantments.SHARPNESS, 5, Enchantments.EFFICIENCY, 5));
			case 6 -> out.add(gear(level, Items.NETHERITE_SWORD, "Rasoir de Mehrunes", Enchantments.SHARPNESS, 5, Enchantments.FIRE_ASPECT, 2));
			case 7 -> out.add(gear(level, Items.NETHERITE_SWORD, "Lame d'ébonite", Enchantments.SWEEPING_EDGE, 3, Enchantments.LOOTING, 3));
			case 8 -> out.add(gear(level, Items.NETHERITE_SPEAR, "Aubéclat", Enchantments.SMITE, 5, Enchantments.FIRE_ASPECT, 2));
			case 9 -> out.add(gear(level, Items.MACE, "Masse de Molag Bal", Enchantments.DENSITY, 5, Enchantments.FIRE_ASPECT, 2));
			case 10 -> out.add(named(new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 8), "Festin de Namira"));
			case 11 -> out.add(gear(level, Items.SHIELD, "Brise-sorts", Enchantments.MENDING, 1));
			case 12 -> out.add(gear(level, Items.TRIDENT, "Rose de Sanguine", Enchantments.LOYALTY, 3, Enchantments.IMPALING, 5));
			case 13 -> {
				out.add(gear(level, Items.CROSSBOW, "Wabbajack", Enchantments.MULTISHOT, 1, Enchantments.QUICK_CHARGE, 3));
				ItemStack rockets = new ItemStack(Items.FIREWORK_ROCKET, 32);
				rockets.set(DataComponents.FIREWORKS, new Fireworks(1, List.of(
					new FireworkExplosion(FireworkExplosion.Shape.LARGE_BALL, IntList.of(0xB03FE0, 0xFFD23F), IntList.of(0x3FE0C8), true, true),
					new FireworkExplosion(FireworkExplosion.Shape.BURST, IntList.of(0xFF4040), IntList.of(), false, true)
				)));
				out.add(rockets);
			}
			case 14 -> {
				out.add(gear(level, Items.ELYTRA, "Ailes de Vaermina"));
				out.add(new ItemStack(Items.FIREWORK_ROCKET, 32));
			}
			default -> {
			}
		}
		return out;
	}

	/** A named piece of gear with its enchantments (key, level pairs) and Unbreaking III. */
	private static ItemStack gear(ServerLevel level, Item item, String name, Object... enchantments) {
		ItemStack stack = new ItemStack(item);
		for (int i = 0; i + 1 < enchantments.length; i += 2) {
			@SuppressWarnings("unchecked")
			ResourceKey<Enchantment> key = (ResourceKey<Enchantment>) enchantments[i];
			enchant(level, stack, key, (Integer) enchantments[i + 1]);
		}
		enchant(level, stack, Enchantments.UNBREAKING, 3);
		return named(stack, name);
	}

	private static void enchant(ServerLevel level, ItemStack stack, ResourceKey<Enchantment> key, int enchantmentLevel) {
		stack.enchant(level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key), enchantmentLevel);
	}

	private static ItemStack named(ItemStack stack, String name) {
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(style -> style.withItalic(false).withColor(ChatFormatting.LIGHT_PURPLE)));
		return stack;
	}

	// ---- which quests have paid out (one line each in the world's folder) ----------------------

	private static Path file(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve(GIVEN_FILE);
	}

	private static Set<Integer> load(MinecraftServer server) {
		Set<Integer> given = new TreeSet<>();
		try {
			Path file = file(server);
			if (Files.exists(file)) {
				for (String line : Files.readAllLines(file)) {
					if (!line.isBlank()) {
						given.add(Integer.parseInt(line.trim()));
					}
				}
			}
		} catch (IOException | NumberFormatException e) {
			SkyCraft.LOG.warn("SkyCraft: couldn't read {}", GIVEN_FILE, e);
		}
		return given;
	}

	private static void save(MinecraftServer server, Set<Integer> given) {
		try {
			Files.write(file(server), given.stream().map(String::valueOf).toList());
		} catch (IOException e) {
			SkyCraft.LOG.warn("SkyCraft: couldn't write {}", GIVEN_FILE, e);
		}
	}
}
