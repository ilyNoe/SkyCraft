#include "Game.h"

#include <SimpleIni.h>
#include <cstdlib>

// Minecraft's hostile mobs as something Skyrim's NPCs can fight.
//
// Minecraft reports the hostile mobs near the player (the mob table). Each one gets an invisible,
// AI-less stand-in actor here, kept exactly where the mob is. Skyrim's NPCs see it, attack it and
// hit it like any creature; what they take off its health is sent back to Minecraft, where it hurts
// the real mob (which then turns on whoever hit it). When a mob hits an NPC, that NPC (and any
// guard or follower close by) starts fighting the mob's stand-in.

namespace skycraft
{
	namespace
	{
		// The stand-in's body: a wolf (EncWolf). Never drawn; its size barely matters, and wolves are
		// already something guards and travellers fight. SkyCraft.ini [Mobs] sStandIn overrides it.
		constexpr RE::FormID kDefaultStandIn = 0x00023ABE;
		// Marks our stand-ins (ActorValue Variable10), so ones left in a save are removed on load.
		constexpr float kStandInMark = 7351.0f;
		constexpr float kStandInHealth = 5000.0f;
		constexpr float kReach = 60.0f * static_cast<float>(proto::kUnitsPerBlock);       // stand-ins only this close
		constexpr float kRallyRange = 22.0f * static_cast<float>(proto::kUnitsPerBlock);  // guards come this far
		constexpr float kFlushSeconds = 0.25f;                                            // damage batched (Minecraft i-frames)
		constexpr float kRallySeconds = 0.5f;

		struct Foe
		{
			RE::ActorHandle actor;
			std::uint32_t   targetFormId{ 0 };
			float           pendingDamage{ 0.0f };
			RE::FormID      lastAttacker{ 0 };
			float           flushTimer{ 0.0f };
			float           rallyTimer{ 0.0f };
			bool            seen{ false };
		};

		std::unordered_map<std::uint32_t, Foe>        foes;     // by Minecraft entity id
		std::unordered_map<RE::FormID, std::uint32_t> byActor;  // stand-in form id -> entity id
		std::vector<proto::MobRecord>                 table;
		bool                                          sweepPending = true;
		bool                                          announced = false;

		RE::TESNPC* StandInBase()
		{
			static RE::TESNPC* base = [] {
				CSimpleIniA ini;
				ini.SetUnicode();
				ini.LoadFile("Data/SKSE/Plugins/SkyCraft.ini");
				const char*      text = ini.GetValue("Mobs", "sStandIn", "");
				RE::FormID       id = kDefaultStandIn;
				if (text && *text) {
					id = static_cast<RE::FormID>(std::strtoul(text, nullptr, 16));
				}
				auto* npc = RE::TESForm::LookupByID<RE::TESNPC>(id);
				if (npc) {
					logger::info("mobs: Minecraft mobs get stand-ins made from {:08X}", id);
				} else {
					logger::warn("mobs: {:08X} isn't an actor; Skyrim's NPCs won't fight Minecraft mobs back", id);
				}
				return npc;
			}();
			return base;
		}

		bool Marked(RE::Actor* a_actor)
		{
			return a_actor->AsActorValueOwner()->GetBaseActorValue(RE::ActorValue::kVariable10) == kStandInMark;
		}

		void Remove(RE::Actor* a_actor)
		{
			if (!a_actor) {
				return;
			}
			byActor.erase(a_actor->GetFormID());
			a_actor->Disable();
			a_actor->SetDelete(true);
		}

		// Stand-ins a save kept (Skyrim saved while a mob was around) belong to no mob any more.
		void SweepLeftovers()
		{
			auto* lists = RE::ProcessLists::GetSingleton();
			auto* base = StandInBase();
			if (!lists || !base) {
				return;
			}
			int removed = 0;
			for (auto* handles : { &lists->highActorHandles, &lists->middleHighActorHandles, &lists->middleLowActorHandles, &lists->lowActorHandles }) {
				for (auto& handle : *handles) {
					auto actor = handle.get();
					if (actor && (actor->GetFormID() >> 24) == 0xFF && actor->GetActorBase() == base && Marked(actor.get()) &&
						!byActor.contains(actor->GetFormID())) {
						actor->Disable();
						actor->SetDelete(true);
						++removed;
					}
				}
			}
			if (removed) {
				logger::info("mobs: removed {} stand-ins left over in the save", removed);
			}
		}

