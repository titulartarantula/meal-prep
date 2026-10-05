package dev.mealprep.app

private object FixtureAnchor

fun fixture(name: String): String =
    FixtureAnchor::class.java.classLoader!!.getResource("fixtures/$name")!!.readText()
