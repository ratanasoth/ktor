package io.ktor.client.backend.cio

import io.ktor.client.tests.*
import org.junit.experimental.runners.*
import org.junit.runner.*

@RunWith(Enclosed::class)
class CIOClientTestSuite {
    class CIOCacheTest : CacheTests(CIOBackend)

    class CIOCookiesTest : CookiesTests(CIOBackend)

    class CIOFullFormTests : FullFormTests(CIOBackend)

    class CIOPostTests : PostTests(CIOBackend)
}
