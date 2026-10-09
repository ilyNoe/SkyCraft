package dev.skycraft.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A mob's AI goals, so SkyMobs can teach hostile mobs to go after Skyrim's NPCs. */
@Mixin(Mob.class)
public interface MobAccessor {
	@Accessor("goalSelector")
	GoalSelector skycraft$goalSelector();

	@Accessor("targetSelector")
	GoalSelector skycraft$targetSelector();
}
