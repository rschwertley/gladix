package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.settings.Settings
import java.util.Locale

object DeezerCountries {

    private const val COUNTRY_CODE_KEY = "countryCode"
    private const val LANGUAGE_CODE_KEY = "languageCode"

    data class Country(val name: String, val code: String)
    data class Language(val name: String, val code: String)

    private const val COUNTRY_DATA = "Afghanistan\tAF\nAlbania\tAL\nAlgeria\tDZ\nAngola\tAO\nAnguilla\tAI\nAntigua and Barbuda\tAG\nArgentina\tAR\nArmenia\tAM\nAustralia\tAU\nAustria\tAT\nAzerbaijan\tAZ\nBahrain\tBH\nBangladesh\tBD\nBarbados\tBB\nBelgium\tBE\nBenin\tBJ\nBhutan\tBT\nBolivia\tBO\nBosnia and Herzegovina\tBA\nBotswana\tBW\nBrazil\tBR\nBritish Indian Ocean Territory\tIO\nBritish Virgin Islands\tVG\nBrunei\tBN\nBulgaria\tBG\nBurkina Faso\tBF\nBurundi\tBI\nCambodia\tKH\nCameroon\tCM\nCanada\tCA\nCape Verde\tCV\nCayman Islands\tKY\nCentral African Republic\tCF\nChad\tTD\nChile\tCL\nChristmas Island\tCX\nCocos Islands\tCC\nColombia\tCO\nCook Islands\tCK\nCosta Rica\tCR\nCroatia\tHR\nCyprus\tCY\nCzech Republic\tCZ\nDemocratic Republic of the Congo\tCD\nDenmark\tDK\nDjibouti\tDJ\nDominica\tDM\nDominican Republic\tDO\nEast Timor\tTL\nEcuador\tEC\nEgypt\tEG\nEl Salvador\tSV\nEquatorial Guinea\tGQ\nEritrea\tER\nEstonia\tEE\nEthiopia\tET\nFederated States of Micronesia\tFM\nFiji\tFJ\nFinland\tFI\nFrance\tFR\nGabon\tGA\nGambia\tGM\nGeorgia\tGE\nGermany\tDE\nGhana\tGH\nGreece\tGR\nGrenada\tGD\nGuatemala\tGT\nGuinea\tGN\nGuinea-Bissau\tGW\nHonduras\tHN\nHungary\tHU\nIceland\tIS\nIndonesia\tID\nIraq\tIQ\nIreland\tIE\nIsrael\tIL\nItaly\tIT\nJamaica\tJM\nJapan\tJP\nJordan\tJO\nKazakhstan\tKZ\nKenya\tKE\nKiribati\tKI\nKuwait\tKW\nKyrgyzstan\tKG\nLaos\tLA\nLatvia\tLV\nLebanon\tLB\nLesotho\tLS\nLiberia\tLR\nLibya\tLY\nLithuania\tLT\nLuxembourg\tLU\nNorth Macedonia\tMK\nMadagascar\tMG\nMalawi\tMW\nMalaysia\tMY\nMali\tML\nMalta\tMT\nMarshall Islands\tMH\nMauritania\tMR\nMauritius\tMU\nMexico\tMX\nMoldova\tMD\nMongolia\tMN\nMontenegro\tME\nMontserrat\tMS\nMorocco\tMA\nMozambique\tMZ\nNamibia\tNA\nNauru\tNR\nNepal\tNP\nNew Zealand\tNZ\nNicaragua\tNI\nNiger\tNE\nNigeria\tNG\nNiue\tNU\nNorfolk Island\tNF\nNorway\tNO\nOman\tOM\nPakistan\tPK\nPalau\tPW\nPanama\tPA\nPapua New Guinea\tPG\nParaguay\tPY\nPeru\tPE\nPoland\tPL\nPortugal\tPT\nQatar\tQA\nRepublic of the Congo\tCG\nRomania\tRO\nRwanda\tRW\nSaint Kitts and Nevis\tKN\nSaint Lucia\tLC\nSaint Vincent and the Grenadines\tVC\nSamoa\tWS\nSão Tomé and Príncipe\tST\nSaudi Arabia\tSA\nSenegal\tSN\nSerbia\tRS\nSeychelles\tSC\nSierra Leone\tSL\nSingapore\tSG\nSlovakia\tSK\nSlovenia\tSI\nSomalia\tSO\nSouth Africa\tZA\nSpain\tES\nSri Lanka\tLK\nSvalbard and Jan Mayen\tSJ\nEswatini\tSZ\nSweden\tSE\nSwitzerland\tCH\nTajikistan\tTJ\nTanzania\tTZ\nThailand\tTH\nComoros\tKM\nFalkland Islands\tFK\nIvory Coast\tCI\nMaldives\tMV\nNetherlands\tNL\nPhilippines\tPH\nPitcairn Islands\tPN\nSolomon Islands\tSB\nTogo\tTG\nTokelau\tTK\nTonga\tTO\nTunisia\tTN\nTurkey\tTR\nTurkmenistan\tTM\nTurks and Caicos Islands\tTC\nTuvalu\tTV\nUganda\tUG\nUkraine\tUA\nUnited Arab Emirates\tAE\nUnited Kingdom\tGB\nUnited States of America\tUS\nUruguay\tUY\nUzbekistan\tUZ\nVanuatu\tVU\nVenezuela\tVE\nVietnam\tVN\nYemen\tYE\nZambia\tZM\nZimbabwe\tZW"

