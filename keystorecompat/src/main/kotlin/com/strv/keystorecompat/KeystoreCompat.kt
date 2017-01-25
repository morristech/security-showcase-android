package com.strv.keystorecompat

import android.annotation.TargetApi
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyProperties
import android.util.Log
import com.strv.keystorecompat.utility.*
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.*
import javax.security.auth.x500.X500Principal


/**
 * The Keystore itself is encrypted using (not only) the user’s own lockScreen pin/password,
 * hence, when the device screen is locked the Keystore is unavailable.
 * Keep this in mind if you have a background service that could need to access your application secrets.
 *
 * With KeyStoreProvider each app can only access to their KeyStore instances or aliases!
 *
 * CredentialsKeystoreProvider is intended to be called with Lollipop&Up versions.
 * Call by KitKat is mysteriously failing on M-specific code (even when it is not called).
 */
@TargetApi(Build.VERSION_CODES.KITKAT)
object KeystoreCompat {

    val cipherMode: String = "RSA/None/PKCS1Padding"
    lateinit var context: Context
    lateinit var config: KeystoreCompatConfig

    private val KEYSTORE_KEYWORD = "AndroidKeyStore"
    private lateinit var keyStore: KeyStore
    private lateinit var certSubject: X500Principal
    private lateinit var algorithm: String
    private lateinit var uniqueId: String


    private val LOG_TAG = javaClass.name
    // In memory baypass/way how to force typing Android credentials for LOLLIPOP based generated keyPairs
    private var forceTypeCredentials = true
    private var encryptedUserData by stringPref("secure_pin_data")
    private var signUpCancelCount by intPref("sign_up_cancel_count")


    fun <T : KeystoreCompatConfig> init(context: Context, config: T) {
        if (!isKeystoreCompatAvailable()) {
            logUnsupportedVersionForKeystore()
        }
        runSinceKitKat {
            this.context = context
            this.config = config
            this.uniqueId = Settings.Secure.getString(KeystoreCompat.context.getContentResolver(), Settings.Secure.ANDROID_ID)
            PrefDelegate.initialize(this.context)
            certSubject = X500Principal("CN=$uniqueId, O=Android Authority")

            algorithm = "RSA"
            runSinceMarshmallow {
                algorithm = KeyProperties.KEY_ALGORITHM_RSA
            }
            keyStore = KeyStore.getInstance(KEYSTORE_KEYWORD)
            keyStore.load(null)
            KeystoreCompatImpl.init(Build.VERSION.SDK_INT)
        }
    }

    /**
     * Keystore is available since API1.
     * CredentialsKeystoreProvider is since API18.
     * Relatively trusted secure keystore is known since API19.
     * Improved security is then since API 23.
     */
    fun isKeystoreCompatAvailable(): Boolean {
        //Pre-KitKat version are not supported
        return (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT);
    }

    /**
     * Keystore is available only for secured devices!
     */
    fun isSecurityEnabled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            return false
        } else {
            return KeystoreCompatImpl.keystoreCompat.isSecurityEnabled(KeystoreCompat.context)
        }
    }

    /**
     * Store credentials string in encrypted form to shared preferences.
     * Call this function in separated thread, as eventual keyPair init may takes longer time
     */
    fun storeCredentials(composedCredentials: String, onError: () -> Unit) {
        runSinceKitKat {
            Log.d(LOG_TAG, "Before load KeyPair...")
            if (isKeystoreCompatAvailable() && isSecurityEnabled()) {
                initKeyPairIfNecessary(uniqueId)
                KeystoreCompat.encryptedUserData = KeystoreCrypto.encryptCredentials(composedCredentials, KeystoreCompat.keyStore.getEntry(uniqueId, null) as KeyStore.PrivateKeyEntry)
            } else {
                onError.invoke()
            }
        }
    }

    /**
     * Check if shared preferences contains some secret to be loadable.
     */
    fun hasCredentialsLoadable(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            if (isKeystoreCompatAvailable() && isSecurityEnabled()) {//Is usage of Keystore allowed?
                if (signUpCancelled()) return false
                return ((encryptedUserData?.isNotBlank() ?: false) //Is there content to decrypt
                        && (keyStore.getEntry(uniqueId, null) != null))//Is there a key for decryption?
            } else return false
        } else return false
    }

    /**
     * Load credentials string in decrypted form from shared preferences
     */
    fun loadCredentials(onSuccess: (cre: String) -> Unit, onFailure: (e: Exception) -> Unit, forceFlag: Boolean?) {
        runSinceKitKat {
            val privateEntry: KeyStore.PrivateKeyEntry = KeystoreCompat.keyStore.getEntry(KeystoreCompat.uniqueId, null) as KeyStore.PrivateKeyEntry
            KeystoreCompatImpl.keystoreCompat.loadCredentials(onSuccess,
                    onFailure,
                    { clearCredentials() },
                    forceFlag,
                    this.encryptedUserData,
                    privateEntry)
        }
    }

    /**
     * CleanUp credentials string from shared preferences.
     */
    fun clearCredentials() {
        runSinceKitKat {
            encryptedUserData = ""
            keyStore.deleteEntry(uniqueId)
            if (keyStore.containsAlias(uniqueId))
                throw RuntimeException("Cert delete wasn't successful!")
        }
    }

    fun disableForceTypeCredentials() {
        runSinceKitKat {
            forceTypeCredentials = false
        }
    }

    fun enableForceTypeCredentials() {
        runSinceKitKat {
            forceTypeCredentials = true
        }
    }

    fun increaseSignUpCancel() {
        runSinceKitKat {
            signUpCancelCount++
        }
    }

    fun signUpSuccessful() {
        runSinceKitKat {
            signUpCancelCount = 0
        }
    }

    internal fun initKeyPairIfNecessary(alias: String) {
        if (isKeystoreCompatAvailable() && isSecurityEnabled()) {
            if (keyStore.containsAlias(alias) && isCertificateValid()) return
            else createNewKeyPair(alias)
        }
    }

    internal fun signUpCancelled(): Boolean {
        return signUpCancelCount >= config.getProviderForbidThreshold()
    }

    private fun logUnsupportedVersionForKeystore() {
        Log.w(LOG_TAG, "Device Android version[${Build.VERSION.SDK_INT}] doesn't offer trusted keystore functionality!")
    }


    private fun isCertificateValid(): Boolean {
        //TODO solve real certificate validity
        return true
    }


    private fun createNewKeyPair(aliasText: String) {
        try {
            val start = Calendar.getInstance()
            val end = Calendar.getInstance()
            end.add(Calendar.YEAR, 1)//TODO handle with outdated certificates!
            generateKeyPair(aliasText, start.time, end.time)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Unable to create keys!", e)
            throw e
        }
    }

    private fun generateKeyPair(alias: String, start: Date, end: Date) {
        val generator = KeyPairGenerator.getInstance(algorithm, KEYSTORE_KEYWORD)
        generator.initialize(KeystoreCompatImpl.keystoreCompat.getAlgorithmParameterSpec(this.certSubject, alias, start, end, this.context))
        generator.generateKeyPair()
        if (!keyStore.containsAlias(alias))
            throw RuntimeException("KeyPair was NOT stored!")
    }

}

