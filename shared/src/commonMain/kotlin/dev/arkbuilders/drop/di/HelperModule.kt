package dev.arkbuilders.drop.di

import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import dev.arkbuilders.drop.data.helper.AvatarHelper
import dev.arkbuilders.drop.data.helper.NetworkStatus
import dev.arkbuilders.drop.data.helper.PermissionsHelper
import dev.arkbuilders.drop.data.helper.ResourcesHelper

val helperModule = module {
    singleOf(::AvatarHelper)
    singleOf(::NetworkStatus)
    singleOf(::PermissionsHelper)
    singleOf(::ResourcesHelper)
}