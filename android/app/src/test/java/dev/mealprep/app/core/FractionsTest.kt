package dev.mealprep.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class FractionsTest {
    @Test fun `amounts read like the shopping list`() {
        assertEquals("1¾ cups flour", Fractions.pretty("1 3/4 cups flour"))
        assertEquals("½ teaspoon salt", Fractions.pretty("1/2 teaspoon salt"))
        assertEquals("2 (14 oz) cans, ⅓ cup water", Fractions.pretty("2 (14 oz) cans, 1/3 cup water"))
        assertEquals("a ½-inch piece", Fractions.pretty("a 1/2-inch piece"))
    }

    @Test fun `other slashes are left alone`() {
        assertEquals("Bake at 350/180", Fractions.pretty("Bake at 350/180"))
        assertEquals("on 10/11", Fractions.pretty("on 10/11"))
        assertEquals("1 2/7 parts", Fractions.pretty("1 2/7 parts"))
        assertEquals("75 ml/1/3 cup", Fractions.pretty("75 ml/1/3 cup"))
        assertEquals("1.5/2 cups", Fractions.pretty("1.5/2 cups"))
    }
}
