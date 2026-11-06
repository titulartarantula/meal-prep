package dev.mealprep.app

import dev.mealprep.app.data.settings.SecretCipher
import dev.mealprep.app.data.settings.testCipher

/** The real MealPrepApp with a JCA key for the saved tokens (Robolectric has no AndroidKeyStore). */
class KeystorelessApp : MealPrepApp() {
    override fun secretCipher(): SecretCipher = testCipher()
}
