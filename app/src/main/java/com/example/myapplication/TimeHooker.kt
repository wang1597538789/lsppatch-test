package com.example.myapplication
import android.content.Intent
import android.os.Bundle
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.Calendar

class TimeHooker : IXposedHookLoadPackage {

    // 【配置中心】
    private val TARGET_PACKAGE = "com.xdja.clusterdemo" // 目标包名
    private val SAFE_TIMESTAMP: Long = 1710460800000L // 安全时间点：2024-03-15 00:00:00

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PACKAGE) return

        XposedBridge.log("TimeHooker: 已进入防御模式，目标: ${lpparam.packageName}")

        // --- 第一重：锁定时间 (防止过期) ---
        applyTimeShield(lpparam)

        // --- 第二重：拦截 Intent 卸载请求 ---
        hookUninstallationIntent(lpparam)

        // --- 第三重：拦截 Shell 命令卸载 ---
        hookRuntimeExec()
    }

    private fun applyTimeShield(lpparam: XC_LoadPackage.LoadPackageParam) {
        val timeHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val realTime = param.result as? Long ?: return
                if (realTime > SAFE_TIMESTAMP) {
                    param.result = SAFE_TIMESTAMP
                }
            }
        }

        try {
            // 1. System.currentTimeMillis
            XposedHelpers.findAndHookMethod(System::class.java, "currentTimeMillis", timeHook)

            // 2. Date & Calendar (使用更稳健的注入方式)
            XposedHelpers.findAndHookMethod(java.util.Date::class.java, "getTime", timeHook)
            XposedHelpers.findAndHookMethod(Calendar::class.java, "getTimeInMillis", timeHook)

            // 3. Java 8 现代时间 API (直接返回常量以确保万无一失)
            val fakeInstant = java.time.Instant.ofEpochMilli(SAFE_TIMESTAMP)
            XposedHelpers.findAndHookMethod("java.time.Instant", lpparam.classLoader, "now",
                XC_MethodReplacement.returnConstant(fakeInstant))

            XposedBridge.log("TimeHooker: [成功] 时间结界已展开")
        } catch (e: Throwable) {
            XposedBridge.log("TimeHooker: [警告] 时间钩子部分失效: ${e.message}")
        }
    }

    private fun hookUninstallationIntent(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.ContextImpl",
                lpparam.classLoader,
                "startActivity",
                Intent::class.java,
                Bundle::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val intent = param.args[0] as? Intent ?: return
                        val action = intent.action
                        val data = intent.dataString ?: ""

                        // 拦截常见的卸载 Action
                        val isUninstall = action == Intent.ACTION_DELETE ||
                                action == "android.intent.action.UNINSTALL_PACKAGE"

                        if (isUninstall && data.contains(TARGET_PACKAGE)) {
                            param.result = null // 彻底拦截跳转
                            XposedBridge.log("TimeHooker: 🛡️ 成功拦截针对 [$TARGET_PACKAGE] 的卸载 Intent！")
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("TimeHooker: Intent 拦截器部署失败")
        }
    }

    private fun hookRuntimeExec() {
        try {
            XposedHelpers.findAndHookMethod(
                java.lang.Runtime::class.java,
                "exec",
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val cmd = param.args[0] as? String ?: return
                        if (cmd.contains("pm uninstall") && cmd.contains(TARGET_PACKAGE)) {
                            param.result = null // 阻止执行
                            XposedBridge.log("TimeHooker: 🛡️ 成功拦截命令行卸载尝试: $cmd")
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("TimeHooker: Shell 拦截器部署失败")
        }
    }
}
