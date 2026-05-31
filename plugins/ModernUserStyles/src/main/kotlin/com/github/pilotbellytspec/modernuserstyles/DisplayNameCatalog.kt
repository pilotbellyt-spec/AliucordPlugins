package com.github.pilotbellytspec.modernuserstyles

import android.graphics.Typeface

object DisplayNameCatalog {
    object Effect {
        const val SOLID = 1
        const val GRADIENT = 2
        const val NEON = 3
        const val TOON = 4
        const val POP = 5
        const val GLOW = 6
        const val TEST_1 = 1001
        const val TEST_2 = 1002
        const val TEST_3 = 1003
        const val TEST_4 = 1004
    }

    object Font {
        const val BANGERS = 1
        const val BIO_RHYME = 2
        const val CHERRY_BOMB = 3
        const val CHICLE = 4
        const val COMPAGNON = 5
        const val MUSEO_MODERNO = 6
        const val NEO_CASTEL = 7
        const val PIXELIFY = 8
        const val RIBES = 9
        const val SINISTRE = 10
        const val DEFAULT = 11
        const val ZILLA_SLAB = 12
    }

    val effects = linkedMapOf(
        Effect.SOLID to "SOLID",
        Effect.GRADIENT to "GRADIENT",
        Effect.NEON to "NEON",
        Effect.TOON to "TOON",
        Effect.POP to "POP",
        Effect.GLOW to "GLOW",
        Effect.TEST_1 to "TEST_1",
        Effect.TEST_2 to "TEST_2",
        Effect.TEST_3 to "TEST_3",
        Effect.TEST_4 to "TEST_4",
    )

    val fonts = linkedMapOf(
        Font.BANGERS to "BANGERS",
        Font.BIO_RHYME to "BIO_RHYME",
        Font.CHERRY_BOMB to "CHERRY_BOMB",
        Font.CHICLE to "CHICLE",
        Font.COMPAGNON to "COMPAGNON",
        Font.MUSEO_MODERNO to "MUSEO_MODERNO",
        Font.NEO_CASTEL to "NEO_CASTEL",
        Font.PIXELIFY to "PIXELIFY",
        Font.RIBES to "RIBES",
        Font.SINISTRE to "SINISTRE",
        Font.DEFAULT to "DEFAULT",
        Font.ZILLA_SLAB to "ZILLA_SLAB",
    )

    fun typeface(fontId: Int?, original: Typeface?): Typeface? = when (fontId) {
        Font.ZILLA_SLAB -> Typeface.create(original, Typeface.BOLD)
        Font.DEFAULT -> original
        else -> original
    }

    fun zipFontPath(fontId: Int?): String? = when (fontId) {
        Font.BANGERS -> "modern_user_styles/fonts/Bangers-Regular.ttf"
        Font.BIO_RHYME -> "modern_user_styles/fonts/BioRhyme-Regular.ttf"
        Font.CHERRY_BOMB -> "modern_user_styles/fonts/CherryBombOne-Regular.ttf"
        Font.CHICLE -> "modern_user_styles/fonts/Chicle-Regular.ttf"
        Font.COMPAGNON -> "modern_user_styles/fonts/Compagnon-Medium.otf"
        Font.MUSEO_MODERNO -> "modern_user_styles/fonts/MuseoModerno-Regular.ttf"
        Font.NEO_CASTEL -> "modern_user_styles/fonts/NeoCastel.otf"
        Font.PIXELIFY -> "modern_user_styles/fonts/PixelifySans-Regular.otf"
        Font.RIBES -> "modern_user_styles/fonts/Ribes-Black.otf"
        Font.SINISTRE -> "modern_user_styles/fonts/Sinistre-Bold.otf"
        Font.ZILLA_SLAB -> "modern_user_styles/fonts/ZillaSlab-SemiBold.ttf"
        else -> null
    }

    fun letterSpacing(fontId: Int?, original: Float): Float = when (fontId) {
        Font.CHERRY_BOMB -> 0.04f
        Font.MUSEO_MODERNO -> 0.01f
        Font.NEO_CASTEL -> 0.02f
        Font.PIXELIFY -> 0.02f
        Font.SINISTRE -> 0.01f
        Font.ZILLA_SLAB -> 0.03f
        else -> original
    }
}
