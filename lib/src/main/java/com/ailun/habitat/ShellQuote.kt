package com.ailun.habitat

/** 拼 shell 命令时的参数转义与校验。凡是来自参数/变量的值，拼进命令前都要经过这里。 */
object ShellQuote {
    private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    /** POSIX 单引号转义：abc'd → 'abc'\''d'。结果可直接作为一个 shell 参数。 */
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** Android 包名校验（com.foo.bar）。 */
    fun isPackageName(value: String): Boolean = PACKAGE_NAME.matches(value)
}
