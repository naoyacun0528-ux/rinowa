package blog.nextlab.echo.ui.profile

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blog.nextlab.echo.data.RinowaServices
import blog.nextlab.echo.core.model.UserId
import blog.nextlab.echo.core.model.UserProfile
import kotlinx.coroutines.launch

/**
 * 自分のプロフィールを編集する。
 *
 * 画像は、それを指すドキュメントより先に公開する。順番が大事で、まだ無い画像の
 * ハッシュを持つ利用者ドキュメントがあると、他の全クライアントが存在しないものを
 * 取りに行く。逆（誰も指していない画像）の代償は、孤立したドキュメント1つだけ。
 */
class ProfileViewModel(
    private val services: RinowaServices,
    private val me: UserId,
) : ViewModel() {

    var name by mutableStateOf("")
        private set

    var statusMessage by mutableStateOf("")
        private set

    /** 選ばれた、まだ切り取っていない画像。非 null なら切り取り画面が出る。 */
    var cropping by mutableStateOf<Uri?>(null)
        private set

    /** 切り取り済みで、まだ公開していないもの。null は「写真はこのまま」。 */
    var pendingPhoto by mutableStateOf<android.graphics.Bitmap?>(null)
        private set

    var photoHash by mutableStateOf<String?>(null)
        private set

    /** 読み込みが終わるまで true。失敗しても false になるので、結果は [ready] で見る。 */
    var loading by mutableStateOf(false)
        private set

    /** 読み込めたか。失敗すると false のまま残り、これが保存を止める根拠になる。 */
    val ready: Boolean
        get() = loaded != null

    var saving by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    /** 読み込んだ時点の値と違えば true。 */
    val dirty: Boolean
        get() = pendingPhoto != null ||
            name.trim() != (loaded?.displayName ?: "") ||
            statusMessage.trim() != (loaded?.statusMessage ?: "") ||
            photoHash != loaded?.photoHash

    private var loaded: UserProfile? = null

    init {
        load()
    }

    /**
     * サーバーにある今の値を取る。読み直しにも使う。
     *
     * 失敗したら `loaded` は null のまま残す。これが保存を止める。空の画面を
     * そのまま保存すると、更新は「地図に無い項目」を残すだけなので、null として
     * 送られる一行と写真が値ごと消える。読めていないものは書き戻せない。
     */
    fun load() {
        if (loading) return
        loading = true
        error = null

        viewModelScope.launch {
            services.users.profile(me)
                .onSuccess { profile ->
                    loaded = profile
                    name = profile.displayName
                    statusMessage = profile.statusMessage.orEmpty()
                    photoHash = profile.photoHash
                    services.photos?.fetch(me, profile.photoHash)
                }
                .onFailure { error = "プロフィールを読み込めませんでした。" }
            loading = false
        }
    }

    // `setName` / `setStatusMessage` にはできない。プロパティのセッターが
    // その JVM 名をすでに使っている。
    fun updateName(value: String) {
        name = value.take(NAME_LIMIT)
        error = null
    }

    fun updateStatusMessage(value: String) {
        statusMessage = value.take(STATUS_LIMIT)
        error = null
    }

    fun pickPhoto(uri: Uri?) {
        if (uri == null) return
        cropping = uri
        error = null
    }

    fun cancelCrop() {
        cropping = null
    }

    fun applyCrop(bitmap: android.graphics.Bitmap) {
        pendingPhoto = bitmap
        cropping = null
    }

    /** 写真を消す。他の変更と同じく、効くのは保存したとき。 */
    fun removePhoto() {
        pendingPhoto = null
        photoHash = null
    }

    fun save(onDone: () -> Unit) {
        // 読み込めていないときは書かない。画面も編集欄を出さないが、保存が消しうる
        // 操作である以上、止める場所は画面だけであってはいけない。
        if (saving || loading || !ready || name.isBlank()) return
        saving = true
        error = null

        viewModelScope.launch {
            val photos = services.photos
            var hash = photoHash

            pendingPhoto?.let { bitmap ->
                if (photos == null) {
                    error = "画像を保存できませんでした。"
                    saving = false
                    return@launch
                }
                photos.publish(me, bitmap).fold(
                    onSuccess = { hash = it },
                    onFailure = { failure ->
                        // 気を利かせた推測ではなく本当の理由。ここで握り潰したせいで、
                        // この画面の最初の版は診断不能だった。
                        error = "画像を保存できませんでした — " +
                            (failure.message ?: failure::class.simpleName.orEmpty())
                        saving = false
                        return@launch
                    },
                )
            }

            // 写真を消すときは保存された画像も消す。古いハッシュを持っている人に
            // 読めるまま残さない。
            if (hash == null && loaded?.photoHash != null) photos?.remove(me)

            services.users.updateProfile(
                id = me,
                name = name,
                statusMessage = statusMessage,
                photoHash = hash,
            ).fold(
                onSuccess = {
                    photoHash = hash
                    pendingPhoto = null
                    loaded = loaded?.copy(
                        displayName = name.trim(),
                        statusMessage = statusMessage.trim().takeIf { it.isNotEmpty() },
                        photoHash = hash,
                    )
                    onDone()
                },
                onFailure = { error = "保存できませんでした。通信を確認してください。" },
            )
            saving = false
        }
    }

    private companion object {
        const val NAME_LIMIT = 40
        const val STATUS_LIMIT = 120
    }
}
