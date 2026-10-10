package actron.soak

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidReports {
    @Test fun resourceBudgetsOnAndroid() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().context
        val reports = File(context.filesDir, "resource-soak-android")
        check(!reports.exists() || reports.deleteRecursively()) { "Cannot remove previous benchmark reports" }
        SoakPlatform.reportDirectory = reports.absolutePath
        runResourceSoak()
    }
}
