package blog.nextlab.echo.data

import blog.nextlab.echo.auth.AuthRepository
import blog.nextlab.echo.auth.GoogleCredentialClient
import blog.nextlab.echo.backup.BackupRepository
import blog.nextlab.echo.calls.CallSignaling
import blog.nextlab.echo.crypto.CryptoTransport
import blog.nextlab.echo.media.MediaStoreClient
import blog.nextlab.echo.core.model.UserId
import blog.nextlab.echo.notifications.PushSender
import blog.nextlab.echo.notifications.PushTokenRegistrar

/**
 * バックエンドを要るものを1つの袋に。
 *
 * このビルドに Firebase の設定があるときだけ存在する（`RinowaApplication` を参照）。
 * 無いことが、Rinowa をオフラインの試作に戻す唯一のスイッチで、設定ファイル無しで
 * ソースを渡された人でもビルドできるのはそのおかげ。
 *
 * DI の枠組みではなく手書きの入れ物にしてある。オブジェクトは7つで寿命は1つ。
 * Hilt はコンパイラプラグインと注釈一式を持ち込んで、この企画にまだ無い問題を解く。
 */
class RinowaServices(
    val auth: AuthRepository,
    val googleCredentials: GoogleCredentialClient,
    val users: UserRepository,
    val conversations: ConversationRepository,
    val messages: MessageRepository,
    val stickers: StickerRepository,
    val feedback: FeedbackRepository,
    val settings: SettingsRepository,
    val photos: ProfilePhotos? = null,
    val media: MediaRepository? = null,
    /**
     * 保管庫そのもの。再生用。
     *
     * 動画は取得してから再生するのではなく、範囲要求で読みながら再生する。だから
     * プレイヤーが要るのはリポジトリではなく通信路のほう。EncryptedMediaSource を参照。
     */
    val mediaStore: MediaStoreClient? = null,
    val calls: CallSignaling? = null,
    /** push の窓口が設定されていないビルドでは null。 */
    val push: PushSender? = null,
    /**
     * E2EE の鍵を運ぶもの。Firebase の無いビルドでは null。
     *
     * Firestore のハンドルを配らずここで持つ。生のデータベース参照はどこからでも
     * 書きたくなるが、鍵のコレクションのルールは1つのクラスが所有していて初めて意味を持つ。
     */
    val crypto: CryptoTransport? = null,
    /**
     * 履歴を控え、戻すもの。
     *
     * Firebase も Play Services も無いビルドでは null（控えるものも、置き場所も無い）。
     */
    val backup: BackupRepository? = null,
    private val context: android.content.Context? = null,
    private val firestore: com.google.firebase.firestore.FirebaseFirestore? = null,
) {
    suspend fun registerPushToken(owner: UserId) {
        val ctx = context ?: return
        val db = firestore ?: return
        PushTokenRegistrar.register(ctx, db, owner)
    }

    suspend fun unregisterPushToken(owner: UserId) {
        val ctx = context ?: return
        val db = firestore ?: return
        PushTokenRegistrar.unregister(ctx, db, owner)
    }
}
