package dev.skycraft.combat;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.SkyLink;
import dev.skycraft.mixin.MobAccessor;
import dev.skycraft.net.SkyNet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ProjectileWeaponItem;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft's hostile mobs and Skyrim's NPCs fight each other.
 *
 * <p>Zombies, skeletons, spiders, creepers and the like go for the nearest Skyrim NPC (their
 * invisible {@link SkyrimActorEntity} stand-ins) as readily as for a player. Their hits reach the
 * real NPC like a player's do, flagged as a mob's. The other way round, Skyrim is told where every
 * hostile mob near the player is (the mob table); it gives each one an invisible actor of its own
 * that its NPCs and guards see and fight, and their hits on it come back here as damage on the mob.
 */
public final class SkyMobs {
	/** Mobs this far from the host player (blocks) are reported to Skyrim. */
	private static final double REPORT_RANGE = 56.0;
	/** A mob notices NPCs this far away (blocks). */
	private static final double NOTICE_RANGE = 16.0;
	/** Gives up on an NPC this far away (blocks). */
	private static final double GIVE_UP_RANGE = 28.0;

	private static final List<SkyLink.Mob> TABLE = new ArrayList<>();
	private static int tick;
	private static boolean wroteEmpty = true;

	private SkyMobs() {
	}

	public static void init() {
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof PathfinderMob mob && huntsNpcs(mob)) {
				teach(mob);
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(SkyMobs::serverTick);
	}

	/** Hostile, and not something that only fights back (endermen, zombified piglins, ...). */
	private static boolean huntsNpcs(Mob mob) {
		return mob instanceof Enemy && !(mob instanceof NeutralMob);
	}

	private static void teach(PathfinderMob mob) {
		MobAccessor ai = (MobAccessor) mob;
		GoalSelector goals = ai.skycraft$goalSelector();
		GoalSelector targets = ai.skycraft$targetSelector();
		// Chunks load and unload: teach each mob once.
		if (targets.getAvailableGoals().stream().anyMatch(g -> g.getGoal() instanceof TargetNpcGoal)) {
			return;
		}
		// Same priority as their own "nearest player" goal: whichever is nearer gets picked.
		targets.addGoal(2, new TargetNpcGoal(mob));
		goals.addGoal(1, new ChaseNpcGoal(mob));
	}

	// ---- the mob table: where Skyrim should put stand-ins ------------------------------------

