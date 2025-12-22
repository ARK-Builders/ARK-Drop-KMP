package dev.arkbuilders.drop.domain.repository

import dev.arkbuilders.drop.domain.model.UserAvatar
import dev.arkbuilders.drop.domain.model.UserProfile
import kotlinx.coroutines.flow.StateFlow

interface ProfileRepo {
    val profile: StateFlow<UserProfile>

    fun getCurrentProfile() = profile.value

    fun updateProfile(profile: UserProfile)

    fun updateName(name: String)

    fun updateAvatar(avatar: UserAvatar)
}