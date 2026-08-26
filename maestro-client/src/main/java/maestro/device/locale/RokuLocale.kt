package maestro.device.locale
import maestro.device.Platform

/**
 * Roku device locale. Fixed set for now — Roku locale switching isn't wired up
 * (ECP has no locale endpoint), so only the default is offered.
 */
enum class RokuLocale(override val code: String) : DeviceLocale {
  EN_US("en_US");

  override val displayName: String
    get() = DeviceLocale.getDisplayNameFromCode(code)

  override val languageCode: String
    get() = code.split("_", "-")[0]

  override val countryCode: String
    get() = code.split("_", "-")[1]

  override val platform: Platform = Platform.ROKU

  companion object {
    val allCodes: Set<String>
      get() = entries.map { it.code }.toSet()

    fun fromString(localeString: String): RokuLocale {
      return entries.find { it.code == localeString }
        ?: throw LocaleValidationException("Failed to validate Roku locale: $localeString. Here is a full list of supported locales: \n\n ${allCodes.joinToString(", ")}")
    }

    fun isValid(localeString: String): Boolean {
      return entries.any { it.code == localeString }
    }

    fun find(languageCode: String, countryCode: String): String? {
      val underscoreFormat = "${languageCode}_$countryCode"
      return if (isValid(underscoreFormat)) underscoreFormat else null
    }
  }
}