	private static void serverTick(MinecraftServer server) {
		if ((++tick & 1) != 0) {
			return;
		}
		if (!SkyLink.active()) {
			TABLE.clear();
			wroteEmpty = true;
			return;
		}
		ServerPlayer host = null;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (SkyNet.isHost(player)) {
				host = player;
				break;
			}
		}
		TABLE.clear();
		if (host != null && host.isAlive()) {
			ServerLevel level = host.level();
			List<Mob> near = level.getEntitiesOfClass(Mob.class, host.getBoundingBox().inflate(REPORT_RANGE),
				m -> m.isAlive() && m instanceof Enemy);
			near.sort(Comparator.comparingDouble(m -> m.distanceToSqr(host)));
			for (Mob m : near) {
				int target = m.getTarget() instanceof SkyrimActorEntity proxy ? proxy.formId() : 0;
				float health = m.getMaxHealth() > 0.0F ? Math.clamp(m.getHealth() / m.getMaxHealth(), 0.0F, 1.0F) : 0.0F;
				TABLE.add(new SkyLink.Mob(m.getId(), target, (float) m.getX(), (float) m.getY(), (float) m.getZ(), m.getBbWidth(), m.getBbHeight(), health));
				if (TABLE.size() >= dev.skycraft.link.Proto.MAX_MOBS) {
					break;
				}
			}
		}
		if (TABLE.isEmpty() && wroteEmpty) {
			return;
		}
		SkyLink.writeMobs(TABLE);
		wroteEmpty = TABLE.isEmpty();
	}

	/**
	 * Server thread: a Skyrim actor hit mob {@code entityId}'s stand-in in the host's Skyrim for
	 * {@code skyrimDamage}. It lands as that actor's attack, so the mob turns on whoever hit it.
	 */
	public static void hurtByNpc(MinecraftServer server, int entityId, float skyrimDamage, int attackerFormId) {
		if (skyrimDamage <= 0.0F) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!SkyNet.isHost(player)) {
				continue;
			}
			ServerLevel level = player.level();
			Entity entity = level.getEntity(entityId);
			if (!(entity instanceof LivingEntity victim) || entity instanceof Player || !victim.isAlive()) {
				return;
			}
			SkyrimActorEntity attacker = SkyCombat.proxy(attackerFormId);
			DamageSource source = attacker != null ? level.damageSources().mobAttack(attacker) : level.damageSources().generic();
			float damage = skyrimDamage / SkyCombat.SKYRIM_TO_MC_DAMAGE;
			victim.hurtServer(level, source, damage);
			SkyCraft.LOG.info("SkyCraft: {} hit {} for {} Skyrim damage ({} Minecraft): health {}", attacker != null ? attacker.getName().getString() : "a Skyrim actor",
				victim.getName().getString(), skyrimDamage, damage, victim.getHealth());
			return;
		}
	}

	// ---- AI ------------------------------------------------------------------------------------

	private static @Nullable SkyrimActorEntity nearestNpc(Mob mob, double range) {
		SkyrimActorEntity best = null;
		double bestDist = range * range;
		for (SkyrimActorEntity npc : mob.level().getEntitiesOfClass(SkyrimActorEntity.class, mob.getBoundingBox().inflate(range, 8.0, range), e -> !e.isRemoved())) {
			double d = mob.distanceToSqr(npc);
			if (d < bestDist) {
				bestDist = d;
				best = npc;
			}
		}
		return best;
	}

	private static double nearestPlayerDistSqr(Mob mob, double range) {
		double best = Double.MAX_VALUE;
		for (Player player : mob.level().players()) {
			if (player.isAlive() && !player.isSpectator() && !player.isCreative()) {
				best = Math.min(best, mob.distanceToSqr(player));
			}
		}
		return best;
	}

	/** Picks the nearest Skyrim NPC as the target when no player is nearer. */
	static final class TargetNpcGoal extends Goal {
		private final Mob mob;
		private @Nullable SkyrimActorEntity candidate;
		private int cooldown;

		TargetNpcGoal(Mob mob) {
			this.mob = mob;
			this.setFlags(EnumSet.of(Goal.Flag.TARGET));
		}

		@Override
		public boolean canUse() {
			if (--this.cooldown > 0) {
				return false;
			}
			this.cooldown = 10 + this.mob.getRandom().nextInt(10);
			LivingEntity current = this.mob.getTarget();
			if (current != null && current.isAlive()) {
				return false;
			}
			this.candidate = nearestNpc(this.mob, NOTICE_RANGE);
			return this.candidate != null && this.mob.distanceToSqr(this.candidate) < nearestPlayerDistSqr(this.mob, NOTICE_RANGE);
		}

		@Override
		public void start() {
			this.mob.setTarget(this.candidate);
		}

		@Override
		public boolean canContinueToUse() {
			return this.mob.getTarget() instanceof SkyrimActorEntity npc && !npc.isRemoved() && this.mob.distanceToSqr(npc) < GIVE_UP_RANGE * GIVE_UP_RANGE;
		}

		@Override
		public void stop() {
			if (this.mob.getTarget() instanceof SkyrimActorEntity) {
				this.mob.setTarget(null);
			}
			this.candidate = null;
		}
	}

	/**
	 * Goes for a Skyrim NPC and hits it. Skyrim's ground isn't made of blocks, so Minecraft's path
	 * finding may find no path over it: the mob then walks straight at the NPC (Skyrim's collision
	 * still applies to it). Mobs with a bow or crossbow keep their own shooting AI; creepers stop
	 * close by and let their own fuse do the rest.
	 */
	static final class ChaseNpcGoal extends Goal {
		private final Mob mob;
		private int attackCooldown;
		private int repath;
		private boolean direct;

		ChaseNpcGoal(Mob mob) {
			this.mob = mob;
			this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
		}

		private @Nullable SkyrimActorEntity npc() {
			return this.mob.getTarget() instanceof SkyrimActorEntity npc && !npc.isRemoved() ? npc : null;
		}

		private boolean creeper() {
			return this.mob.getType() == EntityType.CREEPER;
		}

		private boolean ranged() {
			return this.mob.getMainHandItem().getItem() instanceof ProjectileWeaponItem || this.mob.getType() == EntityType.WITCH;
		}

		private boolean touching(SkyrimActorEntity npc) {
			return this.mob.getBoundingBox().inflate(0.9, 0.0, 0.9).intersects(npc.getBoundingBox());
		}

		@Override
		public boolean canUse() {
			SkyrimActorEntity npc = this.npc();
			if (npc == null || this.ranged()) {
				return false;
			}
			return !this.creeper() || this.mob.distanceToSqr(npc) > 2.5 * 2.5;
		}

		@Override
		public boolean canContinueToUse() {
			return this.canUse();
		}

		@Override
		public boolean requiresUpdateEveryTick() {
			return true;
		}

		@Override
		public void start() {
			this.repath = 0;
			this.direct = false;
		}

		@Override
		public void stop() {
			this.mob.getNavigation().stop();
		}

		@Override
		public void tick() {
			SkyrimActorEntity npc = this.npc();
			if (npc == null) {
				return;
			}
			this.mob.getLookControl().setLookAt(npc, 30.0F, 30.0F);
			if (this.attackCooldown > 0) {
				this.attackCooldown--;
			}
			if (this.touching(npc)) {
				this.mob.getNavigation().stop();
				if (this.attackCooldown <= 0 && !this.creeper() && this.mob.level() instanceof ServerLevel level) {
					this.mob.swing(InteractionHand.MAIN_HAND);
					this.mob.doHurtTarget(level, npc);
					this.attackCooldown = 20;
				}
				return;
			}
			if (--this.repath <= 0) {
				this.repath = 10;
				this.direct = !this.mob.getNavigation().moveTo(npc, 1.0);
			}
			if (this.direct || this.mob.getNavigation().isDone()) {
				this.mob.getMoveControl().setWantedPosition(npc.getX(), npc.getY(), npc.getZ(), 1.0);
			}
		}
	}
}
