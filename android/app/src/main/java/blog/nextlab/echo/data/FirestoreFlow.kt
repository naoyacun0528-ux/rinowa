package blog.nextlab.echo.data

import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Firestore のリスナーを Flow にする。
 *
 * どの購読も同じ4行で始まり同じ1行で終わっていた。大事なのは最後の `remove()` で、
 * これを書き忘れたリスナーは画面を閉じても回線を掴んだまま残る。8か所に散らすと
 * 8回書き忘れる機会があるので、ここ1か所で面倒を見る。
 *
 * [emit] は購読側と同じ [ProducerScope] の上で走るので `trySend` がそのまま使える。
 * エラーの扱いは購読ごとに違う（黙って捨てる、呼び元へ返す、null を流す）ので
 * ここでは決めない。
 */
internal fun <T> Query.snapshotFlow(
    emit: ProducerScope<T>.(QuerySnapshot?, FirebaseFirestoreException?) -> Unit,
): Flow<T> = callbackFlow {
    val registration = addSnapshotListener { snapshot, error -> emit(snapshot, error) }
    awaitClose { registration.remove() }
}

/** 1件の文書を見る版。 */
internal fun <T> DocumentReference.snapshotFlow(
    emit: ProducerScope<T>.(DocumentSnapshot?, FirebaseFirestoreException?) -> Unit,
): Flow<T> = callbackFlow {
    val registration = addSnapshotListener { snapshot, error -> emit(snapshot, error) }
    awaitClose { registration.remove() }
}

/**
 * 一覧の購読。エラーは残して、そのスナップショットだけ捨てる。
 *
 * 黙って return すると「ルールに拒否された」と「1件も無い」が同じ見た目になる。
 * 通話のリスナーで実際にそうなり、1セッション溶けた。同じ注意書きが3か所に
 * 貼ってあったので、処理ごとここへ移した。
 */
internal fun <T> Query.documentsFlow(tag: String, map: (QuerySnapshot) -> T): Flow<T> =
    snapshotFlow { snapshot, error ->
        if (error != null) {
            android.util.Log.w(tag, "listener failed", error)
            return@snapshotFlow
        }
        if (snapshot == null) return@snapshotFlow
        trySend(map(snapshot))
    }
