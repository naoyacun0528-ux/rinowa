package blog.nextlab.echo

import android.app.Application
import blog.nextlab.echo.analytics.FirebaseAnalyticsSink
import blog.nextlab.echo.auth.AuthRepository
import blog.nextlab.echo.auth.GoogleCredentialClient
import blog.nextlab.echo.backup.BackupRepository
import blog.nextlab.echo.backup.DriveAppData
import blog.nextlab.echo.backup.DriveAuthorization
import blog.nextlab.echo.backup.RestoredMessages
import blog.nextlab.echo.calls.CallSignaling
import blog.nextlab.echo.core.analytics.Analytics
import blog.nextlab.echo.core.analytics.AnalyticsUserProperty
import blog.nextlab.echo.core.analytics.DebugAnalytics
import blog.nextlab.echo.core.analytics.HapticTierId
import blog.nextlab.echo.core.analytics.NoOpAnalytics
import blog.nextlab.echo.core.haptics.AndroidHaptics
import blog.nextlab.echo.core.haptics.HapticTier
import blog.nextlab.echo.core.haptics.RinowaHaptics
import blog.nextlab.echo.core.model.ReactionPalette
import blog.nextlab.echo.core.model.UserId
import blog.nextlab.echo.crypto.CryptoEngine
import blog.nextlab.echo.crypto.CryptoProblems
import blog.nextlab.echo.crypto.CryptoTransport
import blog.nextlab.echo.crypto.ToDeviceLedger
import blog.nextlab.echo.data.ConversationRepository
import blog.nextlab.echo.data.FeedbackRepository
import blog.nextlab.echo.data.LocalStickerStore
import blog.nextlab.echo.data.MediaBudget
import blog.nextlab.echo.data.MediaRepository
import blog.nextlab.echo.data.MessageRepository
import blog.nextlab.echo.data.ProfilePhotos
import blog.nextlab.echo.data.RinowaServices
import blog.nextlab.echo.data.SettingsRepository
import blog.nextlab.echo.data.StickerRepository
import blog.nextlab.echo.data.UserRepository
import blog.nextlab.echo.media.MediaStoreClient
import blog.nextlab.echo.notifications.PushSender
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RinowaApplication : Application() {

    /**
     * どの画面より長く生きるスコープ。
     *
     * 通話がコンポジションのスコープを使えない理由。以前は `rememberCoroutineScope()` で、
     * それを作ったコンポジションと一緒に死ぬ。画面には正しく、**通話には間違い**。
     * 通話中の通知で終了を押すと Activity が前に出るが、Android が再利用ではなく
     * 作り直しを選ぶと、切断が走る**前に**コンポジションが破棄される。スコープが
     * 取り消され、Firestore に `Ended` を書くコルーチンは始まらず、相手は誰もいない
     * 通話に取り残される。
     *
     * 報告されたのはまさにこの症状で、こちらでは終了が効いて相手には効かなかった。
     * 通話を終わらせることは画面の仕事ではない。
     */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * 暗号エンジンは1つだけ。サインインしたときに開く。
     *
     * リポジトリではなくここに置くのは、エンジンがディスク上の SQLite を持つから。
     * メッセージごと・画面ごとに開くと、同じラチェット状態に複数のハンドルが付き、
     * 2つが同時に進めるのは扱いきれない。
     *
     * アカウントを鍵にするのは、ストアが利用者ごとだから。別のアカウントで入り直した
     * 人に前の人の鍵を渡さないよう、身元は仮定せず確認する。
     */
    private val cryptoLock = Mutex()
    private var engine: CryptoEngine? = null
    private var engineOwner: UserId? = null

    suspend fun cryptoEngine(me: UserId): CryptoEngine? = cryptoLock.withLock {
        engine?.takeIf { engineOwner == me }?.let { return@withLock it }

        val transport = services?.crypto ?: return@withLock null
        val opened = CryptoEngine.open(
            context = this,
            transport = transport,
            me = me,
            onFailure = { CryptoProblems.record("open", IllegalStateException(it)) },
        ) ?: return@withLock null

        engine = opened
        engineOwner = me
        // 最初に開いたときにこの端末の鍵を publish する。無いと誰もこの端末宛に
        // 暗号化できず、症状は「メッセージが届かない」になる。
        opened.pump()
        opened
    }

    lateinit var haptics: RinowaHaptics
        private set

    lateinit var analytics: Analytics
        private set

    lateinit var stickers: LocalStickerStore
        private set

    lateinit var settings: SettingsRepository
        private set

    /**
     * このビルドに Firebase の設定が無ければ null。
     *
     * app/build.gradle.kts は google-services.json 無しでも建つようにしてある
     * （新しく clone した場合や、公開しているソース zip には入っていない）。Firebase は
     * そのファイルから生成されるリソースで自動初期化するので、無ければ話す相手がおらず、
     * 起動時に落ちるのではなくオフラインで動く。
     */
    var services: RinowaServices? = null
        private set

    override fun onCreate() {
        super.onCreate()

        haptics = AndroidHaptics(this)

        val firebaseReady = FirebaseApp.getApps(this).isNotEmpty()
        val firestore = if (firebaseReady) FirebaseFirestore.getInstance().apply {
            // オフライン永続化を入れる。通信が答えるまで何も出ないメッセンジャーは、
            // 電車の中では壊れているように感じる。Firestore はキャッシュのスレッドを
            // すぐ出し、繋がったら整合させる。
            firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(PersistentCacheSettings.newBuilder().build())
                .build()
        } else {
            null
        }

        settings = SettingsRepository(this, firestore)

        stickers = LocalStickerStore(
            context = this,
            remote = firestore?.let(::StickerRepository),
        )

        // **ここでディスクを触らない。**
        //
        // onCreate は最初の1コマより前に走る。ここでファイルを開いた分だけ、
        // 起動が遅れる。同梱セットの展開も、前回落としたものの読み直しも、
        // 最初のスタンプが必要になるまでには十分間に合う。
        //
        // 以前はここで installBuiltIns() と rescan() を直接呼んでいた。
        // rescan() は自作スタンプを**全部メモリに読み込む**ので、持っている人ほど
        // 起動が遅くなっていた。持っている人ほど遅い、は逆になっている。
        CoroutineScope(Dispatchers.IO).launch {
            stickers.installBuiltIns()
            stickers.rescan()

            // 前の版が filesDir に貯めた写真を捨てる。あそこは消せない側で、
            // 上限も無かった。次に開いたときに落とし直す。
            MediaBudget.forgetOldLocation(this@RinowaApplication)
            MediaBudget.prune(
                java.io.File(cacheDir, MediaRepository.DIR),
                MediaBudget.bytesFor(this@RinowaApplication),
            )
        }

        if (firestore != null) {
            val users = UserRepository(firestore)
            val mediaStore = MediaStoreClient(FirebaseAuth.getInstance())

            // 復元したバックアップが端末に教えたこと。鍵で開けなかったときだけ読む。
            // 復元がサーバーに書かない理由は RestoredMessages を参照。
            val restored = RestoredMessages(this)
            val driveAuth = DriveAuthorization(this)
            val conversationRepository = ConversationRepository(firestore, users)
            val messageRepository = MessageRepository(
                db = firestore,
                encrypt = { conversationId, me, members, plaintext ->
                    cryptoEngine(me)?.encrypt(conversationId, members, plaintext)
                },
                decrypt = { conversationId, me, sender, ciphertext ->
                    val active = cryptoEngine(me)
                    // メッセージが鍵を追い越すことがあるので、外れたら to-device の
                    // 受信箱を空にしてもう一度だけ試す。それでも駄目なら 🔒 を出し、
                    // 永久に再試行はしない。
                    active?.decrypt(conversationId, sender, ciphertext)
                        ?: active?.let {
                            it.receive()
                            it.decrypt(conversationId, sender, ciphertext)
                        }
                },
                restored = restored,
            )
            services = RinowaServices(
                auth = AuthRepository(FirebaseAuth.getInstance()),
                googleCredentials = GoogleCredentialClient(this),
                users = users,
                conversations = conversationRepository,
                messages = messageRepository,
                backup = BackupRepository(
                    conversations = conversationRepository,
                    messages = messageRepository,
                    restored = restored,
                    // トークンは要求が必要になった瞬間に取り、画面は出さない。
                    // DriveAuthorization を参照。
                    drive = DriveAppData {
                        driveAuth.tokenOrNull() ?: error("Google ドライブの許可がありません")
                    },
                ),
                stickers = StickerRepository(firestore),
                feedback = FeedbackRepository(firestore),
                settings = settings,
                photos = ProfilePhotos(this, firestore),
                media = MediaRepository(this, firestore, mediaStore),
                mediaStore = mediaStore,
                calls = CallSignaling(firestore),
                crypto = CryptoTransport(
                    firestore,
                    ToDeviceLedger(
                        object : ToDeviceLedger.Store {
                            // データベースの表ではなく設定ファイル。数十個の id を
                            // 受信箱を空にするたびに書くだけで、しかもプロセスが死んでも
                            // 残る必要がある（持っている意味がそこにある）。
                            private val prefs = getSharedPreferences(
                                "rinowa.todevice",
                                MODE_PRIVATE,
                            )

                            override fun read(): Set<String> =
                                prefs.getStringSet("seen", emptySet()).orEmpty()

                            override fun write(ids: Set<String>) {
                                // apply ではなく commit。非同期の書き込みは、これが
                                // 守ろうとしているクラッシュでちょうど失われるもの。
                                prefs.edit().putStringSet("seen", ids).commit()
                            }
                        },
                    ),
                ),
                push = PushSender(FirebaseAuth.getInstance()),
                context = this,
                firestore = firestore,
            )
        }

        analytics = when {
            // Firebase Analytics は、送り先の Firebase があって release のときだけ。
            // debug では logcat に出す。開発中の操作が、製品を判断する数字に混ざらないように。
            firebaseReady && !BuildConfig.DEBUG ->
                FirebaseAnalyticsSink(
                    context = this,
                    initiallyOptedOut = settings.localOptedOut(),
                    onOptOutChanged = { settings.putLocal(optedOut = it) },
                )

            BuildConfig.DEBUG -> DebugAnalytics()
            else -> NoOpAnalytics()
        }

        haptics.setPreferences(settings.localHaptics())

        analytics.setUserProperty(
            AnalyticsUserProperty.OsApiLevel(android.os.Build.VERSION.SDK_INT),
        )
        analytics.setUserProperty(
            AnalyticsUserProperty.AppVersionCode(BuildConfig.VERSION_CODE),
        )
        analytics.setUserProperty(
            AnalyticsUserProperty.HapticTierProperty(haptics.capabilities.bestTier.toAnalyticsId()),
        )
        analytics.setUserProperty(
            AnalyticsUserProperty.ReactionPaletteVersion(
                ReactionPalette.VERSION,
            ),
        )
    }
}

private fun HapticTier.toAnalyticsId(): HapticTierId = when (this) {
    HapticTier.Envelope -> HapticTierId.Envelope
    HapticTier.PrimitiveRich -> HapticTierId.PrimitiveRich
    HapticTier.Primitive -> HapticTierId.Primitive
    HapticTier.Predefined -> HapticTierId.Predefined
    HapticTier.Waveform -> HapticTierId.Waveform
    HapticTier.Legacy -> HapticTierId.Legacy
    HapticTier.None -> HapticTierId.None
}
