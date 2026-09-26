package org.futo.inputmethod.latin.uix

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A small built-in word list that gets seeded into the user dictionary on first run, so that
 * common profanity and over-the-counter medicine names are recognized as real words instead of
 * being miscorrected.
 */
object BuiltinWordList {
    val SWEAR_WORDS = listOf(
        "fuck", "fucking", "fucked", "fucker", "motherfucker",
        "shit", "shitty", "bullshit", "crap", "crappy",
        "ass", "arse", "asshole", "bastard", "bitch",
        "bitches", "bitchy", "damn", "damned", "dammit",
        "goddamn", "hell", "piss", "pissed", "dick",
        "dickhead", "cock", "cunt", "pussy", "twat",
        "wanker", "bollocks", "bloody", "bugger", "sod",
        "prick", "tosser", "douche", "douchebag", "jackass",
        "dumbass", "screw", "slut", "whore", "horny",
        "boobs", "tits", "balls", "freaking", "wtf"
    )

    val OTC_MEDICINES = listOf(
        "acetaminophen", "ibuprofen", "naproxen", "aspirin", "diphenhydramine",
        "loratadine", "cetirizine", "levocetirizine", "fexofenadine", "chlorpheniramine",
        "brompheniramine", "doxylamine", "dextromethorphan", "guaifenesin", "phenylephrine",
        "pseudoephedrine", "oxymetazoline", "xylometazoline", "benzocaine", "lidocaine",
        "pramoxine", "hydrocortisone", "triamcinolone", "calamine", "clotrimazole",
        "miconazole", "terbinafine", "tolnaftate", "butenafine", "ketoconazole",
        "selenium", "zinc", "petrolatum", "dimethicone", "allantoin",
        "trolamine", "menthol", "camphor", "eucalyptus", "capsaicin",
        "salicylate", "urea", "salicylic", "benzoyl", "adapalene",
        "resorcinol", "sulfur", "loperamide", "bismuth", "famotidine",
        "cimetidine", "omeprazole", "esomeprazole", "lansoprazole", "pantoprazole",
        "calcium", "magnesium", "aluminum", "simethicone", "senna",
        "docusate", "bisacodyl", "glycerin", "lactulose", "psyllium",
        "meclizine", "dimenhydrinate", "scopolamine", "dextrose", "glucose",
        "phenazopyridine", "methenamine", "hyoscyamine", "chlorhexidine", "povidone",
        "iodine", "isopropyl", "hydrogen", "benzalkonium", "benzethonium",
        "permethrin", "pyrethrin", "ivermectin", "hydroquinone", "nystatin",
        "bacitracin", "neomycin", "polymyxin", "mupirocin", "cetylpyridinium",
        "thymol", "eucalyptol", "chloroxylenol", "phenol", "titanium",
        "colloidal", "oatmeal", "saline", "antacid", "antihistamine"
    )

    val ALL: List<String> = (SWEAR_WORDS + OTC_MEDICINES).distinct()
}

val BUILTIN_WORDS_SEEDED = SettingsKey(
    booleanPreferencesKey("builtin_words_seeded_v1"),
    false
)

/**
 * Adds [BuiltinWordList.ALL] to the user dictionary exactly once. Deletions made by the user are
 * respected afterwards because the seeded flag is persisted.
 */
suspend fun Context.seedBuiltinWordsIfNeeded() {
    if (getSetting(BUILTIN_WORDS_SEEDED)) return

    withContext(Dispatchers.IO) {
        val dict = UserDictionaryIO(this@seedBuiltinWordsIfNeeded)
        val existing = dict.get().map { it.word.lowercase() }.toSet()
        val toAdd = BuiltinWordList.ALL
            .filter { it.lowercase() !in existing }
            .map { PersonalWord(it, 160, null, 0, null) }

        if (toAdd.isNotEmpty()) {
            dict.put(toAdd)
        }

        setSetting(BUILTIN_WORDS_SEEDED, true)
    }
}