    private const val LANGUAGE_DATA = "English (UK)\ten-GB\nFrench\tfr-FR\nGerman\tde-DE\nSpanish (Spain)\tes-ES\nItalian\tit-IT\nDutch\tnl-NL\nPortuguese (Portugal)\tpt-PT\nRussian\tru-RU\nPortuguese (Brazil)\tpt-BR\nPolish\tpl-PL\nTurkish\ttr-TR\nRomanian\tro-RO\nHungarian\thu-HU\nSerbian\tsr-RS\nArabic\tar-SA\nCroatian\thr-HR\nSpanish (Mexico)\tes-MX\nCzech\tcs-CZ\nSlovak\tsk-SK\nSwedish\tsv-SE\nEnglish (US)\ten-US\nJapanese\tja-JP\nBulgarian\tbg-BG\nDanish\tda-DK\nFinnish\tfi-FI\nSlovenian\tsl-SI\nUkrainian\tuk-UA"

    val countries: List<Country> by lazy {
        COUNTRY_DATA.split('\n').map { line ->
            val tab = line.indexOf('\t')
            Country(line.take(tab), line.substring(tab + 1))
        }
    }

    val languages: List<Language> by lazy {
        LANGUAGE_DATA.split('\n').map { line ->
            val tab = line.indexOf('\t')
            Language(line.take(tab), line.substring(tab + 1))
        }
    }

    /** Language subtags Deezer's gateway accepts, derived from [LANGUAGE_DATA] rather than restated. */
    private val supportedLanguagePrefixes: Set<String> by lazy {
        languages.map { it.code.substringBefore('-').lowercase() }.toSet()
    }

    // Deliberately the same value getDefaultLanguageIndex falls back to (index 0 of LANGUAGE_DATA), so
    // the tag we SEND and the row the settings screen pre-selects cannot disagree for a user whose
    // language is unsupported.
    private const val FALLBACK_LANGUAGE_TAG = "en-GB"

    /**
     * The BCP-47 tag to send to Deezer: [stored] if the user picked one, otherwise a tag whose language
     * subtag is one Deezer accepts.
     *
     * ⚠⚠ WHY THIS EXISTS: THE SETTING KEY THE DEFAULT-INDEX HELPER WRITES IS NOT THE KEY THE API
     * READS. getDefaultLanguageIndex seeds "languageCode" (LANGUAGE_CODE_KEY) from
     * Locale.getDefault().toLanguageTag(); DeezerApi.language reads "lang", which is written ONLY when
     * the user actually picks a row in the SettingList. So for every user who never opened that screen,
     * "lang" is unset and the raw device tag was going out as gateway LANG - unvalidated, and
     * LANGUAGE_DATA holds only 27 tags. A device set to Filipino, Hebrew, Hindi, Malay or Catalan was
     * sending fil/he/hi/ms/ca, none of which Deezer lists.
     * ⚠️ READING "languageCode" INSTEAD WOULD FIX NOTHING, which is the trap here: that key
     * holds the same unvalidated device tag. The defect is not which key is read - it is that the
     * FALLBACK never went through the supported list. "lang" stays authoritative.
     *
     * ⚠⚠ IT PRESERVES THE DEVICE REGION, AND THAT IS NOT COSMETIC. DeezerApi.show passes the
     * full tag to DeezerShow.show, which derives `country` from `language.substringAfter("-")`.
     * Returning a bare "en" would send country=en, because substringAfter returns the WHOLE string
     * when the delimiter is absent. Rewriting the region instead (en-AU -> en-GB) would change the
     * country of users whose language is already supported and who are working fine today. So:
     *   stored set                 -> returned verbatim. A user's choice is never second-guessed.
     *   device prefix supported    -> device tag verbatim. Byte-identical to the old behaviour for
     *                                 every user who is not currently sending an unsupported LANG.
     *   prefix unsupported         -> "en-<device region>", so LANG becomes `en` and the region - the
     *                                 part Deezer never objected to - survives.
     *   no usable region           -> [FALLBACK_LANGUAGE_TAG].
     * ⚠️ ONE DELIBERATE WIDENING, SMALL AND STATED: a supported tag with NO region ("en", which
     * Locale.getDefault().toLanguageTag() can produce when the system locale carries no country) is
     * completed from [deviceRegion] rather than passed through. That is the only input whose behaviour
     * changes without its LANG having been wrong, and it changes DeezerShow's country from the garbage
     * "en" to a real region. Drop the `contains('-')` branch to revert just that.
     *
     * Not memoized: this is 27 string comparisons behind a settings read that already happens per
     * request, and the result must track a mid-session language change.
     *
     * @param stored       the "lang" setting, or null/blank when the user never picked one
     * @param deviceTag    Locale.getDefault().toLanguageTag()
     * @param deviceRegion Locale.getDefault().country - read separately because it is a real ISO region
     *                     whatever shape the tag has (zh-Hans-CN would otherwise yield "Hans-CN")
     */
    fun resolveApiLanguageTag(stored: String?, deviceTag: String, deviceRegion: String): String {
        if (!stored.isNullOrBlank()) return stored
        val region = deviceRegion.takeIf { it.length == 2 && it.all(Char::isLetter) }?.uppercase()
        val prefix = deviceTag.substringBefore('-')
        if (prefix.lowercase() in supportedLanguagePrefixes) return when {
            deviceTag.contains('-') -> deviceTag
            region != null -> "$prefix-$region"
            else -> deviceTag
        }
        return region?.let { "en-$it" } ?: FALLBACK_LANGUAGE_TAG
    }

