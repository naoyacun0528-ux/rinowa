package blog.nextlab.echo.crypto

import android.content.Context
import blog.nextlab.echo.data.renamedPreferences
import blog.nextlab.echo.model.UserId
import org.json.JSONObject

/**
 * 前に見た相手の端末を覚えておく。
 *
 * **これが無いと「鍵が変わった」を言えない。** エンジンは「いまの端末はこれ」しか
 * 教えてくれず、前と同じかどうかは持っていない。比べるには、こちらが覚えておくしかない。
 *
 * 覚えるのは端末 ID と指紋だけ。本文には触らない。
 *
 * ### なぜ黙って変わってはいけないか
 *
 * 相手が機種変更しても、乗っ取られても、**この端末から見える変化は同じ**——
 * 知らない端末 ID と知らない指紋が現れる。区別する方法は無い。
 *
 * だから区別しようとせず、**変わったことだけを言う**。判断は利用者がする。
 * 黙って受け入れると、中間者と機種変更が同じ静けさで通る。
 */
class KnownDevices(context: Context) {

    private val store = context.renamedPreferences("rinowa_known_devices", "echo_known_devices")

    /** 変化の中身。画面はこれを見て何を出すか決める。 */
    data class Change(
        val added: List<DeviceFingerprint>,
        /** 端末 ID は同じなのに指紋が違うもの。**一番きな臭い。** */
        val changed: List<DeviceFingerprint>,
        val removedIds: List<String>,
    ) {
        fun isEmpty(): Boolean = added.isEmpty() && changed.isEmpty() && removedIds.isEmpty()
    }

    /**
     * いまの一覧と、覚えているものを比べる。**記録は更新しない。**
     *
     * 更新を分けているのは、画面が「見せる前に消してしまう」ことを防ぐため。
     * 利用者が気づく前に記録が新しくなったら、警告は一度も出ない。
     */
    fun compare(user: UserId, current: List<DeviceFingerprint>): Change? {
        val raw = store.getString(user.value, null) ?: return null // 初回は比べない
        val remembered = parse(raw)

        val added = current.filter { it.deviceId !in remembered.keys }
        val changed = current.filter { device ->
            val before = remembered[device.deviceId]
            before != null && before != device.ed25519
        }
        val removed = remembered.keys.filter { id -> current.none { it.deviceId == id } }
        return Change(added, changed, removed)
    }

    /** いまの一覧を覚え直す。**利用者に見せたあとで呼ぶ。** */
    fun remember(user: UserId, current: List<DeviceFingerprint>) {
        val json = JSONObject()
        for (device in current) json.put(device.deviceId, device.ed25519)
        store.edit().putString(user.value, json.toString()).apply()
    }

    private fun parse(raw: String): Map<String, String> =
        // swallow-ok: 壊れた記録は「何も覚えていない」として扱う。次に見たときに
        // 書き直される。ここで投げると、確認の画面が開かなくなる。
        runCatching {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { json.optString(it) }
        }.getOrDefault(emptyMap())
}
