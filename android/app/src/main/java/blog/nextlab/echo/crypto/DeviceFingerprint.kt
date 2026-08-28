package blog.nextlab.echo.crypto

/**
 * 端末1台と、その指紋。
 *
 * 画面が見るのはこれだけ。matrix の `Device` をそのまま渡すと、UI が FFI の型に
 * 縛られる。**指紋の見せ方はこちらの問題で、あちらの都合ではない。**
 */
data class DeviceFingerprint(
    val deviceId: String,
    /** Ed25519 の公開鍵。これを突き合わせる。 */
    val ed25519: String,
    /** この端末で「確かめた」と印を付けたか。押した本人の端末にしか無い記録。 */
    val locallyTrusted: Boolean,
    /** 相手自身の署名で束ねられているか（クロスサイニング）。未導入なので常に false。 */
    val crossSigned: Boolean,
    /** 初めてこの端末を見た時刻。0 なら不明。 */
    val firstSeenMs: Long,
) {
    /**
     * 読み上げられる形に切る。
     *
     * 43文字の base64 を一息で照合させるのは無理。**4文字ずつ区切ると、
     * 電話越しでも読み合わせができる。** Signal も同種の区切りを使っている。
     */
    fun readable(): String = ed25519.chunked(4).joinToString(" ")
}
