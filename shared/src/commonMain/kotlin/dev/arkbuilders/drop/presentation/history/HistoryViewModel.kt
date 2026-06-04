package dev.arkbuilders.drop.presentation.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.arkbuilders.drop.domain.model.TransferSession
import dev.arkbuilders.drop.domain.repository.TransferSessionRepo
import dev.arkbuilders.drop.instrumentation.AnalyticsEvents
import dev.arkbuilders.drop.instrumentation.AnalyticsReporter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.orbitmvi.orbit.Container
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.viewmodel.container

data class HistoryScreenState(
    val historyItems: List<TransferSession>,
    val showClearDialog: Boolean,
    val showDeleteDialog: Boolean,
)

sealed class HistoryScreenEffect

class HistoryViewModel(
    private val historyItemRepository: TransferSessionRepo,
    private val analyticsReporter: AnalyticsReporter,
) : ViewModel(), ContainerHost<HistoryScreenState, HistoryScreenEffect> {
    override val container: Container<HistoryScreenState, HistoryScreenEffect> =
        container(
            HistoryScreenState(
                historyItems = emptyList(),
                showClearDialog = false,
                showDeleteDialog = false,
            ),
        )

    init {
        historyItemRepository.historyItems.onEach { items ->
            intent {
                reduce {
                    state.copy(historyItems = items)
                }
            }
        }.launchIn(viewModelScope)
    }

    fun onShowClearDialog() =
        intent {
            reduce {
                state.copy(showClearDialog = true)
            }
        }

    fun onClear() =
        intent {
            analyticsReporter.logEvent(
                AnalyticsEvents.HISTORY_CLEARED,
                mapOf(AnalyticsEvents.PARAM_ITEM_COUNT to state.historyItems.size),
            )
            historyItemRepository.clearHistory()
            reduce {
                state.copy(showClearDialog = false)
            }
        }

    fun onDismissClearDialog() =
        intent {
            reduce {
                state.copy(showClearDialog = false)
            }
        }

    fun onShowDeleteDialog() =
        intent {
            reduce {
                state.copy(showDeleteDialog = true)
            }
        }

    fun onDelete(id: Long) =
        intent {
            analyticsReporter.logEvent(AnalyticsEvents.HISTORY_ITEM_DELETED)
            historyItemRepository.deleteSession(id)
            reduce {
                state.copy(showDeleteDialog = false)
            }
        }

    fun onDismissDeleteDialog() =
        intent {
            reduce {
                state.copy(showDeleteDialog = false)
            }
        }
}
