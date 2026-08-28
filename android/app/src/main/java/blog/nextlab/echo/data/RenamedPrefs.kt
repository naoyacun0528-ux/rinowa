package blog.nextlab.echo.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 前は別の名前だった設定ファイル。
 *
 * アプリが Echo だった頃、設定は `echo_settings` と `echo_push` にあった。名前を
 * 変えるのは1行だが、それだけだと中身が黙って捨てられる。選んだ触覚の設定と、
 * もっと悪いことに push の登録が紐づく device id。device id を忘れた端末は通知が
 * 止まるのではなく、*2つ目の*登録を作り、整理されるまで古いほうにも送られ続ける。
 *
 * なので新しい名前で最初に読むときに、古いファイルを移して空にする。インストールに
 * つき1回、数百バイトのファイルに対して走るだけ。
 *
 * 書き込みは apply ではなく commit。呼び出し側は次の行で結果を読むが、`apply()` は
 * 「いずれ書く」としか約束しない。
 */
fun Context.renamedPreferences(name: String, formerly: String): SharedPreferences {
    val prefs = getSharedPreferences(name, Context.MODE_PRIVATE)
    if (prefs.all.isNotEmpty()) return prefs

    val old = getSharedPreferences(formerly, Context.MODE_PRIVATE)
    val had = old.all
    if (had.isEmpty()) return prefs

    val editor = prefs.edit()
    for ((key, value) in had) {
        when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is String -> editor.putString(key, value)
            // 設定ファイルが持てるのは、これに Set を足した5種類だけ。
            is Set<*> -> {
                @Suppress("UNCHECKED_CAST")
                editor.putStringSet(key, value as Set<String>)
            }
        }
    }
    editor.commit()
    old.edit().clear().commit()
    return prefs
}
