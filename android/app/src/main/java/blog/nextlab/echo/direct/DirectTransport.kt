package blog.nextlab.echo.direct

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Rinowa Direct — 端末どうしの経路。
 *
 * > 遠いときはクラウド、近いときは Direct。
 *
 * docs/DIRECT_ARCHITECTURE.md を参照。ここは Direct-1、つまり通信路そのもので、
 * インターネット無しで Android 2台の間を通ることまで。身元の確認・経路選択・
 * クラウドへの退避は Direct-2 で、意図的に**まだ入れていない**（1バイト運べることを
 * 示せない通信路をルータで包む意味が無い）。
 *
 * 段は2つ:
 *
 * | | 圏外で動く | iPhone に届く | 中身 |
 * |---|---|---|---|
 * | [DirectTier.Nearby] | ○ | まだ | Nearby Connections。無線は自分で選ぶ |
 * | [DirectTier.Lan] | × | **いずれ○** | 同じ Wi-Fi 上の素のソケット |
 *
 * 2つ目は Android だけ見ると重複に見える（Nearby も使えるときは Wi-Fi を使う）。
 * それでもあるのは、将来 iPhone が入れるのがこちらだけだから。Nearby の iOS SDK は
 * Wi-Fi LAN しか対応せず、iOS は背面に回ったアプリを Bluetooth で Android に
 * 見つけさせない。「同じ回線」は退避先ではなく、プラットフォームをまたぐ本道。
 */
/**
 * リンクを何に最適化するか。
 *
 * Nearby は無線を自分で選び、放っておくと最速のもの＝両者が同じ Wi-Fi にいれば
 * Wi-Fi を選ぶ。速度としては正しく、証明としては間違い。回線が落ちたら死ぬリンクは、
 * 画面が何と呼ぼうとオフラインの段ではない。
 *
 * なので推測させずに明示する。
 */
enum class DirectPreference {
    /** 使える中で最速。可能なら Wi-Fi に上げる。 */
    Fastest,

    /**
     * 回線がまったく無くても動く無線に留まる。
     *
     * わざと遅い。「圏外でも届く」に本当に必要なのはこれで、その主張を試せる唯一の設定。
     */
    OfflineCapable,
}

enum class DirectTier {
    /** 無線は Nearby Connections が選ぶ。どんな回線も要らない。 */
    Nearby,

    /** 両方が同じ Wi-Fi 上。速度差は問題にならない程度で、プラットフォームをまたげるのはこちら。 */
    Lan,
}

@Immutable
data class TransportCapabilities(
    val tier: DirectTier,
    val worksOffline: Boolean,
    /** この段の相手側が iOS にできたら true。いまはどれも false。 */
    val crossPlatform: Boolean,
)

/** 見つけたばかりの相手。何も確認していない状態。 */
@Immutable
data class DiscoveredPeer(
    val endpointId: String,
    /**
     * 相手が広告に載せていたもの。
     *
     * Direct-1 では2台を見分けるために人が読める名前を入れている。**検証専用の判断。**
     * docs/DIRECT_THREAT_MODEL.md T1 は識別できるものの発信を禁じており、Direct-2 では
     * 友達だけが解決できる入れ替わるトークンに置き換わる。その変更が外へ波及しないよう、
     * 項目は不透明なままにしてある。
     */
    val advertisedLabel: String,
    val tier: DirectTier,
)

/**
 * 接続はできたが、**まだ認証していない**相手。
 *
 * 型を分けておくことで、確認していない相手へ誤って送れないようにする
 * （[DirectTransport.send] が取るのは [AuthenticatedPeer] で、これは違う）。
 * Direct-1 で手に入れる方法は [DirectTransport.trustForTesting] だけ。Direct-2 では
 * チャレンジ・レスポンスに置き換わり、そう仮定していた呼び出し箇所をコンパイラが全部指す。
 */
@Immutable
data class PeerLink(val endpointId: String, val tier: DirectTier)

@Immutable
data class AuthenticatedPeer(val endpointId: String, val tier: DirectTier)

/** 直リンクで運ぶもの。Direct-1 では文字だけ。添付は Direct-2。 */
@Immutable
data class DirectPayload(
    val fromEndpointId: String,
    val bytes: ByteArray,
) {
    val asText: String get() = String(bytes, Charsets.UTF_8)

    // ByteArray は参照比較なので、同じ内容の2つが「違うもの」になり、
    // set や distinct() が黙って壊れる。
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is DirectPayload && fromEndpointId == other.fromEndpointId &&
                bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * fromEndpointId.hashCode() + bytes.contentHashCode()

    /** 中身は出さない。直リンクの payload も本文には違いない。 */
    override fun toString(): String = "DirectPayload(from=$fromEndpointId, size=${bytes.size})"
}

enum class DirectConnectionState { Discovered, Connecting, Connected, Authenticated, Lost }

sealed interface DirectFailure {
    data object PermissionMissing : DirectFailure
    data object RadiosOff : DirectFailure
    data object Unsupported : DirectFailure
    data object Rejected : DirectFailure
    data object Timeout : DirectFailure
    data object Unknown : DirectFailure
}

class DirectException(val failure: DirectFailure) : Exception(failure::class.simpleName)

/**
 * 近くの端末へ届く手段1つ。
 *
 * `connect` と `authenticate` を分けているのは意図的。繋がっていることと、
 * 誰と繋がっているかを知っていることは別の事実で、まとめると
 * 「近くにいたから信用した」が事故として書かれる。
 */
interface DirectTransport {

    val capabilities: TransportCapabilities

    val connectionState: StateFlow<Map<String, DirectConnectionState>>

    /** この端末を告知し、見つけたものを流す。cold — collect で始まる。 */
    fun discoverPeers(
        label: String,
        preference: DirectPreference = DirectPreference.Fastest,
    ): Flow<DiscoveredPeer>

    suspend fun stopDiscovery()

    suspend fun connect(peer: DiscoveredPeer): Result<PeerLink>

    /**
     * Direct-1 専用。何も証明せずにリンクを受け入れる。
     *
     * 身元の実装が入る前に通信路を測るためにある。うっかり残せない名前にしてある。
     * Direct-2 で削除し、docs/DIRECT_ARCHITECTURE.md §6 のチャレンジ・レスポンスを置く。
     */
    fun trustForTesting(link: PeerLink): AuthenticatedPeer

    suspend fun send(peer: AuthenticatedPeer, payload: ByteArray): Result<Unit>

    fun receive(): Flow<DirectPayload>

    suspend fun disconnect(endpointId: String)

    suspend fun shutdown()
}