		RE::Actor* Spawn(RE::PlayerCharacter* a_player, const proto::MobRecord& a_mob)
		{
			auto* base = StandInBase();
			if (!base) {
				return nullptr;
			}
			auto ref = a_player->PlaceObjectAtMe(base, false);
			auto* actor = ref ? ref->As<RE::Actor>() : nullptr;
			if (!actor) {
				return nullptr;
			}
			auto* av = actor->AsActorValueOwner();
			av->SetBaseActorValue(RE::ActorValue::kVariable10, kStandInMark);
			av->SetBaseActorValue(RE::ActorValue::kHealth, kStandInHealth);
			av->SetBaseActorValue(RE::ActorValue::kAggression, 0.0f);
			actor->EnableAI(false);
			actor->SetPosition(McToSky(a_mob.x, a_mob.y, a_mob.z), true);
			byActor[actor->GetFormID()] = a_mob.entityId;
			if (!announced) {
				announced = true;
				logger::info("mobs: first Minecraft mob near the player; Skyrim's NPCs can fight it now");
			}
			return actor;
		}

		// Never drawn, never thinking, never dying here: Minecraft's mob is the real thing.
		void Keep(RE::Actor* a_actor, const proto::MobRecord& a_mob)
		{
			if (auto* root = a_actor->Get3D(); root && !root->GetAppCulled()) {
				// Its 3D just loaded (or reloaded): hide it, and make sure it still doesn't think.
				root->SetAppCulled(true);
				a_actor->EnableAI(false);
			}
			a_actor->SetPosition(McToSky(a_mob.x, a_mob.y, a_mob.z), true);
		}

		// What Skyrim's NPCs took off the stand-in, sent to Minecraft as damage on the mob.
		void Bleed(Foe& a_foe, RE::Actor* a_actor, std::uint32_t a_entityId, float a_delta)
		{
			auto*       av = a_actor->AsActorValueOwner();
			const float max = a_actor->GetActorValueMax(RE::ActorValue::kHealth);
			const float cur = av->GetActorValue(RE::ActorValue::kHealth);
			if (max - cur > 0.01f) {
				av->RestoreActorValue(RE::ActorValue::kHealth, max - cur);
				a_foe.pendingDamage += max - cur;
			}
			a_foe.flushTimer += a_delta;
			if (a_foe.flushTimer < kFlushSeconds) {
				return;
			}
			a_foe.flushTimer = 0.0f;
			if (a_foe.pendingDamage > 0.01f) {
				Link::Get().PushInput(proto::kInMobHurt, 0, static_cast<std::int32_t>(a_foe.pendingDamage * 100.0f), static_cast<std::int32_t>(a_foe.lastAttacker),
					static_cast<std::int32_t>(a_entityId));
				logger::info("mobs: Skyrim hit Minecraft mob {} for {:.1f} (by {:08X})", a_entityId, a_foe.pendingDamage, a_foe.lastAttacker);
				a_foe.pendingDamage = 0.0f;
			}
		}

		void Fight(RE::Actor* a_who, RE::Actor* a_standIn)
		{
			if (!a_who || a_who->IsDead() || a_who->IsPlayerRef() || byActor.contains(a_who->GetFormID())) {
				return;
			}
			if (a_who->IsInCombat()) {
				return;  // already busy (maybe with the player, maybe with this mob)
			}
			a_who->StartCombat(a_standIn);
		}

