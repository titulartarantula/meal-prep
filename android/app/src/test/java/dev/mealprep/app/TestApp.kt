package dev.mealprep.app

import android.app.Application

/** Plain Application for Robolectric: the real MealPrepApp opens DataStore/Room, which must not happen once per test. */
class TestApp : Application()
