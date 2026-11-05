package io.github.gabrielastilla.quiethome

/**
 * Bundled photo wallpapers from Unsplash (assets/wallpapers, 1920×1080 crops of the originals).
 *
 * Each photo's page was checked on 2026-11-05: free under the Unsplash License (not Unsplash+).
 * The licence allows copying, modifying and distributing photos, including commercially, without
 * permission or attribution. It does not allow selling unaltered copies or compiling photos to build
 * a similar or competing service; a small set of backgrounds inside a launcher is neither. Credits
 * are shown anyway (Settings › Wallpaper › Wallpaper credits, and CREDITS.md).
 */
object Photos {

    const val LICENSE_URL = "https://unsplash.com/license"
    const val VERIFIED = "2026-11-05"

    class Photo(
        val id: String,
        val asset: String,
        val title: String,
        val category: String,
        val photographer: String,
        val profileUrl: String,
        val pageUrl: String,
        val location: String,
    )

    val ALL = listOf(
        Photo("u-austria-lake", "wallpapers/austria-lake.jpg", "Alpine Lake", "Nature", "Jerry Kavan",
            "https://unsplash.com/@jerrykavan",
            "https://unsplash.com/photos/dramatic-mountain-landscape-with-a-lake-and-stormy-sky-F2UvQ-iIqqA", "Austria"),
        Photo("u-passo-giau", "wallpapers/passo-giau.jpg", "Passo di Giau", "Nature", "Edoardo Bortoli",
            "https://unsplash.com/@edo_bor",
            "https://unsplash.com/photos/a-mountain-with-a-reflection-in-the-water-dlHB_6NGzjk", "Passo di Giau, Italy"),
        Photo("u-braies", "wallpapers/braies-lake.jpg", "Lago di Braies", "Nature", "Nenad Radojčić",
            "https://unsplash.com/@necone",
            "https://unsplash.com/photos/lake-surrounded-by-trees-and-mountains-GyID_V3ShgU", "Lago di Braies, South Tyrol, Italy"),
        Photo("u-falcade", "wallpapers/falcade-forest.jpg", "Misty Forest", "Nature", "Luca Bravo",
            "https://unsplash.com/@lucabravo",
            "https://unsplash.com/photos/misty-forest-landscape-with-evergreen-trees-1ityuURfcQQ", "Falcade, Italy"),
        Photo("u-france-beach", "wallpapers/france-beach.jpg", "Golden Shore", "Nature", "Bernd Dittrich",
            "https://unsplash.com/@hdbernd",
            "https://unsplash.com/photos/an-aerial-view-of-a-beach-and-the-ocean-bRbi2YM4GEg", "France"),
        Photo("u-waves", "wallpapers/waves-shore.jpg", "Shoreline", "Nature", "Simona Sergi",
            "https://unsplash.com/@i_am_simoesse",
            "https://unsplash.com/photos/aerial-view-of-ocean-waves-on-shore-during-daytime-wv8ML_rnssc", "—"),
        Photo("u-dunes", "wallpapers/merzouga-dunes.jpg", "Merzouga Dunes", "Nature", "Wietse Jongsma",
            "https://unsplash.com/@wietsej",
            "https://unsplash.com/photos/a-group-of-sand-dunes-in-the-desert-K_GLVR6O1ME", "Merzouga, Morocco"),
        Photo("u-soft-shapes", "wallpapers/soft-shapes.jpg", "Soft Shapes", "Abstract", "Pawel Czerwinski",
            "https://unsplash.com/@pawel_czerwinski",
            "https://unsplash.com/photos/abstract-shapes-create-a-calm-and-soft-background-4E78075zc1w", "—"),
        Photo("u-geometric", "wallpapers/geometric.jpg", "Minimal Geometry", "Abstract", "Pawel Czerwinski",
            "https://unsplash.com/@pawel_czerwinski",
            "https://unsplash.com/photos/abstract-geometric-shapes-form-a-minimalist-pattern--6GvTDpkkPU", "—"),
        Photo("u-pink-blue", "wallpapers/pink-blue.jpg", "Glass Gradient", "Abstract", "Yue Ma",
            "https://unsplash.com/@eternalzard",
            "https://unsplash.com/photos/abstract-soft-pink-and-blue-gradient-background-nPpQprSfKUI", "—"),
        Photo("u-gradient-waves", "wallpapers/gradient-waves.jpg", "Gradient Waves", "Abstract", "BoliviaInteligente",
            "https://unsplash.com/@boliviainteligente",
            "https://unsplash.com/photos/abstract-soft-gradient-waves-of-blue-and-pink-9ZC6hkI4mY0", "—"),
    )

    fun credits(): String = ALL.joinToString("\n") { "${it.title} — ${it.photographer} (Unsplash)" } +
        "\n\nAll under the Unsplash License ($LICENSE_URL), verified $VERIFIED."
}
