package blog.nextlab.echo.data

import blog.nextlab.echo.auth.RinowaUser
import blog.nextlab.echo.core.model.UserId
import blog.nextlab.echo.core.model.UserProfile
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.security.SecureRandom
import kotlinx.coroutines.tasks.await

/**
 * プロフィールと、人を見つける唯一の方法。
 *
 * メールアドレスで検索できるようにすると、Rinowa は「この人は Rinowa を使っているか」に
 * どんなアドレスでも答えることになる。同意していない人についての本当の開示なので、
 * 引く表の鍵は本人が渡すと決めたコードにしてある。firestore.rules はどちらの
 * コレクションも一覧できないので、コードから引くことはできても、コードの集合を
 * 歩き回ることはできない。
 */
class UserRepository(private val db: FirebaseFirestore) {

    /**
     * サインイン中のアカウントのプロフィールが存在することを確かめる。
     *
     * 登録時だけでなくサインインのたびに呼ぶ。Firebase Auth にアカウントがあって
     * Firestore にプロフィールが無い状態は起こりうる（登録の途中でアプリが殺された、
     * このコレクションより古いアカウント）。そうなると名前を引く画面が全部、
     * 黙って何も出さなくなる。
     */
    suspend fun ensureProfile(user: RinowaUser): Result<UserProfile> = runCatching {
        val document = db.collection(RinowaDb.Users.COLLECTION).document(user.uid)
        val existing = document.get().await()

        val fallbackName = user.displayName
            ?: user.email?.substringBefore('@')
            ?: "名前未設定"

        if (existing.exists()) {
            if (existing.getString(RinowaDb.Users.INVITE_CODE).isNullOrBlank()) {
                // set(merge) ではなく update()。ルールは書き上がった状態の
                // ドキュメントに対して `displayName is string` を見るので、コードだけの
                // merge は名前の無いドキュメントとして判定される。
                document.update(
                    mapOf(
                        RinowaDb.Users.INVITE_CODE to claimInviteCode(user.uid),
                        RinowaDb.Users.UPDATED_AT to FieldValue.serverTimestamp(),
                    ),
                ).await()
            }
            return@runCatching existing.toProfile(UserId(user.uid), fallbackName)
        }

        // コードは先に確保するが、書くのはここ、プロフィールと同じドキュメントに。
        // 先に単体で書くと、項目がコードしかない create になってルールに弾かれる。
        // それは正しい（プロフィールではないから）。
        val code = claimInviteCode(user.uid)
        document.set(
            mapOf(
                RinowaDb.Users.DISPLAY_NAME to fallbackName,
                RinowaDb.Users.PHOTO_URL to null,
                RinowaDb.Users.INVITE_CODE to code,
                RinowaDb.Users.CREATED_AT to FieldValue.serverTimestamp(),
                RinowaDb.Users.UPDATED_AT to FieldValue.serverTimestamp(),
            ),
        ).await()

        UserProfile(id = UserId(user.uid), displayName = fallbackName, photoUrl = null)
    }

    suspend fun profile(id: UserId): Result<UserProfile> = runCatching {
        userDocument(id).toProfile(id, fallbackName = "退会したユーザー")
    }

    /** 複数のプロフィールをまとめて解決する。読めなかったものは飛ばす。 */
    suspend fun profiles(ids: Collection<UserId>): Map<UserId, UserProfile> =
        ids.distinct().mapNotNull { id -> profile(id).getOrNull()?.let { id to it } }.toMap()

    suspend fun inviteCode(id: UserId): Result<String> = runCatching {
        db.collection(RinowaDb.Users.COLLECTION).document(id.value).get().await()
            .getString(RinowaDb.Users.INVITE_CODE)
            ?: error("no invite code")
    }

    /**
     * 確かめられなかったときは失敗を返す。「誰も持っていない」と「こちらが確かめ
     * られなかった」を同じ null にすると、呼ぶ側はどちらもコードの打ち間違いとして扱う。
     *
     * @return そのコードが属するアカウント。誰も持っていなければ null。
     */
    suspend fun findByInviteCode(code: String): Result<UserProfile?> = runCatching {
        val normalised = normalise(code)
        if (normalised.length != CODE_LENGTH) return@runCatching null

        val entry = db.collection(RinowaDb.InviteCodes.COLLECTION).document(normalised).get().await()
        if (entry.unverifiedAbsence()) error("invite code lookup only saw the cache")
        val uid = entry.getString(RinowaDb.InviteCodes.UID) ?: return@runCatching null

        // ここは profile() を通さず自分で読む。あちらはドキュメントが無くても
        // 退会した人として必ず1件返す作りで、読めなかったことを言う口を持たない。
        val user = userDocument(UserId(uid))
        if (user.unverifiedAbsence()) error("profile lookup only saw the cache")
        user.toProfile(UserId(uid), fallbackName = "退会したユーザー")
    }

