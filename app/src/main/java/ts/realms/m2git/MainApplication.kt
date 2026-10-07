package ts.realms.m2git

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.core.os.LocaleListCompat
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.conscrypt.Conscrypt
import org.eclipse.jgit.transport.CredentialsProvider
import timber.log.Timber
import timber.log.Timber.DebugTree
import ts.realms.m2git.core.network.transport.AndroidJschCredentialsProvider
import ts.realms.m2git.core.network.transport.MGitHttpConnectionFactory
import ts.realms.m2git.local.preference.PreferenceHelper
import ts.realms.m2git.local.preference.Profile
import ts.realms.m2git.local.preference.SecurePrefsHelper
import ts.realms.m2git.ui.common.errors.SecurePrefsException
import java.security.Security


/**
 * Custom Application Singleton
 */
open class MainApplication : Application() {

    companion object {
        @SuppressLint("StaticFieldLeak")
        private lateinit var mContext: Context
        private lateinit var mCredentialsProvider: CredentialsProvider
        val context: Context
            get() = mContext

        @JvmStatic
        fun getContext(): MainApplication {
            return mContext as MainApplication
        }

        @JvmStatic
        fun getJschCredentialsProvider(): CredentialsProvider {
            return mCredentialsProvider
        }

        init {
            MGitHttpConnectionFactory.install()
            Security.addProvider(BouncyCastleProvider())
            Security.addProvider(Conscrypt.newProvider())
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(DebugTree())
        }
        // 对可能出现的异常进行捕获，避免因未处理的异常导致应用崩溃。
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            Timber.tag("MGitApplication").e(throwable, "未处理的异常")
            // 可以选择退出应用或重启应用
            // System.exit(0);
        }
        mContext = applicationContext
        setAppVersionPref()
        migrateLegacyLanguageSetting()

        try {
            mCredentialsProvider =
                AndroidJschCredentialsProvider(SecurePrefsHelper.getInstance(this))
        } catch (e: SecurePrefsException) {
            Timber.e(e)
        }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
    }

    private fun setAppVersionPref() {
        val sharedPreference = getSharedPreferences(
            getString(R.string.preference_file_key), MODE_PRIVATE
        )
        val version = BuildConfig.VERSION_NAME + "(" + BuildConfig.VERSION_CODE + ")"
        sharedPreference.edit {
            putString(getString(R.string.preference_key_app_version), version)
        }
    }

    /**
     * 一次性迁移：把旧版「使用英文」开关写入 AppCompat per-app locale 存储；
     * API < 33 依赖 autoStoreLocales（AppLocalesMetadataHolderService）跨进程持久化。
     */
    private fun migrateLegacyLanguageSetting() {
        val sharedPreference = getSharedPreferences(
            getString(R.string.preference_file_key), MODE_PRIVATE
        )
        if (sharedPreference.getBoolean(getString(R.string.pref_key_app_locale_migrated), false)) {
            return
        }
        sharedPreference.edit {
            putBoolean(getString(R.string.pref_key_app_locale_migrated), true)
        }
        if (Profile.useEnglishLocale(this)) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
        }
    }
}
