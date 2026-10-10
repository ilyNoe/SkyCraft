#include "Game.h"

namespace skycraft
{
	namespace
	{
		// Skyrim's fifteen Daedric quests (base game), in the order Minecraft knows them by
		// (SkyRewards.java). When one is completed, Minecraft leaves a chest with its reward.
		constexpr const char* kDaedric[] = {
			"DA01",  // Azura: The Black Star
			"DA02",  // Boethiah: Boethiah's Calling
			"DA03",  // Clavicus Vile: A Daedra's Best Friend
			"DA04",  // Hermaeus Mora: Discerning the Transmundane
			"DA05",  // Hircine: Ill Met by Moonlight
			"DA06",  // Malacath: The Cursed Tribe
			"DA07",  // Mehrunes Dagon: Pieces of the Past
			"DA08",  // Mephala: The Whispering Door
			"DA09",  // Meridia: The Break of Dawn
			"DA10",  // Molag Bal: The House of Horrors
			"DA11",  // Namira: The Taste of Death
			"DA13",  // Peryite: The Only Cure
			"DA14",  // Sanguine: A Night to Remember
			"DA15",  // Sheogorath: The Mind of Madness
			"DA16",  // Vaermina: Waking Nightmare
		};
		constexpr std::size_t kCount = std::size(kDaedric);

		RE::TESQuest* quests[kCount]{};
		bool          resolved = false;
		// -1 not looked at yet (after loading a save: whatever is done already is not news), 0 open, 1 completed.
		std::int8_t done[kCount]{ -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 };
		float       quiet = 1.0f;  // seconds to wait after a loading screen before looking again

		// ---- emeralds for every quest -------------------------------------------------------------
		// Any quest in the journal (one with a name and a type) pays Minecraft emeralds when it's
		// completed, more for the bigger storylines. Radiant quests pay each time they come round.
		std::unordered_map<RE::FormID, std::int8_t> questDone;  // as done[] above, for every quest
		std::vector<RE::TESQuest*>                  journalQuests;
		bool                                        journalResolved = false;
		float                                       scanTimer = 0.0f;

		int EmeraldsFor(const RE::TESQuest* a_quest)
		{
			using Type = RE::QUEST_DATA::Type;
			switch (a_quest->GetType()) {
			case Type::kMainQuest:
			case Type::kDLC01_Vampire:
			case Type::kDLC02_Dragonborn:
				return 5;
			case Type::kMagesGuild:
			case Type::kThievesGuild:
			case Type::kDarkBrotherhood:
			case Type::kCompanionsQuest:
			case Type::kDaedric:
			case Type::kCivilWar:
				return 3;
			case Type::kSideQuest:
				return 2;
			case Type::kMiscellaneous:
				return 1;
			default:
				return 0;
			}
		}

		void ResolveJournal()
		{
			journalResolved = true;
			auto* data = RE::TESDataHandler::GetSingleton();
			if (!data) {
				return;
			}
			for (auto* quest : data->GetFormArray<RE::TESQuest>()) {
				const char* name = quest ? quest->GetName() : nullptr;
				if (quest && name && *name && EmeraldsFor(quest) > 0) {
					journalQuests.push_back(quest);
				}
			}
			logger::info("quest emeralds: watching {} journal quests", journalQuests.size());
		}

		void ScanJournal()
		{
			if (!journalResolved) {
				ResolveJournal();
			}
			for (auto* quest : journalQuests) {
				const bool now = quest->IsCompleted();
				auto [it, fresh] = questDone.try_emplace(quest->GetFormID(), static_cast<std::int8_t>(now ? 1 : 0));
				if (fresh) {
					continue;  // first look since loading: whatever is done already isn't news
				}
				if (it->second == 0 && now) {
					if (!State().mcInWorld) {
						continue;  // Minecraft isn't there to hear it: tell it when it is
					}
					const int emeralds = EmeraldsFor(quest);
					logger::info("quest emeralds: {} ({:08X}) completed; {} emeralds", quest->GetName(), quest->GetFormID(), emeralds);
					Link::Get().PushInput(proto::kInQuestEmeralds, static_cast<std::uint16_t>(emeralds), static_cast<std::int32_t>(quest->GetFormID()));
				}
				it->second = now ? 1 : 0;
			}
		}

		void Resolve()
		{
			resolved = true;
			std::size_t found = 0;
			for (std::size_t i = 0; i < kCount; ++i) {
				quests[i] = RE::TESForm::LookupByEditorID<RE::TESQuest>(kDaedric[i]);
				if (quests[i]) {
					++found;
				} else {
					logger::warn("daedric rewards: quest {} not found", kDaedric[i]);
				}
			}
			logger::info("daedric rewards: watching {} of {} quests", found, kCount);
		}
	}

	namespace Quests
	{
		void OnGameLoaded()
		{
			for (auto& d : done) {
				d = -1;
			}
			questDone.clear();
			quiet = 1.0f;
		}

		void PerFrame(bool a_loading, float a_delta)
		{
			if (a_loading) {
				quiet = 1.0f;  // a save may be loading: its quests aren't news (OnGameLoaded follows)
				return;
			}
			if (quiet > 0.0f) {
				quiet -= a_delta;
				return;
			}
			if (!resolved) {
				Resolve();
			}
			for (std::size_t i = 0; i < kCount; ++i) {
				if (!quests[i]) {
					continue;
				}
				const bool now = quests[i]->IsCompleted();
				if (done[i] == 0 && now) {
					if (!State().mcInWorld) {
						continue;  // Minecraft isn't there to hear it: tell it when it is
					}
					logger::info("daedric rewards: {} completed; telling Minecraft", kDaedric[i]);
					Link::Get().PushInput(proto::kInQuestDone, static_cast<std::uint16_t>(i));
				}
				done[i] = now ? 1 : 0;
			}
			scanTimer -= a_delta;
			if (scanTimer <= 0.0f) {
				scanTimer = 0.5f;
				ScanJournal();
			}
		}
	}
}
