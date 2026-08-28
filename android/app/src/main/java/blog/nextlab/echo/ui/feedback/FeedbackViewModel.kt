package blog.nextlab.echo.ui.feedback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blog.nextlab.echo.core.analytics.Analytics
import blog.nextlab.echo.core.analytics.AnalyticsEvent
import blog.nextlab.echo.core.analytics.FeedbackCategory
import blog.nextlab.echo.core.analytics.VoteDirection
import blog.nextlab.echo.data.RinowaServices
import blog.nextlab.echo.data.FeedbackItem
import blog.nextlab.echo.core.model.UserId
import kotlinx.coroutines.launch

/**
 * フィードバックと投票。
 *
 * 行き先が2つあり、厳密に分けてある:
 *
 *  - 文章は Firestore へ。開発者に読ませるために本人が書いたものだから。
 *  - 計測に行くのは**長さ**と分類だけで、1文字も行かない。
 *
 * docs/ANALYTICS_SCHEMA.md §6 に書いてある。文章がそこにあるぶん境目は曖昧に
 * しやすく、曖昧にすれば、この企画の他のプライバシーの主張も全部弱くなる。
 */
class FeedbackViewModel(
    private val services: RinowaServices,
    private val analytics: Analytics,
    private val me: UserId,
) : ViewModel() {

    var items by mutableStateOf<List<FeedbackItem>>(emptyList())
        private set

    var loading by mutableStateOf(true)
        private set

    var submitting by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            services.feedback.list(me)
                .onSuccess { items = it }
                .onFailure { error = "読み込めませんでした。" }
            loading = false
        }
    }

    fun submit(
        title: String,
        body: String,
        category: FeedbackCategory,
        onDone: (Boolean) -> Unit,
    ) {
        if (submitting) return
        submitting = true
        error = null
        viewModelScope.launch {
            services.feedback.submit(me, title, body, category).fold(
                onSuccess = {
                    analytics.log(
                        AnalyticsEvent.FeedbackSubmitted(
                            category = category,
                            // 長さだけ。仮に誰かがやろうとしても、文章を運べる
                            // 計測の項目は存在しない。
                            bodyLength = body.length,
                            hasScreenshot = false,
                        ),
                    )
                    refresh()
                    onDone(true)
                },
                onFailure = {
                    error = "送信できませんでした。通信を確認してください。"
                    onDone(false)
                },
            )
            submitting = false
        }
    }

    fun toggleVote(item: FeedbackItem) {
        viewModelScope.launch {
            services.feedback.toggleVote(me, item.id).onSuccess { voted ->
                analytics.log(
                    AnalyticsEvent.FeedbackVoted(
                        if (voted) VoteDirection.Up else VoteDirection.Unvote,
                    ),
                )
                items = items.map {
                    if (it.id == item.id) {
                        it.copy(
                            votedByMe = voted,
                            voteCount = (it.voteCount + if (voted) 1 else -1).coerceAtLeast(0),
                        )
                    } else {
                        it
                    }
                }
            }
        }
    }

    fun withdraw(item: FeedbackItem) {
        viewModelScope.launch {
            services.feedback.withdraw(item.id).onSuccess {
                items = items.filterNot { it.id == item.id }
            }
        }
    }
}
