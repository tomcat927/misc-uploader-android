package com.tomcat927.miscuploader.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** 上传模式(拍板 A2,对齐桌面端两种模式) */
enum class UploadMode(val label: String) {
    /** 手动:上传到所选目标目录 */
    MANUAL("手动目录"),

    /** 按日期自动:按每个文件自身修改时间归 auto/yyyy/MM */
    AUTO_DATE("按日期自动"),
}

/** 已保存的连接配置(密码密文态) */
data class StoredConfig(
    val baseUrl: String,
    val username: String,
    val hasPassword: Boolean,
)

/** 解密后的完整连接配置 */
data class ServerConfig(
    val baseUrl: String,
    val username: String,
    val password: String,
)

/**
 * 设置持久化(拍板:对齐桌面端交互——无保存按钮,失焦即落盘;空服务器/用户名的保存直接拒绝;
 * 密码以 Keystore AES-GCM 密文存 DataStore,永不明文落盘)。
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val crypto: PasswordCrypto,
) {

    private object Keys {
        val BASE_URL = stringPreferencesKey("base_url")
        val USERNAME = stringPreferencesKey("username")
        val PASSWORD_CIPHER = stringPreferencesKey("password_cipher")
        val UPLOAD_MODE = stringPreferencesKey("upload_mode")
        val STARTUP_UPDATE_CHECK = booleanPreferencesKey("startup_update_check")
        val UPDATE_DISMISSED_TAG = stringPreferencesKey("update_dismissed_tag")
    }

    /** 上传模式流(默认手动) */
    val uploadModeFlow: Flow<UploadMode> = context.dataStore.data.map { prefs ->
        when (prefs[Keys.UPLOAD_MODE]) {
            "auto_date" -> UploadMode.AUTO_DATE
            else -> UploadMode.MANUAL
        }
    }

    suspend fun loadUploadModeOnce(): UploadMode = uploadModeFlow.first()

    suspend fun saveUploadMode(mode: UploadMode) {
        context.dataStore.edit { prefs ->
            prefs[Keys.UPLOAD_MODE] = if (mode == UploadMode.AUTO_DATE) "auto_date" else "manual"
        }
    }

    /** 启动检查更新开关(拍板 2026-10-04:默认开;关闭后启动完全不发起更新源请求) */
    val startupUpdateCheckFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.STARTUP_UPDATE_CHECK] ?: true
    }

    suspend fun startupUpdateCheckOnce(): Boolean = startupUpdateCheckFlow.first()

    suspend fun saveStartupUpdateCheck(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.STARTUP_UPDATE_CHECK] = enabled
        }
    }

    /** 启动弹窗被「暂不」的版本 tag(每版本只弹一次确认框) */
    suspend fun updateDismissedTagOnce(): String =
        context.dataStore.data.first()[Keys.UPDATE_DISMISSED_TAG].orEmpty()

    suspend fun saveUpdateDismissedTag(tag: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.UPDATE_DISMISSED_TAG] = tag
        }
    }

    /** 密文态配置流(密码不经过此流) */
    val storedConfigFlow: Flow<StoredConfig> = context.dataStore.data.map { prefs ->
        StoredConfig(
            baseUrl = prefs[Keys.BASE_URL].orEmpty(),
            username = prefs[Keys.USERNAME].orEmpty(),
            hasPassword = !prefs[Keys.PASSWORD_CIPHER].isNullOrEmpty(),
        )
    }

    suspend fun loadOnce(): StoredConfig =
        storedConfigFlow.first()

    /** 解密后的完整配置;服务器/用户名/密码任一缺失 = 配置不齐,返回 null */
    suspend fun loadDecryptedOnce(): ServerConfig? {
        val prefs = context.dataStore.data.first()
        val url = prefs[Keys.BASE_URL].orEmpty()
        val user = prefs[Keys.USERNAME].orEmpty()
        val cipher = prefs[Keys.PASSWORD_CIPHER] ?: return null
        if (url.isEmpty() || user.isEmpty()) return null
        return runCatching { ServerConfig(url, user, crypto.decrypt(cipher)) }.getOrNull()
    }

    /**
     * 保存(失焦即存)。
     * @param password null = 沿用已存密码(密码框留空的语义);空串 = 不保存密码
     * @return true = 落盘;false = 校验拒绝(空服务器/用户名不落盘)
     */
    suspend fun save(baseUrl: String, username: String, password: String?): Boolean {
        val url = baseUrl.trim()
        val user = username.trim()
        if (url.isEmpty() || user.isEmpty()) return false
        context.dataStore.edit { prefs ->
            prefs[Keys.BASE_URL] = url
            prefs[Keys.USERNAME] = user
            if (password != null && password.isNotEmpty()) {
                prefs[Keys.PASSWORD_CIPHER] = crypto.encrypt(password)
            } else if (password?.isEmpty() == true) {
                // 显式清空密码
                prefs.remove(Keys.PASSWORD_CIPHER)
            }
            // password == null:沿用已存
        }
        return true
    }

    /** 「显示」按钮:按需取回真实密码(掩码态不经过此方法) */
    suspend fun revealPassword(): String? {
        val cipher = context.dataStore.data.first()[Keys.PASSWORD_CIPHER] ?: return null
        return runCatching { crypto.decrypt(cipher) }.getOrNull()
    }

    companion object {
        /** 掩码回显:与桌面端同语义,已存密码显示 8 位掩码点 */
        const val PASSWORD_MASK = "••••••••"

        /** 地址归一化:无 scheme 补 https://,去尾部斜杠 */
        fun normalizeBaseUrl(raw: String): String {
            var u = raw.trim()
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
            return u.trimEnd('/')
        }
    }
}