    /** Country codes Deezer's gateway accepts, derived from [COUNTRY_DATA] rather than restated. */
    private val supportedCountryCodes: Set<String> by lazy {
        countries.map { it.code.uppercase() }.toSet()
    }

    /**
     * The country to send as RECOMMENDATION_COUNTRY, or NULL meaning "do not send one".
     *
     * ⚠⚠ THE COUNTRY TWIN OF [resolveApiLanguageTag]'s DEFECT, AND IT IS THE SAME MISMATCH:
     * getDefaultCountryIndex seeds "countryCode" (COUNTRY_CODE_KEY) from Locale.getDefault().country,
     * while DeezerApi.country read "country" - the key only an explicit pick in the SettingList ever
     * writes. So for every user who never opened that screen the raw device region went out
     * unvalidated, and [COUNTRY_DATA] holds 186 entries that do NOT include IN, CN, RU, KR, IR, HK or
     * TW. India alone is a very large Android market.
     * ⚠️ WHETHER DEEZER REJECTS AN UNLISTED CODE IS NOT KNOWN - COUNTRY_DATA is this
     * extension's own dropdown, not a published contract. What IS read is the consequence if it does:
     * DeezerSearchClient.browseFeed used to rethrow, so a refusal failed the whole browse feed. A
     * second reachable shape is the EMPTY string, which Locale.getDefault().country returns for a
     * region-less locale (reachable here via the per-app language override).
     *
     * ⚠⚠ IT RETURNS NULL RATHER THAN A SUBSTITUTE COUNTRY, AND THAT IS A DELIBERATE DEPARTURE
     * FROM [resolveApiLanguageTag], WHICH ALWAYS RETURNS A TAG. The asymmetry is in the quantities,
     * not in the style:
     *   LANG is a PER-REQUEST parameter with no server-side memory, so it must always carry a value
     *     and a validated fallback (en-GB) was the only option;
     *   RECOMMENDATION_COUNTRY is a STORED ACCOUNT PREFERENCE, so not setting it leaves whatever
     *     Deezer already has - derived from the user's own signup/IP and more likely right than
     *     anything we could guess.
     * Substituting would be the one option that can make results WORSE for exactly the users it is
     * meant to help: if Deezer accepts "IN", forcing "US" degrades their recommendations.
     * ⚠️ AND THE OBVIOUS SUBSTITUTE IS PARTICULARLY BAD. Mirroring getDefaultCountryIndex's
     * `?: 0` fallback would send COUNTRY_DATA's first entry, which is alphabetical: "AF". Nobody's
     * sensible recommendation region.
     *
     * @param stored        the "country" setting, or null/blank when the user never picked one
     * @param deviceCountry Locale.getDefault().country - may be empty, or a real code Deezer omits
     */
    fun resolveApiCountry(stored: String?, deviceCountry: String): String? {
        if (!stored.isNullOrBlank()) return stored
        val device = deviceCountry.uppercase()
        return device.takeIf { it in supportedCountryCodes }
    }

    fun getDefaultCountryIndex(settings: Settings?): Int {
        val storedCountryCode = settings?.getString(COUNTRY_CODE_KEY)
        val countryCode = storedCountryCode ?: Locale.getDefault().country.also {
            settings?.putString(COUNTRY_CODE_KEY, it)
        }
        return countries.indexOfFirst { it.code.equals(countryCode, ignoreCase = true) }.takeIf { it >= 0 } ?: 0
    }

    fun getDefaultLanguageIndex(settings: Settings?): Int {
        val storedLanguageCode = settings?.getString(LANGUAGE_CODE_KEY)
        val languageCode = storedLanguageCode ?: Locale.getDefault().toLanguageTag().also {
            settings?.putString(LANGUAGE_CODE_KEY, it)
        }
        return languages.indexOfFirst { it.code.equals(languageCode, ignoreCase = true) }.takeIf { it >= 0 } ?: 0
    }
}