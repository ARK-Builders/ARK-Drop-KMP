package dev.arkbuilders.drop.presentation.edit

import androidx.lifecycle.ViewModel
import dev.arkbuilders.drop.data.helper.AvatarHelper
import dev.arkbuilders.drop.domain.model.UserAvatar
import dev.arkbuilders.drop.domain.model.UserProfile
import dev.arkbuilders.drop.domain.repository.ProfileRepo
import dev.arkbuilders.drop.instrumentation.AnalyticsEvents
import dev.arkbuilders.drop.instrumentation.AnalyticsReporter
import kotlinx.coroutines.flow.first
import org.orbitmvi.orbit.Container
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.blockingIntent
import org.orbitmvi.orbit.viewmodel.container

data class EditProfileScreenState(
    val currentProfile: UserProfile,
    val name: String,
    val nameError: EditProfileNameError?,
    val avatar: UserAvatar,
    val avatarImageLoadingFailed: Boolean = false,
    val hasChanges: Boolean = false,
)

enum class EditProfileNameError {
    EMPTY,
    TOO_SHORT,
    TOO_LONG,
}

sealed class EditProfileScreenEffect {
    data object LaunchImagePicker : EditProfileScreenEffect()

    data object NavigateBack : EditProfileScreenEffect()
}

class EditProfileViewModel(
    private val profileRepo: ProfileRepo,
    private val avatarHelper: AvatarHelper,
    private val analyticsReporter: AnalyticsReporter,
) : ViewModel(), ContainerHost<EditProfileScreenState, EditProfileScreenEffect> {
    override val container: Container<EditProfileScreenState, EditProfileScreenEffect> =
        container(
            EditProfileScreenState(
                currentProfile = UserProfile.empty(),
                name = "",
                nameError = null,
                avatar = UserAvatar("", null),
            ),
        )

    init {
        intent {
            val profile = profileRepo.profile.first()
            reduce {
                state.copy(currentProfile = profile, name = profile.name, avatar = profile.avatar)
            }
        }
    }

    fun onNameChanged(newName: String) =
        blockingIntent {
            val nameError =
                when {
                    newName.isBlank() -> EditProfileNameError.EMPTY
                    newName.trim().length < 2 -> EditProfileNameError.TOO_SHORT
                    newName.length > 50 -> EditProfileNameError.TOO_LONG
                    else -> null
                }
            reduce {
                state.copy(
                    name = newName,
                    nameError = nameError,
                    hasChanges = state.currentProfile.name != newName,
                )
            }
        }

    fun onPickImage() =
        intent {
            analyticsReporter.logEvent(AnalyticsEvents.PROFILE_IMAGE_PICKER_OPENED)
            postSideEffect(EditProfileScreenEffect.LaunchImagePicker)
        }

    fun onImagePicked(uri: String) =
        intent {
            val base64 = avatarHelper.uriToBase64(uri)
            base64?.let {
                reduce {
                    state.copy(
                        avatar = UserAvatar(base64, predefinedId = null),
                        hasChanges = state.avatar != state.currentProfile.avatar,
                    )
                }
            } ?: let {
                state.copy(
                    avatarImageLoadingFailed = true,
                )
            }
        }

    fun onAvatarSelected(id: String) =
        intent {
            val base64 = avatarHelper.getDefaultAvatarBase64(id)
            reduce {
                state.copy(
                    avatar = UserAvatar(base64, predefinedId = id),
                    hasChanges = state.avatar != state.currentProfile.avatar,
                )
            }
        }

    fun onSave() =
        intent {
            analyticsReporter.logEvent(
                AnalyticsEvents.PROFILE_SAVED,
                mapOf(
                    AnalyticsEvents.PARAM_CHANGED_NAME to
                        (state.currentProfile.name != state.name),
                    AnalyticsEvents.PARAM_CHANGED_AVATAR to
                        (state.currentProfile.avatar != state.avatar),
                ),
            )
            profileRepo.updateProfile(UserProfile(state.name, state.avatar))
            postSideEffect(EditProfileScreenEffect.NavigateBack)
        }

    fun clearAvatarLoadingError() =
        intent {
            reduce {
                state.copy(avatarImageLoadingFailed = false)
            }
        }
}
