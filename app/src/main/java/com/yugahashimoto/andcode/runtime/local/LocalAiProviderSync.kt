package com.yugahashimoto.andcode.runtime.local

/** Provider id and base URL used for the embedded llama-server in opencode. */
object LocalAiProvider {
    const val ID = "local-ai"
    const val NAME = "Local AI"
    const val BASE_URL = "http://127.0.0.1:8097/v1"
}

/**
 * Keeps the opencode custom-provider store in sync with the running local model.
 *
 * While a model is running its provider definition lives in [CustomProviderStore] so that every
 * local-runtime start (which regenerates `opencode.json` via [CustomProviderStore.syncToRuntime])
 * advertises the local model to opencode; [clear] removes it again when the server stops.
 */
class LocalAiProviderSync(
    private val customProviders: CustomProviderStore,
) {
    /** Advertise [model] as the local provider. Safe to call repeatedly. */
    fun advertise(model: LocalAiModel) {
        customProviders.upsert(
            CustomProviderDefinition(
                id = LocalAiProvider.ID,
                name = LocalAiProvider.NAME,
                baseUrl = LocalAiProvider.BASE_URL,
                models = listOf(model.modelId),
            ),
        )
    }

    /** Remove the local provider entry so a stopped server is never advertised. */
    fun clear() {
        customProviders.remove(LocalAiProvider.ID)
    }
}
