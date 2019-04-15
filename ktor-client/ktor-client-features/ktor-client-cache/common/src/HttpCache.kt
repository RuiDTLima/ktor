package io.ktor.client.features.cache

import io.ktor.client.*
import io.ktor.client.features.*
import io.ktor.client.request.*
import io.ktor.client.response.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.util.*
import io.ktor.util.pipeline.*

internal object CacheControl {
    internal val NO_STORE = HeaderValue("no-store")
    internal val PRIVATE = HeaderValue("private")
    internal val MUST_REVALIDATE = HeaderValue("must-revalidate")
}

class HttpCache(
    val publicStorage: HttpCacheStorage,
    val privateStorage: HttpCacheStorage
) {
    class Config {
        var publicStorage: HttpCacheStorage = HttpCacheStorage.Default
        var privateStorage: HttpCacheStorage = HttpCacheStorage.Empty
    }

    companion object : HttpClientFeature<Config, HttpCache> {
        override val key: AttributeKey<HttpCache> = AttributeKey("HttpCache")

        override fun prepare(block: Config.() -> Unit): HttpCache {
            TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
        }

        override fun install(feature: HttpCache, scope: HttpClient) {
            val CachePhase = PipelinePhase("Cache")
            scope.sendPipeline.insertPhaseAfter(HttpSendPipeline.State, CachePhase)

            scope.sendPipeline.intercept(CachePhase) { content ->
                if (content !is OutgoingContent.NoContent || context.method != HttpMethod.Get) return@intercept

                val cache = feature.findResponse(context, content) ?: return@intercept
                if (cache.isValid()) {
                    finish()
                    proceedWith(cache)
                }

                cache.responseHeaders[HttpHeaders.ETag]?.let { etag ->
                    context.header(HttpHeaders.IfNoneMatch, etag)
                }

                cache.responseHeaders[HttpHeaders.LastModified]?.let {
                    context.header(HttpHeaders.IfModifiedSince, it)
                }
            }

            scope.receivePipeline.intercept(HttpReceivePipeline.State) { response ->
                if (context.request.method != HttpMethod.Get) return@intercept

                if (response.status.isSuccess()) {
                    val reusableResponse = feature.cacheResponse(response)
                    proceedWith(reusableResponse)
                    return@intercept
                }

                if (response.status == HttpStatusCode.NotModified) {
                    val responseFromCache = feature.findAndRefresh(response)
                        ?: throw InvalidCacheStateException(context.request.url)

                    proceedWith(responseFromCache)
                }
            }
        }
    }

    private fun cacheResponse(response: HttpResponse): HttpResponse {
        val request = response.call.request

        val responseCacheControl: List<HeaderValue> = response.cacheControl()

        val storage = if (CacheControl.PRIVATE in responseCacheControl) privateStorage else publicStorage

        if (CacheControl.NO_STORE in responseCacheControl) {
            return response
        }

        val cacheEntry = storage.store(request.url, response)
        return cacheEntry.produceResponse()
    }

    private fun findAndRefresh(response: HttpResponse): HttpResponse? {
        val url = response.call.request.url
        val cacheControl = response.cacheControl()

        val storage = if (CacheControl.PRIVATE in cacheControl) privateStorage else publicStorage
        val cache = storage.find(url, response.varyKeys()).produceResponse()

        storage.store(url, HttpCacheEntry(response.cacheExpires(), response.varyKeys(), cache))

        return cache
    }


    private fun findResponse(context: HttpRequestBuilder, content: OutgoingContent): HttpCacheEntry? {
        val url = Url(context.url)
        val lookup = mergedHeadersLookup(context.headers, content)

        val cachedResponses = privateStorage.findByUrl(url) + publicStorage.findByUrl(url)
        for (item in cachedResponses) {
            val varyKeys = item.varyKeys
            if (varyKeys.isEmpty() || varyKeys.all { (key, value) -> lookup(key) == value }) return item
        }

        return null
    }
}


@InternalAPI
private fun mergedHeadersLookup(
    requestHeaders: HeadersBuilder,
    content: OutgoingContent
): (String) -> String {
    TODO()
}

@Suppress("KDocMissingDocumentation")
class InvalidCacheStateException(requestUrl: Url) : IllegalStateException(
    "The entry for url: $requestUrl was removed from cache"
)