    suspend fun updateDisplayName(id: UserId, name: String): Result<Unit> = runCatching {
        val trimmed = name.trim().take(MAX_NAME_LENGTH)
        require(trimmed.isNotEmpty())
        db.collection(RinowaDb.Users.COLLECTION).document(id.value).update(
            mapOf(
                RinowaDb.Users.DISPLAY_NAME to trimmed,
                RinowaDb.Users.UPDATED_AT to FieldValue.serverTimestamp(),
            ),
        ).await()
    }

    /**
     * このアカウント用に、まだ使われていないコードを取る。
     *
     * 乱数を信じず、衝突したらやり直す。32^8 通りあるので衝突はまず起きないが、
     * 「まず起きない」と「起こりえない」は別で、失敗の形は2人が同じ身元を共有すること。
     */
    private suspend fun claimInviteCode(uid: String): String {
        repeat(CLAIM_ATTEMPTS) {
            val candidate = randomCode()
            val document = db.collection(RinowaDb.InviteCodes.COLLECTION).document(candidate)
            if (document.get().await().exists()) return@repeat
            // swallow-ok: このループ自体が処理。確保できなかったコードは、確認と
            // 書き込みの間に取られたということで、次の試行がそのためにある。
            // 試行回数が尽きた場合は下で例外を投げる。
            runCatching {
                document.set(
                    mapOf(
                        RinowaDb.InviteCodes.UID to uid,
                        RinowaDb.InviteCodes.CREATED_AT to FieldValue.serverTimestamp(),
                    ),
                ).await()
            }.onSuccess { return candidate }
        }
        error("could not claim an invite code")
    }

    private fun randomCode(): String {
        val random = SecureRandom()
        return buildString {
            repeat(CODE_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        }
    }

    /** 名前、一行、写真。プロフィールとはこの3つ。 */
    suspend fun updateProfile(
        id: UserId,
        name: String,
        statusMessage: String?,
        photoHash: String?,
    ): Result<Unit> = runCatching {
        val trimmed = name.trim().take(MAX_NAME_LENGTH)
        require(trimmed.isNotEmpty())
        db.collection(RinowaDb.Users.COLLECTION).document(id.value).set(
            buildMap {
                put(RinowaDb.Users.DISPLAY_NAME, trimmed)
                put(
                    RinowaDb.Users.STATUS_MESSAGE,
                    statusMessage?.trim()?.take(MAX_STATUS_LENGTH)?.takeIf { it.isNotEmpty() },
                )
                put(RinowaDb.Users.PHOTO_HASH, photoHash)
                put(RinowaDb.Users.UPDATED_AT, FieldValue.serverTimestamp())
            },
            com.google.firebase.firestore.SetOptions.merge(),
        ).await()
    }

    private suspend fun userDocument(id: UserId) =
        db.collection(RinowaDb.Users.COLLECTION).document(id.value).get().await()

    /**
     * キャッシュしか見ていない「無い」は、無いことの証拠にならない。
     *
     * 圏外でも Firestore は例外を投げず、手元に残っているものだけで答える。一度も
     * 届いていないドキュメントは、そこでは本当に存在しないものと同じ顔で返ってくる。
     * 招待コードの照会でそれを鵜呑みにすると、繋がっていないだけの相手に
     * 「そのコードの相手が見つかりませんでした」と言い切ることになる。
     */
    private fun com.google.firebase.firestore.DocumentSnapshot.unverifiedAbsence(): Boolean =
        !exists() && metadata.isFromCache

    private fun com.google.firebase.firestore.DocumentSnapshot.toProfile(
        id: UserId,
        fallbackName: String,
    ) = UserProfile(
        id = id,
        displayName = getString(RinowaDb.Users.DISPLAY_NAME)?.takeIf { it.isNotBlank() }
            ?: fallbackName,
        photoUrl = getString(RinowaDb.Users.PHOTO_URL),
        statusMessage = getString(RinowaDb.Users.STATUS_MESSAGE)?.takeIf { it.isNotBlank() },
        photoHash = getString(RinowaDb.Users.PHOTO_HASH)?.takeIf { it.isNotBlank() },
    )

    companion object {
        /**
         * I、O、0、1 を使わない。コードは読み上げられ、記憶で打ち込まれるもので、
         * 間違うのはこの4文字。
         */
        const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        const val CODE_LENGTH = 8
        const val MAX_NAME_LENGTH = 40
        const val MAX_STATUS_LENGTH = 120
        private const val CLAIM_ATTEMPTS = 6

        fun normalise(code: String): String =
            code.uppercase().filter { it in ALPHABET }

        /** `ABCD-EFGH`。読みやすさのための区切りで、この形では保存しない。 */
        fun format(code: String): String =
            if (code.length == CODE_LENGTH) "${code.take(4)}-${code.drop(4)}" else code
    }
}
