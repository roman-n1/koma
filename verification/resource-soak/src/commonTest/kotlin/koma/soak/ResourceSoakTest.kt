package koma.soak

import kotlinx.coroutines.runBlocking
import kotlin.test.Test

class ResourceSoakTest {
    @Test fun resourcesRemainBoundedAcrossStoreAndRecordingLifecycles() = runBlocking { runResourceSoak() }
}
