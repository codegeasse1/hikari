package com.hikari.desktop.engine

/** Uniform contract every extension ecosystem implements on desktop. */
interface Engine {
    val kind: EngineKind

    suspend fun catalogs(provider: EngineProvider): List<EngineCatalog> = emptyList()

    suspend fun catalog(
        provider: EngineProvider,
        catalog: EngineCatalog,
        page: Int,
    ): List<EngineMedia> = emptyList()

    suspend fun search(provider: EngineProvider, query: String, page: Int): List<EngineMedia> =
        emptyList()

    suspend fun meta(provider: EngineProvider, media: EngineMedia): EngineMedia = media

    suspend fun episodes(provider: EngineProvider, media: EngineMedia): List<EngineEpisode> =
        emptyList()

    suspend fun streams(
        provider: EngineProvider,
        media: EngineMedia,
        episode: EngineEpisode?,
    ): List<EngineStream> = emptyList()

    /** Install-time check. Null = usable, otherwise a human-readable reason. */
    suspend fun validate(provider: EngineProvider): String? = null
}
