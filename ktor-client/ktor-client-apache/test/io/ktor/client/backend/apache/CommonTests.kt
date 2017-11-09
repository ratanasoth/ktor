package io.ktor.client.backend.apache

import io.ktor.client.tests.*
import org.junit.experimental.runners.*
import org.junit.runner.*


@RunWith(Enclosed::class)
class ApacheClientTestSuite {
    class ApacheCacheTest : CacheTests(ApacheBackend)

    class ApacheCookiesTest : CookiesTests(ApacheBackend)

    class ApacheFullFormTests : FullFormTests(ApacheBackend)

    class ApachePostTests : PostTests(ApacheBackend)

    class ApacheMultithreadedTest : MultithreadedTest(ApacheBackend)
}
