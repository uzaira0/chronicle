package com.openlattice.chronicle.compat

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.storage.ChronicleDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApiFloorDependencySmokeTest {
    @Test
    fun apiFloorLoadsSerializerAndSqlCipher() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(Build.VERSION.SDK_INT >= 23)

        val json = JsonSerializer.toJson(mapOf("api" to Build.VERSION.SDK_INT))
        assertEquals(Build.VERSION.SDK_INT, JsonSerializer.fromJson<Map<String, Int>>(json)?.get("api"))

        assertTrue(ChronicleDb.getInstance(context).openHelper.writableDatabase.isOpen)
    }
}
