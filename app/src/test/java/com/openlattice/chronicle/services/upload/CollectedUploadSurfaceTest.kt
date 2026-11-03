package com.openlattice.chronicle.services.upload

import com.openlattice.chronicle.android.AndroidDeviceSensorAvailability
import com.openlattice.chronicle.api.ChronicleStudyApi
import com.openlattice.chronicle.collection.CollectionAcknowledgment
import com.openlattice.chronicle.participantaccess.CreateParticipantFormAccessCodeRequest
import com.openlattice.chronicle.serialization.ChronicleCallAdapterFactory
import com.openlattice.chronicle.serialization.ChronicleJson
import com.openlattice.chronicle.sources.SourceDevice
import java.io.File
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Method
import java.util.UUID
import java.util.jar.JarFile
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body

/** Enumerates compiled Retrofit interfaces and invokes every collected-body route. */
class CollectedUploadSurfaceTest {
    private fun compiledApis(): List<Class<*>> {
        val source = File(checkNotNull(ChronicleStudyApi::class.java.protectionDomain?.codeSource?.location).toURI())
        val prefix = ChronicleStudyApi::class.java.packageName.replace('.', '/') + "/"
        val names = if (source.isDirectory) File(source, prefix).walkTopDown().filter { it.isFile }
            .map { it.relativeTo(source).invariantSeparatorsPath }.toList()
        else JarFile(source).use { jar -> jar.entries().asSequence().map { it.name }.toList() }
        return names.filter { it.startsWith(prefix) && it.endsWith(".class") && '$' !in it }
            .map { Class.forName(it.removeSuffix(".class").replace('/', '.')) }
            .filter { it.isInterface }
    }

    @Test fun everyCompiledCollectedEndpointSendsOnlyMeasuredBodies() {
        val interfaces = compiledApis()
        assertTrue(interfaces.contains(ChronicleStudyApi::class.java))
        var exercised = 0
        val methods = interfaces.flatMap { it.methods.toList() }
        for (method in methods) {
            val bodies = method.parameters.filter { it.isAnnotationPresent(Body::class.java) }
            if (bodies.isEmpty()) continue
            val type = bodies.single().parameterizedType
            // Enrollment, consent and form access are bounded control messages, not collected rows.
            if (type is Class<*>) {
                assertTrue("Unbudgeted body: $method", type in setOf(
                    SourceDevice::class.java, CollectionAcknowledgment::class.java, CreateParticipantFormAccessCodeRequest::class.java))
            } else {
                exerciseCollectedEndpoint(method, type as ParameterizedType)
                exercised++
            }
        }
        assertTrue("No collected endpoints discovered", exercised >= 6)
        println("Collected Retrofit endpoints exercised: $exercised")
    }

    private fun exerciseCollectedEndpoint(method: Method, type: ParameterizedType) {
        assertEquals("Unbudgeted body: $method", CollectedUploadBody::class.java, type.rawType)
        val rowType = type.actualTypeArguments.single() as Class<*>
        val body = if (rowType == AndroidDeviceSensorAvailability::class.java) {
            CollectedUploadBody.encodeSnapshot(AndroidDeviceSensorAvailability(), rowType)
        } else CollectedUploadBody.encode(emptyList<Any>(), rowType)
        var posted = byteArrayOf()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            posted = uploadedBodyBytes(chain.request().body)
            val reply = when (method.returnType.simpleName) {
                "List" -> "[]"
                "UploadReceipt" -> "{\"acceptedIds\":[],\"discardedIds\":[],\"settingsVersion\":1}"
                else -> "1"
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(reply.toResponseBody()).build()
        }.build()
        val retrofit = Retrofit.Builder().baseUrl("https://example.invalid/").client(client)
            .addConverterFactory(MoshiConverterFactory.create(ChronicleJson.moshi).withNullSerialization())
            .addCallAdapterFactory(ChronicleCallAdapterFactory()).build()
        method.invoke(retrofit.create(method.declaringClass), UUID(0, 1), "test", emptyMap<String, String>(), checkNotNull(body))
        assertArrayEquals(uploadedBodyBytes(body), posted)
    }
}
