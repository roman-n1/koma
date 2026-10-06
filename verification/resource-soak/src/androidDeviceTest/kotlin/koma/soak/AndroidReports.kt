package koma.soak

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidReports {
    @Test fun resourceBudgetsOnAndroid() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().context
        SoakPlatform.reportDirectory = requireNotNull(context.getExternalFilesDir(null)).absolutePath + "/resource-soak-android"
        runResourceSoak()
    }
}
