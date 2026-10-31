package com.dshpet.android.btbu

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.dshpet.android.Prefs
import java.util.concurrent.Executors

/**
 * 同步调度 + 全局状态。UI / HUD / 后台都只跟这个打交道，不直接碰网络。
 *
 * 规则：
 *  - 网络永远在单线程池里跑，绝不占主线程
 *  - 失败不清缓存，只把错误原话留在 [lastError] 里，界面显示"上次同步 x 分钟前"
 *  - 没配账号就直接返回 false，谁的错谁显示
 */
object BtbuSync {

    private val ex = Executors.newSingleThreadExecutor { r ->
        Thread(r, "btbu-sync").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var syncing: Boolean = false
        private set

    fun store(ctx: Context) = BtbuStore(ctx.applicationContext)

    fun state(ctx: Context) = store(ctx).state()

    fun configured(ctx: Context) = Prefs.accUser(ctx).isNotEmpty() && Prefs.accPass(ctx).isNotEmpty()

    /** 全量刷新（课表 + 网费 + 一卡通）。回调在主线程。 */
    fun refreshAsync(ctx: Context, onDone: ((Boolean) -> Unit)? = null) {
        val app = ctx.applicationContext
        if (syncing) {
            main.post { onDone?.invoke(false) }
            return
        }
        val user = Prefs.accUser(app)
        val pwd = Prefs.accPass(app)
        if (user.isEmpty() || pwd.isEmpty()) {
            lastError = "还没填学号/密码"
            main.post { onDone?.invoke(false) }
            return
        }
        syncing = true
        ex.execute {
            val ok = try {
                store(app).refresh(userName = user, password = pwd, uuid = Prefs.uuid(app)).also {
                    lastError = if (it) null else "接口没返回数据"
                }
            } catch (t: Throwable) {
                lastError = t.message ?: t.javaClass.simpleName
                false
            }
            syncing = false
            main.post { onDone?.invoke(ok) }
        }
    }

    /** 只测登录（给"登录并同步"按钮用），成功返回真名 */
    fun loginTest(ctx: Context, userName: String, password: String, onDone: (String?, String?) -> Unit) {
        val app = ctx.applicationContext
        ex.execute {
            try {
                val login = BtbuApi.login(userName, password, Prefs.uuid(app))
                Prefs.setAcc(app, userName, password)
                main.post { onDone(login.realName.ifEmpty { login.userName }, null) }
            } catch (t: Throwable) {
                main.post { onDone(null, t.message ?: t.javaClass.simpleName) }
            }
        }
    }
}