		// The NPC the mob is after fights back, and guards and followers nearby join in.
		void Rally(RE::PlayerCharacter* a_player, Foe& a_foe, RE::Actor* a_standIn, float a_delta)
		{
			a_foe.rallyTimer -= a_delta;
			if (a_foe.rallyTimer > 0.0f) {
				return;
			}
			a_foe.rallyTimer = kRallySeconds;
			if (a_foe.targetFormId) {
				Fight(RE::TESForm::LookupByID<RE::Actor>(a_foe.targetFormId), a_standIn);
			}
			auto* lists = RE::ProcessLists::GetSingleton();
			if (!lists) {
				return;
			}
			const auto where = a_standIn->GetPosition();
			for (auto& handle : lists->highActorHandles) {
				auto actor = handle.get();
				if (!actor || actor.get() == a_player || !actor->Is3DLoaded() || actor->GetPosition().GetDistance(where) > kRallyRange) {
					continue;
				}
				if (actor->IsGuard() || actor->IsPlayerTeammate()) {
					Fight(actor.get(), a_standIn);
				}
			}
		}

		class HitSink final : public RE::BSTEventSink<RE::TESHitEvent>
		{
		public:
			static HitSink* Get()
			{
				static HitSink sink;
				return &sink;
			}

			RE::BSEventNotifyControl ProcessEvent(const RE::TESHitEvent* a_event, RE::BSTEventSource<RE::TESHitEvent>*) override
			{
				if (!a_event || !a_event->target || !a_event->cause) {
					return RE::BSEventNotifyControl::kContinue;
				}
				auto it = byActor.find(a_event->target->GetFormID());
				if (it == byActor.end()) {
					return RE::BSEventNotifyControl::kContinue;
				}
				if (auto foe = foes.find(it->second); foe != foes.end()) {
					foe->second.lastAttacker = a_event->cause->GetFormID();
				}
				return RE::BSEventNotifyControl::kContinue;
			}
		};
	}

	namespace MobFoes
	{
		void Install()
		{
			if (auto* events = RE::ScriptEventSourceHolder::GetSingleton()) {
				events->AddEventSink<RE::TESHitEvent>(HitSink::Get());
			}
		}

		void OnGameLoaded()
		{
			// The save's actors are new objects: forget ours (any it kept are swept up next frame).
			foes.clear();
			byActor.clear();
			sweepPending = true;
		}

		bool IsStandIn(const RE::Actor* a_actor)
		{
			return a_actor && byActor.contains(a_actor->GetFormID());
		}

		RE::Actor* StandInFor(std::uint32_t a_entityId)
		{
			auto it = foes.find(a_entityId);
			if (it == foes.end()) {
				return nullptr;
			}
			auto actor = it->second.actor.get();
			return actor && !actor->IsDisabled() ? actor.get() : nullptr;
		}

		void PerFrame(RE::PlayerCharacter* a_player, bool a_puppeting, float a_delta)
		{
			if (sweepPending && a_player->Is3DLoaded()) {
				sweepPending = false;
				SweepLeftovers();
			}
			if (!a_puppeting || !Link::Get().ReadMobs(table)) {
				if (!a_puppeting) {
					for (auto& [id, foe] : foes) {
						Remove(foe.actor.get().get());
					}
					foes.clear();
				}
				return;
			}
			for (auto& [id, foe] : foes) {
				foe.seen = false;
			}
			const auto playerPos = a_player->GetPosition();
			for (const auto& mob : table) {
				if (McToSky(mob.x, mob.y, mob.z).GetDistance(playerPos) > kReach) {
					continue;
				}
				auto& foe = foes[mob.entityId];
				foe.seen = true;
				foe.targetFormId = mob.targetFormId;
				auto actor = foe.actor.get();
				if (!actor || actor->IsDisabled() || actor->IsDead()) {
					if (actor) {
						Remove(actor.get());
					}
					auto* spawned = Spawn(a_player, mob);
					if (!spawned) {
						foes.erase(mob.entityId);
						continue;
					}
					foe.actor = spawned->GetHandle();
					continue;  // its 3D loads over the next frames
				}
				Keep(actor.get(), mob);
				Bleed(foe, actor.get(), mob.entityId, a_delta);
				Rally(a_player, foe, actor.get(), a_delta);
			}
			// Mobs that died, despawned or wandered off.
			for (auto it = foes.begin(); it != foes.end();) {
				if (it->second.seen) {
					++it;
					continue;
				}
				Remove(it->second.actor.get().get());
				it = foes.erase(it);
			}
		}
	}
}
