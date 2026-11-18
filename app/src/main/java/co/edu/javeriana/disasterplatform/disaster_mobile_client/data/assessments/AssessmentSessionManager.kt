package co.edu.javeriana.disasterplatform.disaster_mobile_client.data.assessments

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

data class AssessmentSession(
    val token: String, val actor: String, val organization: String, val issuer: String,
    val disasters: List<Int>, val roles: List<String>, val expiresAt: Long, val owner: String, val scope: String,
) {
    val authorization get() = "Bearer $token"
    val isCurrent get() = expiresAt > System.currentTimeMillis() / 1000 + 10
    val canWrite get() = roles.any { it == "damage.write" || it == "damage.admin" }
    fun allows(disaster: Int?) = disaster == null || disaster in disasters
}

@Singleton
class AssessmentSessionManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val api: AssessmentApi,
) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "assessment-session"))
    private val gson = Gson()
    private val guard = Mutex()
    private var loaded = false
    private val active = MutableStateFlow<AssessmentSession?>(null)
    val session = active.asStateFlow()

    suspend fun restore() = withContext(Dispatchers.IO) { guard.withLock { if (!loaded) { active.value = read(); loaded = true } } }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("assessment-session", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("assessment-session", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun read(): AssessmentSession? = runCatching {
        val bytes = file.readFully()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        gson.fromJson(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8), AssessmentSession::class.java)
    }.getOrNull()

    // Any future credential acquisition flow supplies its token here; callers do not choose identities.
    suspend fun accept(token: String) = withContext(Dispatchers.IO) {
        require(token.length in 50..16384 && token.none { it.isWhitespace() }) { "JWT inválido" }
        val body = AssessmentWire.json(String(Base64.decode(token.split('.').also { require(it.size == 3) }[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8))
        val actor = AssessmentWire.text(body, "sub"); val organization = AssessmentWire.text(body, "organization_id")
        val issuer = AssessmentWire.text(body, "iss"); val expiry = AssessmentWire.number(body, "exp")
        require(actor.length <= 200 && organization.length <= 200 && expiry > System.currentTimeMillis() / 1000 + 10)
        fun claims(name: String): List<String> = body[name]?.let { value ->
            if (value.isJsonArray) value.asJsonArray.map { it.asString } else listOf(value.asString)
        }.orEmpty().distinct().sorted()
        val roles = claims("role"); require(roles.any { it in setOf("damage.read", "damage.write", "damage.admin") })
        val disasters = claims("disaster_id").map { it.toInt().also { id -> require(id > 0) } }.distinct().sorted()
        val response = api.delta("Bearer $token", null, null, 1)
        require(response.isSuccessful) { "Jakarta rechazó la sesión (${response.code()})" }
        val page = response.body() ?: error("Respuesta de sesión incompleta")
        AssessmentWire.uuid(AssessmentWire.text(page, "nextCursor"))
        require(page["evaluations"]?.isJsonArray == true)
        // Only after server verification are these claims accepted as an offline identity.
        fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        val owner = digest(gson.toJson(listOf(issuer, actor, organization)))
        val validated = AssessmentSession(token, actor, organization, issuer, disasters, roles, expiry, owner, digest(gson.toJson(listOf(roles, disasters))))
        guard.withLock {
        persist(validated)
        }
    }
    private fun persist(validated:AssessmentSession) {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.ENCRYPT_MODE,key())}
        val bytes=cipher.iv+cipher.doFinal(gson.toJson(validated).toByteArray())
        val stream=file.startWrite()
        try{stream.write(bytes);file.finishWrite(stream)}catch(failure:Exception){file.failWrite(stream);throw failure}
        active.value=validated;loaded=true
    }
    suspend fun blockSynchronization(expected:AssessmentSession)=withContext(Dispatchers.IO){guard.withLock{
        if(active.value?.token==expected.token)persist(expected.copy(expiresAt=0))
    }}
    suspend fun clear(expected: AssessmentSession? = null) = withContext(Dispatchers.IO) { guard.withLock {
        if (expected == null || active.value?.token == expected.token) { file.delete(); active.value = null; loaded = true }
    } }
}
